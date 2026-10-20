package io.github.quyvu01.jfxmap.validation;

import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.MappingRule;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.internal.ProfilePlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks profiles and entity configurations at startup, for the expressions that the javac plugin cannot see because
 * they are not compile-time constants, and for the mistakes the mapper would otherwise report on the first mapping.
 *
 * <pre>{@code
 * JfxMapValidator.validateOrThrow(profiles, entityConfigurations); // e.g. in an ApplicationRunner
 * }</pre>
 *
 * <p>A profile is checked by declaring its rules ({@link ProfileOf#rules()}), planning them as the mapper does
 * (readable keys, writable targets, no dependency cycle), and parsing the fixed expression of each rule. A null or
 * blank expression is not parsed, and the expressions of a conditional rule are functions, so they are skipped. An
 * entity configuration is checked by running its {@code configure()} (id and key declared, exposed names unique) and
 * by checking that each exposed name is a plain name an expression can use.
 *
 * <p>Nothing calls the validator by itself: the expressions are parsed with the Java parser, which may reject a few
 * constructs that FxMap's parser accepts, so the check is opt-in.
 */
public final class JfxMapValidator {
    private JfxMapValidator() {
    }

    /** The problems of {@code profiles}; empty when they are all valid. */
    public static List<ValidationProblem> validate(ProfileOf<?>... profiles) {
        return validate(List.of(profiles));
    }

    /** The problems of {@code profiles}; empty when they are all valid. */
    public static List<ValidationProblem> validate(Iterable<? extends ProfileOf<?>> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        var problems = new ArrayList<ValidationProblem>();
        for (var profile : profiles) checkProfile(profile, problems);
        return List.copyOf(problems);
    }

    /** The problems of the entity configurations; empty when they are all valid. */
    public static List<ValidationProblem> validateEntities(EntityConfigureOf<?>... configurations) {
        return validateEntities(List.of(configurations));
    }

    /** The problems of the entity configurations; empty when they are all valid. */
    public static List<ValidationProblem> validateEntities(Iterable<? extends EntityConfigureOf<?>> configurations) {
        Objects.requireNonNull(configurations, "configurations");
        var problems = new ArrayList<ValidationProblem>();
        for (var configuration : configurations) checkEntity(configuration, problems);
        return List.copyOf(problems);
    }

    /** Throws a {@link JfxMapValidationException} listing every problem of {@code profiles}, if there is any. */
    public static void validateOrThrow(ProfileOf<?>... profiles) {
        throwIfAny(validate(profiles));
    }

    /** Throws a {@link JfxMapValidationException} listing every problem of the configurations, if there is any. */
    public static void validateEntitiesOrThrow(EntityConfigureOf<?>... configurations) {
        throwIfAny(validateEntities(configurations));
    }

    /**
     * Throws a {@link JfxMapValidationException} listing every problem of the profiles and of the entity
     * configurations, if there is any.
     */
    public static void validateOrThrow(Iterable<? extends ProfileOf<?>> profiles,
                                       Iterable<? extends EntityConfigureOf<?>> configurations) {
        var problems = new ArrayList<>(validate(profiles));
        problems.addAll(validateEntities(configurations));
        throwIfAny(problems);
    }

    private static void throwIfAny(List<ValidationProblem> problems) {
        if (!problems.isEmpty()) throw new JfxMapValidationException(problems);
    }

    private static void checkProfile(ProfileOf<?> profile, List<ValidationProblem> problems) {
        Objects.requireNonNull(profile, "profile");
        var source = profile.getClass();
        List<MappingRule> rules;
        try {
            rules = profile.rules();
        } catch (RuntimeException e) {
            problems.add(new ValidationProblem(source, null, null, messageOf(e)));
            return;
        }
        for (var rule : rules) {
            var expression = rule.expression();
            if (expression == null || expression.isBlank()) continue;
            ExpressionSyntax.error(expression).ifPresent(error -> problems.add(new ValidationProblem(
                    source, rule.targetProperty(), expression, ExpressionSyntax.message(expression, error))));
        }
        try {
            ProfilePlan.of(profile);
        } catch (RuntimeException e) {
            problems.add(new ValidationProblem(source, null, null, messageOf(e)));
        }
    }

    private static void checkEntity(EntityConfigureOf<?> configuration, List<ValidationProblem> problems) {
        Objects.requireNonNull(configuration, "configuration");
        var source = configuration.getClass();
        Map<String, String> exposedNames;
        try {
            // Runs configure() and checks that the id and the key are declared
            configuration.key();
            exposedNames = configuration.exposedNames();
        } catch (RuntimeException e) {
            problems.add(new ValidationProblem(source, null, null, messageOf(e)));
            return;
        }
        exposedNames.forEach((name, property) -> {
            if (!isPlainName(name))
                problems.add(new ValidationProblem(source, property, name,
                        "The exposed name '%s' cannot be used in an expression: it must be a name such as UserEmail."
                                .formatted(name)));
        });
    }

    /** Whether an expression made of {@code name} alone reads the property {@code name}. */
    static boolean isPlainName(String name) {
        try {
            return ExpressionParser.parse(name) instanceof PropertyNode property
                    && property.name().equals(name) && !property.isNullSafe();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getName() : e.getMessage();
    }
}
