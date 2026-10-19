package io.github.quyvu01.jfxmap.expressions.evaluation;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the properties of records, JavaBeans and public fields.
 *
 * <p>A name is looked up as it is written, then with its first letter in the other case, so that the names of FxMap
 * expressions, written in PascalCase ({@code Orders:sum(Total)}), find the Java properties ({@code total()},
 * {@code getTotal()}, {@code total}). For each name, in order: a record component, a getter ({@code getName()}, or
 * {@code isName()} for a boolean), a public field.
 */
public final class BeanPropertyAccessor implements PropertyAccessor {
    private final Map<Class<?>, Map<String, Optional<Property>>> cache = new ConcurrentHashMap<>();

    @Override
    public Property find(Class<?> type, String name) {
        return cache.computeIfAbsent(type, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, n -> Optional.ofNullable(resolve(type, n)))
                .orElse(null);
    }

    private static Property resolve(Class<?> type, String name) {
        if (name.isEmpty()) return null;

        var candidates = new LinkedHashSet<String>();
        candidates.add(name);
        candidates.add(withFirstLetter(name, false));
        candidates.add(withFirstLetter(name, true));

        for (var candidate : candidates) {
            var property = recordComponent(type, candidate);
            if (property == null) property = getter(type, candidate);
            if (property == null) property = field(type, candidate);
            if (property != null) return property;
        }

        return null;
    }

    private static Property recordComponent(Class<?> type, String name) {
        if (!type.isRecord()) return null;
        for (var component : type.getRecordComponents()) {
            if (component.getName().equals(name))
                return new MethodProperty(component.getAccessor(), component.getGenericType());
        }
        return null;
    }

    private static Property getter(Class<?> type, String name) {
        var suffix = withFirstLetter(name, true);
        for (var prefix : new String[]{"get", "is"}) {
            Method method;
            try {
                method = type.getMethod(prefix + suffix);
            } catch (NoSuchMethodException e) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) continue;
            if (method.getDeclaringClass() == Object.class) continue; // getClass is not a property
            if (prefix.equals("is") && method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class)
                continue;
            return new MethodProperty(method, method.getGenericReturnType());
        }
        return null;
    }

    private static Property field(Class<?> type, String name) {
        Field field;
        try {
            field = type.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
        if (Modifier.isStatic(field.getModifiers())) return null;
        makeAccessible(field);
        return new Property() {
            @Override
            public Type type() {
                return field.getGenericType();
            }

            @Override
            public Object get(Object target) {
                try {
                    return field.get(target);
                } catch (IllegalAccessException e) {
                    throw new ExpressionEvaluationException("Cannot read field '" + name + "' of " + type.getName(), e);
                }
            }
        };
    }

    private record MethodProperty(Method method, Type type) implements Property {
        MethodProperty {
            makeAccessible(method);
        }

        @Override
        public Object get(Object target) {
            try {
                return method.invoke(target);
            } catch (IllegalAccessException e) {
                throw new ExpressionEvaluationException("Cannot read '" + method.getName() + "' of "
                        + method.getDeclaringClass().getName(), e);
            } catch (InvocationTargetException e) {
                var cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new ExpressionEvaluationException("'" + method.getName() + "' of "
                        + method.getDeclaringClass().getName() + " failed", cause);
            }
        }
    }

    // A public member of a class that is not public (a nested or package-private class) needs it to be invoked
    private static void makeAccessible(AccessibleObject member) {
        member.trySetAccessible();
    }

    private static String withFirstLetter(String name, boolean upper) {
        var first = name.substring(0, 1);
        return (upper ? first.toUpperCase(Locale.ROOT) : first.toLowerCase(Locale.ROOT)) + name.substring(1);
    }
}
