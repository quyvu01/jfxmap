package io.github.quyvu01.jfxmap.kafka;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;

import java.util.Locale;
import java.util.UUID;

/**
 * The names FxMap 5.1.1 gives on Kafka: the request topic of a key, {@code fxmap-request-topic-<full name>} in lower
 * case ({@code fxmap-request-topic-userof} for the string key {@code UserOf}), read by the consumer group
 * {@value #SERVER_GROUP_ID}; and the reply topic of each client, {@code fxmap-response-topic-<application>-<uuid>}.
 */
public final class KafkaNames {
    public static final String REQUEST_TOPIC_PREFIX = "fxmap-request-topic";
    public static final String RESPONSE_TOPIC_PREFIX = "fxmap-response-topic";
    public static final String SERVER_GROUP_ID = "fxmap-rpc-server-group";

    private KafkaNames() {
    }

    public static String requestTopic(DistributedKeyRef key) {
        return (REQUEST_TOPIC_PREFIX + "-" + DotNetKeyNames.fullName(key)).toLowerCase(Locale.ROOT);
    }

    /** A new reply topic for a client of the application (FxMap uses the entry assembly's name). */
    public static String newResponseTopic(String applicationName) {
        if (applicationName == null || applicationName.isBlank())
            throw new IllegalArgumentException("The application name is blank: it names the reply topic.");
        return RESPONSE_TOPIC_PREFIX + "-" + applicationName.toLowerCase(Locale.ROOT) + "-"
               + UUID.randomUUID().toString().replace("-", "");
    }
}
