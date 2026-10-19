package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A boolean function of a collection: Orders:any, Orders:any(Status = 'Done'), Documents:all(IsApproved = true)
 *
 * <ul>
 *   <li>{@code Orders:any}: true if Orders is not empty</li>
 *   <li>{@code Orders:any(Status = 'Done')}: true if any order has Status = 'Done'</li>
 *   <li>{@code Documents:all}: always true</li>
 *   <li>{@code Documents:all(IsApproved = true)}: true if all documents are approved</li>
 * </ul>
 *
 * @param source       the collection
 * @param functionName any or all
 * @param condition    the condition each item is checked against, or null
 */
public record BooleanFunctionNode(ExpressionNode source, BooleanFunctionType functionName, ConditionNode condition)
        implements ExpressionNode {
    public BooleanFunctionNode(ExpressionNode source, BooleanFunctionType functionName) {
        this(source, functionName, null);
    }

    /**
     * Whether the function has a condition.
     */
    public boolean hasCondition() {
        return condition != null;
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitBooleanFunction(this, context);
    }
}
