package io.github.quyvu01.jfxmap.expressions.tokens;

/**
 * Thrown when an expression cannot be split into tokens.
 */
public class ExpressionTokenizeException extends RuntimeException {
    public ExpressionTokenizeException(String message) {
        super(message);
    }
}
