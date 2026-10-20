package io.github.quyvu01.jfxmap.mongodb;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.internal.BeanIntrospector;
import io.github.quyvu01.jfxmap.internal.BeanProperty;
import io.github.quyvu01.jfxmap.provider.EntityExpressionEvaluator;
import io.github.quyvu01.jfxmap.provider.IdConverter;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Answers the requests for one entity from a MongoDB collection (FxMap's MongoDbQueryHandler and
 * {@code AddMongoDb(cfg => cfg.AddCollection(...))}).
 *
 * <p>Each request runs one {@code find({<id field>: {$in: [...]}})} per chunk of {@value #DEFAULT_BATCH_SIZE} ids,
 * then evaluates the expressions on the loaded documents with an {@link EntityExpressionEvaluator}: every expression
 * of the FxMap language is supported, with the same answers as the in-memory handler. Expressions are not translated
 * to MongoDB projections yet, so whole documents are read.
 *
 * <p>The documents are read with the collection's codecs; when it has none for the entity type, a POJO codec is
 * added ({@link PojoCodecProvider} with automatic class models), which also reads records. The id field is the stored
 * name of the configured id property: {@code _id} when it carries {@code @BsonId} or is the {@code id} property of a
 * POJO (not of a record, which the record codec stores as {@code id}), the name of {@code @BsonProperty} when it has one, the property name otherwise; or the one given
 * to {@link #builder}. The requested ids are converted to the type of the id property (String, ObjectId, UUID, the
 * integer types, BigInteger, BigDecimal or an enum), and an id that does not convert is not found.
 *
 * <pre>{@code
 * var handler = new MongoQueryHandler<>(new UserConfig(), database.getCollection("users", User.class));
 * }</pre>
 *
 * @param <T> the entity type
 */
public final class MongoQueryHandler<T> implements QueryHandler {
    public static final int DEFAULT_BATCH_SIZE = 1000;

    private static final Executor VIRTUAL_THREADS = task -> Thread.ofVirtual().name("jfxmap-mongodb").start(task);

    private final EntityConfigureOf<T> config;
    private final MongoCollection<T> collection;
    private final String idField;
    private final Executor executor;
    private final EntityExpressionEvaluator<T> evaluator;
    private final BeanProperty idProperty;
    private final IdConverter idConverter;

    private MongoQueryHandler(Builder<T> builder) {
        this.config = builder.config;
        var type = config.entityType();
        this.collection = withCodec(builder.collection.withDocumentClass(type));
        try {
            this.idProperty = BeanIntrospector.property(type, config.idProperty());
        } catch (RuntimeException e) {
            throw new MongoMapException.IdPropertyNotFound(type, config.idProperty(), e);
        }
        if (idProperty == null) throw new MongoMapException.IdPropertyNotFound(type, config.idProperty(), null);
        this.idField = builder.idField != null ? builder.idField : IdFields.storedName(type, config.idProperty());
        this.idConverter = idConverter(type, idProperty.type());
        this.executor = builder.executor;
        this.evaluator = new EntityExpressionEvaluator<>(config, builder.clock);
    }

    /**
     * @param config     the entity configuration
     * @param collection the collection that holds the entity
     * @throws MongoMapException when the id property or its type does not fit
     */
    public MongoQueryHandler(EntityConfigureOf<T> config, MongoCollection<?> collection) {
        this(builder(config, collection));
    }

    /** A handler with a given id field, executor or clock. */
    public static <T> Builder<T> builder(EntityConfigureOf<T> config, MongoCollection<?> collection) {
        return new Builder<>(config, collection);
    }

    @Override
    public DistributedKeyRef key() {
        return config.key();
    }

    @Override
    public Class<?> entityType() {
        return config.entityType();
    }

    /** The collection the documents are read from. */
    public MongoCollection<T> collection() {
        return collection;
    }

    /** The stored name of the id. */
    public String idField() {
        return idField;
    }

    @Override
    public String dbSystem() {
        return "mongodb";
    }

    @Override
    public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(() -> answer(request), executor);
    }

    private ItemsResponse answer(MapRequest request) {
        var ids = new LinkedHashSet<>();
        for (var id : request.selectorIds()) {
            var converted = idConverter.convert(id);
            if (converted != null) ids.add(converted);
        }
        var byId = new HashMap<Object, T>();
        var list = new ArrayList<>(ids);
        for (int start = 0; start < list.size(); start += DEFAULT_BATCH_SIZE) {
            List<Object> chunk = list.subList(start, Math.min(list.size(), start + DEFAULT_BATCH_SIZE));
            for (T document : collection.find(Filters.in(idField, chunk))) {
                var id = idProperty.get(document);
                if (id != null) byId.putIfAbsent(id, document);
            }
        }
        return evaluator.answer(request, id -> {
            var converted = idConverter.convert(id);
            return converted == null ? null : byId.get(converted);
        });
    }

    private static <T> MongoCollection<T> withCodec(MongoCollection<T> collection) {
        var registry = collection.getCodecRegistry();
        try {
            registry.get(collection.getDocumentClass());
            return collection;
        } catch (CodecConfigurationException e) {
            return collection.withCodecRegistry(CodecRegistries.fromRegistries(registry,
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())));
        }
    }

    private static IdConverter idConverter(Class<?> entityType, Class<?> idType) {
        if (idType == ObjectId.class)
            return IdConverter.using(s -> {
                if (!ObjectId.isValid(s.trim())) throw new IllegalArgumentException(s);
                return new ObjectId(s.trim());
            });
        var converter = IdConverter.of(idType);
        if (converter == null) throw new MongoMapException.UnsupportedIdType(entityType, idType);
        return converter;
    }

    /** The options of a {@link MongoQueryHandler}. */
    public static final class Builder<T> {
        private final EntityConfigureOf<T> config;
        private final MongoCollection<?> collection;
        private String idField;
        private Executor executor = VIRTUAL_THREADS;
        private Clock clock = Clock.systemDefaultZone();

        private Builder(EntityConfigureOf<T> config, MongoCollection<?> collection) {
            this.config = Objects.requireNonNull(config, "config");
            this.collection = Objects.requireNonNull(collection, "collection");
        }

        /** The stored name of the id, when it is not the one inferred from the id property. */
        public Builder<T> idField(String idField) {
            if (idField == null || idField.isBlank()) throw new IllegalArgumentException("The id field is blank.");
            this.idField = idField;
            return this;
        }

        /** Runs the queries; by default on virtual threads. */
        public Builder<T> executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Gives today for {@code :daysAgo}. */
        public Builder<T> clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** @throws MongoMapException when the id property or its type does not fit */
        public MongoQueryHandler<T> build() {
            return new MongoQueryHandler<>(this);
        }
    }
}
