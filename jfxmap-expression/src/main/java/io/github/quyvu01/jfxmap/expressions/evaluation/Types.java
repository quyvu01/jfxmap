package io.github.quyvu01.jfxmap.expressions.evaluation;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;

/**
 * The declared types the evaluator follows, so that a collection that is empty still knows its item type.
 */
final class Types {
    private Types() {
    }

    /**
     * The raw class of a type, or null when it is not known.
     */
    static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return raw(p.getRawType());
        if (type instanceof GenericArrayType a) {
            var component = raw(a.getGenericComponentType());
            return component == null ? null : component.arrayType();
        }
        if (type instanceof WildcardType w) return raw(w.getUpperBounds()[0]);
        if (type instanceof TypeVariable<?> v) return raw(v.getBounds()[0]);
        return null;
    }

    /**
     * The item type of a collection type (Iterable&lt;T&gt;, T[]), or null when it is not known.
     */
    static Type elementType(Type type) {
        if (type instanceof Class<?> c) return c.isArray() ? c.getComponentType() : null;
        if (type instanceof GenericArrayType a) return a.getGenericComponentType();
        if (type instanceof ParameterizedType p) {
            var rawClass = raw(p);
            if (rawClass != null && Iterable.class.isAssignableFrom(rawClass)) {
                var arguments = p.getActualTypeArguments();
                if (arguments.length == 1) return arguments[0];
            }
        }
        return null;
    }

    /**
     * A list of items of a type.
     */
    static Type listOf(Type element) {
        if (element == null) return List.class;
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[]{element};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public String toString() {
                return "List<" + element.getTypeName() + ">";
            }
        };
    }

    /**
     * Whether the properties of a type can be checked by its class: not for an unknown type, Object, a Map or an
     * interface (the objects can be of any class).
     */
    static boolean isCheckable(Class<?> type) {
        return type != null && type != Object.class && !Map.class.isAssignableFrom(type) && !type.isInterface()
                && !type.isPrimitive();
    }
}
