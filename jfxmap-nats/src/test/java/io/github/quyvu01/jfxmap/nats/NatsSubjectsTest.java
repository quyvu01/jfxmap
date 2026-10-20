package io.github.quyvu01.jfxmap.nats;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NatsSubjectsTest {
    @Test
    void aStringKeyHasAnEmptyNamespace() {
        assertThat(NatsSubjects.of(DistributedKeyRef.of("UserOf"), null)).isEqualTo("fxmap--userof");
        assertThat(NatsSubjects.of(DistributedKeyRef.of("UserOf"), "")).isEqualTo("fxmap--userof");
    }

    @Test
    void aDottedKeyIsSplitIntoNamespaceAndName() {
        assertThat(NatsSubjects.of(DistributedKeyRef.of("Shop.Keys.UserOf"), null)).isEqualTo("fxmap-shop.keys-userof");
    }

    @Test
    void thePrefixComesFirstAndIsLowerCasedToo() {
        assertThat(NatsSubjects.of(DistributedKeyRef.of("UserOf"), "Prod")).isEqualTo("prod-fxmap--userof");
    }
}
