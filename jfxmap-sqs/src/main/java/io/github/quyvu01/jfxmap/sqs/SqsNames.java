package io.github.quyvu01.jfxmap.sqs;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;

import java.util.Locale;
import java.util.UUID;

/**
 * The names FxMap 5.1.1 gives on Amazon SQS: one request queue per key, {@code fxmap-<namespace>-<name>} with the dots
 * turned into dashes, in lower case ({@code fxmap--userof} for the string key {@code UserOf}), and a response queue
 * per client, {@code fxmap-response-<machine name>-<uuid>}.
 */
public final class SqsNames {
    public static final String RESPONSE_QUEUE_PREFIX = "fxmap-response";

    /** The message attribute that carries the correlation id of a request and of its reply. */
    public static final String CORRELATION_ID = "CorrelationId";
    /** The message attribute that carries the URL of the response queue of a request. */
    public static final String REPLY_TO = "ReplyTo";
    /** The message attribute that carries the assembly-qualified name of the key of a request. */
    public static final String TYPE = "Type";

    // SQS: up to 80 letters, digits, dashes and underscores
    private static final int MAX_QUEUE_NAME = 80;

    private SqsNames() {
    }

    /** The request queue of a key, which its owner creates and reads. */
    public static String queue(DistributedKeyRef key) {
        return ("FxMap-" + DotNetKeyNames.namespace(key) + "-" + DotNetKeyNames.name(key))
                .replace('.', '-').toLowerCase(Locale.ROOT);
    }

    /**
     * A new response queue for a client on a machine. The characters that SQS refuses in a name are dropped from the
     * machine name, which is shortened to keep the name within 80 characters.
     */
    public static String responseQueue(String machineName) {
        var machine = machineName == null ? "" : machineName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        var suffix = "-" + UUID.randomUUID();
        var room = MAX_QUEUE_NAME - RESPONSE_QUEUE_PREFIX.length() - 1 - suffix.length();
        if (machine.length() > room) machine = machine.substring(0, room);
        return RESPONSE_QUEUE_PREFIX + "-" + machine + suffix;
    }
}
