package io.github.quyvu01.jfxmap.validation;

import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParseException;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;
import io.github.quyvu01.jfxmap.expressions.tokens.ExpressionTokenizeException;

import java.util.Optional;

/**
 * Checks the syntax of an expression with the parser of jfxmap-expression, as FxMap.Analyzers does with
 * {@code ExpressionParser.Parse}. Shared by the javac plugin and {@link JfxMapValidator}.
 */
public final class ExpressionSyntax {
    /** The id of the diagnostic, FxMap.Analyzers' {@code OFX001}. */
    public static final String DIAGNOSTIC_ID = "OFX001";

    private ExpressionSyntax() {
    }

    /**
     * Why {@code expression} does not parse, or empty when it does.
     *
     * @param expression a non-null expression
     */
    public static Optional<String> error(String expression) {
        try {
            ExpressionParser.parse(expression);
            return Optional.empty();
        } catch (ExpressionTokenizeException | ExpressionParseException e) {
            return Optional.of(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return Optional.of("Unexpected error: " + e.getMessage());
        }
    }

    /** The message reported for an invalid expression: {@code Expression '<expression>' is invalid: <error>}. */
    public static String message(String expression, String error) {
        return "Expression '%s' is invalid: %s".formatted(expression, error);
    }
}
