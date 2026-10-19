package io.github.quyvu01.jfxmap.response;

/**
 * An expression the owner could not evaluate. The other expressions of the same request are still answered.
 *
 * @param expression the expression
 * @param message    why it failed
 */
public record ExpressionError(String expression, String message) {
}
