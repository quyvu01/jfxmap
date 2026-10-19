package io.github.quyvu01.jfxmap.expressions.tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Splits an FxMap expression into a sequence of tokens.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>Property navigation: Country.Name, User?.Address</li>
 *   <li>Filters: :where(Status = 'Active', Name:count &gt; 3)</li>
 *   <li>Collection functions: :orderBy(Name):first, :orderBy(CreatedAt desc):limit(10)</li>
 *   <li>Projections: .{Id, Name, Description}</li>
 *   <li>Functions: :count, :sum, :avg, :min, :max</li>
 * </ul>
 */
public final class Tokenizer {
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("true", TokenType.BOOLEAN),
            Map.entry("false", TokenType.BOOLEAN),
            Map.entry("null", TokenType.NULL),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT),
            Map.entry("asc", TokenType.ASC),
            Map.entry("desc", TokenType.DESC),
            Map.entry("as", TokenType.AS),
            Map.entry("contains", TokenType.CONTAINS),
            Map.entry("startswith", TokenType.STARTS_WITH),
            Map.entry("endswith", TokenType.ENDS_WITH));

    private final String expression;
    private final List<Token> tokens = new ArrayList<>();
    private int position;

    public Tokenizer(String expression) {
        this.expression = Objects.requireNonNull(expression, "expression");
    }

    /**
     * Tokenizes the expression; the last token is always {@link TokenType#END_OF_EXPRESSION}.
     */
    public List<Token> tokenize() {
        tokens.clear();
        position = 0;

        while (!isAtEnd()) {
            skipWhitespace();
            if (isAtEnd()) break;
            tokens.add(scanToken());
        }

        tokens.add(new Token(TokenType.END_OF_EXPRESSION, "", position));
        return List.copyOf(tokens);
    }

    private Token scanToken() {
        var startPosition = position;
        var c = advance();

        switch (c) {
            case '.': return new Token(TokenType.DOT, ".", startPosition);
            case ':': return new Token(TokenType.COLON, ":", startPosition);
            case ',': return new Token(TokenType.COMMA, ",", startPosition);
            case '?':
                return match('?')
                        ? new Token(TokenType.QUESTION_QUESTION, "??", startPosition)
                        : new Token(TokenType.QUESTION, "?", startPosition);
            case '(': return new Token(TokenType.OPEN_PAREN, "(", startPosition);
            case ')': return new Token(TokenType.CLOSE_PAREN, ")", startPosition);
            case '[': return new Token(TokenType.OPEN_BRACKET, "[", startPosition);
            case ']': return new Token(TokenType.CLOSE_BRACKET, "]", startPosition);
            case '{': return new Token(TokenType.OPEN_BRACE, "{", startPosition);
            case '}': return new Token(TokenType.CLOSE_BRACE, "}", startPosition);
            case '=': return new Token(TokenType.EQUAL, "=", startPosition);
            case '!':
                return match('=')
                        ? new Token(TokenType.NOT_EQUAL, "!=", startPosition)
                        : new Token(TokenType.NOT, "!", startPosition);
            case '>':
                return match('=')
                        ? new Token(TokenType.GREATER_THAN_OR_EQUAL, ">=", startPosition)
                        : new Token(TokenType.GREATER_THAN, ">", startPosition);
            case '<':
                return match('=')
                        ? new Token(TokenType.LESS_THAN_OR_EQUAL, "<=", startPosition)
                        : new Token(TokenType.LESS_THAN, "<", startPosition);
            case '&':
                if (match('&')) return new Token(TokenType.AND, "&&", startPosition);
                throw new ExpressionTokenizeException("Expected '&&' at position " + startPosition);
            case '|':
                if (match('|')) return new Token(TokenType.OR, "||", startPosition);
                throw new ExpressionTokenizeException("Expected '||' at position " + startPosition);
            case '\'':
            case '"':
                return scanString(c, startPosition);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return scanNumber(c, startPosition);
                if (isIdentifierStart(c)) return scanIdentifier(c, startPosition);
                throw new ExpressionTokenizeException(
                        "Unexpected character '" + c + "' at position " + startPosition);
        }
    }

    private Token scanString(char quote, int startPosition) {
        var sb = new StringBuilder();

        while (!isAtEnd() && peek() != quote) {
            if (peek() == '\\' && position + 1 < expression.length()) {
                advance(); // consume backslash
                var escaped = advance();
                switch (escaped) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(escaped); break; // \\, \', \" and any other character as it is
                }
            } else {
                sb.append(advance());
            }
        }

        if (isAtEnd())
            throw new ExpressionTokenizeException("Unterminated string starting at position " + startPosition);

        advance(); // consume closing quote
        return new Token(TokenType.STRING, sb.toString(), startPosition);
    }

    private Token scanNumber(char firstChar, int startPosition) {
        var sb = new StringBuilder();
        sb.append(firstChar);

        while (!isAtEnd() && (Character.isDigit(peek()) || peek() == '.')) {
            // Avoid consuming '.' if it's a property accessor (e.g., "123.Name")
            if (peek() == '.' && position + 1 < expression.length()
                    && !Character.isDigit(expression.charAt(position + 1)))
                break;

            sb.append(advance());
        }

        return new Token(TokenType.NUMBER, sb.toString(), startPosition);
    }

    private Token scanIdentifier(char firstChar, int startPosition) {
        var sb = new StringBuilder();
        sb.append(firstChar);

        while (!isAtEnd() && isIdentifierPart(peek())) sb.append(advance());

        var value = sb.toString();

        // Keywords are case-insensitive
        var keywordType = KEYWORDS.get(value.toLowerCase(Locale.ROOT));
        return new Token(keywordType != null ? keywordType : TokenType.IDENTIFIER, value, startPosition);
    }

    private void skipWhitespace() {
        while (!isAtEnd() && Character.isWhitespace(peek())) advance();
    }

    private boolean isAtEnd() {
        return position >= expression.length();
    }

    private char peek() {
        return isAtEnd() ? '\0' : expression.charAt(position);
    }

    private char advance() {
        return expression.charAt(position++);
    }

    private boolean match(char expected) {
        if (isAtEnd() || expression.charAt(position) != expected) return false;
        position++;
        return true;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
