package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A comparison: Status = 'Active', Name:count &gt; 3
 *
 * @param left     the left operand (a property or a function)
 * @param operator the comparison operator
 * @param right    the right operand (a literal)
 */
public record BinaryConditionNode(ExpressionNode left, ComparisonOperator operator, ExpressionNode right)
        implements ConditionNode {
    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitBinaryCondition(this, context);
    }
}
