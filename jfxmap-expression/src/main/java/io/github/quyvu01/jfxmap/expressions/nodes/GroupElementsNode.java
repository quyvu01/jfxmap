package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * The items of a group, in a projection after groupBy: the source of {@code :count}, {@code :sum(Total)} and of an
 * inner projection {@code {Id, Total} as Items}.
 */
public record GroupElementsNode() implements ExpressionNode {
    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitGroupElements(this, context);
    }
}
