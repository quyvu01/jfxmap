package io.github.quyvu01.jfxmap.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionSyntaxTest {
    @Test
    void acceptsAValidExpression() {
        assertThat(ExpressionSyntax.error("Countries:where(Active = true):orderBy(Name).{Id, Name}")).isEmpty();
    }

    @Test
    void givesTheParserMessageOfAnInvalidExpression() {
        assertThat(ExpressionSyntax.error("Name:invalid")).hasValueSatisfying(error ->
                assertThat(error).contains("invalid"));
        assertThat(ExpressionSyntax.error("Country{Id, Name}")).isPresent();
        assertThat(ExpressionSyntax.error("Provinces:orderBy(Name")).isPresent();
    }

    @Test
    void givesTheTokenizerMessageOfAnExpressionThatCannotBeTokenized() {
        assertThat(ExpressionSyntax.error("Name = 'unterminated")).isPresent();
    }

    @Test
    void formatsTheMessageAsFxmapAnalyzersDoes() {
        assertThat(ExpressionSyntax.message("Name:invalid", "Unknown function"))
                .isEqualTo("Expression 'Name:invalid' is invalid: Unknown function");
        assertThat(ExpressionSyntax.DIAGNOSTIC_ID).isEqualTo("OFX001");
    }
}
