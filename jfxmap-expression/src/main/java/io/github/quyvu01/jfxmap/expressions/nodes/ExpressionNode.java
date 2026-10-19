package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A node of the abstract syntax tree of the FxMap expression language.
 */
public sealed interface ExpressionNode
        permits PropertyNode, NavigationNode, FilterNode, ProjectionNode, RootProjectionNode, FunctionNode,
        BooleanFunctionNode, ConditionNode, LiteralNode, AggregationNode, CoalesceNode, TernaryNode, GroupByNode,
        GroupElementsNode, SortKeyNode {

    /**
     * Accepts a visitor to process this node.
     */
    <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context);
}
