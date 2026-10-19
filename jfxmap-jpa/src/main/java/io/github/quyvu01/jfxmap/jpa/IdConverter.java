package io.github.quyvu01.jfxmap.jpa;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Converts the ids of a request, which travel as strings, to the type of the id attribute. A string that is not a
 * valid id of that type gives {@code null}: no entity can have it.
 */
final class IdConverter {
    private static final Map<Class<?>, Function<String, Object>> PARSERS = Map.ofEntries(
            Map.entry(String.class, s -> s),
            Map.entry(UUID.class, UUID::fromString),
            Map.entry(Long.class, s -> Long.valueOf(s.trim())),
            Map.entry(long.class, s -> Long.valueOf(s.trim())),
            Map.entry(Integer.class, s -> Integer.valueOf(s.trim())),
            Map.entry(int.class, s -> Integer.valueOf(s.trim())),
            Map.entry(Short.class, s -> Short.valueOf(s.trim())),
            Map.entry(short.class, s -> Short.valueOf(s.trim())),
            Map.entry(Byte.class, s -> Byte.valueOf(s.trim())),
            Map.entry(byte.class, s -> Byte.valueOf(s.trim())),
            Map.entry(BigInteger.class, s -> new BigInteger(s.trim())),
            Map.entry(BigDecimal.class, s -> new BigDecimal(s.trim())));

    private final Function<String, Object> parser;

    private IdConverter(Function<String, Object> parser) {
        this.parser = parser;
    }

    /** The converter to the given type, or {@code null} when the type is not supported. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static IdConverter of(Class<?> idType) {
        if (idType.isEnum()) return new IdConverter(s -> Enum.valueOf((Class) idType, s.trim()));
        var parser = PARSERS.get(idType);
        return parser == null ? null : new IdConverter(parser);
    }

    /** The id, or {@code null} when the string is not an id of this type. */
    Object convert(String id) {
        if (id == null) return null;
        try {
            return parser.apply(id);
        } catch (IllegalArgumentException e) {
            // NumberFormatException included
            return null;
        }
    }
}
