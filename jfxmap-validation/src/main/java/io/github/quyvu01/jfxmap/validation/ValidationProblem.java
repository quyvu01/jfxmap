package io.github.quyvu01.jfxmap.validation;

import java.util.Objects;

/**
 * A problem found by {@link JfxMapValidator}.
 *
 * @param source     the profile or entity configuration class
 * @param property   the property of the rule or of the entity, or {@code null} when the problem is the whole class
 * @param expression the invalid expression or exposed name, or {@code null}
 * @param message    what is wrong
 */
public record ValidationProblem(Class<?> source, String property, String expression, String message) {
    public ValidationProblem {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
    }

    /** One line: {@code OrderProfile.userEmail: Expression 'Email:' is invalid: ...}. */
    public String describe() {
        return (property == null ? source.getName() : source.getName() + "." + property) + ": " + message;
    }
}
