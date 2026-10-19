package io.github.quyvu01.jfxmap.expressions.evaluation;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Collections, numbers and comparisons of evaluated values.
 */
final class Values {
    private Values() {
    }

    /**
     * Whether a value is a collection: an Iterable or an array. A string and a Map are not.
     */
    static boolean isCollection(Object value) {
        return value instanceof Iterable<?> || (value != null && value.getClass().isArray());
    }

    static boolean isCollectionType(Class<?> type) {
        return type != null && (Iterable.class.isAssignableFrom(type) || type.isArray())
                && !Map.class.isAssignableFrom(type);
    }

    /**
     * The items of a collection, as a list.
     */
    static List<Object> items(Object value) {
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection);
        var list = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (var item : iterable) list.add(item);
        } else {
            var length = Array.getLength(value);
            for (var i = 0; i < length; i++) list.add(Array.get(value, i));
        }
        return list;
    }

    // Numbers

    /**
     * The kind of a number, from the narrowest to the widest of the operations of FxMap.
     */
    enum NumberKind { INT, LONG, DOUBLE, DECIMAL }

    static NumberKind kindOf(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return NumberKind.INT;
        if (value instanceof Long) return NumberKind.LONG;
        if (value instanceof Double || value instanceof Float) return NumberKind.DOUBLE;
        if (value instanceof BigDecimal || value instanceof BigInteger) return NumberKind.DECIMAL;
        if (value instanceof Number) return NumberKind.DOUBLE;
        return null;
    }

    /**
     * The kind of the result of an operation of two numbers: decimal if one is decimal, then double, then long,
     * otherwise int.
     */
    static NumberKind resultKind(NumberKind left, NumberKind right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    static Number convert(Number value, NumberKind kind) {
        switch (kind) {
            case INT:
                return value.intValue();
            case LONG:
                return value.longValue();
            case DOUBLE:
                return value.doubleValue();
            default:
                return toDecimal(value);
        }
    }

    static BigDecimal toDecimal(Number value) {
        if (value instanceof BigDecimal d) return d;
        if (value instanceof BigInteger i) return new BigDecimal(i);
        if (value instanceof Double || value instanceof Float) return BigDecimal.valueOf(value.doubleValue());
        return BigDecimal.valueOf(value.longValue());
    }

    static Number number(Object value, String what) {
        if (value instanceof Number n) return n;
        throw new ExpressionEvaluationException(what + " requires a number, got " + describe(value));
    }

    static int wholeNumber(Object value, String what) {
        var number = number(value, what);
        // A conversion to int, as the LINQ builder does it: the fraction is dropped
        return number.intValue();
    }

    static Number arithmetic(char operator, Number left, Number right) {
        var kind = resultKind(kindOf(left), kindOf(right));
        switch (kind) {
            case INT: {
                int l = left.intValue(), r = right.intValue();
                switch (operator) {
                    case '+': return l + r;
                    case '-': return l - r;
                    case '*': return l * r;
                    case '/': return l / r;
                    default: return l % r;
                }
            }
            case LONG: {
                long l = left.longValue(), r = right.longValue();
                switch (operator) {
                    case '+': return l + r;
                    case '-': return l - r;
                    case '*': return l * r;
                    case '/': return l / r;
                    default: return l % r;
                }
            }
            case DOUBLE: {
                double l = left.doubleValue(), r = right.doubleValue();
                switch (operator) {
                    case '+': return l + r;
                    case '-': return l - r;
                    case '*': return l * r;
                    case '/': return l / r;
                    default: return l % r;
                }
            }
            default: {
                BigDecimal l = toDecimal(left), r = toDecimal(right);
                switch (operator) {
                    case '+': return l.add(r);
                    case '-': return l.subtract(r);
                    case '*': return l.multiply(r);
                    case '/': return l.divide(r, MathContext.DECIMAL128);
                    default: return l.remainder(r);
                }
            }
        }
    }

    // Comparisons

    /**
     * Equality of two values: numbers by value (2024 and 2024.0 are equal), an enum and a string by the name of the
     * enum, the rest with equals.
     */
    static boolean areEqual(Object left, Object right) {
        if (left == null || right == null) return left == right;
        if (left instanceof Number l && right instanceof Number r) return toDecimal(l).compareTo(toDecimal(r)) == 0;
        if (left instanceof Enum<?> e && right instanceof String s) return e.name().equals(s);
        if (left instanceof String s && right instanceof Enum<?> e) return e.name().equals(s);
        if (left instanceof Character c && right instanceof String s) return s.equals(String.valueOf(c));
        return left.equals(right);
    }

    /**
     * Orders two values that are not null: numbers by value, the rest by their natural order.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) return toDecimal(l).compareTo(toDecimal(r));
        if (left instanceof Comparable comparable && left.getClass().isInstance(right))
            return comparable.compareTo(right);
        if (right instanceof Comparable comparable && right.getClass().isInstance(left))
            return -comparable.compareTo(left);
        throw new ExpressionEvaluationException("Cannot compare " + describe(left) + " with " + describe(right));
    }

    /**
     * The order of {@code :orderBy}, {@code :min} and {@code :max}: null comes first.
     */
    static int compareNullsFirst(Object left, Object right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        return compare(left, right);
    }

    static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " '" + value + "'";
    }
}
