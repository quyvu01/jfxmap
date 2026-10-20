package io.github.quyvu01.jfxmap.kafka;

import io.github.quyvu01.jfxmap.response.MapRequest;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaMessagesTest {
    @Test
    void wrapsTheRequestWithItsReplyTopic() {
        var json = new String(KafkaMessages.writeRequest(new MapRequest(List.of("1"), Arrays.asList(null, "Email")),
                "fxmap-response-topic-a-1"), UTF_8);

        assertThat(json).isEqualTo("{\"Message\":{\"SelectorIds\":[\"1\"],\"Expressions\":[null,\"Email\"]},"
                                   + "\"ReplyTo\":\"fxmap-response-topic-a-1\"}");
    }

    @Test
    void readsAWrappedRequestWhateverTheCase() {
        var received = KafkaMessages.readRequest(
                "{\"message\":{\"SelectorIds\":[\"2\"],\"Expressions\":[\"Name\"]},\"replyTo\":\"r\"}".getBytes(UTF_8));

        assertThat(received.request().selectorIds()).containsExactly("2");
        assertThat(received.request().expressions()).containsExactly("Name");
        assertThat(received.replyTo()).isEqualTo("r");
    }

    @Test
    void aRequestWithoutMessageOrReplyToIsReadAsSuch() {
        assertThatThrownBy(() -> KafkaMessages.readRequest("{\"ReplyTo\":\"r\"}".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(KafkaMessages.readRequest("{\"Message\":{},\"ReplyTo\":null}".getBytes(UTF_8)).replyTo()).isNull();
        assertThatThrownBy(() -> KafkaMessages.readRequest("[]".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHeadersTravelInUtf8() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("tenant", "é");
        headers.put("empty", null);

        var kafka = KafkaMessages.toKafka(headers);

        assertThat(new String(kafka.lastHeader("tenant").value(), UTF_8)).isEqualTo("é");
        assertThat(KafkaMessages.fromKafka(kafka)).containsExactly(Map.entry("tenant", "é"), Map.entry("empty", ""));
    }

    @Test
    void theFirstValueOfARepeatedHeaderWins() {
        var kafka = new RecordHeaders().add("a", "1".getBytes(UTF_8)).add("a", "2".getBytes(UTF_8)).add("b", null);

        assertThat(KafkaMessages.fromKafka(kafka)).containsExactly(Map.entry("a", "1"), Map.entry("b", ""));
        assertThat(KafkaMessages.fromKafka(null)).isEmpty();
    }
}
