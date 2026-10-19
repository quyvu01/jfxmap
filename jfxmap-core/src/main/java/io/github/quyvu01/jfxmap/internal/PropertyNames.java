package io.github.quyvu01.jfxmap.internal;

import io.github.quyvu01.jfxmap.PropertyRef;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Optional;

/** Reads the name of the property behind a {@link PropertyRef} method reference. Not part of the public API. */
public final class PropertyNames {
    private PropertyNames() {
    }

    /**
     * The property name of a getter reference: {@code getUserName} and {@code isActive} give {@code userName} and
     * {@code active}, a record accessor {@code userName} gives itself. Empty for a lambda, which has no name.
     */
    public static Optional<String> of(PropertyRef<?, ?> ref) {
        var lambda = serialized(ref);
        var method = lambda.getImplMethodName();
        if (method.startsWith("lambda$")) return Optional.empty();
        return Optional.of(propertyNameOfAccessor(method));
    }

    static String propertyNameOfAccessor(String method) {
        if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3)))
            return decapitalize(method.substring(3));
        if (method.length() > 2 && method.startsWith("is") && Character.isUpperCase(method.charAt(2)))
            return decapitalize(method.substring(2));
        return method;
    }

    // Same rule as java.beans.Introspector.decapitalize: "URL" stays "URL", "Name" becomes "name".
    static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0)))
            return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static SerializedLambda serialized(PropertyRef<?, ?> ref) {
        try {
            Method writeReplace = ref.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            return (SerializedLambda) writeReplace.invoke(ref);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("A property reference must be a method reference such as Dto::getName.", e);
        }
    }
}
