package io.github.quyvu01.jfxmap.nats;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;

import java.util.Locale;

/**
 * The subject of a distributed key, as FxMap 5.1.1 names it: {@code fxmap-<namespace>-<name>} in lower case, after
 * {@code <prefix>-} when a topic prefix is set. The string key {@code UserOf} has no namespace: its subject is
 * {@code fxmap--userof}.
 */
public final class NatsSubjects {
    private NatsSubjects() {
    }

    /** @param topicPrefix the prefix of every subject, or {@code null} or empty for none */
    public static String of(DistributedKeyRef key, String topicPrefix) {
        var subject = "fxmap-" + DotNetKeyNames.namespace(key) + "-" + DotNetKeyNames.name(key);
        if (topicPrefix != null && !topicPrefix.isEmpty()) subject = topicPrefix + "-" + subject;
        return subject.toLowerCase(Locale.ROOT);
    }
}
