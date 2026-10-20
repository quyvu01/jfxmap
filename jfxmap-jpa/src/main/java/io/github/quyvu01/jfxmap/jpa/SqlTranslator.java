package io.github.quyvu01.jfxmap.jpa;

import io.github.quyvu01.jfxmap.expressions.evaluation.ExpressionEvaluator;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationType;
import io.github.quyvu01.jfxmap.expressions.nodes.BinaryConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.CoalesceNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ComparisonOperator;
import io.github.quyvu01.jfxmap.expressions.nodes.ConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ExpressionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FilterNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.LiteralNode;
import io.github.quyvu01.jfxmap.expressions.nodes.LiteralType;
import io.github.quyvu01.jfxmap.expressions.nodes.LogicalConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.LogicalOperator;
import io.github.quyvu01.jfxmap.expressions.nodes.NavigationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.OrderDirection;
import io.github.quyvu01.jfxmap.expressions.nodes.ProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ProjectionProperty;
import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.RootProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.SortKeyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.TernaryNode;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.LocalDateField;
import jakarta.persistence.criteria.LocalDateTimeField;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.criteria.TemporalField;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Translates FxMap expressions into JPA Criteria queries, the way FxMap's EF Core handler translates them into LINQ
 * for the database (FxMap's LinqExpressionBuilder).
 *
 * <p>A request becomes one main query, {@code select id, <columns> from Entity e where id in :ids}, in which:
 * <ul>
 *   <li>properties and navigations through singular associations and embeddables are columns, with left joins;</li>
 *   <li>the string, date and math functions, conditions, {@code ??} and {@code ?:} are SQL functions and
 *   {@code case} expressions;</li>
 *   <li>the aggregations of a collection ({@code :count}, {@code :sum}, {@code :avg}, {@code :min}, {@code :max},
 *   {@code :any}, {@code :all}), after any {@code :where} and {@code :orderBy}, are correlated subqueries.</li>
 * </ul>
 * An expression that gives a list of projected items ({@code Orders:where(...).{Id, Total}},
 * {@code Orders:orderBy(Total desc):first.{Id, Total}}, {@code Orders:distinct(Status)}) runs as one more query,
 * {@code select id, <item columns> from Entity e join e.orders o where id in :ids and <filters> order by <keys>}, whose
 * rows are grouped by owner.
 *
 * <p>A function that SQL cannot compute the same way as the in-memory evaluator ({@code :format}, {@code :daysAgo},
 * {@code :dayOfWeek}, {@code :split}, decimal division, {@code :offset}/{@code :limit}/{@code :first} of projected
 * items...) is applied in memory to the values the query read: the data still comes from the database. What cannot
 * be read from columns at all, collections of whole entities ({@code Orders:where(...)} with no projection, their
 * {@code :first}), {@code :groupBy} and non-persistent properties, is {@link Untranslatable}: the handler evaluates
 * that expression on the loaded entities instead.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class SqlTranslator {
    private static final String SOURCE = "$source";
    private static final Set<FunctionType> VALUE_ARGUMENT_FUNCTIONS = Set.of(FunctionType.SUBSTRING,
            FunctionType.REPLACE, FunctionType.CONCAT, FunctionType.SPLIT, FunctionType.FORMAT, FunctionType.ROUND,
            FunctionType.ADD, FunctionType.SUBTRACT, FunctionType.MULTIPLY, FunctionType.DIVIDE, FunctionType.MOD,
            FunctionType.POW);
    private static final Set<FunctionType> ITEM_FUNCTIONS = Set.of(FunctionType.FIRST, FunctionType.LAST,
            FunctionType.SKIP, FunctionType.TAKE);

    private final CriteriaBuilder cb;
    private final Metamodel metamodel;
    private final EntityConfigureOf<?> config;
    private final ExpressionEvaluator evaluator;

    /**
     * @param evaluator applies in memory the functions that SQL does not compute, to the values read from the database
     */
    SqlTranslator(CriteriaBuilder cb, Metamodel metamodel, EntityConfigureOf<?> config, ExpressionEvaluator evaluator) {
        this.cb = cb;
        this.metamodel = metamodel;
        this.config = config;
        this.evaluator = evaluator;
    }

    /** A part of an expression that SQL cannot answer: the whole expression is evaluated on the loaded entities. */
    static final class Untranslatable extends RuntimeException {
        Untranslatable(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Reads the value of an expression from a row of its query. */
    @FunctionalInterface
    interface Reader {
        Object read(Object[] row);
    }

    /** The selected columns of a query; the readers read them by index. */
    static final class Columns {
        private final List<Selection<?>> selections = new ArrayList<>();

        int add(Expression<?> expression) {
            selections.add(expression);
            return selections.size() - 1;
        }

        List<Selection<?>> selections() {
            return selections;
        }
    }

    /**
     * A query that reads the items of a collection for each owner: {@code select ownerId, <item columns> ...}. Its
     * rows are grouped by owner id when it runs.
     */
    static final class CollectionQuery {
        final CriteriaQuery<Object[]> query;
        final ParameterExpression<Collection> ids;
        final Map<Object, List<Object[]>> rows = new HashMap<>();

        CollectionQuery(CriteriaQuery<Object[]> query, ParameterExpression<Collection> ids) {
            this.query = query;
            this.ids = ids;
        }

        void add(List<Object[]> result) {
            for (var row : result) rows.computeIfAbsent(row[0], id -> new ArrayList<>()).add(row);
        }

        List<Object[]> of(Object ownerId) {
            return rows.getOrDefault(ownerId, List.of());
        }
    }

    /**
     * The queries of a request: the main query (column 0 is the configured id) and the collection queries, and a
     * reader per expression ({@code null} for the expressions evaluated in memory).
     */
    record Plan(CriteriaQuery<Object[]> query, ParameterExpression<Collection> ids, List<CollectionQuery> collections,
                Reader[] readers) {
    }

    /**
     * Where an expression is read from.
     *
     * @param path        the root, a join or an embeddable
     * @param type        its managed type, or null for the basic items of an element collection
     * @param javaType    its Java type
     * @param query       the query or subquery it belongs to
     * @param columns     the columns of that query; null in a subquery
     * @param pathFromRoot the singular attributes from the root of the main query to this scope, when a collection
     *                    query can start from it; null otherwise
     * @param plan        collects the collection queries; null where there cannot be any
     * @param joins       the joins and paths already made from this scope, by attribute
     */
    private record Scope(Path<?> path, ManagedType<?> type, Class<?> javaType, AbstractQuery<?> query, Columns columns,
                         List<String> pathFromRoot, List<CollectionQuery> plan, Map<String, Scope> joins) {
        Scope(Path<?> path, ManagedType<?> type, AbstractQuery<?> query, Columns columns, List<String> pathFromRoot,
              List<CollectionQuery> plan) {
            this(path, type, type == null ? path.getJavaType() : type.getJavaType(), query, columns, pathFromRoot,
                    plan, new HashMap<>());
        }

        From<?, ?> from() {
            if (path instanceof From<?, ?> from) return from;
            throw new Untranslatable("an association of an embeddable");
        }
    }

    /** A SQL expression, its Java type, and a conversion of the value read, which makes it not composable. */
    private record Scalar(Expression<?> expression, Class<?> type, UnaryOperator<Object> post) {
        Scalar(Expression<?> expression, Class<?> type) {
            this(expression, type, null);
        }

        Expression<?> composable() {
            if (post != null) throw new Untranslatable("a value converted after it is read");
            return expression;
        }
    }

    // Plans

    /**
     * Plans the queries of the expressions.
     *
     * @param expressions the parsed expressions; a null node is one evaluated in memory
     * @param skip        the indexes to evaluate in memory
     */
    Plan plan(List<ExpressionNode> expressions, Set<Integer> skip) {
        var query = cb.createQuery(Object[].class);
        Root<?> root = query.from(config.entityType());
        var ids = cb.parameter(Collection.class);
        query.where(root.get(config.idProperty()).in(ids));
        var columns = new Columns();
        columns.add(root.get(config.idProperty()));
        var collections = new ArrayList<CollectionQuery>();
        var scope = new Scope(root, metamodel.entity(config.entityType()), query, columns, List.of(), collections);

        var readers = new Reader[expressions.size()];
        for (var i = 0; i < readers.length; i++) {
            var node = expressions.get(i);
            if (node == null || skip.contains(i)) continue;
            var columnCount = columns.selections.size();
            var collectionCount = collections.size();
            try {
                readers[i] = value(node, scope);
            } catch (Untranslatable | IllegalArgumentException | IllegalStateException e) {
                // What was added for it is not read; the joins it made are left joins of singular associations
                columns.selections.subList(columnCount, columns.selections.size()).clear();
                collections.subList(collectionCount, collections.size()).clear();
            }
        }
        query.select(cb.array(columns.selections));
        return new Plan(query, ids, collections, readers);
    }

    // Values: what an expression gives, read from the columns

    private Reader value(ExpressionNode node, Scope scope) {
        node = normalize(node);
        switch (node) {
            case LiteralNode literal -> {
                var value = literal.value();
                return row -> value;
            }
            case CoalesceNode coalesce -> {
                var left = value(coalesce.left(), scope);
                var right = value(coalesce.right(), scope);
                return row -> {
                    var value = left.read(row);
                    return value != null ? value : right.read(row);
                };
            }
            case TernaryNode ternary -> {
                var condition = column(scope, new Scalar(bool(predicate(ternary.condition(), scope)), Boolean.class));
                var whenTrue = value(ternary.whenTrue(), scope);
                var whenFalse = value(ternary.whenFalse(), scope);
                return row -> Boolean.TRUE.equals(condition.read(row)) ? whenTrue.read(row) : whenFalse.read(row);
            }
            case RootProjectionNode projection -> {
                return project(projection.properties(), scope, null);
            }
            case ProjectionNode projection -> {
                return projection(projection, scope);
            }
            case NavigationNode navigation -> {
                var segments = navigation.segments();
                var target = scope;
                for (var segment : segments.subList(0, segments.size() - 1)) target = navigate(segment, target);
                return value(segments.getLast(), target);
            }
            case AggregationNode aggregation when aggregation.aggregationType() == AggregationType.AVERAGE -> {
                try {
                    return column(scope, scalar(aggregation, scope));
                } catch (Untranslatable e) {
                    return decimalAverage(aggregation, scope);
                }
            }
            case PropertyNode property when collectionOf(property, scope) -> {
                // The values of an element collection
                return items(pipeline(property, scope), scope, item -> column(item, new Scalar(item.path(),
                        item.javaType())), List.of());
            }
            case FunctionNode function when function.functionName() == FunctionType.DISTINCT -> {
                try {
                    return distinct(function, scope);
                } catch (Untranslatable e) {
                    return inMemory(function, scope);
                }
            }
            default -> {
                try {
                    return column(scope, scalar(node, scope));
                } catch (Untranslatable e) {
                    return inMemory(node, scope);
                }
            }
        }
    }

    private Reader column(Scope scope, Scalar scalar) {
        if (scope.columns() == null) throw new Untranslatable("a value of a subquery");
        var index = scope.columns().add(scalar.expression());
        var post = scalar.post();
        return post == null ? row -> row[index] : row -> {
            var value = row[index];
            return value == null ? null : post.apply(value);
        };
    }

    /**
     * A function the database does not compute as the evaluator does, applied in memory to the value read from the
     * database: {@code CreatedAt:format('yyyy-MM-dd')}, {@code Orders.{Id, Total}:limit(2)}...
     */
    private Reader inMemory(ExpressionNode node, Scope scope) {
        var source = switch (node) {
            case FunctionNode function -> function.source();
            case AggregationNode aggregation -> aggregation.source();
            case BooleanFunctionNode function -> function.source();
            default -> throw new Untranslatable(node.getClass().getSimpleName());
        };
        var sourceValue = value(source, scope);
        var placeholders = new LinkedHashMap<String, Reader>();
        placeholders.put(SOURCE, sourceValue);
        var replaced = switch (node) {
            case FunctionNode function -> {
                var arguments = new ArrayList<ExpressionNode>();
                for (var argument : function.getArguments()) {
                    if (VALUE_ARGUMENT_FUNCTIONS.contains(function.functionName())
                            && !(argument instanceof LiteralNode)) {
                        var name = "$argument" + placeholders.size();
                        placeholders.put(name, value(argument, scope));
                        arguments.add(new PropertyNode(name));
                    } else {
                        arguments.add(argument);
                    }
                }
                yield new FunctionNode(new PropertyNode(SOURCE), function.functionName(), null, arguments);
            }
            case AggregationNode aggregation ->
                    new AggregationNode(new PropertyNode(SOURCE), aggregation.aggregationType(),
                            aggregation.propertyName());
            case BooleanFunctionNode function ->
                    new BooleanFunctionNode(new PropertyNode(SOURCE), function.functionName(), function.condition());
            default -> throw new IllegalStateException();
        };
        return row -> {
            var values = new HashMap<String, Object>();
            placeholders.forEach((name, reader) -> values.put(name, reader.read(row)));
            return evaluator.evaluate(replaced, values);
        };
    }

    // The average of decimals as the evaluator computes it: the exact sum divided with 34 digits
    private Reader decimalAverage(AggregationNode aggregation, Scope scope) {
        var count = items(aggregation.source(), scope, Long.class);
        var value = itemValue(count, aggregation.propertyName());
        if (!isDecimal(value.type())) throw new Untranslatable("an average of " + value.type().getSimpleName());
        count.query().select((Expression) cb.count(value.composable()))
                .where(count.predicates().toArray(Predicate[]::new));
        var total = column(scope, scalar(new AggregationNode(aggregation.source(), AggregationType.SUM,
                aggregation.propertyName()), scope));
        var counted = column(scope, new Scalar(count.query(), Long.class));
        return row -> {
            var n = (Long) counted.read(row);
            if (n == null || n == 0) return null;
            var sum = total.read(row);
            var decimal = sum instanceof BigInteger i ? new BigDecimal(i) : (BigDecimal) sum;
            return decimal.divide(BigDecimal.valueOf(n), MathContext.DECIMAL128);
        };
    }

    // Projections

    private Reader projection(ProjectionNode projection, Scope scope) {
        // A single object: Country.{Id, Name}
        Scope target = null;
        try {
            target = objectScope(projection.source(), scope);
        } catch (Untranslatable e) {
            // a collection
        }
        if (target != null) {
            var present = target.path() instanceof Join<?, ?> join
                    ? column(scope, new Scalar(bool(cb.isNotNull(join)), Boolean.class))
                    : null;
            return project(projection.properties(), target, present);
        }

        // The items of a collection: Orders.{Id, Total}, Orders:orderBy(Total desc):first.{Id, Total}
        var functions = new ArrayList<FunctionNode>();
        var source = normalize(projection.source());
        while (source instanceof FunctionNode function && ITEM_FUNCTIONS.contains(function.functionName())) {
            functions.addFirst(function);
            source = normalize(function.source());
        }
        var properties = projection.properties();
        return items(pipeline(source, scope), scope, item -> project(properties, item, null), functions);
    }

    private Reader project(List<ProjectionProperty> properties, Scope scope, Reader present) {
        var readers = new LinkedHashMap<String, Reader>();
        for (var property : properties) {
            var key = property.outputKey();
            if (readers.containsKey(key)) throw new Untranslatable("two properties named " + key);
            readers.put(key, value(property.isComputed() ? property.expression() : path(property.pathSegments()),
                    scope));
        }
        return row -> {
            var absent = present != null && !Boolean.TRUE.equals(present.read(row));
            var result = new LinkedHashMap<String, Object>();
            readers.forEach((key, reader) -> result.put(key, absent ? null : reader.read(row)));
            return result;
        };
    }

    private static ExpressionNode path(String[] segments) {
        if (segments.length == 1) return new PropertyNode(segments[0]);
        var nodes = new ArrayList<ExpressionNode>();
        for (var segment : segments) nodes.add(new PropertyNode(segment));
        return new NavigationNode(nodes);
    }

    // Collections read by a collection query

    /**
     * A collection and its filters and order, as written: {@code Country.Users:where(...):orderBy(Name)}.
     *
     * @param path   the singular attributes from the scope to the owner of the collection
     * @param plural the collection attribute
     * @param keys   the sort keys, the last orderBy first
     */
    private record Pipeline(List<ExpressionNode> path, PropertyNode plural, List<ConditionNode> filters,
                            List<SortKeyNode> keys) {
        Pipeline filter(ConditionNode condition) {
            var all = new ArrayList<>(filters);
            all.add(condition);
            return new Pipeline(path, plural, all, keys);
        }

        Pipeline orderBy(List<SortKeyNode> newKeys) {
            // A stable sort by the new keys keeps the previous order among equal items
            var all = new ArrayList<>(newKeys);
            all.addAll(keys);
            return new Pipeline(path, plural, filters, all);
        }
    }

    private Pipeline pipeline(ExpressionNode node, Scope scope) {
        node = normalize(node);
        switch (node) {
            case PropertyNode property -> {
                var attribute = attribute(scope, property.name());
                if (!(attribute instanceof PluralAttribute<?, ?, ?>))
                    throw new Untranslatable(property.name() + " is not a collection");
                return new Pipeline(List.of(), property, List.of(), List.of());
            }
            case NavigationNode navigation -> {
                var segments = navigation.segments();
                var target = scope;
                for (var segment : segments.subList(0, segments.size() - 1)) target = navigate(segment, target);
                var inner = pipeline(segments.getLast(), target);
                var path = new ArrayList<>(segments.subList(0, segments.size() - 1));
                path.addAll(inner.path());
                return new Pipeline(path, inner.plural(), inner.filters(), inner.keys());
            }
            case FilterNode filter -> {
                return pipeline(filter.source(), scope).filter(filter.condition());
            }
            case FunctionNode function when function.functionName() == FunctionType.ORDER_BY -> {
                return pipeline(function.source(), scope).orderBy(sortKeys(function));
            }
            default -> throw new Untranslatable("a collection of " + node.getClass().getSimpleName());
        }
    }

    private static List<SortKeyNode> sortKeys(FunctionNode orderBy) {
        var keys = new ArrayList<SortKeyNode>();
        for (var argument : orderBy.getArguments()) {
            switch (argument) {
                case SortKeyNode key -> keys.add(key);
                case PropertyNode property -> keys.add(new SortKeyNode(property.name()));
                case LiteralNode literal when literal.literalType() == LiteralType.STRING ->
                        keys.add(new SortKeyNode((String) literal.value()));
                default -> throw new Untranslatable("an orderBy argument " + argument);
            }
        }
        if (keys.isEmpty()) throw new Untranslatable("an orderBy without keys");
        return keys;
    }

    /**
     * The items of a collection, from a collection query: {@code select ownerId, <item columns> from Entity e join
     * <path> o where e.id in :ids and <filters> order by <keys>, <item id>}. Then the functions are applied in memory
     * to the list of items, and the first or last of no item is an item with null properties, as the evaluator gives
     * it.
     */
    private Reader items(Pipeline pipeline, Scope scope, Function<Scope, Reader> item, List<FunctionNode> functions) {
        if (scope.plan() == null || scope.pathFromRoot() == null)
            throw new Untranslatable("a collection inside a collection");
        var query = cb.createQuery(Object[].class);
        Root<?> root = query.from(config.entityType());
        var ids = cb.parameter(Collection.class);
        var columns = new Columns();
        columns.add(root.get(config.idProperty()));
        var owner = new Scope(root, metamodel.entity(config.entityType()), query, columns, null, null);
        for (var name : scope.pathFromRoot()) owner = navigate(new PropertyNode(name), owner);
        for (var segment : pipeline.path()) owner = navigate(segment, owner);
        var join = owner.from().join(attribute(owner, pipeline.plural().name()).getName(), JoinType.INNER);
        var itemScope = new Scope(join, managedType(join), query, columns, null, null);

        var reader = item.apply(itemScope);
        var predicates = new ArrayList<Predicate>();
        predicates.add(root.get(config.idProperty()).in(ids));
        for (var filter : pipeline.filters()) predicates.add(predicate(filter, itemScope));
        var orders = new ArrayList<Order>();
        for (var key : pipeline.keys()) {
            var expression = scalar(new PropertyNode(key.name()), itemScope).composable();
            // The evaluator puts null first
            orders.add(key.direction() == OrderDirection.ASC ? cb.asc(expression, Nulls.FIRST)
                    : cb.desc(expression, Nulls.LAST));
        }
        var id = idName(itemScope.type());
        if (id != null) orders.add(cb.asc(join.get(id)));
        query.select(cb.array(columns.selections())).where(predicates.toArray(Predicate[]::new)).orderBy(orders);

        var collection = new CollectionQuery(query, ids);
        scope.plan().add(collection);
        var replaced = new ArrayList<FunctionNode>();
        for (var function : functions)
            replaced.add(new FunctionNode(new PropertyNode(SOURCE), function.functionName(), null,
                    function.getArguments()));
        var single = !functions.isEmpty() && (functions.getLast().functionName() == FunctionType.FIRST
                || functions.getLast().functionName() == FunctionType.LAST);
        return row -> {
            Object value = collection.of(row[0]).stream().map(reader::read).toList();
            for (var function : replaced) {
                var values = new HashMap<String, Object>();
                values.put(SOURCE, value);
                value = evaluator.evaluate(function, values);
            }
            // The projection of no item: its properties are null
            if (value == null && single) return reader.read(new Object[columns.selections().size()]);
            return value;
        };
    }

    // Orders:distinct(Status): the values of the property, as maps the evaluator reads by name
    private Reader distinct(FunctionNode function, Scope scope) {
        var arguments = function.getArguments();
        if (arguments.size() != 1) throw new Untranslatable("distinct without one property");
        var name = switch (arguments.getFirst()) {
            case PropertyNode property -> property.name();
            case LiteralNode literal when literal.literalType() == LiteralType.STRING -> (String) literal.value();
            default -> throw new Untranslatable("a distinct argument");
        };
        var replaced = new FunctionNode(new PropertyNode(SOURCE), FunctionType.DISTINCT, null, arguments);
        var items = items(pipeline(function.source(), scope), scope, item -> {
            var property = value(new PropertyNode(name), item);
            return row -> {
                var map = new HashMap<String, Object>();
                map.put(name, property.read(row));
                return map;
            };
        }, List.of());
        return row -> {
            var values = new HashMap<String, Object>();
            values.put(SOURCE, items.read(row));
            return evaluator.evaluate(replaced, values);
        };
    }

    private boolean collectionOf(PropertyNode property, Scope scope) {
        if (scope.type() == null) return false;
        var attribute = attribute(scope, property.name());
        return attribute instanceof PluralAttribute<?, ?, ?> plural
                && plural.getElementType().getPersistenceType() == Type.PersistenceType.BASIC;
    }

    // Scalars: values computed by the database

    private Scalar scalar(ExpressionNode node, Scope scope) {
        node = normalize(node);
        return switch (node) {
            case PropertyNode property -> property(property, scope);
            case NavigationNode navigation -> {
                var segments = navigation.segments();
                var target = scope;
                for (var segment : segments.subList(0, segments.size() - 1)) target = navigate(segment, target);
                yield scalar(segments.getLast(), target);
            }
            case LiteralNode literal -> {
                if (literal.value() == null) throw new Untranslatable("a null literal");
                yield new Scalar(cb.literal(literal.value()), literal.value().getClass());
            }
            case ConditionNode condition -> new Scalar(bool(predicate(condition, scope)), Boolean.class);
            case BooleanFunctionNode function -> new Scalar(bool(booleanFunction(function, scope)), Boolean.class);
            case CoalesceNode coalesce -> {
                var left = scalar(coalesce.left(), scope);
                var right = scalar(coalesce.right(), scope);
                yield new Scalar(cb.coalesce((Expression) left.composable(), (Expression) right.composable()),
                        left.type());
            }
            case TernaryNode ternary -> {
                var whenTrue = scalar(ternary.whenTrue(), scope);
                var whenFalse = scalar(ternary.whenFalse(), scope);
                yield new Scalar(cb.selectCase().when(predicate(ternary.condition(), scope),
                        (Expression) whenTrue.composable()).otherwise((Expression) whenFalse.composable()),
                        whenTrue.type());
            }
            case AggregationNode aggregation -> aggregation(aggregation, scope);
            case FunctionNode function -> function(function, scope);
            default -> throw new Untranslatable(node.getClass().getSimpleName());
        };
    }

    private Scalar property(PropertyNode node, Scope scope) {
        if (scope.type() == null) throw new Untranslatable("a property of a basic value");
        var attribute = attribute(scope, node.name());
        if (attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC)
            throw new Untranslatable(node.name() + " is not a basic value");
        var path = scope.path().get(attribute.getName());
        return new Scalar(path, wrap(attribute.getJavaType()));
    }

    // Country, Address: a left join of a singular association, or the path of an embeddable
    private Scope navigate(ExpressionNode segment, Scope scope) {
        if (!(segment instanceof PropertyNode property)) throw new Untranslatable("a navigation from a function");
        if (scope.type() == null) throw new Untranslatable("a navigation from a basic value");
        var attribute = attribute(scope, property.name());
        var name = attribute.getName();
        var existing = scope.joins().get(name);
        if (existing != null) return existing;
        var pathFromRoot = scope.pathFromRoot() == null ? null : append(scope.pathFromRoot(), name);
        Scope target = switch (attribute.getPersistentAttributeType()) {
            case MANY_TO_ONE, ONE_TO_ONE -> {
                Join<?, ?> join = scope.from().join(name, JoinType.LEFT);
                yield new Scope(join, managedType(join), scope.query(), scope.columns(), pathFromRoot, scope.plan());
            }
            case EMBEDDED -> {
                Path<?> path = scope.path().get(name);
                yield new Scope(path, metamodel.embeddable(path.getJavaType()), scope.query(), scope.columns(), null,
                        scope.plan());
            }
            default -> throw new Untranslatable("a navigation through " + name);
        };
        scope.joins().put(name, target);
        return target;
    }

    private Scope objectScope(ExpressionNode node, Scope scope) {
        node = normalize(node);
        return switch (node) {
            case PropertyNode property -> navigate(property, scope);
            case NavigationNode navigation -> {
                var target = scope;
                for (var segment : navigation.segments()) target = navigate(segment, target);
                yield target;
            }
            default -> throw new Untranslatable("an object of " + node.getClass().getSimpleName());
        };
    }

    // Aggregations: correlated subqueries

    private record Items(Subquery<Object> query, Scope item, List<Predicate> predicates) {
    }

    /** The items of a collection in a subquery that gives a {@code type}, correlated with the scope. */
    private Items items(ExpressionNode source, Scope scope, Class<?> type) {
        source = normalize(source);
        switch (source) {
            case PropertyNode property -> {
                var attribute = attribute(scope, property.name());
                if (!(attribute instanceof PluralAttribute<?, ?, ?>))
                    throw new Untranslatable(property.name() + " is not a collection");
                Subquery<Object> query = (Subquery<Object>) scope.query().subquery(type);
                From<?, ?> correlated = switch (scope.from()) {
                    case Root<?> root -> query.correlate(root);
                    case Join<?, ?> join -> query.correlate(join);
                    default -> throw new Untranslatable("a collection of " + scope.path());
                };
                Join<?, ?> join = correlated.join(attribute.getName(), JoinType.INNER);
                var item = new Scope(join, managedType(join), query, null, null, null);
                return new Items(query, item, new ArrayList<>());
            }
            case NavigationNode navigation -> {
                var segments = navigation.segments();
                var target = scope;
                for (var segment : segments.subList(0, segments.size() - 1)) target = navigate(segment, target);
                return items(segments.getLast(), target, type);
            }
            case FilterNode filter -> {
                var items = items(filter.source(), scope, type);
                items.predicates().add(predicate(filter.condition(), items.item()));
                return items;
            }
            // The order does not change an aggregation
            case FunctionNode function when function.functionName() == FunctionType.ORDER_BY -> {
                return items(function.source(), scope, type);
            }
            default -> throw new Untranslatable("the items of " + source.getClass().getSimpleName());
        }
    }

    private Scalar itemValue(Items items, String propertyName) {
        if (propertyName != null) return property(new PropertyNode(propertyName), items.item());
        if (items.item().type() != null) throw new Untranslatable("an aggregation of entities");
        return new Scalar(items.item().path(), wrap(items.item().javaType()));
    }

    private Scalar aggregation(AggregationNode node, Scope scope) {
        if (node.aggregationType() == AggregationType.COUNT) {
            var items = items(node.source(), scope, Long.class);
            items.query().select((Expression) cb.count(items.item().path()))
                    .where(items.predicates().toArray(Predicate[]::new));
            return new Scalar(items.query(), Long.class);
        }
        // The subquery is typed with what it gives, which the value of the items tells
        var probe = itemValue(items(node.source(), scope, Object.class), node.propertyName()).type();
        var items = items(node.source(), scope, switch (node.aggregationType()) {
            case SUM -> zero(probe).getClass();
            case AVERAGE -> Double.class;
            default -> probe;
        });
        var query = items.query();
        query.where(items.predicates().toArray(Predicate[]::new));
        var value = itemValue(items, node.propertyName());
        var expression = (Expression) value.composable();
        return switch (node.aggregationType()) {
            case SUM -> {
                requireNumber(value);
                query.select(cb.sum(expression));
                // The sum of no value is 0, of the type the database sums to
                var zero = zero(value.type());
                yield new Scalar(cb.coalesce((Expression) query, zero), zero.getClass());
            }
            case AVERAGE -> {
                requireNumber(value);
                if (isDecimal(value.type())) throw new Untranslatable("an average of decimals");
                query.select(cb.avg(expression));
                yield new Scalar(query, Double.class);
            }
            case MIN -> {
                query.select(cb.least(expression));
                yield new Scalar(query, value.type());
            }
            case MAX -> {
                query.select(cb.greatest(expression));
                yield new Scalar(query, value.type());
            }
            default -> throw new IllegalStateException();
        };
    }

    private Predicate booleanFunction(BooleanFunctionNode node, Scope scope) {
        if (node.functionName() == BooleanFunctionType.ALL && node.condition() == null) return cb.conjunction();
        var items = items(node.source(), scope, node.functionName() == BooleanFunctionType.ANY ? Integer.class
                : Long.class);
        if (node.functionName() == BooleanFunctionType.ANY) {
            if (node.condition() != null) items.predicates().add(predicate(node.condition(), items.item()));
            items.query().select((Expression) cb.literal(1)).where(items.predicates().toArray(Predicate[]::new));
            return cb.exists(items.query());
        }
        // All: as many items match the condition as there are items
        items.query().select((Expression) cb.count(items.item().path()))
                .where(items.predicates().toArray(Predicate[]::new));
        var matching = items(node.source(), scope, Long.class);
        matching.predicates().add(predicate(node.condition(), matching.item()));
        matching.query().select((Expression) cb.count(matching.item().path()))
                .where(matching.predicates().toArray(Predicate[]::new));
        return cb.equal(items.query(), matching.query());
    }

    // Functions

    private Scalar function(FunctionNode node, Scope scope) {
        var arguments = node.getArguments();
        return switch (node.functionName()) {
            case COUNT -> {
                Scalar source;
                try {
                    source = aggregation(new AggregationNode(node.source(), AggregationType.COUNT), scope);
                } catch (Untranslatable e) {
                    source = null;
                }
                if (source != null) yield source;
                // The length of a string
                yield new Scalar(cb.length(string(scalar(node.source(), scope))), Integer.class);
            }
            case UPPER -> new Scalar(cb.upper(string(scalar(node.source(), scope))), String.class);
            case LOWER -> new Scalar(cb.lower(string(scalar(node.source(), scope))), String.class);
            case TRIM -> new Scalar(cb.trim(string(scalar(node.source(), scope))), String.class);
            case SUBSTRING -> {
                var source = string(scalar(node.source(), scope));
                var start = wholeNumber(arguments.getFirst());
                yield new Scalar(arguments.size() > 1
                        ? cb.substring(source, start + 1, wholeNumber(arguments.get(1)))
                        : cb.substring(source, start + 1), String.class);
            }
            case REPLACE -> {
                var source = string(scalar(node.source(), scope));
                var oldValue = stringLiteral(arguments.getFirst());
                if (oldValue == null || oldValue.isEmpty()) throw new Untranslatable("replace of nothing");
                var newValue = arguments.size() > 1 ? stringLiteral(arguments.get(1)) : null;
                yield new Scalar(cb.replace(source, oldValue, newValue == null ? "" : newValue), String.class);
            }
            case CONCAT -> {
                // As string.Concat: a null part is empty
                var parts = new ArrayList<Expression<String>>();
                parts.add(cb.coalesce(string(scalar(node.source(), scope)), ""));
                for (var argument : arguments) {
                    if (argument instanceof LiteralNode literal) {
                        if (literal.value() != null) parts.add(cb.literal(literal.value() instanceof BigDecimal d
                                ? d.toPlainString() : literal.value().toString()));
                    } else {
                        parts.add(cb.coalesce(string(scalar(argument, scope)), ""));
                    }
                }
                yield new Scalar(cb.concat(parts), String.class);
            }
            case YEAR -> datePart(node, scope, LocalDateField.YEAR, LocalDateTimeField.YEAR);
            case MONTH -> datePart(node, scope, LocalDateField.MONTH, LocalDateTimeField.MONTH);
            case DAY -> datePart(node, scope, LocalDateField.DAY, LocalDateTimeField.DAY);
            case HOUR -> datePart(node, scope, null, LocalDateTimeField.HOUR);
            case MINUTE -> datePart(node, scope, null, LocalDateTimeField.MINUTE);
            case SECOND -> {
                var seconds = datePart(node, scope, null, LocalDateTimeField.SECOND);
                // The database gives the fraction of the second too
                yield new Scalar(seconds.expression(), Integer.class, value -> ((Number) value).intValue());
            }
            case ROUND -> {
                var source = number(scalar(node.source(), scope));
                var decimals = arguments.isEmpty() ? 0 : wholeNumber(arguments.getFirst());
                yield new Scalar(cb.round(source, decimals), Double.class, SqlTranslator::toDouble);
            }
            case FLOOR -> new Scalar(cb.floor(number(scalar(node.source(), scope))), Double.class,
                    SqlTranslator::toDouble);
            case CEIL -> new Scalar(cb.ceiling(number(scalar(node.source(), scope))), Double.class,
                    SqlTranslator::toDouble);
            case ABS -> {
                var source = scalar(node.source(), scope);
                yield new Scalar(cb.abs(number(source)), source.type());
            }
            case POW -> new Scalar(cb.power(number(scalar(node.source(), scope)),
                    number(operand(arguments, scope))), Double.class);
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MOD -> arithmetic(node, scope);
            default -> throw new Untranslatable(node.functionName().pascalName());
        };
    }

    private Scalar datePart(FunctionNode node, Scope scope, TemporalField dateField, TemporalField dateTimeField) {
        var source = scalar(node.source(), scope);
        var field = source.type() == LocalDateTime.class ? dateTimeField
                : source.type() == LocalDate.class ? dateField : null;
        if (field == null)
            throw new Untranslatable(node.functionName().pascalName() + " of " + source.type().getSimpleName());
        return new Scalar(cb.extract(field, (Expression) source.composable()), Integer.class);
    }

    private Scalar arithmetic(FunctionNode node, Scope scope) {
        var left = scalar(node.source(), scope);
        var right = operand(node.getArguments(), scope);
        var kind = widest(numberType(left), numberType(right));
        var l = number(left);
        var r = number(right);
        return switch (node.functionName()) {
            case ADD -> new Scalar(cb.sum(l, r), kind);
            case SUBTRACT -> new Scalar(cb.diff(l, r), kind);
            case MULTIPLY -> new Scalar(cb.prod(l, r), kind);
            // Only whole numbers divide the same way in SQL (5 / 2 is 2)
            case DIVIDE -> {
                if (!isWhole(kind)) throw new Untranslatable("a division of " + kind.getSimpleName());
                yield new Scalar(cb.quot(l, r), kind);
            }
            case MOD -> {
                if (!isWhole(kind)) throw new Untranslatable("a remainder of " + kind.getSimpleName());
                yield new Scalar(cb.mod((Expression<Integer>) (Expression) l, (Expression<Integer>) (Expression) r),
                        kind);
            }
            default -> throw new IllegalStateException();
        };
    }

    private Scalar operand(List<ExpressionNode> arguments, Scope scope) {
        if (arguments.isEmpty()) throw new Untranslatable("an operation without an operand");
        var argument = arguments.getFirst();
        if (argument instanceof LiteralNode literal) {
            if (!(literal.value() instanceof BigDecimal number)) throw new Untranslatable("a non-number operand");
            // A number literal is a decimal for the evaluator (7:divide(2) is 3.5); a whole one is an int in SQL
            try {
                return new Scalar(cb.literal(number.intValueExact()), BigDecimal.class);
            } catch (ArithmeticException e) {
                return new Scalar(cb.literal(number), BigDecimal.class);
            }
        }
        return scalar(argument, scope);
    }

    // Conditions

    private Predicate predicate(ConditionNode condition, Scope scope) {
        return switch (condition) {
            case LogicalConditionNode logical -> logical.operator() == LogicalOperator.AND
                    ? cb.and(predicate(logical.left(), scope), predicate(logical.right(), scope))
                    : cb.or(predicate(logical.left(), scope), predicate(logical.right(), scope));
            case BinaryConditionNode binary -> comparison(binary, scope);
        };
    }

    /**
     * A comparison with the evaluator's results for null: {@code null = null} is true, {@code x != null} is true
     * when x has a value, and an ordering or a string match with a null is false.
     */
    private Predicate comparison(BinaryConditionNode node, Scope scope) {
        var leftNode = normalize(node.left());
        var rightNode = normalize(node.right());
        if (leftNode instanceof LiteralNode && !(rightNode instanceof LiteralNode)) {
            // 'Done' = Status is Status = 'Done'
            var swapped = switch (node.operator()) {
                case EQUAL, NOT_EQUAL -> node.operator();
                case GREATER_THAN -> ComparisonOperator.LESS_THAN;
                case LESS_THAN -> ComparisonOperator.GREATER_THAN;
                case GREATER_THAN_OR_EQUAL ->
                        ComparisonOperator.LESS_THAN_OR_EQUAL;
                case LESS_THAN_OR_EQUAL ->
                        ComparisonOperator.GREATER_THAN_OR_EQUAL;
                default -> throw new Untranslatable("a string match of a literal");
            };
            return comparison(new BinaryConditionNode(rightNode, swapped, leftNode), scope);
        }

        var left = scalar(leftNode, scope);
        var l = (Expression) left.composable();
        if (rightNode instanceof LiteralNode literal) {
            if (literal.value() == null) {
                return switch (node.operator()) {
                    case EQUAL -> cb.isNull(l);
                    case NOT_EQUAL -> cb.isNotNull(l);
                    default -> cb.disjunction();
                };
            }
            var value = literalOf(literal.value(), left.type());
            return switch (node.operator()) {
                case EQUAL -> cb.equal(l, value);
                case NOT_EQUAL -> cb.or(cb.notEqual(l, value), cb.isNull(l));
                case GREATER_THAN -> cb.greaterThan(l, (Comparable) value);
                case LESS_THAN -> cb.lessThan(l, (Comparable) value);
                case GREATER_THAN_OR_EQUAL -> cb.greaterThanOrEqualTo(l, (Comparable) value);
                case LESS_THAN_OR_EQUAL -> cb.lessThanOrEqualTo(l, (Comparable) value);
                case CONTAINS -> cb.like(string(left), "%" + escapeLike(value) + "%", '\\');
                case STARTS_WITH -> cb.like(string(left), escapeLike(value) + "%", '\\');
                case ENDS_WITH -> cb.like(string(left), "%" + escapeLike(value), '\\');
            };
        }

        var right = scalar(rightNode, scope);
        var r = (Expression) right.composable();
        return switch (node.operator()) {
            case EQUAL -> cb.or(cb.equal(l, r), cb.and(cb.isNull(l), cb.isNull(r)));
            case NOT_EQUAL -> cb.or(cb.notEqual(l, r), cb.and(cb.isNull(l), cb.isNotNull(r)),
                    cb.and(cb.isNotNull(l), cb.isNull(r)));
            case GREATER_THAN -> cb.greaterThan(l, r);
            case LESS_THAN -> cb.lessThan(l, r);
            case GREATER_THAN_OR_EQUAL -> cb.greaterThanOrEqualTo(l, r);
            case LESS_THAN_OR_EQUAL -> cb.lessThanOrEqualTo(l, r);
            default -> throw new Untranslatable("a string match of two values");
        };
    }

    // The literal of a comparison, of the type of the value it is compared with
    private static Object literalOf(Object literal, Class<?> type) {
        if (literal instanceof String text) {
            if (type == String.class) return text;
            if (type.isEnum()) {
                for (var constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(text)) return constant;
                }
                throw new Untranslatable("'" + text + "' is not a " + type.getSimpleName());
            }
            if (type == UUID.class) {
                try {
                    return UUID.fromString(text);
                } catch (IllegalArgumentException e) {
                    throw new Untranslatable("'" + text + "' is not a UUID");
                }
            }
            if (type == Character.class && text.length() == 1) return text.charAt(0);
            throw new Untranslatable("a string compared with a " + type.getSimpleName());
        }
        if (literal instanceof BigDecimal number) {
            try {
                if (type == Integer.class) return number.intValueExact();
                if (type == Long.class) return number.longValueExact();
                if (type == Short.class) return number.shortValueExact();
                if (type == Byte.class) return number.byteValueExact();
                if (type == BigInteger.class) return number.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new Untranslatable(number + " compared with a " + type.getSimpleName());
            }
            if (type == Double.class) return number.doubleValue();
            if (type == Float.class) return number.floatValue();
            if (type == BigDecimal.class) return number;
            throw new Untranslatable("a number compared with a " + type.getSimpleName());
        }
        if (literal instanceof Boolean && type == Boolean.class) return literal;
        throw new Untranslatable(literal + " compared with a " + type.getSimpleName());
    }

    private static String escapeLike(Object value) {
        return value.toString().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Expression<Boolean> bool(Predicate predicate) {
        return cb.<Boolean>selectCase().when(predicate, true).otherwise(false);
    }

    // Names and types

    /** The attribute of an expression name: the exposed names of the entity type, then the name in either case. */
    private Attribute<?, ?> attribute(Scope scope, String name) {
        var type = scope.type();
        if (type == null) throw new Untranslatable("a property of a basic value");
        if (config.entityType().isAssignableFrom(type.getJavaType())) {
            var exposed = config.exposedNames().get(name);
            if (exposed != null) name = exposed;
        }
        var candidates = new LinkedHashSet<String>();
        candidates.add(name);
        if (!name.isEmpty()) {
            candidates.add(name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1));
            candidates.add(name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1));
        }
        for (var candidate : candidates) {
            try {
                return type.getAttribute(candidate);
            } catch (IllegalArgumentException e) {
                // not this spelling
            }
        }
        throw new Untranslatable("'" + name + "' is not persistent");
    }

    private ManagedType<?> managedType(Path<?> path) {
        var javaType = path.getJavaType();
        for (var type : metamodel.getManagedTypes()) {
            if (type.getJavaType() == javaType) return type;
        }
        return null;
    }

    private static String idName(ManagedType<?> type) {
        if (!(type instanceof EntityType<?> entity) || !entity.hasSingleIdAttribute()) return null;
        return entity.getId(entity.getIdType().getJavaType()).getName();
    }

    // FxMap's FunctionNode for sum(Total) and the like is an aggregation
    private static ExpressionNode normalize(ExpressionNode node) {
        if (node instanceof FunctionNode function) {
            var type = switch (function.functionName()) {
                case SUM -> AggregationType.SUM;
                case AVG -> AggregationType.AVERAGE;
                case MIN -> AggregationType.MIN;
                case MAX -> AggregationType.MAX;
                default -> null;
            };
            if (type != null) {
                var arguments = function.getArguments();
                String property = null;
                if (!arguments.isEmpty()) {
                    if (!(arguments.getFirst() instanceof PropertyNode p))
                        throw new Untranslatable("an aggregation argument");
                    property = p.name();
                }
                return new AggregationNode(function.source(), type, property);
            }
        }
        return node;
    }

    private static Expression<String> string(Scalar scalar) {
        if (scalar.type() != String.class) throw new Untranslatable("a string function of " + scalar.type());
        return (Expression<String>) scalar.composable();
    }

    private static Expression<Number> number(Scalar scalar) {
        numberType(scalar);
        return (Expression<Number>) scalar.composable();
    }

    private static Class<?> numberType(Scalar scalar) {
        if (!Number.class.isAssignableFrom(scalar.type()))
            throw new Untranslatable("a math function of " + scalar.type().getSimpleName());
        return scalar.type();
    }

    private static void requireNumber(Scalar scalar) {
        numberType(scalar);
    }

    // The kind of the result of an operation, as the evaluator computes it: decimal, then double, long, int
    private static Class<?> widest(Class<?> left, Class<?> right) {
        return rank(left) >= rank(right) ? normalizeNumber(left) : normalizeNumber(right);
    }

    private static int rank(Class<?> type) {
        if (type == Integer.class || type == Short.class || type == Byte.class) return 0;
        if (type == Long.class) return 1;
        if (type == Double.class || type == Float.class) return 2;
        return 3;
    }

    private static Class<?> normalizeNumber(Class<?> type) {
        return switch (rank(type)) {
            case 0 -> Integer.class;
            case 1 -> Long.class;
            case 2 -> Double.class;
            default -> BigDecimal.class;
        };
    }

    private static boolean isWhole(Class<?> type) {
        return type == Integer.class || type == Long.class;
    }

    private static boolean isDecimal(Class<?> type) {
        return type == BigDecimal.class || type == BigInteger.class;
    }

    private static Object zero(Class<?> type) {
        if (type == BigDecimal.class) return BigDecimal.ZERO;
        if (type == BigInteger.class) return BigInteger.ZERO;
        if (type == Double.class || type == Float.class) return 0.0;
        return 0L;
    }

    private static Object toDouble(Object value) {
        return ((Number) value).doubleValue();
    }

    private static int wholeNumber(ExpressionNode node) {
        if (node instanceof LiteralNode literal && literal.value() instanceof BigDecimal number) {
            try {
                return number.intValueExact();
            } catch (ArithmeticException e) {
                throw new Untranslatable(number + " is not a whole number");
            }
        }
        throw new Untranslatable("a function argument that is not a number");
    }

    private static String stringLiteral(ExpressionNode node) {
        if (node instanceof LiteralNode literal && (literal.value() == null || literal.value() instanceof String))
            return (String) literal.value();
        throw new Untranslatable("a function argument that is not a string");
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static List<String> append(List<String> path, String name) {
        var result = new ArrayList<>(path);
        result.add(name);
        return List.copyOf(result);
    }
}
