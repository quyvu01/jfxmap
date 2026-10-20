package io.github.quyvu01.jfxmap.wire;

import io.github.quyvu01.jfxmap.DistributedKeyRef;

import java.util.Objects;
import java.util.Optional;

/**
 * The names that FxMap 5.1.1 (.NET) derives from a distributed key, which the transports put on the wire (topics,
 * subjects, exchanges, the type of a message).
 *
 * <p>FxMap turns a string key ({@code UseDistributedKey("UserOf")}) into a type generated at runtime in the assembly
 * {@code FxMap.DynamicDistributedKeys}, named after the key: its namespace is what comes before the last dot
 * ({@code ""} for {@code UserOf}) and its name what follows it. jfxmap reads every key the same way, so a Java service
 * and a .NET service that use the same string key find each other. A key declared with a Java class is named after
 * the class, which only another jfxmap service knows.
 */
public final class DotNetKeyNames {
    /** What follows the key name in the assembly-qualified name of a .NET string key. */
    public static final String DYNAMIC_ASSEMBLY =
            "FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null";

    private static final String SUFFIX = ", " + DYNAMIC_ASSEMBLY;

    private DotNetKeyNames() {
    }

    /** The full name of the key's type ({@code Type.FullName}): the key itself. */
    public static String fullName(DistributedKeyRef key) {
        return Objects.requireNonNull(key, "key").name();
    }

    /** The namespace of the key's type ({@code Type.Namespace}), or {@code ""} when it has none. */
    public static String namespace(DistributedKeyRef key) {
        var name = fullName(key);
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    /** The simple name of the key's type ({@code Type.Name}). */
    public static String name(DistributedKeyRef key) {
        var name = fullName(key);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * The assembly-qualified name of the key's type ({@code Type.AssemblyQualifiedName}):
     * {@code UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null}.
     */
    public static String assemblyQualifiedName(DistributedKeyRef key) {
        return fullName(key) + SUFFIX;
    }

    /**
     * The key of a received assembly-qualified name, or of a bare key name. A name from another .NET assembly (a key
     * declared with a .NET type) gives nothing, since no Java key matches it.
     */
    public static Optional<DistributedKeyRef> fromAssemblyQualifiedName(String assemblyQualifiedName) {
        if (assemblyQualifiedName == null) return Optional.empty();
        var name = assemblyQualifiedName.trim();
        if (name.endsWith(SUFFIX)) name = name.substring(0, name.length() - SUFFIX.length()).trim();
        if (name.isEmpty() || name.contains(",")) return Optional.empty();
        return Optional.of(new DistributedKeyRef(name));
    }
}
