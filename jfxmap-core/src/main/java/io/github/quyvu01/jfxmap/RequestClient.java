package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.util.concurrent.CompletionStage;

/**
 * Sends a {@link MapRequest} to the service that owns a distributed key. This is what a transport implements
 * (NATS, gRPC, Kafka...); {@link io.github.quyvu01.jfxmap.provider.LocalRequestClient} answers in the same process.
 */
@FunctionalInterface
public interface RequestClient {
    CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context);
}
