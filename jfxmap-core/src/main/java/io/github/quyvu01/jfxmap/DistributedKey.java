package io.github.quyvu01.jfxmap;

/**
 * Marker for a type that identifies which remote service owns an entity type.
 *
 * <p>Declare one final class per entity type, in a contracts module shared by the provider and the consumer:
 *
 * <pre>{@code
 * public final class UserOf implements DistributedKey {}
 * }</pre>
 *
 * When the services cannot share a binary, use a string key instead
 * ({@code useDistributedKey("UserOf")}); see {@link DistributedKeyRef}.
 */
public interface DistributedKey {
}
