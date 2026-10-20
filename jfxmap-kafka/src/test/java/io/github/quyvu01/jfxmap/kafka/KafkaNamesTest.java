package io.github.quyvu01.jfxmap.kafka;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaNamesTest {
    @Test
    void theRequestTopicIsTheFullNameOfTheKeyInLowerCase() {
        assertThat(KafkaNames.requestTopic(DistributedKeyRef.of("UserOf"))).isEqualTo("fxmap-request-topic-userof");
        assertThat(KafkaNames.requestTopic(DistributedKeyRef.of("Shop.Keys.UserOf")))
                .isEqualTo("fxmap-request-topic-shop.keys.userof");
    }

    @Test
    void eachResponseTopicIsNew() {
        var first = KafkaNames.newResponseTopic("OrderService");

        assertThat(first).matches("fxmap-response-topic-orderservice-[0-9a-f]{32}");
        assertThat(KafkaNames.newResponseTopic("OrderService")).isNotEqualTo(first);
        assertThatThrownBy(() -> KafkaNames.newResponseTopic(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaNames.newResponseTopic(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theServersShareFxMapsGroup() {
        assertThat(KafkaNames.SERVER_GROUP_ID).isEqualTo("fxmap-rpc-server-group");
    }
}
