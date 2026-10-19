package io.github.quyvu01.jfxmap.expressions.nodes;

import java.util.List;

/**
 * A projection selecting properties from a source: Orders.{Id, Name, Description}.
 * The source is a collection (each item is projected) or a single object.
 *
 * <ul>
 *   <li>{@code Orders.{Id, Name}}: simple properties</li>
 *   <li>{@code Orders.{Id, Customer.Name as CustomerName}}: navigation with alias</li>
 *   <li>{@code Orders.{Id, (Status = 'Done' ? 'Yes' : 'No') as StatusText}}: computed expression</li>
 * </ul>
 *
 * @param source     the source to project from
 * @param properties the properties to select
 */
public record ProjectionNode(ExpressionNode source, List<ProjectionProperty> properties) implements ExpressionNode {
    public ProjectionNode {
        properties = List.copyOf(properties);
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitProjection(this, context);
    }
}
