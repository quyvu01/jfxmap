package io.github.quyvu01.jfxmap.internal;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Tells which types can hold an object to map (a DTO, or a collection of DTOs) and which are leaves.
 * Not part of the public API.
 */
public final class TypeClassifier {
    private TypeClassifier() {
    }

    /** A value of this type can never contain a model: numbers, strings, dates, enums, ids... */
    public static boolean isLeaf(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type == UUID.class
                || TemporalAccessor.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || (type.isArray() && isLeaf(type.getComponentType()));
    }

    /**
     * Whether a property declared with this type may hold objects to walk. JDK types other than collections and
     * maps never do, nor do collections whose items are leaves ({@code List<String>}).
     */
    public static boolean shouldWalk(Type declared) {
        return shouldWalk(declared, 0);
    }

    private static boolean shouldWalk(Type declared, int depth) {
        if (depth > 8) return true;
        var raw = rawType(declared);
        if (raw == null) return true;
        if (isLeaf(raw)) return false;
        if (raw.isArray()) return shouldWalk(raw.getComponentType(), depth + 1);
        if (Collection.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw))
            return typeArgument(declared, 0).map(t -> shouldWalk(t, depth + 1)).orElse(true);
        if (Map.class.isAssignableFrom(raw))
            return typeArgument(declared, 1).map(t -> shouldWalk(t, depth + 1)).orElse(true);
        // Optional, Class, URI, streams and the rest of the JDK: nothing to map inside
        var packageName = raw.getPackageName();
        return !(packageName.startsWith("java.") || packageName.startsWith("javax.") || packageName.startsWith("jdk."));
    }

    static Class<?> rawType(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return rawType(p.getRawType());
        if (type instanceof WildcardType w) return w.getUpperBounds().length == 1 ? rawType(w.getUpperBounds()[0]) : null;
        // Type variables and generic arrays: unknown until runtime
        return null;
    }

    private static java.util.Optional<Type> typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index)
            return java.util.Optional.of(p.getActualTypeArguments()[index]);
        return java.util.Optional.empty();
    }
}
