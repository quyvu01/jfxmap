package io.github.quyvu01.jfxmap.sqs;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the client and the server check before they call SQS. */
class SqsBuildersTest {
    record User(int id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            useDistributedKey("UserOf");
        }
    }

    private static final SqsClient SQS = (SqsClient) Proxy.newProxyInstance(SqsClient.class.getClassLoader(),
            new Class<?>[]{SqsClient.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void theClientNeedsAClientAPositiveTimeoutAWaitTimeAndAnExecutor() {
        assertThatThrownBy(() -> SqsRequestClient.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SqsRequestClient.builder(SQS).timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqsRequestClient.builder(SQS).waitTime(Duration.ofSeconds(21)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqsRequestClient.builder(SQS).waitTime(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqsRequestClient.builder(SQS).executor(null))
                .isInstanceOf(NullPointerException.class);
        try (var client = SqsRequestClient.builder(SQS).timeout(Duration.ofSeconds(1)).waitTime(Duration.ZERO)
                .build()) {
            assertThat(client.pendingRequests()).isZero();
            assertThat(client.responseQueue()).isNull();
        }
    }

    @Test
    void theServerKnowsItsKeysBeforeItStarts() {
        var server = SqsServer.builder(SQS, List.of(new InMemoryQueryHandler<>(new UserConfig(), List.of()))).build();

        assertThat(server.keys()).containsExactly(DistributedKeyRef.of("UserOf"));
        assertThat(server.queueUrls()).isEmpty();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void aServerWithoutKeysDoesNotStart() {
        var server = SqsServer.builder(SQS, List.of()).build().start();

        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void theServerRejectsBadOptionsAndSharedKeys() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), List.<User>of());

        assertThatThrownBy(() -> SqsServer.builder(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SqsServer.builder(SQS, List.of()).maxConcurrentProcessing(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqsServer.builder(SQS, List.of()).timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqsServer.builder(SQS, List.of()).waitTime(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqsServer.builder(SQS, List.of(handler, handler)).build())
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
    }
}
