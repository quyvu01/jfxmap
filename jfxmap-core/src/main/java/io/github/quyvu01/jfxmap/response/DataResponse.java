package io.github.quyvu01.jfxmap.response;

import java.util.List;
import java.util.Objects;

/**
 * The values of the requested expressions for one entity.
 *
 * @param id     the id as it was requested
 * @param values one value per expression
 */
public record DataResponse(String id, List<ValueResponse> values) {
    public DataResponse {
        values = List.copyOf(Objects.requireNonNullElse(values, List.of()));
    }
}
