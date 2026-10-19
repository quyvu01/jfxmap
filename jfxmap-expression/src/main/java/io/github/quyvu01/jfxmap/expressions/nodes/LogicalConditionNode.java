package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A logical combination of conditions: (A &amp;&amp; B), (A || B)
 */
public record LogicalConditionNode(ConditionNode left, LogicalOperator operator, ConditionNode right)
        implements ConditionNode {
    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitLogicalCondition(this, context);
    }
}
