package io.github.quyvu01.jfxmap.expressions.tokens;

/**
 * A single token of the FxMap expression language.
 *
 * @param type     the type of the token
 * @param value    the text of the token (the unescaped content for a string)
 * @param position the position of the token in the expression
 */
public record Token(TokenType type, String value, int position) {
    @Override
    public String toString() {
        return type + "(" + value + ") at " + position;
    }
}
