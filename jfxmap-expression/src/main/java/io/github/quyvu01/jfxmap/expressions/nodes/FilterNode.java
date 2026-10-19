package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A filter applied to a collection: Orders:where(Status = 'Completed')
 *
 * @param source    the collection to filter
 * @param condition the condition an item must meet
 */
public record FilterNode(ExpressionNode source, ConditionNode condition) implements ExpressionNode {
    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitFilter(this, context);
    }
}
