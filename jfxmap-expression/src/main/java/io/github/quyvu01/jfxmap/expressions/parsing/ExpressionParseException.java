package io.github.quyvu01.jfxmap.expressions.parsing;

/**
 * Thrown when an expression is not valid.
 */
public class ExpressionParseException extends RuntimeException {
    public ExpressionParseException(String message) {
        super(message);
    }
}
