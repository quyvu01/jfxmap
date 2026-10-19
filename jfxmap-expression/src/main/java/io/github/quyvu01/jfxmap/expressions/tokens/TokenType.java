package io.github.quyvu01.jfxmap.expressions.tokens;

/**
 * The type of a token of the FxMap expression language.
 */
public enum TokenType {
    // Literals
    IDENTIFIER,            // Property names, function names: Name, Country, count
    STRING,                // 'value' or "value"
    NUMBER,                // 123, 45.67, -10
    BOOLEAN,               // true, false
    NULL,                  // null

    // Operators - Comparison
    EQUAL,                 // =
    NOT_EQUAL,             // !=
    GREATER_THAN,          // >
    LESS_THAN,             // <
    GREATER_THAN_OR_EQUAL, // >=
    LESS_THAN_OR_EQUAL,    // <=

    // Operators - String
    CONTAINS,              // contains
    STARTS_WITH,           // startswith
    ENDS_WITH,             // endswith

    // Operators - Logical
    AND,                   // && or and
    OR,                    // || or or
    NOT,                   // ! or not

    // Punctuation
    DOT,                   // .
    COLON,                 // :
    COMMA,                 // ,
    QUESTION,              // ?
    QUESTION_QUESTION,     // ?? (null coalescing)

    // Brackets
    OPEN_PAREN,            // (
    CLOSE_PAREN,           // )
    OPEN_BRACKET,          // [
    CLOSE_BRACKET,         // ]
    OPEN_BRACE,            // {
    CLOSE_BRACE,           // }

    // Order direction
    ASC,                   // asc
    DESC,                  // desc

    // Alias
    AS,                    // as (for aliasing: Country.Name as CountryName)

    // End of expression
    END_OF_EXPRESSION
}
