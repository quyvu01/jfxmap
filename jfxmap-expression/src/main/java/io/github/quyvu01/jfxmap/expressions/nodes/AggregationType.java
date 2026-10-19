package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * The aggregations of a collection.
 */
public enum AggregationType {
    COUNT,
    SUM,
    AVERAGE,
    MIN,
    MAX;

    /**
     * The name as FxMap writes it: AVERAGE is "Average".
     */
    public String pascalName() {
        return Names.pascal(this);
    }
}
