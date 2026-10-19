package io.github.quyvu01.jfxmap.response;

import java.util.List;
import java.util.Objects;

/**
 * The answer to a {@link MapRequest}: one item per id found, and the expressions that failed.
 *
 * @param items  the entities found
 * @param errors the expressions the owner could not evaluate
 */
public record ItemsResponse(List<DataResponse> items, List<ExpressionError> errors) {
    private static final ItemsResponse EMPTY = new ItemsResponse(List.of(), List.of());

    public ItemsResponse {
        items = List.copyOf(Objects.requireNonNullElse(items, List.of()));
        errors = List.copyOf(Objects.requireNonNullElse(errors, List.of()));
    }

    public ItemsResponse(List<DataResponse> items) {
        this(items, List.of());
    }

    public static ItemsResponse empty() {
        return EMPTY;
    }
}
