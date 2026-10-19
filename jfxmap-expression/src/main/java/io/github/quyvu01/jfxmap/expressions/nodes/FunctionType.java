package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * The functions of the expression language.
 */
public enum FunctionType {
    /** Count of the items of a collection, or length of a string. */
    COUNT,
    /** Sum of values. */
    SUM,
    /** Average of values. */
    AVG,
    /** Minimum value. */
    MIN,
    /** Maximum value. */
    MAX,
    /** True if any item matches the condition (or the collection is not empty). */
    ANY,
    /** True if all items match the condition. */
    ALL,

    // String functions

    /** Name:upper is "JOHN". */
    UPPER,
    /** Name:lower is "john". */
    LOWER,
    /** Removes whitespace from both ends: Name:trim is "John". */
    TRIM,
    /** Name:substring(0, 3) is "Joh"; arguments: start, length (optional). */
    SUBSTRING,
    /** Name:replace('o', 'a') is "Jahn"; arguments: old value, new value. */
    REPLACE,
    /** Name:concat(' ', LastName) is "John Doe"; arguments: values (strings or property references). */
    CONCAT,
    /** Tags:split(',') is ["a", "b", "c"]; argument: separator. */
    SPLIT,

    // Date/Time functions

    /** CreatedAt:year is 2024. */
    YEAR,
    /** CreatedAt:month is 12 (1-12). */
    MONTH,
    /** CreatedAt:day is 25 (1-31). */
    DAY,
    /** CreatedAt:hour is 14 (0-23). */
    HOUR,
    /** CreatedAt:minute is 30 (0-59). */
    MINUTE,
    /** CreatedAt:second is 45 (0-59). */
    SECOND,
    /** CreatedAt:dayOfWeek is 1 (Sunday=0, Monday=1, ..., Saturday=6). */
    DAY_OF_WEEK,
    /** CreatedAt:daysAgo is 5: the number of days from the date to today. */
    DAYS_AGO,
    /** CreatedAt:format('yyyy-MM-dd') is "2024-12-25"; argument: the pattern. */
    FORMAT,

    // Math functions

    /** Price:round(2) is 10.50; argument: decimal places (optional, default 0). */
    ROUND,
    /** Price:floor is 10. */
    FLOOR,
    /** Price:ceil is 11. */
    CEIL,
    /** Balance:abs is 100. */
    ABS,
    /** Price:add(Tax); argument: a property reference or a number. */
    ADD,
    /** Price:subtract(Discount); argument: a property reference or a number. */
    SUBTRACT,
    /** Price:multiply(Quantity); argument: a property reference or a number. */
    MULTIPLY,
    /** Total:divide(2); argument: a property reference or a number. */
    DIVIDE,
    /** Value:mod(3); argument: a property reference or a number. */
    MOD,
    /** Value:pow(2); argument: a property reference or a number. */
    POW,

    // Collection functions

    /** Items:offset(2): all items but the first two; argument: a whole number, 0 or greater. */
    SKIP,
    /** Items:limit(10): at most 10 items; argument: a whole number, 0 or greater. */
    TAKE,
    /**
     * Items:orderBy(CreatedAt, CreatedBy desc); arguments: {@link SortKeyNode}s. The first key orders,
     * the next keys order the items that are equal on the previous ones (thenBy).
     */
    ORDER_BY,
    /** The first item of a collection, or null when it is empty. It does not order the collection. */
    FIRST,
    /** The last item of a collection, or null when it is empty. It does not order the collection. */
    LAST,
    /** Items:distinct(Name): the distinct values of a property. */
    DISTINCT;

    /**
     * The name as FxMap writes it: ORDER_BY is "OrderBy".
     */
    public String pascalName() {
        return Names.pascal(this);
    }
}
