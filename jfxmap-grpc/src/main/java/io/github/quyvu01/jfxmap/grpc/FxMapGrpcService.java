package io.github.quyvu01.jfxmap.grpc;

import com.google.protobuf.StringValue;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.grpc.proto.DistributedKeyTypeResponse;
import io.github.quyvu01.jfxmap.grpc.proto.ExpressionErrorGrpc;
import io.github.quyvu01.jfxmap.grpc.proto.FxMapItemsGrpcResponse;
import io.github.quyvu01.jfxmap.grpc.proto.FxMapTransportServiceGrpc;
import io.github.quyvu01.jfxmap.grpc.proto.FxMapValueItemGrpc;
import io.github.quyvu01.jfxmap.grpc.proto.GetDistributedKeysQuery;
import io.github.quyvu01.jfxmap.grpc.proto.GetFxMapGrpcQuery;
import io.github.quyvu01.jfxmap.grpc.proto.ItemGrpc;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.grpc.BindableService;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * The gRPC server side (FxMap's GrpcServer): answers {@code GetItems} with the {@link QueryHandler} of the requested
 * key, and {@code GetDistributedKeys} with the keys it handles, so that clients find which host owns a key.
 *
 * <p>Add it to any gRPC server; {@link #bindService()} already carries the interceptor that passes the request
 * metadata to the handlers as headers:
 *
 * <pre>{@code
 * Server server = Grpc.newServerBuilderForPort(9090, InsecureServerCredentials.create())
 *         .addService(new FxMapGrpcService(List.of(new JpaQueryHandler<>(new UserConfig(), emf))))
 *         .build()
 *         .start();
 * }</pre>
 */
public final class FxMapGrpcService implements FxMapTransportServiceGrpc.AsyncService, BindableService {
    private static final System.Logger LOGGER = System.getLogger(FxMapGrpcService.class.getName());

    private final Map<DistributedKeyRef, QueryHandler> handlers;

    /**
     * @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when two handlers share a key
     */
    public FxMapGrpcService(Collection<? extends QueryHandler> handlers) {
        var byKey = new LinkedHashMap<DistributedKeyRef, QueryHandler>();
        for (var handler : handlers) {
            var existing = byKey.putIfAbsent(handler.key(), handler);
            if (existing != null)
                throw new DistributedMapException.DistributedKeyAssignedToMultipleEntities(handler.key(),
                        List.of(existing.entityType(), handler.entityType()));
        }
        this.handlers = Map.copyOf(byKey);
    }

    /** The keys this service answers for. */
    public Collection<DistributedKeyRef> keys() {
        return handlers.keySet();
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerInterceptors.intercept(FxMapTransportServiceGrpc.bindService(this), new GrpcHeaders.Interceptor());
    }

    @Override
    public void getDistributedKeys(GetDistributedKeysQuery request,
                                   StreamObserver<DistributedKeyTypeResponse> responseObserver) {
        var response = DistributedKeyTypeResponse.newBuilder();
        handlers.keySet().stream().map(DistributedKeyWireNames::toWire).sorted()
                .forEach(response::addDistributedKeyTypes);
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getItems(GetFxMapGrpcQuery request, StreamObserver<FxMapItemsGrpcResponse> responseObserver) {
        var wireName = request.hasDistributedKeyAssemblyType() ? request.getDistributedKeyAssemblyType().getValue() : null;
        var handler = DistributedKeyWireNames.fromWire(wireName).map(handlers::get).orElse(null);
        if (handler == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("The FxMap distributed key seems not a part of this application: " + wireName)
                    .asRuntimeException());
            return;
        }
        MapRequest mapRequest;
        try {
            mapRequest = new MapRequest(request.getSelectorIdsList(),
                    ExpressionsJson.read(request.hasExpression() ? request.getExpression().getValue() : null));
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        var headers = GrpcHeaders.HEADERS.get();
        var context = new MapContext(headers == null ? Map.of() : headers, null);
        try {
            handler.handle(mapRequest, context).whenComplete((response, failure) -> {
                if (failure == null) {
                    responseObserver.onNext(toGrpc(response));
                    responseObserver.onCompleted();
                } else {
                    fail(responseObserver, handler.key(), failure);
                }
            });
        } catch (RuntimeException e) {
            fail(responseObserver, handler.key(), e);
        }
    }

    private static void fail(StreamObserver<?> responseObserver, DistributedKeyRef key, Throwable failure) {
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        LOGGER.log(System.Logger.Level.ERROR, "The request for the distributed key " + key + " failed.", cause);
        responseObserver.onError(Status.INTERNAL.withDescription(cause.getMessage()).withCause(cause)
                .asRuntimeException());
    }

    static FxMapItemsGrpcResponse toGrpc(ItemsResponse response) {
        var grpc = FxMapItemsGrpcResponse.newBuilder();
        for (var item : response.items()) {
            var itemGrpc = ItemGrpc.newBuilder().setId(item.id());
            for (var value : item.values()) {
                var valueGrpc = FxMapValueItemGrpc.newBuilder();
                if (value.expression() != null) valueGrpc.setExpression(StringValue.of(value.expression()));
                if (value.value() != null) valueGrpc.setValue(StringValue.of(value.value()));
                itemGrpc.addFxmapValues(valueGrpc);
            }
            grpc.addItems(itemGrpc);
        }
        for (var error : response.errors()) {
            var errorGrpc = ExpressionErrorGrpc.newBuilder();
            if (error.expression() != null) errorGrpc.setExpression(StringValue.of(error.expression()));
            if (error.message() != null) errorGrpc.setMessage(StringValue.of(error.message()));
            grpc.addErrors(errorGrpc);
        }
        return grpc.build();
    }
}
