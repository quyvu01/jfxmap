package io.github.quyvu01.jfxmap.spring;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;

import java.util.Objects;

/**
 * The builder of the Service Bus clients of jfxmap, from {@code jfxmap.servicebus.*}: the clients built from it share
 * one connection.
 */
public record JfxMapServiceBusClients(ServiceBusClientBuilder builder) {
    public JfxMapServiceBusClients {
        Objects.requireNonNull(builder, "builder");
    }
}
