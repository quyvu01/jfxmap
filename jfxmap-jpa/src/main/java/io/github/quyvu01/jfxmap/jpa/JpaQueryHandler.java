package io.github.quyvu01.jfxmap.jpa;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.EntityExpressionEvaluator;
import io.github.quyvu01.jfxmap.provider.IdConverter;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Tuple;

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
 * Answers the requests for one entity from a JPA persistence unit (FxMap's EntityFrameworkQueryHandler).
 *
 * <p>Each request runs one query per chunk of {@value #DEFAULT_BATCH_SIZE} ids
 * ({@code select e.id, e from Entity e where e.id in :ids}, with the id of the entity configuration), then evaluates the expressions on the loaded entities with an
 * {@link EntityExpressionEvaluator}, while the entity manager is still open: navigations and collections
 * ({@code Country.Name}, {@code Orders:sum(Total)}) are read through the entities' lazy associations. So every
 * expression of the FxMap language is supported, and the answers are the same as those of the in-memory handler.
 * Expressions are not translated to SQL yet.
 *
 * <p>The requested ids travel as strings; they are converted to the type of the id attribute (String, UUID, the
 * integer types, BigInteger, BigDecimal or an enum), and an id that does not convert is not found.
 *
 * @param <T> the entity type
 */
public final class JpaQueryHandler<T> implements QueryHandler {
    public static final int DEFAULT_BATCH_SIZE = 1000;

    private static final Executor VIRTUAL_THREADS = task -> Thread.ofVirtual().name("jfxmap-jpa").start(task);

    private final EntityConfigureOf<T> config;
    private final EntityManagerFactory entityManagerFactory;
    private final Executor executor;
    private final EntityExpressionEvaluator<T> evaluator;
    private final IdConverter idConverter;

    /**
     * @param config               the entity configuration; its id must be a persistent attribute of the entity
     * @param entityManagerFactory the persistence unit that holds the entity
     * @param executor             runs the queries
     * @param clock                gives today for {@code :daysAgo}
     * @throws JpaMapException when the entity, its id attribute or the id type does not fit
     */
    public JpaQueryHandler(EntityConfigureOf<T> config, EntityManagerFactory entityManagerFactory, Executor executor,
                           Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.evaluator = new EntityExpressionEvaluator<>(config, clock);
        this.idConverter = idConverter(config, entityManagerFactory);
    }

    /** Runs the queries on virtual threads. */
    public JpaQueryHandler(EntityConfigureOf<T> config, EntityManagerFactory entityManagerFactory) {
        this(config, entityManagerFactory, VIRTUAL_THREADS, Clock.systemDefaultZone());
    }

    /** Whether the persistence unit holds the entity type, so that a {@code JpaQueryHandler} can answer for it. */
    public static boolean isManaged(EntityManagerFactory entityManagerFactory, Class<?> entityType) {
        return entityManagerFactory.getMetamodel().getManagedTypes().stream()
                .anyMatch(type -> type.getJavaType() == entityType);
    }

    @Override
    public DistributedKeyRef key() {
        return config.key();
    }

    @Override
    public Class<?> entityType() {
        return config.entityType();
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
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var byId = new HashMap<Object, T>();
            for (var chunk : chunks(new ArrayList<>(ids))) {
                for (var row : find(entityManager, chunk))
                    byId.putIfAbsent(row.get(0), config.entityType().cast(row.get(1)));
            }
            // Evaluated before the entity manager closes, so that lazy associations can still load
            return evaluator.answer(request, id -> {
                var converted = idConverter.convert(id);
                return converted == null ? null : byId.get(converted);
            });
        }
    }

    /** The configured id and the entity, since the configured id need not be the JPA {@code @Id}. */
    private List<Tuple> find(EntityManager entityManager, List<Object> ids) {
        var builder = entityManager.getCriteriaBuilder();
        var query = builder.createTupleQuery();
        var root = query.from(config.entityType());
        var id = root.get(config.idProperty());
        query.select(builder.tuple(id, root)).where(id.in(ids));
        return entityManager.createQuery(query).getResultList();
    }

    private static List<List<Object>> chunks(List<Object> ids) {
        var chunks = new ArrayList<List<Object>>();
        for (int start = 0; start < ids.size(); start += DEFAULT_BATCH_SIZE)
            chunks.add(ids.subList(start, Math.min(ids.size(), start + DEFAULT_BATCH_SIZE)));
        return chunks;
    }

    private static IdConverter idConverter(EntityConfigureOf<?> config, EntityManagerFactory entityManagerFactory) {
        var entityType = config.entityType();
        jakarta.persistence.metamodel.EntityType<?> metamodel;
        try {
            metamodel = entityManagerFactory.getMetamodel().entity(entityType);
        } catch (IllegalArgumentException e) {
            throw new JpaMapException.EntityNotManaged(entityType, e);
        }
        Class<?> idType;
        try {
            idType = metamodel.getAttribute(config.idProperty()).getJavaType();
        } catch (IllegalArgumentException e) {
            throw new JpaMapException.IdAttributeNotFound(entityType, config.idProperty(), e);
        }
        var converter = IdConverter.of(idType);
        if (converter == null) throw new JpaMapException.UnsupportedIdType(entityType, idType);
        return converter;
    }
}
