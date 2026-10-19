package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The identity of a distributed key, whether it was declared with a {@link DistributedKey} type or with a string.
 *
 * <p>A typed key is identified by its fully qualified class name, a string key by the string itself. Two rules that
 * use the same key are batched into the same request.
 *
 * @param name the name that identifies the key
 */
public record DistributedKeyRef(String name) {
    private static final Pattern VALID_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    public DistributedKeyRef {
        Objects.requireNonNull(name, "name");
    }

    /** The key of a {@link DistributedKey} type. */
    public static DistributedKeyRef of(Class<? extends DistributedKey> keyType) {
        Objects.requireNonNull(keyType, "keyType");
        return new DistributedKeyRef(keyType.getName());
    }

    /**
     * A string key, for services that do not share a key type.
     *
     * @throws DistributedMapException.DistributedKeyNullOrEmpty when the key is null or blank
     * @throws DistributedMapException.InvalidDistributedKeyName when the key is not a (dotted) identifier
     */
    public static DistributedKeyRef of(String key) {
        if (key == null || key.isBlank()) throw new DistributedMapException.DistributedKeyNullOrEmpty();
        if (!VALID_NAME.matcher(key).matches()) throw new DistributedMapException.InvalidDistributedKeyName(key);
        return new DistributedKeyRef(key);
    }

    @Override
    public String toString() {
        return name;
    }
}
