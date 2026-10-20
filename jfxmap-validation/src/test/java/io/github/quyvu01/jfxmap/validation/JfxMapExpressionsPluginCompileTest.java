package io.github.quyvu01.jfxmap.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.Diagnostic;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles real sources with javac and the plugin, as FxMap.Analyzers.Tests runs the analyzer on real code.
 */
class JfxMapExpressionsPluginCompileTest {
    private static final String PLUGIN = JfxMapExpressionsPlugin.NAME;

    /** A profile whose configure() ends with {@code rules}, after {@code useDistributedKey("UserOf").of(...)}. */
    private static String profile(String rules) {
        return profile("", rules);
    }

    private static String profile(String members, String rules) {
        return """
                package sample;

                import io.github.quyvu01.jfxmap.fluent.ProfileOf;

                public class OrderProfile extends ProfileOf<OrderProfile.Dto> {
                    public static class Dto {
                        private Integer userId;
                        private String userName;

                        public Integer getUserId() {
                            return userId;
                        }

                        public String getUserName() {
                            return userName;
                        }

                        public void setUserName(String userName) {
                            this.userName = userName;
                        }
                    }
                %s
                    @Override
                    protected void configure() {
                        useDistributedKey("UserOf")
                                .of(Dto::getUserId)
                %s;
                    }
                }
                """.formatted(members, rules);
    }

    private static Javac.Result compile(String source) {
        return compile(PLUGIN, source);
    }

    private static Javac.Result compile(String plugin, String source) {
        return Javac.compile(plugin, Map.of("sample/OrderProfile.java", source));
    }

    private static long lineOf(String source, String text) {
        var index = source.indexOf(text);
        assertThat(index).as("'%s' in the source", text).isNotNegative();
        return source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Country.Name",
            "Country.{Id, Name}",
            "Countries:where(Active = true)",
            "{Id, Name, Country.Name as CountryName}",
            "Countries:where(Active = true):orderBy(Name).{Id, Name}",
            "Country?.Name",
            "Name:upper",
            "Addresses:count"})
    void acceptsAValidMapExpression(String expression) {
        var result = compile(profile("            .map(Dto::getUserName, \"%s\")".formatted(expression)));

        assertThat(result.success()).as("%s", result.errors()).isTrue();
        assertThat(result.reported(Diagnostic.Kind.ERROR)).isEmpty();
        assertThat(result.reported(Diagnostic.Kind.WARNING)).isEmpty();
    }

    @Test
    void reportsAnInvalidMapExpressionOnItsLiteral() {
        var source = profile("            .map(Dto::getUserName, \"Provinces:orderBy(Name\")");

        var result = compile(source);

        assertThat(result.success()).isFalse();
        var reported = result.reported(Diagnostic.Kind.ERROR);
        assertThat(reported).hasSize(1);
        assertThat(reported.getFirst().getMessage(null))
                .startsWith("OFX001: Expression 'Provinces:orderBy(Name' is invalid: ")
                .contains("orderBy");
        assertThat(reported.getFirst().getLineNumber()).isEqualTo(lineOf(source, "\"Provinces:orderBy(Name\""));
        // The diagnostic points at the literal, not at the call
        assertThat(reported.getFirst().getColumnNumber())
                .isEqualTo(source.lines().filter(l -> l.contains("Provinces")).findFirst().orElseThrow()
                        .indexOf('"') + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Country{Id, Name}", "Name:invalid", "{Id, (Name:upper)}", "Country.{Id, Name",
            "Orders:where(Status = 'Done')Items"})
    void reportsEachInvalidMapExpression(String expression) {
        var result = compile(profile("            .map(Dto::getUserName, \"%s\")".formatted(expression)));

        assertThat(result.success()).isFalse();
        assertThat(result.messages(Diagnostic.Kind.ERROR)).singleElement().asString()
                .startsWith("OFX001: Expression '%s' is invalid: ".formatted(expression));
    }

    @Test
    void reportsEveryInvalidExpressionOfAChain() {
        var result = compile(profile("""
                            .map(Dto::getUserName, "Name:invalid")
                            .map(Dto::getUserName, "Country.Name")
                            .map(Dto::getUserName, "Country{Id}")"""));

        assertThat(result.messages(Diagnostic.Kind.ERROR)).hasSize(2)
                .anyMatch(m -> m.contains("'Name:invalid'"))
                .anyMatch(m -> m.contains("'Country{Id}'"));
    }

    @Test
    void reportsInvalidConditionalExpressions() {
        var source = profile("""
                            .map(Dto::getUserName, c -> c
                                    .when(ctx -> true)
                                    .expression("Country{Id, Name}")
                                    .otherwise("Name:invalid"))""");

        var result = compile(source);

        var reported = result.reported(Diagnostic.Kind.ERROR);
        assertThat(reported).hasSize(2);
        assertThat(reported.get(0).getMessage(null)).startsWith("OFX001: Expression 'Country{Id, Name}' is invalid");
        assertThat(reported.get(0).getLineNumber()).isEqualTo(lineOf(source, "\"Country{Id, Name}\""));
        assertThat(reported.get(1).getMessage(null)).startsWith("OFX001: Expression 'Name:invalid' is invalid");
        assertThat(reported.get(1).getLineNumber()).isEqualTo(lineOf(source, "\"Name:invalid\""));
    }

    @Test
    void acceptsValidConditionalExpressionsAndSkipsTheFunctionOverloads() {
        var result = compile(profile("""
                            .map(Dto::getUserName, c -> c
                                    .when(ctx -> true)
                                    .expression("Country.Name")
                                    .otherwise("Name"))
                            .map(Dto::getUserName, c -> c
                                    .when(ctx -> true)
                                    .expression(ctx -> "Name:invalid")
                                    .otherwise(ctx -> "Country{Id}"))"""));

        assertThat(result.success()).as("%s", result.errors()).isTrue();
        assertThat(result.reported(Diagnostic.Kind.ERROR)).isEmpty();
    }

    @Test
    void skipsNullBlankAndNonConstantExpressions() {
        var result = compile(profile("""
                    private String fromConfig() {
                        return "Name:invalid";
                    }
                """, """
                            .map(Dto::getUserName, (String) null)
                            .map(Dto::getUserName, "")
                            .map(Dto::getUserName, "   ")
                            .map(Dto::getUserName, fromConfig())
                            .map(Dto::getUserName, String.valueOf("Name:invalid"))"""));

        assertThat(result.success()).as("%s", result.errors()).isTrue();
        assertThat(result.reported(Diagnostic.Kind.ERROR)).isEmpty();
    }

    @Test
    void reportsCompileTimeConstants() {
        var source = profile("""
                    private static final String BAD = "Name:invalid";
                    private static final String PREFIX = "Country";
                """, """
                            .map(Dto::getUserName, BAD)
                            .map(Dto::getUserName, OrderProfile.BAD)
                            .map(Dto::getUserName, PREFIX + "{Id}")
                            .map(Dto::getUserName, (PREFIX + ".Name"))
                            .map(Dto::getUserName, Constants.INVALID)""");

        var result = Javac.compile(PLUGIN, Map.of(
                "sample/OrderProfile.java", source,
                "sample/Constants.java", """
                        package sample;

                        final class Constants {
                            static final String INVALID = "Provinces:orderBy(Name" + "";

                            private Constants() {
                            }
                        }
                        """));

        assertThat(result.messages(Diagnostic.Kind.ERROR)).containsExactly(
                "OFX001: Expression 'Name:invalid' is invalid: " + ExpressionSyntax.error("Name:invalid").orElseThrow(),
                "OFX001: Expression 'Name:invalid' is invalid: " + ExpressionSyntax.error("Name:invalid").orElseThrow(),
                "OFX001: Expression 'Country{Id}' is invalid: " + ExpressionSyntax.error("Country{Id}").orElseThrow(),
                "OFX001: Expression 'Provinces:orderBy(Name' is invalid: "
                        + ExpressionSyntax.error("Provinces:orderBy(Name").orElseThrow());
    }

    @Test
    void ignoresMethodsOfOtherClassesWithTheSameNames() {
        var source = """
                package sample;

                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import java.util.function.Function;

                public class Unrelated {
                    static final class PropertyRuleBuilder {
                        PropertyRuleBuilder map(Object target, String expression) {
                            return this;
                        }

                        void expression(String expression) {
                        }

                        void otherwise(String expression) {
                        }
                    }

                    String map(Object target, String expression) {
                        return expression;
                    }

                    void run() {
                        map(this, "Name:invalid");
                        new PropertyRuleBuilder().map(this, "Country{Id}").expression("Name:invalid");
                        new PropertyRuleBuilder().otherwise("Name:invalid");
                        List.of("a").stream().map(s -> "Name:invalid").toList();
                        Optional.of("x").map(Function.identity());
                        Map.of("Name:invalid", "Country{Id}");
                    }
                }
                """;

        var result = Javac.compile(PLUGIN, Map.of("sample/Unrelated.java", source));

        assertThat(result.success()).as("%s", result.errors()).isTrue();
        assertThat(result.reported(Diagnostic.Kind.ERROR)).isEmpty();
        assertThat(result.reported(Diagnostic.Kind.WARNING)).isEmpty();
    }

    @Test
    void reportsOnceInNestedAndSecondaryClasses() {
        var source = """
                package sample;

                import io.github.quyvu01.jfxmap.fluent.ProfileOf;

                public class Outer {
                    public static class Dto {
                        private Integer userId;
                        private String userName;

                        public Integer getUserId() {
                            return userId;
                        }

                        public String getUserName() {
                            return userName;
                        }

                        public void setUserName(String userName) {
                            this.userName = userName;
                        }
                    }

                    public static class NestedProfile extends ProfileOf<Dto> {
                        @Override
                        protected void configure() {
                            useDistributedKey("UserOf").of(Dto::getUserId).map(Dto::getUserName, "Nested{Id}");
                        }
                    }
                }

                class SecondaryProfile extends ProfileOf<Outer.Dto> {
                    @Override
                    protected void configure() {
                        useDistributedKey("UserOf").of(Outer.Dto::getUserId)
                                .map(Outer.Dto::getUserName, "Secondary{Id}");
                        new ProfileOf<Outer.Dto>(Outer.Dto.class) {
                            @Override
                            protected void configure() {
                                useDistributedKey("UserOf").of(Outer.Dto::getUserId)
                                        .map(Outer.Dto::getUserName, "Anonymous{Id}");
                            }
                        }.rules();
                    }
                }
                """;

        var result = Javac.compile(PLUGIN, Map.of("sample/Outer.java", source));

        assertThat(result.messages(Diagnostic.Kind.ERROR)).hasSize(3)
                .anyMatch(m -> m.contains("'Nested{Id}'"))
                .anyMatch(m -> m.contains("'Secondary{Id}'"))
                .anyMatch(m -> m.contains("'Anonymous{Id}'"));
    }

    @Test
    void reportsAWarningInWarnMode() {
        var source = profile("            .map(Dto::getUserName, \"Name:invalid\")");

        var result = compile(PLUGIN + " warn", source);

        assertThat(result.success()).as("%s", result.errors()).isTrue();
        assertThat(result.reported(Diagnostic.Kind.ERROR)).isEmpty();
        assertThat(result.messages(Diagnostic.Kind.WARNING)).singleElement().asString()
                .startsWith("OFX001: Expression 'Name:invalid' is invalid: ");
    }

    @Test
    void reportsAnErrorWithTheExplicitErrorArgument() {
        var result = compile(PLUGIN + " error", profile("            .map(Dto::getUserName, \"Name:invalid\")"));

        assertThat(result.success()).isFalse();
        assertThat(result.reported(Diagnostic.Kind.ERROR)).hasSize(1);
    }

    @Test
    void failsTheCompilationOnAnUnknownArgument() {
        var result = compile(PLUGIN + " loud", profile("            .map(Dto::getUserName, \"Name\")"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement().asString()
                .contains("JfxMapExpressions: unknown argument 'loud', expected 'error' or 'warn'.");
    }

    @Test
    void doesNothingWithoutThePluginOption() {
        var result = compile(null, profile("            .map(Dto::getUserName, \"Name:invalid\")"));

        assertThat(result.success()).as("%s", result.errors()).isTrue();
        assertThat(result.reported(Diagnostic.Kind.ERROR)).isEmpty();
    }
}
