package io.github.quyvu01.jfxmap.nats;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.wire.WireMessages;
import io.github.quyvu01.jfxmap.wire.WireResponder;
import io.nats.client.Connection;
import io.nats.client.JetStreamStatusException;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import io.nats.client.support.NatsRequestCompletableFuture;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

/**
 * Sends requests over NATS request-reply (FxMap's NatsRequestClient and {@code AddNats}): the request goes to the
 * subject of its key ({@link NatsSubjects}) with the headers of the mapping call as NATS headers, and the owner
 * answers on the reply inbox. The connection belongs to the caller, who closes it.
 *
 * <pre>{@code
 * Connection connection = Nats.connect("nats://localhost:4222");
 * NatsRequestClient client = NatsRequestClient.builder(connection).build();
 * }</pre>
 */
public final class NatsRequestClient implements RequestClient {
    private final Connection connection;
    private final String topicPrefix;
    private final Duration timeout;

    private NatsRequestClient(Builder builder) {
        this.connection = builder.connection;
        this.topicPrefix = builder.topicPrefix;
        this.timeout = builder.timeout;
    }

    public static Builder builder(Connection connection) {
        return new Builder(connection);
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        var headers = new Headers();
        context.headers().forEach((name, value) -> {
            if (value != null) headers.add(name, value);
        });
        CompletableFuture<Message> reply;
        try {
            reply = connection.requestWithTimeout(NatsSubjects.of(key, topicPrefix),
                    headers.isEmpty() ? null : headers, WireMessages.writeRequest(request), timeout);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        var sent = reply;
        return reply.handle((message, failure) -> {
            if (failure != null) throw translate(key, sent, failure);
            if (message.isStatusMessage())
                throw message.getStatus().getCode() == 503
                        ? new DistributedMapException.NoHandlerForDistributedKey(key)
                        : new DistributedMapException.ReceivedFault(null, List.of(
                        new DistributedMapException.ReceivedFault.ExceptionInfo("NATS status " +
                                message.getStatus().getCode(), message.getStatus().getMessage(), null)));
            return WireMessages.readResult(message.getData());
        });
    }

    private RuntimeException translate(DistributedKeyRef key, CompletableFuture<Message> sent, Throwable failure) {
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        var request = sent instanceof NatsRequestCompletableFuture natsRequest ? natsRequest : null;
        // jnats cancels a request that timed out, unless the connection asks for a TimeoutException
        if (cause instanceof TimeoutException || request != null && request.wasCancelledTimedOut())
            return new DistributedMapException.RequestTimedOut(key, timeout);
        if (request != null && request.wasCancelledClosing())
            return new IllegalStateException("The NATS connection closed before the reply for " + key + " came.", cause);
        // No subscriber on the subject: the server answers "no responders", which cancels the request
        if (cause instanceof CancellationException || cause instanceof JetStreamStatusException)
            return new DistributedMapException.NoHandlerForDistributedKey(key);
        return cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
    }

    public static final class Builder {
        private final Connection connection;
        private String topicPrefix;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;

        private Builder(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        /** The prefix of the subjects (FxMap's {@code TopicPrefix}); none by default. */
        public Builder topicPrefix(String topicPrefix) {
            this.topicPrefix = topicPrefix;
            return this;
        }

        /** How long a request may wait for its reply (default 30 seconds, FxMap's DefaultRequestTimeout). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        public NatsRequestClient build() {
            return new NatsRequestClient(this);
        }
    }
}
