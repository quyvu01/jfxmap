package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A key of {@code :orderBy(...)}: a property and its direction. {@code CreatedAt} and {@code CreatedAt asc} are
 * ascending. It exists only as an argument of orderBy, which reads it directly; it is not visited like other nodes.
 */
public record SortKeyNode(String name, OrderDirection direction) implements ExpressionNode {
    public SortKeyNode(String name) {
        this(name, OrderDirection.ASC);
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        throw new UnsupportedOperationException("A sort key is only valid as an argument of the orderBy function");
    }
}
