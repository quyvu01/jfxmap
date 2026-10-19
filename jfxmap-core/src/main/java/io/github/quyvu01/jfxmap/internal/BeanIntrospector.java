package io.github.quyvu01.jfxmap.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the properties of a class: JavaBean getters ({@code getX}/{@code isX}), record components and plain
 * instance fields. A property is written through {@code setX} when there is one, else through its field.
 * Not part of the public API.
 */
public final class BeanIntrospector {
    private static final Map<Class<?>, Map<String, BeanProperty>> CACHE = new ConcurrentHashMap<>();

    private BeanIntrospector() {
    }

    public static Map<String, BeanProperty> properties(Class<?> type) {
        return CACHE.computeIfAbsent(type, BeanIntrospector::introspect);
    }

    public static BeanProperty property(Class<?> type, String name) {
        return properties(type).get(name);
    }

    private static Map<String, BeanProperty> introspect(Class<?> type) {
        var getters = new LinkedHashMap<String, Method>();
        var setters = new LinkedHashMap<String, Method>();
        var fields = new LinkedHashMap<String, Field>();

        for (var c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                fields.putIfAbsent(f.getName(), f);
            }
        }

        if (type.isRecord()) {
            for (var component : type.getRecordComponents())
                getters.put(component.getName(), component.getAccessor());
        }

        for (var m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class || m.isBridge()) continue;
            var name = m.getName();
            if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                boolean bean = (name.startsWith("get") && name.length() > 3)
                        || (name.startsWith("is") && name.length() > 2
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class));
                if (bean) getters.putIfAbsent(PropertyNames.propertyNameOfAccessor(name), m);
                // A fluent accessor (name()) for a field of the same name, as records and many builders use
                else if (fields.containsKey(name)) getters.putIfAbsent(name, m);
            } else if (m.getParameterCount() == 1 && name.startsWith("set") && name.length() > 3) {
                setters.putIfAbsent(PropertyNames.decapitalize(name.substring(3)), m);
            }
        }

        var result = new LinkedHashMap<String, BeanProperty>();
        for (var entry : getters.entrySet()) {
            var name = entry.getKey();
            var getter = accessible(entry.getValue());
            var setter = setters.get(name);
            if (setter != null && !setter.getParameterTypes()[0].isAssignableFrom(getter.getReturnType())) setter = null;
            setter = accessible(setter);
            var field = accessible(fields.get(name));
            result.put(name, new BeanProperty(type, name, getter.getReturnType(), getter.getGenericReturnType(),
                    getter, setter, setter == null ? field : null));
        }
        // Public-less classes with plain fields: read and write the fields themselves
        for (var entry : fields.entrySet()) {
            if (result.containsKey(entry.getKey())) continue;
            var field = accessible(entry.getValue());
            if (field == null) continue;
            result.put(entry.getKey(), new BeanProperty(type, entry.getKey(), field.getType(), field.getGenericType(),
                    null, accessible(setters.get(entry.getKey())), field));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Field accessible(Field field) {
        if (field == null) return null;
        try {
            field.setAccessible(true);
            return field;
        } catch (RuntimeException e) {
            // Inaccessible module: the property is read and written through its methods only
            return null;
        }
    }

    // A public method of a non-public class (a nested DTO) cannot be invoked from here without it.
    private static Method accessible(Method method) {
        if (method == null) return null;
        try {
            method.setAccessible(true);
        } catch (RuntimeException e) {
            // Inaccessible module: left as is, the public ones still work
        }
        return method;
    }
}
