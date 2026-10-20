package io.github.quyvu01.jfxmap.jpa;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.expressions.evaluation.BeanPropertyAccessor;
import io.github.quyvu01.jfxmap.expressions.evaluation.ExpressionEvaluationException;
import io.github.quyvu01.jfxmap.expressions.evaluation.ExpressionEvaluator;
import io.github.quyvu01.jfxmap.expressions.nodes.ExpressionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParseException;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;
import io.github.quyvu01.jfxmap.expressions.tokens.ExpressionTokenizeException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.internal.Json;
import io.github.quyvu01.jfxmap.provider.EntityExpressionEvaluator;
import io.github.quyvu01.jfxmap.provider.IdConverter;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Answers the requests for one entity from a JPA persistence unit (FxMap's EntityFrameworkQueryHandler).
 *
 * <p>The expressions are translated into SQL, as FxMap's EF Core handler translates them into LINQ: each chunk of
 * {@value #DEFAULT_BATCH_SIZE} ids is one query, {@code select e.id, <one or more columns per expression> from Entity
 * e where e.id in :ids} (with the id of the entity configuration), in which navigations are left joins, functions and
 * conditions are SQL, and the aggregations of collections ({@code Orders:where(Status = 'Done'):sum(Total)}) are
 * subqueries. An expression that gives a list of items ({@code Orders:orderBy(Total desc):limit(3).{Id, Total}}) is
 * one more query, filtered and ordered by the database. Only the expressions that cannot be read from columns are
 * evaluated in memory, on the entities loaded while the entity manager is open: collections of whole entities
 * ({@code Orders:where(...)} with no projection), {@code :groupBy} and non-persistent properties. See
 * {@link #runsInDatabase(String)}.
 *
 * <p>The answers are those of the in-memory handler, with the semantics of the database where they differ: a
 * navigation through a null association ({@code Country.Name}) is null, as in FxMap's EF Core handler, instead of an
 * error; strings are ordered, compared and trimmed by the database; {@code :round} and {@code :substring} out of range
 * follow the database.
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
    private final ExpressionEvaluator valueEvaluator;
    private final IdConverter idConverter;
    // The expressions the persistence provider rejected once
    private final Set<ExpressionNode> rejected = ConcurrentHashMap.newKeySet();

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
        this.valueEvaluator = new ExpressionEvaluator(new BeanPropertyAccessor(), clock);
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

    /**
     * Whether an expression is answered by the database, rather than evaluated in memory on the loaded entities.
     *
     * @param expression the expression, or {@code null} for the default property
     */
    public boolean runsInDatabase(String expression) {
        var nodes = parse(Collections.singletonList(expression));
        if (nodes.getFirst() == null) return false;
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return compile(entityManager, nodes).plan().readers()[0] != null;
        }
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
        // Nothing to query: the expressions are still checked
        if (ids.isEmpty()) return evaluator.answer(request, id -> null);

        var expressions = request.expressions();
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var compiled = compile(entityManager, parse(expressions));
            var readers = compiled.plan().readers();
            var rows = new HashMap<Object, Object[]>();
            for (var chunk : chunks(new ArrayList<>(ids))) {
                for (var row : compiled.query().setParameter(compiled.plan().ids(), chunk).getResultList())
                    rows.putIfAbsent(row[0], row);
                for (var i = 0; i < compiled.collections().size(); i++) {
                    var collection = compiled.plan().collections().get(i);
                    collection.add(compiled.collections().get(i).setParameter(collection.ids, chunk)
                            .getResultList());
                }
            }

            var errors = new LinkedHashMap<String, ExpressionError>();
            var inMemory = inMemory(entityManager, request, readers, rows.keySet(), errors);
            var items = new ArrayList<DataResponse>();
            for (var id : request.selectorIds()) {
                var converted = idConverter.convert(id);
                var row = converted == null ? null : rows.get(converted);
                if (row == null) continue;
                var values = new ArrayList<ValueResponse>(expressions.size());
                for (var i = 0; i < expressions.size(); i++) {
                    var expression = expressions.get(i);
                    if (errors.containsKey(expression)) continue;
                    if (readers[i] == null) {
                        var value = inMemory.get(id).get(expression);
                        if (value != null) values.add(value);
                        continue;
                    }
                    try {
                        var value = readers[i].read(row);
                        values.add(new ValueResponse(expression, value == null ? null : Json.write(value)));
                    } catch (ExpressionEvaluationException e) {
                        errors.put(expression, new ExpressionError(expression, e.getMessage()));
                    }
                }
                items.add(new DataResponse(id, values));
            }
            // An expression that failed on a later entity is dropped from the earlier ones too
            if (!errors.isEmpty())
                items.replaceAll(item -> new DataResponse(item.id(), item.values().stream()
                        .filter(v -> !errors.containsKey(v.expression())).toList()));
            var ordered = new ArrayList<ExpressionError>();
            for (var expression : new LinkedHashSet<>(expressions)) {
                var error = errors.get(expression);
                if (error != null) ordered.add(error);
            }
            return new ItemsResponse(items, ordered);
        }
    }

    /**
     * Evaluates the expressions that are not translated on the entities found, loaded with the entity manager that
     * is still open, so that lazy associations can load.
     *
     * @return the values of each requested id, by expression
     */
    private Map<String, Map<String, ValueResponse>> inMemory(EntityManager entityManager, MapRequest request,
                                                             SqlTranslator.Reader[] readers, Set<Object> found,
                                                             Map<String, ExpressionError> errors) {
        var expressions = new ArrayList<String>();
        for (var i = 0; i < readers.length; i++) {
            var expression = request.expressions().get(i);
            if (readers[i] == null && !expressions.contains(expression)) expressions.add(expression);
        }
        if (expressions.isEmpty()) return Map.of();

        var byId = new HashMap<Object, T>();
        for (var chunk : chunks(new ArrayList<>(found))) {
            for (var row : find(entityManager, chunk))
                byId.putIfAbsent(row.get(0), config.entityType().cast(row.get(1)));
        }
        var answer = evaluator.answer(new MapRequest(request.selectorIds(), expressions), id -> {
            var converted = idConverter.convert(id);
            return converted == null ? null : byId.get(converted);
        });
        for (var error : answer.errors()) errors.put(error.expression(), error);
        var values = new HashMap<String, Map<String, ValueResponse>>();
        for (var item : answer.items()) {
            var byExpression = new HashMap<String, ValueResponse>();
            for (var value : item.values()) byExpression.put(value.expression(), value);
            values.putIfAbsent(item.id(), byExpression);
        }
        return values;
    }

    /** The parsed expressions; null for those evaluated in memory, which report their own errors. */
    private List<ExpressionNode> parse(List<String> expressions) {
        var nodes = new ArrayList<ExpressionNode>(expressions.size());
        for (var expression : expressions) {
            if (expression == null) {
                nodes.add(config.defaultProperty() == null ? null : new PropertyNode(config.defaultProperty()));
                continue;
            }
            try {
                nodes.add(ExpressionParser.parse(expression));
            } catch (ExpressionTokenizeException | ExpressionParseException e) {
                nodes.add(null);
            }
        }
        return nodes;
    }

    record Compiled(SqlTranslator.Plan plan, TypedQuery<Object[]> query, List<TypedQuery<Object[]>> collections) {
    }

    /**
     * Plans the queries and has the persistence provider compile them. When the provider rejects one, each expression
     * is compiled alone, and those it rejects are evaluated in memory, from then on.
     */
    Compiled compile(EntityManager entityManager, List<ExpressionNode> nodes) {
        var translator = new SqlTranslator(entityManager.getCriteriaBuilder(), entityManager.getMetamodel(), config,
                valueEvaluator);
        var skip = new HashSet<Integer>();
        for (var i = 0; i < nodes.size(); i++)
            if (nodes.get(i) != null && rejected.contains(nodes.get(i))) skip.add(i);
        try {
            return compile(entityManager, translator.plan(nodes, skip));
        } catch (RuntimeException e) {
            for (var i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) == null || skip.contains(i)) continue;
                var others = new HashSet<Integer>();
                for (var j = 0; j < nodes.size(); j++) if (j != i) others.add(j);
                try {
                    compile(entityManager, translator.plan(nodes, others));
                } catch (RuntimeException rejection) {
                    rejected.add(nodes.get(i));
                    skip.add(i);
                }
            }
            return compile(entityManager, translator.plan(nodes, skip));
        }
    }

    private static Compiled compile(EntityManager entityManager, SqlTranslator.Plan plan) {
        var collections = new ArrayList<TypedQuery<Object[]>>();
        for (var collection : plan.collections()) collections.add(entityManager.createQuery(collection.query));
        return new Compiled(plan, entityManager.createQuery(plan.query()), collections);
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
