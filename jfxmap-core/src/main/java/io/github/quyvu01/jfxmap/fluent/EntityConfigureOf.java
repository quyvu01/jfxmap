package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.PropertyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.internal.PropertyNames;
import io.github.quyvu01.jfxmap.internal.TypeTokens;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Declares how an entity is exposed to the mapping engine (provider side).
 *
 * <pre>{@code
 * public class UserConfig extends EntityConfigureOf<User> {
 *     @Override
 *     protected void configure() {
 *         id(User::getId);                        // required
 *         defaultProperty(User::getName);         // returned when a rule has no expression
 *         useDistributedKey(UserOf.class);        // required
 *         exposedName(User::getEmail, "UserEmail");
 *     }
 * }
 * }</pre>
 *
 * @param <T> the entity type
 */
public abstract class EntityConfigureOf<T> {
    private final Class<T> entityType;
    private boolean configuring;
    private boolean configured;
    private String idProperty;
    private String defaultProperty;
    private DistributedKeyRef key;
    private final Map<String, String> exposedNames = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    protected EntityConfigureOf() {
        this.entityType = (Class<T>) TypeTokens.typeArgument(getClass(), EntityConfigureOf.class);
    }

    /** For a configuration whose entity type cannot be read from the class declaration. */
    protected EntityConfigureOf(Class<T> entityType) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
    }

    /** Declare the id, the distributed key, and optionally the default property and the exposed names. */
    protected abstract void configure();

    /** The property that identifies the entity. Required, once. */
    protected final void id(PropertyRef<T, ?> selector) {
        checkConfiguring();
        idProperty = selectorName(selector, "id", idProperty);
    }

    /** The property returned when a rule has no expression. */
    protected final void defaultProperty(PropertyRef<T, ?> selector) {
        checkConfiguring();
        defaultProperty = selectorName(selector, "defaultProperty", defaultProperty);
    }

    /** Exposes a property under another name, which expressions may use. */
    protected final void exposedName(PropertyRef<T, ?> selector, String exposedName) {
        checkConfiguring();
        Objects.requireNonNull(exposedName, "exposedName");
        var property = selectorName(selector, "exposedName", null);
        if (exposedNames.putIfAbsent(exposedName, property) != null)
            throw new DistributedMapException.DuplicatedNameByExposedName(entityType, exposedName);
    }

    /** Links the entity to a shared key type. */
    protected final void useDistributedKey(Class<? extends DistributedKey> keyType) {
        setKey(DistributedKeyRef.of(keyType));
    }

    /** Links the entity to a string key, when the services share no key type. */
    protected final void useDistributedKey(String key) {
        setKey(DistributedKeyRef.of(key));
    }

    public final Class<T> entityType() {
        return entityType;
    }

    public final String idProperty() {
        ensureConfigured();
        return idProperty;
    }

    /** The default property, or {@code null} when none is declared. */
    public final String defaultProperty() {
        ensureConfigured();
        return defaultProperty;
    }

    public final DistributedKeyRef key() {
        ensureConfigured();
        return key;
    }

    /** The exposed names, each mapped to the property it stands for. */
    public final Map<String, String> exposedNames() {
        ensureConfigured();
        return Collections.unmodifiableMap(exposedNames);
    }

    private synchronized void ensureConfigured() {
        if (configured) return;
        configuring = true;
        try {
            configure();
        } finally {
            configuring = false;
        }
        if (idProperty == null) throw new DistributedMapException.EntityIdNotConfigured(entityType);
        if (key == null) throw new DistributedMapException.InvalidDistributedKeyConfiguration(getClass());
        configured = true;
    }

    private void setKey(DistributedKeyRef newKey) {
        checkConfiguring();
        if (key != null) throw new DistributedMapException.InvalidDistributedKeyConfiguration(getClass());
        key = newKey;
    }

    private String selectorName(PropertyRef<T, ?> selector, String name, String alreadyDeclared) {
        Objects.requireNonNull(selector, name);
        if (alreadyDeclared != null)
            throw new DistributedMapException.InvalidEntitySelector(entityType, name, "it is declared more than once.");
        return PropertyNames.of(selector).orElseThrow(() -> new DistributedMapException.InvalidEntitySelector(
                entityType, name, "use a method reference such as User::getId, not a lambda."));
    }

    private void checkConfiguring() {
        if (!configuring) throw new IllegalStateException("The entity can only be configured inside configure().");
    }
}
