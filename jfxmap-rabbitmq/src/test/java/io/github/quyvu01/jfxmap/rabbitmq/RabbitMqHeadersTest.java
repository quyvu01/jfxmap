package io.github.quyvu01.jfxmap.rabbitmq;

import com.rabbitmq.client.LongString;
import com.rabbitmq.client.impl.LongStringHelper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqHeadersTest {
    @Test
    void sendsTheHeadersAsStringsWithoutNulls() {
        var headers = new HashMap<String, String>();
        headers.put("tenant", "a");
        headers.put("empty", null);

        assertThat(RabbitMqHeaders.toAmqp(headers)).containsExactly(Map.entry("tenant", "a"));
    }

    @Test
    void readsLongStringsBytesAndOtherValues() {
        var amqp = new HashMap<String, Object>();
        LongString longString = LongStringHelper.asLongString("a");
        amqp.put("long", longString);
        amqp.put("bytes", "b".getBytes(UTF_8));
        amqp.put("number", 7);
        amqp.put("none", null);

        assertThat(RabbitMqHeaders.fromAmqp(amqp)).containsOnly(Map.entry("long", "a"), Map.entry("bytes", "b"),
                Map.entry("number", "7"));
        assertThat(RabbitMqHeaders.fromAmqp(null)).isEmpty();
    }
}
