package io.github.quyvu01.jfxmap.azure.servicebus;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceBusMessagesTest {
    @Test
    void readsTheApplicationPropertiesAsStringsAndBytesAsUtf8() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("authorization", "Bearer x");
        properties.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
                .getBytes(StandardCharsets.UTF_8));
        properties.put("count", 3);
        properties.put("missing", null);

        assertThat(ServiceBusMessages.headers(properties)).containsExactly(
                Map.entry("authorization", "Bearer x"),
                Map.entry("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
                Map.entry("count", "3"));
        assertThat(ServiceBusMessages.headers((Map<String, Object>) null)).isEmpty();
    }

    @Test
    void leavesTheTraceContextOutOfTheSentProperties() {
        assertThat(ServiceBusMessages.isSent("authorization")).isTrue();
        assertThat(ServiceBusMessages.isSent("traceparent")).isFalse();
        assertThat(ServiceBusMessages.isSent("tracestate")).isFalse();
    }
}
