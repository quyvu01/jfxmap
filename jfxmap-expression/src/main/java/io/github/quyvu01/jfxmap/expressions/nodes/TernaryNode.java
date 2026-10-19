package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A conditional expression: Condition ? WhenTrue : WhenFalse
 *
 * <ul>
 *   <li>{@code IsActive = true ? 'Active' : 'Inactive'}</li>
 *   <li>{@code Orders:count > 0 ? Orders:sum(Total) : 0}</li>
 *   <li>{@code Score >= 90 ? 'A' : Score >= 80 ? 'B' : 'C'} (nested)</li>
 * </ul>
 */
public record TernaryNode(ConditionNode condition, ExpressionNode whenTrue, ExpressionNode whenFalse)
        implements ExpressionNode {
    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitTernary(this, context);
    }
}
