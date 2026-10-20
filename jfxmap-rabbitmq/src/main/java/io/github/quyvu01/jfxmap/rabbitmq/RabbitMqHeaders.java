package io.github.quyvu01.jfxmap.rabbitmq;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** The headers of a mapping call, as AMQP headers: strings, which RabbitMQ delivers as long strings (bytes). */
final class RabbitMqHeaders {
    private RabbitMqHeaders() {
    }

    static Map<String, Object> toAmqp(Map<String, String> headers) {
        var amqp = new LinkedHashMap<String, Object>();
        headers.forEach((name, value) -> {
            if (value != null) amqp.put(name, value);
        });
        return amqp;
    }

    static Map<String, String> fromAmqp(Map<String, Object> amqp) {
        var headers = new LinkedHashMap<String, String>();
        if (amqp == null) return headers;
        amqp.forEach((name, value) -> {
            if (value == null) return;
            headers.put(name, value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                    : value.toString());
        });
        return headers;
    }
}
