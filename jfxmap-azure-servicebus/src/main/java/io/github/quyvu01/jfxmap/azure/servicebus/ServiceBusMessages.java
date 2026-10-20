package io.github.quyvu01.jfxmap.azure.servicebus;

import com.azure.messaging.servicebus.ServiceBusFailureReason;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the client and the server share: the headers of a message and the errors of a missing queue. */
final class ServiceBusMessages {
    private ServiceBusMessages() {
    }

    /**
     * The application properties of a message, as strings: what an FxMap server gives to the handler as headers.
     * FxMap sends its trace context as bytes, read as UTF-8.
     */
    static Map<String, String> headers(ServiceBusReceivedMessage message) {
        return headers(message.getApplicationProperties());
    }

    static Map<String, String> headers(Map<String, Object> properties) {
        var headers = new LinkedHashMap<String, String>();
        if (properties == null) return headers;
        properties.forEach((name, value) -> {
            if (value == null) return;
            headers.put(name, value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                    : value.toString());
        });
        return headers;
    }

    /**
     * A header as an application property: a string, except the W3C trace context ({@code traceparent},
     * {@code tracestate}), which FxMap sends and reads as UTF-8 bytes.
     */
    static Object property(String name, String value) {
        return "traceparent".equals(name) || "tracestate".equals(name) ? value.getBytes(StandardCharsets.UTF_8)
                : value;
    }

    /** Whether a failure says that a queue does not exist. */
    static boolean isMissingEntity(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause())
            if (current instanceof ServiceBusException e
                && e.getReason() == ServiceBusFailureReason.MESSAGING_ENTITY_NOT_FOUND) return true;
        return false;
    }
}
