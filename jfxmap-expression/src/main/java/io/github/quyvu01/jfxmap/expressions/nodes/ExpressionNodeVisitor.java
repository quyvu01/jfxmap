package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * Visitor for traversing the nodes of an expression.
 *
 * @param <R> the result of a visit
 * @param <C> the context passed down the tree
 */
public interface ExpressionNodeVisitor<R, C> {
    R visitProperty(PropertyNode node, C context);

    R visitNavigation(NavigationNode node, C context);

    R visitFilter(FilterNode node, C context);

    R visitProjection(ProjectionNode node, C context);

    R visitRootProjection(RootProjectionNode node, C context);

    R visitFunction(FunctionNode node, C context);

    R visitBooleanFunction(BooleanFunctionNode node, C context);

    R visitBinaryCondition(BinaryConditionNode node, C context);

    R visitLogicalCondition(LogicalConditionNode node, C context);

    R visitLiteral(LiteralNode node, C context);

    R visitAggregation(AggregationNode node, C context);

    R visitCoalesce(CoalesceNode node, C context);

    R visitTernary(TernaryNode node, C context);

    R visitGroupBy(GroupByNode node, C context);

    R visitGroupElements(GroupElementsNode node, C context);
}
