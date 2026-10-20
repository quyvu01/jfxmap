package io.github.quyvu01.jfxmap.grpc;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;

import java.util.Optional;

/**
 * The names of distributed keys on the gRPC wire, as FxMap 5.1.1 writes them.
 *
 * <p>FxMap (.NET) turns a string key ({@code UseDistributedKey("UserOf")}) into a type generated at runtime, and
 * sends that type's assembly-qualified name in {@code distributed_key_assembly_type}:
 * {@code UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null}. jfxmap writes
 * the same string for every key (see {@link DotNetKeyNames}), so a Java service and a .NET service that use the same
 * string key find each other.
 */
public final class DistributedKeyWireNames {
    /** What follows the key name in the assembly-qualified name of a .NET string key. */
    public static final String DYNAMIC_ASSEMBLY = DotNetKeyNames.DYNAMIC_ASSEMBLY;

    private DistributedKeyWireNames() {
    }

    /** The name to send for a key. */
    public static String toWire(DistributedKeyRef key) {
        return DotNetKeyNames.assemblyQualifiedName(key);
    }

    /**
     * The key of a received name: the assembly-qualified name of a string key, or a bare key name. A name from
     * another .NET assembly (a key declared with a .NET type) gives nothing, since no Java key matches it.
     */
    public static Optional<DistributedKeyRef> fromWire(String wireName) {
        return DotNetKeyNames.fromAssemblyQualifiedName(wireName);
    }
}
