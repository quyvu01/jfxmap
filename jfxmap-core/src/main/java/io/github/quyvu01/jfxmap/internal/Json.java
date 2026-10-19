package io.github.quyvu01.jfxmap.internal;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.lang.reflect.Type;

/**
 * The JSON used for the values on the wire. Property names are matched without case, so that a value written by the
 * .NET FxMap ({@code {"Name": ...}}) reads into a Java bean ({@code name}), and fields are read and written
 * whatever their visibility. Not part of the public API.
 */
public final class Json {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            // DTOs often have no setters: their fields are written directly
            .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .build();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass().getName(), e);
        }
    }

    public static Object read(String json, Type type) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructType(type));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot read " + json + " as " + type.getTypeName(), e);
        }
    }
}
