package io.github.quyvu01.jfxmap.expressions.nodes;

import java.math.BigDecimal;

/**
 * A literal value: 'string', 123, true, null. A number is a {@link BigDecimal}.
 */
public record LiteralNode(Object value, LiteralType literalType) implements ExpressionNode {
    public static LiteralNode string(String value) {
        return new LiteralNode(value, LiteralType.STRING);
    }

    public static LiteralNode number(BigDecimal value) {
        return new LiteralNode(value, LiteralType.NUMBER);
    }

    public static LiteralNode bool(boolean value) {
        return new LiteralNode(value, LiteralType.BOOLEAN);
    }

    public static LiteralNode nullValue() {
        return new LiteralNode(null, LiteralType.NULL);
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitLiteral(this, context);
    }
}
