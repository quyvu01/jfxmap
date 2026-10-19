package io.github.quyvu01.jfxmap.expressions.evaluation;

import io.github.quyvu01.jfxmap.expressions.nodes.AggregationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationType;
import io.github.quyvu01.jfxmap.expressions.nodes.BinaryConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.CoalesceNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ExpressionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ExpressionNodeVisitor;
import io.github.quyvu01.jfxmap.expressions.nodes.FilterNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.GroupByNode;
import io.github.quyvu01.jfxmap.expressions.nodes.GroupElementsNode;
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
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Evaluates FxMap expressions against objects in memory: records, JavaBeans, objects with public fields, Maps,
 * collections and arrays. It is the counterpart of FxMap's LINQ expression builder run over objects.
 *
 * <p>The values it gives:
 * <ul>
 *   <li>a projection ({@code {Id, Name}}, {@code Country.{Id, Name}}) is a {@code Map<String, Object>} in the order
 *   of its properties; the projection of a collection ({@code Orders.{Id, Total}}) is a list of such maps</li>
 *   <li>the filter and the collection functions ({@code :where}, {@code :orderBy}, {@code :offset}, {@code :limit},
 *   {@code :distinct}, {@code :split}) give lists; {@code :first} and {@code :last} give an item, or null for an empty
 *   collection</li>
 *   <li>numbers keep their kind as in FxMap: a number literal is a {@link BigDecimal}; {@code :add} and the other
 *   operations give the widest kind of their operands (decimal, then double, long, int); {@code :round},
 *   {@code :floor}, {@code :ceil} and {@code :pow} give a double; {@code :avg} of whole numbers gives a double</li>
 *   <li>{@code :sum} and {@code :count} of an empty collection are 0; {@code :avg}, {@code :min} and {@code :max} of
 *   an empty collection are null</li>
 *   <li>{@code :groupBy(...)} without a projection gives a list of {@link Grouping}s</li>
 * </ul>
 *
 * <p>Null values: {@code Address.City} fails when Address is null and {@code Address?.City} gives null, as the
 * expression language documents it. A function applied to a null value (a null string, a null collection) gives
 * null, and the projection of a null item ({@code Orders:first.{Id}} of no order) gives its properties as null.
 */
public final class ExpressionEvaluator {
    private static final int MAX_GROUP_KEYS = 5;

    private final PropertyAccessor accessor;
    private final Clock clock;

    /**
     * An evaluator reading properties with a {@link BeanPropertyAccessor}, with the system clock for
     * {@code :daysAgo}.
     */
    public ExpressionEvaluator() {
        this(new BeanPropertyAccessor(), Clock.systemDefaultZone());
    }

    public ExpressionEvaluator(PropertyAccessor accessor) {
        this(accessor, Clock.systemDefaultZone());
    }

    /**
     * @param accessor reads the properties of the objects
     * @param clock    gives today for {@code :daysAgo}
     */
    public ExpressionEvaluator(PropertyAccessor accessor, Clock clock) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Parses an expression and evaluates it against an object.
     */
    public Object evaluate(String expression, Object root) {
        return evaluate(ExpressionParser.parse(expression), root);
    }

    /**
     * Evaluates a parsed expression against an object.
     */
    public Object evaluate(ExpressionNode expression, Object root) {
        var type = root == null ? null : root.getClass();
        return expression.accept(new Visitor(), new Context(root, type, null)).value();
    }

    // A value and its declared type (null when it is not known)
    private record Typed(Object value, Type type) {
        static final Typed NULL = new Typed(null, null);

        static Typed of(Object value) {
            return new Typed(value, null);
        }
    }

    // The object that properties are read from, and the group of a projection after groupBy
    private record Context(Object current, Type type, GroupContext group) {
        Context withCurrent(Typed value) {
            return new Context(value.value(), value.type(), group);
        }

        Context item(Object item, Type itemType) {
            return new Context(item, itemType, null);
        }
    }

    private record GroupContext(List<String> keyProperties, List<Object> keyValues, List<Object> items,
                                Type itemType) {
        int keyIndex(String name) {
            for (var i = 0; i < keyProperties.size(); i++) {
                if (keyProperties.get(i).equalsIgnoreCase(name)) return i;
            }
            return -1;
        }
    }

    private final class Visitor implements ExpressionNodeVisitor<Typed, Context> {

        @Override
        public Typed visitProperty(PropertyNode node, Context context) {
            // After groupBy, a key property is the key of the group
            if (context.group() != null) {
                var keyIndex = context.group().keyIndex(node.name());
                if (keyIndex >= 0) return Typed.of(context.group().keyValues().get(keyIndex));
            }

            return readProperty(context.current(), context.type(), node.name());
        }

        @Override
        public Typed visitNavigation(NavigationNode node, Context context) {
            var currentContext = context;
            Typed result = null;
            var segments = node.segments();

            for (var i = 0; i < segments.size(); i++) {
                var segment = segments.get(i);
                result = segment.accept(this, currentContext);

                if (result.value() == null && i < segments.size() - 1) {
                    // Address?.City stops when Address is null; Address.City cannot go on
                    if (segment instanceof PropertyNode property && property.isNullSafe()) return Typed.NULL;
                    throw new ExpressionEvaluationException("Cannot navigate from " + describeSegment(segment)
                            + ": it is null (a null-safe navigation is written with '?', as in Address?.City)");
                }

                currentContext = currentContext.withCurrent(result);
            }

            if (result == null) throw new ExpressionEvaluationException("Navigation node has no segments");
            return result;
        }

        @Override
        public Typed visitFilter(FilterNode node, Context context) {
            var source = node.source().accept(this, context);
            if (source.value() == null) return Typed.NULL;
            var items = collection(source, "where");
            var itemType = Types.elementType(source.type());

            var result = new ArrayList<>();
            for (var item : items) {
                if (test(node.condition(), context.item(item, itemType))) result.add(item);
            }

            return new Typed(result, Types.listOf(itemType));
        }

        @Override
        public Typed visitProjection(ProjectionNode node, Context context) {
            if (node.source() instanceof GroupByNode groupBy) return projectGroups(node, groupBy, context);

            var source = node.source().accept(this, context);

            // A collection: each item is projected
            if (Values.isCollection(source.value())) {
                var itemType = Types.elementType(source.type());
                var result = new ArrayList<>();
                for (var item : Values.items(source.value()))
                    result.add(project(node.properties(), context.item(item, itemType)));
                return Typed.of(result);
            }

            // A null collection has no items to project
            if (source.value() == null && Values.isCollectionType(Types.raw(source.type()))) return Typed.NULL;

            // A single object
            return Typed.of(project(node.properties(), new Context(source.value(), source.type(), context.group())));
        }

        @Override
        public Typed visitRootProjection(RootProjectionNode node, Context context) {
            return Typed.of(project(node.properties(), context));
        }

        private Map<String, Object> project(List<ProjectionProperty> properties, Context context) {
            var result = new LinkedHashMap<String, Object>();
            for (var property : properties) {
                Object value;
                if (context.current() == null && context.group() == null) {
                    // The projection of a null item: its properties are null
                    value = null;
                } else if (property.isComputed()) {
                    value = property.expression().accept(this, context).value();
                } else {
                    value = readPath(property.pathSegments(), context).value();
                }
                put(result, property.outputKey(), value);
            }
            return result;
        }

        private Typed readPath(String[] segments, Context context) {
            var current = new Typed(context.current(), context.type());
            for (var i = 0; i < segments.length; i++) {
                if (current.value() == null && i > 0)
                    throw new ExpressionEvaluationException("Cannot navigate from '" + segments[i - 1]
                            + "': it is null (a null-safe navigation is written with '?', as in Address?.City)");
                current = readProperty(current.value(), current.type(), segments[i]);
            }
            return current;
        }

        // Orders:groupBy(Status).{Status, :count as Count}
        private Typed projectGroups(ProjectionNode node, GroupByNode groupBy, Context context) {
            var groups = group(groupBy, context);
            if (groups == null) return Typed.NULL;

            var result = new ArrayList<>();
            for (var group : groups) {
                var groupContext = new Context(context.current(), context.type(), group);
                var row = new LinkedHashMap<String, Object>();
                for (var property : node.properties()) {
                    Object value;
                    if (property.isComputed()) {
                        value = property.expression().accept(this, groupContext).value();
                    } else {
                        var keyName = property.pathSegments()[0];
                        var keyIndex = group.keyIndex(keyName);
                        if (keyIndex < 0)
                            throw new ExpressionEvaluationException("Property '" + keyName
                                    + "' is not a key property in GroupBy. Available keys: "
                                    + String.join(", ", groupBy.keyProperties()));
                        value = group.keyValues().get(keyIndex);
                    }
                    put(row, property.outputKey(), value);
                }
                result.add(row);
            }

            return Typed.of(result);
        }

        @Override
        public Typed visitGroupBy(GroupByNode node, Context context) {
            var groups = group(node, context);
            if (groups == null) return Typed.NULL;

            var result = new ArrayList<>();
            for (var group : groups) {
                var key = node.isSingleKey()
                        ? group.keyValues().get(0)
                        : Collections.unmodifiableList(group.keyValues());
                result.add(new Grouping(key, group.items()));
            }
            return new Typed(result, Types.listOf(Grouping.class));
        }

        // The groups of the source, in the order their first item comes in it
        private List<GroupContext> group(GroupByNode node, Context context) {
            if (node.keyProperties().size() > MAX_GROUP_KEYS)
                throw new ExpressionEvaluationException("GroupBy with more than " + MAX_GROUP_KEYS
                        + " keys is not supported. Found " + node.keyProperties().size() + " keys.");

            var source = node.source().accept(this, context);
            if (source.value() == null) return null;
            var items = collection(source, "groupBy");
            var itemType = Types.elementType(source.type());
            for (var key : node.keyProperties()) checkProperty(itemType, key);

            var groups = new LinkedHashMap<List<Object>, List<Object>>();
            for (var item : items) {
                var key = new ArrayList<>();
                for (var property : node.keyProperties())
                    key.add(readProperty(item, itemType, property).value());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }

            var result = new ArrayList<GroupContext>();
            groups.forEach((key, groupItems) ->
                    result.add(new GroupContext(node.keyProperties(), key, groupItems, itemType)));
            return result;
        }

        @Override
        public Typed visitGroupElements(GroupElementsNode node, Context context) {
            if (context.group() == null)
                throw new ExpressionEvaluationException(
                        "The items of a group can only be used within a GroupBy projection. "
                                + "Use syntax like: Orders:groupBy(Status).{Status, :count as Count}");

            var group = context.group();
            return new Typed(group.items(), Types.listOf(group.itemType()));
        }

        @Override
        public Typed visitFunction(FunctionNode node, Context context) {
            var source = node.source().accept(this, context);

            switch (node.functionName()) {
                // Aggregate functions
                case COUNT: return count(source);
                case SUM: return aggregate(source, AggregationType.SUM, node.argument());
                case AVG: return aggregate(source, AggregationType.AVERAGE, node.argument());
                case MIN: return aggregate(source, AggregationType.MIN, node.argument());
                case MAX: return aggregate(source, AggregationType.MAX, node.argument());
                // String functions
                case UPPER: return stringFunction(source, "upper", s -> s.toUpperCase(java.util.Locale.ROOT));
                case LOWER: return stringFunction(source, "lower", s -> s.toLowerCase(java.util.Locale.ROOT));
                case TRIM: return stringFunction(source, "trim", String::strip);
                case SUBSTRING: return substring(source, node, context);
                case REPLACE: return replace(source, node, context);
                case CONCAT: return concat(source, node, context);
                case SPLIT: return split(source, node, context);
                // Date/Time functions
                case YEAR: return dateFunction(source, "year", d -> d.getYear());
                case MONTH: return dateFunction(source, "month", d -> d.getMonthValue());
                case DAY: return dateFunction(source, "day", d -> d.getDayOfMonth());
                case HOUR: return dateFunction(source, "hour", d -> d.getHour());
                case MINUTE: return dateFunction(source, "minute", d -> d.getMinute());
                case SECOND: return dateFunction(source, "second", d -> d.getSecond());
                // Sunday is 0, Monday 1, ..., Saturday 6
                case DAY_OF_WEEK: return dateFunction(source, "dayOfWeek", d -> d.getDayOfWeek().getValue() % 7);
                // The whole days from the date to today: (today - date).Days
                case DAYS_AGO: return dateFunction(source, "daysAgo",
                        d -> (int) Duration.between(d, LocalDate.now(clock).atStartOfDay()).toDays());
                case FORMAT: return format(source, node, context);
                // Math functions
                case ROUND: return round(source, node, context);
                case FLOOR: return mathFunction(source, "floor", Math::floor);
                case CEIL: return mathFunction(source, "ceil", Math::ceil);
                case ABS: return abs(source);
                case ADD: return arithmetic(source, node, context, '+');
                case SUBTRACT: return arithmetic(source, node, context, '-');
                case MULTIPLY: return arithmetic(source, node, context, '*');
                case DIVIDE: return arithmetic(source, node, context, '/');
                case MOD: return arithmetic(source, node, context, '%');
                case POW: return pow(source, node, context);
                // Collection functions
                case ORDER_BY: return orderBy(source, node);
                case SKIP: return skipOrTake(source, node, "offset", true);
                case TAKE: return skipOrTake(source, node, "limit", false);
                case FIRST: return firstOrLast(source, true);
                case LAST: return firstOrLast(source, false);
                case DISTINCT: return distinct(source, node);
                default:
                    throw new ExpressionEvaluationException("Unknown function: " + node.functionName());
            }
        }

        // String functions

        private Typed stringFunction(Typed source, String name, java.util.function.UnaryOperator<String> function) {
            if (source.value() == null) return Typed.NULL;
            return new Typed(function.apply(string(source.value(), name)), String.class);
        }

        private Typed substring(Typed source, FunctionNode node, Context context) {
            var args = node.getArguments();
            var start = Values.wholeNumber(argument(args.get(0), context), "substring");
            Integer length = args.size() > 1 ? Values.wholeNumber(argument(args.get(1), context), "substring") : null;
            if (source.value() == null) return Typed.NULL;

            var text = string(source.value(), "substring");
            try {
                return new Typed(length == null ? text.substring(start) : text.substring(start, start + length),
                        String.class);
            } catch (IndexOutOfBoundsException e) {
                throw new ExpressionEvaluationException("substring(" + start + (length == null ? "" : ", " + length)
                        + ") is out of the range of '" + text + "'", e);
            }
        }

        private Typed replace(Typed source, FunctionNode node, Context context) {
            var args = node.getArguments();
            var oldValue = argument(args.get(0), context);
            var newValue = argument(args.get(1), context);
            if (source.value() == null) return Typed.NULL;
            if (oldValue == null || oldValue.toString().isEmpty())
                throw new ExpressionEvaluationException("replace requires a value to replace");

            return new Typed(string(source.value(), "replace")
                    .replace(oldValue.toString(), newValue == null ? "" : newValue.toString()), String.class);
        }

        private Typed concat(Typed source, FunctionNode node, Context context) {
            // As string.Concat: a null part is empty
            var sb = new StringBuilder();
            if (source.value() != null) sb.append(source.value());
            for (var arg : node.getArguments()) {
                var value = argument(arg, context);
                if (value != null) sb.append(value instanceof BigDecimal d ? d.toPlainString() : value);
            }
            return new Typed(sb.toString(), String.class);
        }

        private Typed split(Typed source, FunctionNode node, Context context) {
            var separator = argument(node.getArguments().get(0), context);
            if (source.value() == null) return Typed.NULL;
            if (separator == null || separator.toString().isEmpty())
                return new Typed(List.of(string(source.value(), "split")), Types.listOf(String.class));

            // The empty parts are kept, as String.Split(string, StringSplitOptions.None) does
            var parts = string(source.value(), "split").split(Pattern.quote(separator.toString()), -1);
            return new Typed(new ArrayList<>(List.of(parts)), Types.listOf(String.class));
        }

        private String string(Object value, String function) {
            if (value instanceof String s) return s;
            throw new ExpressionEvaluationException(
                    "Cannot apply " + function + " function to non-string value " + Values.describe(value));
        }

        // Date/Time functions

        private Typed dateFunction(Typed source, String name,
                                   java.util.function.ToIntFunction<LocalDateTime> function) {
            if (source.value() == null) return Typed.NULL;
            return new Typed(function.applyAsInt(localDateTime(source.value(), name)), Integer.class);
        }

        private Typed format(Typed source, FunctionNode node, Context context) {
            var pattern = argument(node.getArguments().get(0), context);
            if (source.value() == null) return Typed.NULL;
            if (!(pattern instanceof String text))
                throw new ExpressionEvaluationException("format requires a pattern, got " + Values.describe(pattern));

            var value = source.value();
            // The types with an offset or a zone keep them, for the patterns that write them
            TemporalAccessor temporal = value instanceof OffsetDateTime || value instanceof ZonedDateTime
                    ? (TemporalAccessor) value
                    : localDateTime(value, "format");
            try {
                return new Typed(DateFormats.of(text).format(temporal), String.class);
            } catch (RuntimeException e) {
                throw new ExpressionEvaluationException("Cannot format " + Values.describe(value) + " with '"
                        + text + "': " + e.getMessage(), e);
            }
        }

        private LocalDateTime localDateTime(Object value, String function) {
            if (value instanceof LocalDateTime d) return d;
            if (value instanceof LocalDate d) return d.atStartOfDay();
            if (value instanceof OffsetDateTime d) return d.toLocalDateTime();
            if (value instanceof ZonedDateTime d) return d.toLocalDateTime();
            if (value instanceof Instant d) return LocalDateTime.ofInstant(d, ZoneOffset.UTC);
            if (value instanceof Date d)
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(d.getTime()), ZoneId.systemDefault());
            throw new ExpressionEvaluationException(
                    "Cannot apply " + function + " function to non-date value " + Values.describe(value));
        }

        // Math functions

        private Typed round(Typed source, FunctionNode node, Context context) {
            var args = node.getArguments();
            Integer decimals = args.isEmpty() ? null : Values.wholeNumber(argument(args.get(0), context), "round");
            if (source.value() == null) return Typed.NULL;

            var value = Values.number(source.value(), "round").doubleValue();
            // Math.Round rounds a midpoint to the even number
            var rounded = decimals == null
                    ? Math.rint(value)
                    : BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_EVEN).doubleValue();
            return new Typed(rounded, Double.class);
        }

        private Typed mathFunction(Typed source, String name, java.util.function.DoubleUnaryOperator function) {
            if (source.value() == null) return Typed.NULL;
            return new Typed(function.applyAsDouble(Values.number(source.value(), name).doubleValue()), Double.class);
        }

        private Typed abs(Typed source) {
            var value = source.value();
            if (value == null) return Typed.NULL;
            if (value instanceof Integer i) return Typed.of(Math.absExact(i));
            if (value instanceof Long l) return Typed.of(Math.absExact(l));
            if (value instanceof Float f) return Typed.of(Math.abs(f));
            if (value instanceof BigDecimal d) return Typed.of(d.abs());
            if (value instanceof java.math.BigInteger i) return Typed.of(i.abs());
            return Typed.of(Math.abs(Values.number(value, "abs").doubleValue()));
        }

        private Typed arithmetic(Typed source, FunctionNode node, Context context, char operator) {
            var args = node.getArguments();
            if (args.isEmpty()) throw new ExpressionEvaluationException("Math operation requires an operand");

            var operand = argument(args.get(0), context);
            if (source.value() == null || operand == null) return Typed.NULL;

            var name = String.valueOf(operator);
            return Typed.of(Values.arithmetic(operator, Values.number(source.value(), name),
                    Values.number(operand, name)));
        }

        private Typed pow(Typed source, FunctionNode node, Context context) {
            var args = node.getArguments();
            if (args.isEmpty()) throw new ExpressionEvaluationException("pow function requires an exponent argument");

            var exponent = argument(args.get(0), context);
            if (source.value() == null || exponent == null) return Typed.NULL;
            return new Typed(Math.pow(Values.number(source.value(), "pow").doubleValue(),
                    Values.number(exponent, "pow").doubleValue()), Double.class);
        }

        // Collection functions

        private Typed orderBy(Typed source, FunctionNode node) {
            var args = node.getArguments();
            if (args.isEmpty())
                throw new ExpressionEvaluationException("orderBy function requires at least one property argument");
            if (source.value() == null) return Typed.NULL;

            var items = collection(source, "orderBy");
            var itemType = Types.elementType(source.type());

            Comparator<Object> comparator = null;
            for (var arg : args) {
                String propertyName;
                var direction = OrderDirection.ASC;
                if (arg instanceof SortKeyNode key) {
                    propertyName = key.name();
                    direction = key.direction();
                } else if (arg instanceof PropertyNode property) {
                    propertyName = property.name();
                } else if (arg instanceof LiteralNode literal && literal.literalType() == LiteralType.STRING) {
                    propertyName = (String) literal.value();
                } else {
                    throw new ExpressionEvaluationException("orderBy function requires property name arguments");
                }

                checkProperty(itemType, propertyName);
                Comparator<Object> key = (a, b) -> Values.compareNullsFirst(
                        readProperty(a, itemType, propertyName).value(),
                        readProperty(b, itemType, propertyName).value());
                if (direction == OrderDirection.DESC) key = key.reversed();
                comparator = comparator == null ? key : comparator.thenComparing(key);
            }

            // A stable sort, as OrderBy/ThenBy
            items.sort(comparator);
            return new Typed(items, Types.listOf(itemType));
        }

        private Typed skipOrTake(Typed source, FunctionNode node, String name, boolean skip) {
            var count = nonNegativeCount(node, name);
            if (source.value() == null) return Typed.NULL;

            var items = collection(source, name);
            var size = items.size();
            var page = skip ? items.subList(Math.min(count, size), size) : items.subList(0, Math.min(count, size));
            return new Typed(new ArrayList<>(page), Types.listOf(Types.elementType(source.type())));
        }

        // The argument of offset/limit: a whole number that is not negative
        private int nonNegativeCount(FunctionNode node, String name) {
            var args = node.getArguments();
            if (args.size() != 1 || !(args.get(0) instanceof LiteralNode literal)
                    || literal.literalType() != LiteralType.NUMBER)
                throw new ExpressionEvaluationException(name + " function requires one number argument");

            var number = (BigDecimal) literal.value();
            if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0
                    || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)
                throw new ExpressionEvaluationException(name + " function requires a whole number between 0 and "
                        + Integer.MAX_VALUE + ", got " + number.toPlainString());

            return number.intValueExact();
        }

        private Typed firstOrLast(Typed source, boolean first) {
            if (source.value() == null) return Typed.NULL;
            var items = collection(source, first ? "first" : "last");
            var itemType = Types.elementType(source.type());
            if (items.isEmpty()) return new Typed(null, itemType);
            return new Typed(items.get(first ? 0 : items.size() - 1), itemType);
        }

        private Typed distinct(Typed source, FunctionNode node) {
            var args = node.getArguments();
            if (args.isEmpty())
                throw new ExpressionEvaluationException("distinct function requires a property argument");

            String propertyName;
            if (args.get(0) instanceof PropertyNode property) propertyName = property.name();
            else if (args.get(0) instanceof LiteralNode literal && literal.literalType() == LiteralType.STRING)
                propertyName = (String) literal.value();
            else throw new ExpressionEvaluationException("distinct function requires a property name argument");

            if (source.value() == null) return Typed.NULL;
            var items = collection(source, "distinct");
            var itemType = Types.elementType(source.type());
            var propertyType = checkProperty(itemType, propertyName);

            var values = new LinkedHashSet<>();
            for (var item : items) values.add(readProperty(item, itemType, propertyName).value());
            return new Typed(new ArrayList<>(values), Types.listOf(propertyType));
        }

        @Override
        public Typed visitBooleanFunction(BooleanFunctionNode node, Context context) {
            var source = node.source().accept(this, context);

            // All without a condition is true (there is no condition to fail)
            if (node.functionName() == BooleanFunctionType.ALL && node.condition() == null)
                return new Typed(true, Boolean.class);
            if (source.value() == null) return Typed.NULL;

            var items = collection(source, node.functionName() == BooleanFunctionType.ANY ? "any" : "all");
            var itemType = Types.elementType(source.type());

            if (node.condition() == null) return new Typed(!items.isEmpty(), Boolean.class);

            for (var item : items) {
                var matches = test(node.condition(), context.item(item, itemType));
                if (node.functionName() == BooleanFunctionType.ANY && matches) return new Typed(true, Boolean.class);
                if (node.functionName() == BooleanFunctionType.ALL && !matches) return new Typed(false, Boolean.class);
            }
            return new Typed(node.functionName() == BooleanFunctionType.ALL, Boolean.class);
        }

        @Override
        public Typed visitBinaryCondition(BinaryConditionNode node, Context context) {
            var left = node.left().accept(this, context).value();
            var right = node.right().accept(this, context).value();

            boolean result;
            switch (node.operator()) {
                case EQUAL:
                    result = Values.areEqual(left, right);
                    break;
                case NOT_EQUAL:
                    result = !Values.areEqual(left, right);
                    break;
                case GREATER_THAN:
                    result = left != null && right != null && Values.compare(left, right) > 0;
                    break;
                case LESS_THAN:
                    result = left != null && right != null && Values.compare(left, right) < 0;
                    break;
                case GREATER_THAN_OR_EQUAL:
                    result = left != null && right != null && Values.compare(left, right) >= 0;
                    break;
                case LESS_THAN_OR_EQUAL:
                    result = left != null && right != null && Values.compare(left, right) <= 0;
                    break;
                case CONTAINS:
                    result = left != null && right != null && string(left, "contains").contains(right.toString());
                    break;
                case STARTS_WITH:
                    result = left != null && right != null && string(left, "startswith").startsWith(right.toString());
                    break;
                case ENDS_WITH:
                    result = left != null && right != null && string(left, "endswith").endsWith(right.toString());
                    break;
                default:
                    throw new ExpressionEvaluationException("Unknown operator: " + node.operator());
            }

            return new Typed(result, Boolean.class);
        }

        @Override
        public Typed visitLogicalCondition(LogicalConditionNode node, Context context) {
            var left = test(node.left(), context);
            if (node.operator() == LogicalOperator.AND && !left) return new Typed(false, Boolean.class);
            if (node.operator() == LogicalOperator.OR && left) return new Typed(true, Boolean.class);
            return new Typed(test(node.right(), context), Boolean.class);
        }

        @Override
        public Typed visitLiteral(LiteralNode node, Context context) {
            return Typed.of(node.value());
        }

        @Override
        public Typed visitAggregation(AggregationNode node, Context context) {
            var source = node.source().accept(this, context);
            if (node.aggregationType() == AggregationType.COUNT) return count(source);
            return aggregate(source, node.aggregationType(), node.propertyName());
        }

        @Override
        public Typed visitCoalesce(CoalesceNode node, Context context) {
            var left = node.left().accept(this, context);
            return left.value() != null ? left : node.right().accept(this, context);
        }

        @Override
        public Typed visitTernary(TernaryNode node, Context context) {
            return test(node.condition(), context)
                    ? node.whenTrue().accept(this, context)
                    : node.whenFalse().accept(this, context);
        }

        // Aggregations

        private Typed count(Typed source) {
            var value = source.value();
            if (value == null) return Typed.NULL;
            // The length of a string
            if (value instanceof String s) return new Typed(s.length(), Integer.class);
            if (value instanceof Map<?, ?> map) return new Typed(map.size(), Integer.class);
            return new Typed(collection(source, "count").size(), Integer.class);
        }

        private Typed aggregate(Typed source, AggregationType type, String propertyName) {
            if (source.value() == null) return Typed.NULL;

            var name = type.pascalName().toLowerCase(java.util.Locale.ROOT);
            var items = collection(source, name);
            var itemType = Types.elementType(source.type());
            var selectedType = propertyName == null ? itemType : checkProperty(itemType, propertyName);

            // The values, without the nulls (as the aggregates of nullable values)
            var values = new ArrayList<>();
            for (var item : items) {
                var value = propertyName == null ? item : readProperty(item, itemType, propertyName).value();
                if (value != null) values.add(value);
            }

            switch (type) {
                case SUM:
                    return Typed.of(values.isEmpty() ? zero(selectedType) : sum(values, name));
                case AVERAGE: {
                    // The average of an empty collection has no value
                    if (values.isEmpty()) return Typed.NULL;
                    var total = sum(values, name);
                    var kind = Values.kindOf(total);
                    if (kind == Values.NumberKind.DECIMAL)
                        return Typed.of(Values.toDecimal(total)
                                .divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128));
                    return Typed.of(total.doubleValue() / values.size());
                }
                default: {
                    if (values.isEmpty()) return Typed.NULL;
                    Object best = values.get(0);
                    for (var value : values) {
                        var comparison = Values.compare(value, best);
                        if (type == AggregationType.MIN ? comparison < 0 : comparison > 0) best = value;
                    }
                    return new Typed(best, selectedType);
                }
            }
        }

        private Number sum(List<Object> values, String name) {
            Number total = null;
            for (var value : values) {
                var number = Values.number(value, name);
                total = total == null ? number : Values.arithmetic('+', total, number);
            }
            return total;
        }

        // The sum of no values, of the type of the values when it is known
        private Number zero(Type type) {
            var raw = Types.raw(type);
            if (raw == long.class || raw == Long.class) return 0L;
            if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) return 0.0;
            if (raw == BigDecimal.class || raw == java.math.BigInteger.class) return BigDecimal.ZERO;
            return 0;
        }

        // Helpers

        private boolean test(ConditionNode condition, Context context) {
            var value = condition.accept(this, context).value();
            if (value instanceof Boolean b) return b;
            throw new ExpressionEvaluationException("A condition must be true or false, got " + Values.describe(value));
        }

        private Object argument(ExpressionNode argument, Context context) {
            return argument.accept(this, context).value();
        }

        private List<Object> collection(Typed source, String function) {
            if (Values.isCollection(source.value())) return Values.items(source.value());
            throw new ExpressionEvaluationException(
                    "Cannot apply " + function + " function to non-collection value " + Values.describe(source.value()));
        }

        private Typed readProperty(Object target, Type declaredType, String name) {
            if (target == null)
                throw new ExpressionEvaluationException("Cannot read property '" + name + "' of null");

            if (target instanceof Map<?, ?> map) {
                if (!map.containsKey(name))
                    throw new ExpressionEvaluationException("Property '" + name + "' not found in the map");
                return Typed.of(map.get(name));
            }

            var property = accessor.find(target.getClass(), name);
            if (property == null)
                throw new ExpressionEvaluationException(
                        "Property '" + name + "' not found on type '" + target.getClass().getSimpleName() + "'");
            return new Typed(property.get(target), property.type());
        }

        // Checks a property of the items of a collection by their declared type, so that a wrong name fails even
        // when the collection is empty; gives the declared type of the property
        private Type checkProperty(Type itemType, String name) {
            var raw = Types.raw(itemType);
            if (!Types.isCheckable(raw)) return null;
            var property = accessor.find(raw, name);
            if (property == null)
                throw new ExpressionEvaluationException(
                        "Property '" + name + "' not found on type '" + raw.getSimpleName() + "'");
            return property.type();
        }

        private void put(Map<String, Object> row, String key, Object value) {
            if (row.containsKey(key))
                throw new ExpressionEvaluationException("The projection has two properties named '" + key
                        + "'; give one of them another name with 'as'");
            row.put(key, value);
        }

        private String describeSegment(ExpressionNode segment) {
            return segment instanceof PropertyNode property ? "'" + property.name() + "'"
                    : "the " + segment.getClass().getSimpleName().replace("Node", "").toLowerCase(java.util.Locale.ROOT);
        }
    }
}
