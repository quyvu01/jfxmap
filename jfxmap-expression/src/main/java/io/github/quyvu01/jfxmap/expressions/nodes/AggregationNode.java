package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * An aggregation of a collection: Orders:sum(Total), Items:avg(Price)
 *
 * @param source          the collection
 * @param aggregationType the aggregation
 * @param propertyName    the property to aggregate (null for count)
 */
public record AggregationNode(ExpressionNode source, AggregationType aggregationType, String propertyName)
        implements ExpressionNode {
    public AggregationNode(ExpressionNode source, AggregationType aggregationType) {
        this(source, aggregationType, null);
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitAggregation(this, context);
    }
}
