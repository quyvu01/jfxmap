package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.response.ItemsResponse;

import java.util.concurrent.CompletionStage;

/** The rest of a pipeline: the next behavior, or at the end the transport or the handler. */
@FunctionalInterface
public interface Next {
    CompletionStage<ItemsResponse> proceed(RequestContext request);
}
