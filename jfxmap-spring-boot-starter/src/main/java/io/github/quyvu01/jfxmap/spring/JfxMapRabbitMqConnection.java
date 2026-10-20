package io.github.quyvu01.jfxmap.spring;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeoutException;

/**
 * The RabbitMQ connection of jfxmap, from {@code jfxmap.rabbitmq.*}, with automatic recovery (the reply queue and the
 * consumers come back after a network failure). It is not a {@code Connection} bean, so it does not clash with the
 * application's own RabbitMQ setup.
 */
public final class JfxMapRabbitMqConnection implements AutoCloseable {
    private final Connection connection;

    JfxMapRabbitMqConnection(Connection connection) {
        this.connection = connection;
    }

    static JfxMapRabbitMqConnection open(JfxMapProperties.RabbitMq properties) {
        var factory = new ConnectionFactory();
        factory.setHost(properties.getHost());
        factory.setPort(properties.getPort());
        factory.setVirtualHost(properties.getVirtualHost());
        factory.setUsername(properties.getUsername());
        factory.setPassword(properties.getPassword());
        factory.setAutomaticRecoveryEnabled(true);
        try {
            if (properties.isSsl()) factory.useSslProtocol();
            return new JfxMapRabbitMqConnection(factory.newConnection("jfxmap"));
        } catch (IOException e) {
            throw new UncheckedIOException("jfxmap cannot connect to RabbitMQ at " + properties.getHost() + ":"
                                           + properties.getPort(), e);
        } catch (TimeoutException | GeneralSecurityException e) {
            throw new IllegalStateException("jfxmap cannot connect to RabbitMQ at " + properties.getHost() + ":"
                                            + properties.getPort(), e);
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws IOException {
        if (connection.isOpen()) connection.close();
    }
}
