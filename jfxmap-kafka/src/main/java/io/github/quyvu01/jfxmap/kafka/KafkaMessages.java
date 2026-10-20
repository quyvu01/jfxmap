package io.github.quyvu01.jfxmap.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.wire.WireMessages;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The request of FxMap's Kafka transport wraps the request with the topic to reply on:
 * {@code {"Message":{"SelectorIds":[...],"Expressions":[...]},"ReplyTo":"fxmap-response-topic-..."}}. The headers of the
 * mapping call travel as Kafka headers, in UTF-8.
 */
final class KafkaMessages {
    private KafkaMessages() {
    }

    record Received(MapRequest request, String replyTo) {
    }

    static byte[] writeRequest(MapRequest request, String replyTo) {
        var node = WireMessages.requestNode(request);
        var wrapped = node.objectNode();
        wrapped.set("Message", node);
        wrapped.put("ReplyTo", replyTo);
        return WireMessages.write(wrapped);
    }

    /** @throws IllegalArgumentException when the bytes are not a wrapped request */
    static Received readRequest(byte[] json) {
        var node = WireMessages.read(json);
        if (!node.isObject()) throw new IllegalArgumentException("The request is not a JSON object.");
        String replyTo = null;
        JsonNode message = null;
        for (var field : node.properties()) {
            if (field.getKey().equalsIgnoreCase("ReplyTo") && !field.getValue().isNull())
                replyTo = field.getValue().asText();
            if (field.getKey().equalsIgnoreCase("Message")) message = field.getValue();
        }
        return new Received(WireMessages.readRequest(message), replyTo);
    }

    static Headers toKafka(Map<String, String> headers) {
        var kafka = new RecordHeaders();
        headers.forEach((name, value) -> kafka.add(name, (value == null ? "" : value).getBytes(UTF_8)));
        return kafka;
    }

    /** The headers as strings; the first value of a repeated name wins. */
    static Map<String, String> fromKafka(Headers kafka) {
        var headers = new LinkedHashMap<String, String>();
        if (kafka == null) return headers;
        for (var header : kafka)
            headers.putIfAbsent(header.key(), header.value() == null ? "" : new String(header.value(), UTF_8));
        return headers;
    }
}
