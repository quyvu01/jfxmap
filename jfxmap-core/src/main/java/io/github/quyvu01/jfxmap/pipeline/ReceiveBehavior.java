package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.response.ItemsResponse;

import java.util.concurrent.CompletionStage;

/**
 * A step around each request a {@link io.github.quyvu01.jfxmap.provider.QueryHandler} answers, on the owner's side
 * (FxMap's {@code IReceivedPipelineBehavior}): check the headers, log, measure, or answer without the handler by not
 * calling {@code next}. {@link Pipelines#receive(io.github.quyvu01.jfxmap.provider.QueryHandler, java.util.List)}
 * puts them around a handler, in their order.
 */
@FunctionalInterface
public interface ReceiveBehavior {
    CompletionStage<ItemsResponse> receive(RequestContext request, Next next);
}
