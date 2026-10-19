package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A null-coalescing expression, A ?? B: A if it is not null, otherwise B.
 * {@code A ?? B ?? C} is {@code A ?? (B ?? C)}.
 */
public record CoalesceNode(ExpressionNode left, ExpressionNode right) implements ExpressionNode {
    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitCoalesce(this, context);
    }
}
