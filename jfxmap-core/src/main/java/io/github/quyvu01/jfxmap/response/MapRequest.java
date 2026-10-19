package io.github.quyvu01.jfxmap.response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One request to the owner of a distributed key: the ids to look up and the expressions to evaluate on each of them.
 * A {@code null} expression asks for the default property of the entity.
 *
 * @param selectorIds the distinct ids, as strings
 * @param expressions the distinct expressions
 */
public record MapRequest(List<String> selectorIds, List<String> expressions) {
    public MapRequest {
        // List.copyOf rejects null elements, and null is the expression of the default property
        selectorIds = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(selectorIds, "selectorIds")));
        expressions = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(expressions, "expressions")));
    }
}
