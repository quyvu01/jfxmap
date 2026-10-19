package io.github.quyvu01.jfxmap.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The expressions of a request travel as one JSON array in the {@code expression} field, as FxMap's
 * {@code JsonSerializer.Serialize(string[])} writes them: {@code [null,"Email","Country.Name"]}.
 */
final class ExpressionsJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private ExpressionsJson() {
    }

    static String write(List<String> expressions) {
        try {
            return MAPPER.writeValueAsString(expressions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException when the text is not a JSON array of strings */
    static List<String> read(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var expressions = MAPPER.readValue(json, STRINGS);
            return expressions == null ? List.of() : expressions;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The expressions are not a JSON array of strings: " + json, e);
        }
    }
}
