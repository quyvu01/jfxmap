package io.github.quyvu01.jfxmap.expressions.evaluation;

/**
 * Thrown when an expression cannot be evaluated against an object: a property that does not exist, a function applied
 * to a value of the wrong kind, a navigation through a null value without {@code ?}.
 */
public class ExpressionEvaluationException extends RuntimeException {
    public ExpressionEvaluationException(String message) {
        super(message);
    }

    public ExpressionEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
