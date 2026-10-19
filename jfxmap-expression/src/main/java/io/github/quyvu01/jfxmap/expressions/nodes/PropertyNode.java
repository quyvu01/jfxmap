package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A simple property access: Name, Email, Status.
 *
 * @param name       the property name
 * @param isNullSafe whether the access is null-safe ({@code Name?})
 */
public record PropertyNode(String name, boolean isNullSafe) implements ExpressionNode {
    public PropertyNode(String name) {
        this(name, false);
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitProperty(this, context);
    }
}
