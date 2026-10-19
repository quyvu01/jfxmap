package io.github.quyvu01.jfxmap.internal;

import java.lang.reflect.ParameterizedType;

/** Reads the model type that a subclass gives to a generic base such as {@code ProfileOf<UserDto>}. */
public final class TypeTokens {
    private TypeTokens() {
    }

    public static Class<?> typeArgument(Class<?> subclass, Class<?> genericBase) {
        for (var c = subclass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getGenericSuperclass() instanceof ParameterizedType p && p.getRawType() == genericBase) {
                var argument = TypeClassifier.rawType(p.getActualTypeArguments()[0]);
                if (argument != null) return argument;
                break;
            }
        }
        throw new IllegalStateException(("Cannot tell the model type of %s: extend %s with a concrete type, "
                + "or pass the type to the constructor.").formatted(subclass.getName(), genericBase.getSimpleName()));
    }
}
