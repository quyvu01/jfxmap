package io.github.quyvu01.jfxmap.expressions.nodes;

import java.util.List;

/**
 * A projection of the root object: {Id, Name, Country.Name as CountryName}.
 * It differs from {@link ProjectionNode}, which projects from a source.
 *
 * @param properties the properties to select from the root object
 */
public record RootProjectionNode(List<ProjectionProperty> properties) implements ExpressionNode {
    public RootProjectionNode {
        properties = List.copyOf(properties);
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitRootProjection(this, context);
    }
}
