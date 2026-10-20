package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.response.ItemsResponse;

import java.util.concurrent.CompletionStage;

/**
 * A step around each request the mapper sends (FxMap's {@code ISendPipelineBehavior}): add headers, log, measure,
 * cache, or answer without sending by not calling {@code next}. Registered with
 * {@link io.github.quyvu01.jfxmap.FxMap.Builder#addSendBehaviors(SendBehavior...)}, the behaviors run in their
 * order of registration, inside the retries of the {@link RetryPolicy}: each attempt goes through them.
 *
 * <pre>{@code
 * SendBehavior tenant = (request, next) -> next.proceed(request.withHeader("x-tenant", currentTenant()));
 * }</pre>
 */
@FunctionalInterface
public interface SendBehavior {
    CompletionStage<ItemsResponse> send(RequestContext request, Next next);
}
