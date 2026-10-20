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
     * Whether a header can go as an application property. The W3C trace context ({@code traceparent},
     * {@code tracestate}) does not: FxMap 5.1.1 sends and reads it as bytes, which Service Bus refuses ("unsupported
     * type System.Byte[]", for its .NET client as well), and a string would make an FxMap server fail on its cast to
     * bytes. A trace stops at Service Bus, as it does between two FxMap services.
     */
    static boolean isSent(String header) {
        return !"traceparent".equals(header) && !"tracestate".equals(header);
    }

    /** Whether a failure says that a queue does not exist. */
    static boolean isMissingEntity(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause())
            if (current instanceof ServiceBusException e
                && e.getReason() == ServiceBusFailureReason.MESSAGING_ENTITY_NOT_FOUND) return true;
        return false;
    }
}
