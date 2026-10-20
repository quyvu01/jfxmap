package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Puts behaviors around a transport or a handler. The first behavior of the list runs first; a behavior that throws
 * instead of returning a stage fails the request as if its stage had failed.
 *
 * <pre>{@code
 * RequestClient client = Pipelines.send(natsClient, List.of(tenantHeader, timing));
 * QueryHandler users = Pipelines.receive(new JpaQueryHandler<>(new UserConfig(), emf), List.of(checkTenant));
 * }</pre>
 */
public final class Pipelines {
    private Pipelines() {
    }

    /** The client with the behaviors around it; the client itself when there is none. */
    public static RequestClient send(RequestClient client, List<? extends SendBehavior> behaviors) {
        Objects.requireNonNull(client, "client");
        var steps = List.copyOf(Objects.requireNonNull(behaviors, "behaviors"));
        if (steps.isEmpty()) return client;
        return new RequestClient() {
            @Override
            public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request,
                                                          MapContext context) {
                return run(new RequestContext(key, request, context), 0);
            }

            private CompletionStage<ItemsResponse> run(RequestContext request, int index) {
                if (index == steps.size())
                    return call(() -> client.request(request.key(), request.request(), request.context()));
                return call(() -> steps.get(index).send(request, next -> run(next, index + 1)));
            }

            @Override
            public String transport() {
                return client.transport();
            }
        };
    }

    /** The handler with the behaviors around it; the handler itself when there is none. */
    public static QueryHandler receive(QueryHandler handler, List<? extends ReceiveBehavior> behaviors) {
        Objects.requireNonNull(handler, "handler");
        var steps = List.copyOf(Objects.requireNonNull(behaviors, "behaviors"));
        if (steps.isEmpty()) return handler;
        return new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return handler.key();
            }

            @Override
            public Class<?> entityType() {
                return handler.entityType();
            }

            @Override
            public String dbSystem() {
                return handler.dbSystem();
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                return run(new RequestContext(handler.key(), request, context), 0);
            }

            private CompletionStage<ItemsResponse> run(RequestContext request, int index) {
                if (index == steps.size()) return call(() -> handler.handle(request.request(), request.context()));
                return call(() -> steps.get(index).receive(request, next -> run(next, index + 1)));
            }
        };
    }

    /** The handlers, each with the behaviors around it. */
    public static List<QueryHandler> receive(List<? extends QueryHandler> handlers,
                                             List<? extends ReceiveBehavior> behaviors) {
        return handlers.stream().map(handler -> receive(handler, behaviors)).toList();
    }

    private interface Step {
        CompletionStage<ItemsResponse> run();
    }

    private static CompletionStage<ItemsResponse> call(Step step) {
        try {
            var stage = step.run();
            return stage != null ? stage
                    : CompletableFuture.failedFuture(new NullPointerException("A pipeline step returned no stage."));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
