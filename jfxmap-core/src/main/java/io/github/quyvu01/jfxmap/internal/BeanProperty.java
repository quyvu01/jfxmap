package io.github.quyvu01.jfxmap.internal;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * A readable property of a class, read through its getter (or record accessor, or field) and written through its
 * setter (or field). Not part of the public API.
 */
public final class BeanProperty {
    private final Class<?> owner;
    private final String name;
    private final Class<?> type;
    private final Type genericType;
    private final Method getter;
    private final Method setter;
    private final Field field;

    BeanProperty(Class<?> owner, String name, Class<?> type, Type genericType, Method getter, Method setter,
                 Field field) {
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.genericType = genericType;
        this.getter = getter;
        this.setter = setter;
        this.field = field;
    }

    public Class<?> owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public Class<?> type() {
        return type;
    }

    public Type genericType() {
        return genericType;
    }

    public boolean isWritable() {
        return setter != null || (field != null && !java.lang.reflect.Modifier.isFinal(field.getModifiers()));
    }

    public Object get(Object target) {
        try {
            return getter != null ? getter.invoke(target) : field.get(target);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + owner.getName() + "." + name, e);
        }
    }

    public void set(Object target, Object value) {
        try {
            if (setter != null) setter.invoke(target, value);
            else field.set(target, value);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write " + owner.getName() + "." + name, e);
        }
    }

    private static RuntimeException sneaky(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }

    @Override
    public String toString() {
        return owner.getSimpleName() + "." + name;
    }
}
