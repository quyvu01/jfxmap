package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A condition: the argument of a filter, of :any and :all, and the condition of a ternary.
 */
public sealed interface ConditionNode extends ExpressionNode permits BinaryConditionNode, LogicalConditionNode {
}
