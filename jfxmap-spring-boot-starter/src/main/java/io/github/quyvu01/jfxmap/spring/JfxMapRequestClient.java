package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Answers the keys this application owns in process, and sends the others to the remote client (a transport), so a
 * service can be the owner of some keys and the consumer of others.
 */
public final class JfxMapRequestClient implements RequestClient {
    private final JfxMapQueryHandlers localHandlers;
    private final RequestClient remote;

    /**
     * @param localHandlers the keys this application owns
     * @param remote        the transport for the other keys, or {@code null} when there is none
     */
    public JfxMapRequestClient(JfxMapQueryHandlers localHandlers, RequestClient remote) {
        this.localHandlers = Objects.requireNonNull(localHandlers, "localHandlers");
        this.remote = remote;
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        var handler = localHandlers.get(key);
        if (handler != null) return handler.handle(request, context);
        if (remote != null) return remote.request(key, request, context);
        return CompletableFuture.failedFuture(new DistributedMapException.NoHandlerForDistributedKey(key));
    }
}
