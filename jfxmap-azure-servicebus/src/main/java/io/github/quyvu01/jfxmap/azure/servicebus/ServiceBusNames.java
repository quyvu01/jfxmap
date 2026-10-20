package io.github.quyvu01.jfxmap.azure.servicebus;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;

import java.util.Locale;

/**
 * The names FxMap 5.1.1 gives on Azure Service Bus: two queues per key, both requiring sessions, named
 * {@code fxmap-request-<namespace>-<name>} and {@code fxmap-reply-<namespace>-<name>} with the dots turned into
 * dashes, in lower case ({@code fxmap-request--userof} for the string key {@code UserOf}), after an optional prefix
 * ({@code <prefix>-fxmap-request-...}).
 */
public final class ServiceBusNames {
    private ServiceBusNames() {
    }

    /** The queue the owner of a key reads its requests from. */
    public static String requestQueue(String prefix, DistributedKeyRef key) {
        return name(prefix, "fxmap-request", key);
    }

    /** The queue the clients of a key read their replies from, each in its own session. */
    public static String replyQueue(String prefix, DistributedKeyRef key) {
        return name(prefix, "fxmap-reply", key);
    }

    private static String name(String prefix, String kind, DistributedKeyRef key) {
        var name = kind + "-" + DotNetKeyNames.namespace(key) + "-" + DotNetKeyNames.name(key);
        if (prefix != null && !prefix.isEmpty()) name = prefix + "-" + name;
        return name.replace('.', '-').toLowerCase(Locale.ROOT);
    }
}
