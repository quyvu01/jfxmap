package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.grpc.FxMapGrpcService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

/**
 * Serves the {@link FxMapGrpcService} on {@code jfxmap.grpc.server.port} while the application runs (plaintext; put
 * TLS in front of it, or serve the service bean with your own gRPC server instead).
 */
public final class JfxMapGrpcServer implements SmartLifecycle {
    private final FxMapGrpcService service;
    private final int port;
    private volatile Server server;

    public JfxMapGrpcServer(FxMapGrpcService service, int port) {
        this.service = service;
        this.port = port;
    }

    @Override
    public synchronized void start() {
        if (server != null) return;
        try {
            server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create()).addService(service).build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("The FxMap gRPC server cannot listen on port " + port, e);
        }
    }

    @Override
    public synchronized void stop() {
        if (server == null) return;
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) server.shutdownNow();
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
        server = null;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    /** The port the server listens on (the chosen one when the property is 0), or -1 when it is not running. */
    public int getPort() {
        var running = server;
        return running == null ? -1 : running.getPort();
    }
}
