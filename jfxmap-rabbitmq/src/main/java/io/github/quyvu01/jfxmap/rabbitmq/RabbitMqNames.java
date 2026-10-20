package io.github.quyvu01.jfxmap.rabbitmq;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;

import java.util.Locale;
import java.util.Optional;

/**
 * The names FxMap 5.1.1 gives on RabbitMQ: one direct exchange per key, {@code FxMap-<namespace>:<name>}
 * ({@code FxMap-:UserOf} for the string key {@code UserOf}), bound with the routing key {@value #ROUTING_KEY} to the
 * queue of the owning service, {@code fxmap-rpc-queue-<application name in lower case>}.
 */
public final class RabbitMqNames {
    /** The routing key of every request, spelled as FxMap spells it. */
    public static final String ROUTING_KEY = "fxmap-rpc-rounting-key";
    public static final String QUEUE_PREFIX = "fxmap-rpc-queue";
    public static final String REPLY_QUEUE_PREFIX = "fxmap-reply";

    private static final String EXCHANGE_PREFIX = "FxMap-";

    private RabbitMqNames() {
    }

    public static String exchange(DistributedKeyRef key) {
        return EXCHANGE_PREFIX + DotNetKeyNames.namespace(key) + ":" + DotNetKeyNames.name(key);
    }

    /** The key of an exchange name, or empty when it is not one of FxMap. */
    public static Optional<DistributedKeyRef> keyOfExchange(String exchange) {
        if (exchange == null || !exchange.startsWith(EXCHANGE_PREFIX)) return Optional.empty();
        var rest = exchange.substring(EXCHANGE_PREFIX.length());
        var colon = rest.lastIndexOf(':');
        if (colon < 0 || colon == rest.length() - 1) return Optional.empty();
        var namespace = rest.substring(0, colon);
        var name = rest.substring(colon + 1);
        return Optional.of(new DistributedKeyRef(namespace.isEmpty() ? name : namespace + "." + name));
    }

    /** The queue of a service, from its name (FxMap uses the name of the entry assembly). */
    public static String queue(String applicationName) {
        if (applicationName == null || applicationName.isBlank())
            throw new IllegalArgumentException("The application name is blank: it names the queue of the service.");
        return QUEUE_PREFIX + "-" + applicationName.toLowerCase(Locale.ROOT);
    }
}
