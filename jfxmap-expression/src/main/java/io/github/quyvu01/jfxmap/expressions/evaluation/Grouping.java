package io.github.quyvu01.jfxmap.expressions.evaluation;

import java.util.List;

/**
 * A group of {@code :groupBy(...)} that has no projection after it.
 *
 * @param key   the value of the key property, or the list of the values of the key properties of a multi-key groupBy
 * @param items the items of the group, in the order of the source
 */
public record Grouping(Object key, List<Object> items) {
    public Grouping {
        items = List.copyOf(items);
    }
}
