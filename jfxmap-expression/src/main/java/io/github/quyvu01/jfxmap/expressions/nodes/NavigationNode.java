package io.github.quyvu01.jfxmap.expressions.nodes;

import java.util.List;

/**
 * A chain of property navigations: Country.Province.Name
 *
 * @param segments the ordered segments of the navigation
 */
public record NavigationNode(List<ExpressionNode> segments) implements ExpressionNode {
    public NavigationNode {
        segments = List.copyOf(segments);
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitNavigation(this, context);
    }
}
