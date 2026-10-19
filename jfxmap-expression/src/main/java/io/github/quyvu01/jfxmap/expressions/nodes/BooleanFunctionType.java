package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * The boolean functions of a collection.
 */
public enum BooleanFunctionType {
    /** True if any item matches the condition; without a condition, true if the collection is not empty. */
    ANY,
    /** True if all items match the condition; without a condition, true. */
    ALL
}
