package io.github.quyvu01.jfxmap.sqs;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqsNamesTest {
    @Test
    void aStringKeyWithoutNamespaceHasAnEmptySegment() {
        assertThat(SqsNames.queue(DistributedKeyRef.of("UserOf"))).isEqualTo("fxmap--userof");
    }

    @Test
    void theDotsOfTheNamespaceBecomeDashes() {
        assertThat(SqsNames.queue(DistributedKeyRef.of("Shop.Keys.UserOf"))).isEqualTo("fxmap-shop-keys-userof");
    }

    @Test
    void aResponseQueueIsNamedAfterTheMachineAndIsNewEachTime() {
        var name = SqsNames.responseQueue("Build-01");

        assertThat(name).matches("fxmap-response-build-01-[0-9a-f-]{36}");
        assertThat(SqsNames.responseQueue("Build-01")).isNotEqualTo(name);
    }

    @Test
    void aResponseQueueDropsWhatSqsRefusesAndStaysWithin80Characters() {
        assertThat(SqsNames.responseQueue("host.example.com")).startsWith("fxmap-response-hostexamplecom-");
        assertThat(SqsNames.responseQueue("x".repeat(200))).hasSize(80);
        assertThat(SqsNames.responseQueue(null)).matches("fxmap-response--[0-9a-f-]{36}");
    }
}
