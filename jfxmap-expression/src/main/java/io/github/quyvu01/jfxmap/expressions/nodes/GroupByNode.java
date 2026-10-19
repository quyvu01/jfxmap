package io.github.quyvu01.jfxmap.expressions.nodes;

import java.util.List;

/**
 * A grouping of a collection: Orders:groupBy(Status), Orders:groupBy(Year, Month).
 * In a projection that follows it, the key properties are read by their names and the functions with no source
 * ({@code :count}, {@code :sum(Total)}) apply to the items of the group.
 *
 * @param source        the collection to group
 * @param keyProperties the properties to group by
 */
public record GroupByNode(ExpressionNode source, List<String> keyProperties) implements ExpressionNode {
    public GroupByNode {
        keyProperties = List.copyOf(keyProperties);
    }

    /** Whether the grouping has one key (groupBy(Status)). */
    public boolean isSingleKey() {
        return keyProperties.size() == 1;
    }

    /** Whether the grouping has several keys (groupBy(Year, Month)). */
    public boolean isMultiKey() {
        return keyProperties.size() > 1;
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitGroupBy(this, context);
    }
}
