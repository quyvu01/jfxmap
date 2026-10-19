package io.github.quyvu01.jfxmap.response;

/**
 * The value of one expression for one entity.
 *
 * @param expression the expression as it was requested ({@code null} for the default property)
 * @param value      the value serialized as JSON, or {@code null} when there is none
 */
public record ValueResponse(String expression, String value) {
}
