package io.github.quyvu01.jfxmap.wire;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingRepliesTest {
    private static final DistributedKeyRef KEY = DistributedKeyRef.of("UserOf");

    @Test
    void aReplyCompletesItsRequest() {
        var pending = new PendingReplies();
        var reply = pending.register("c1", KEY, Duration.ofSeconds(5));

        assertThat(pending.complete("c1", new byte[]{1})).isTrue();

        assertThat(reply.join()).containsExactly(1);
        assertThat(pending.size()).isZero();
    }

    @Test
    void aReplyThatNoRequestWaitsForIsDropped() {
        var pending = new PendingReplies();

        assertThat(pending.complete("unknown", new byte[0])).isFalse();
        assertThat(pending.complete(null, new byte[0])).isFalse();
    }

    @Test
    void aRequestWithoutReplyTimesOut() {
        var pending = new PendingReplies();
        var reply = pending.register("c1", KEY, Duration.ofMillis(20));

        assertThatThrownBy(reply::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DistributedMapException.RequestTimedOut.class);
        assertThat(pending.size()).isZero();
    }

    @Test
    void aFailedOrClosedRequestFails() {
        var pending = new PendingReplies();
        var failed = pending.register("c1", KEY, Duration.ofSeconds(5));
        var closed = pending.register("c2", KEY, Duration.ofSeconds(5));

        pending.fail("c1", new IllegalStateException("not sent"));
        pending.failAll(new IOException("closed"));

        assertThatThrownBy(failed::join).hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(closed::join).hasRootCauseInstanceOf(IOException.class);
        assertThat(pending.size()).isZero();
    }

    @Test
    void aCorrelationIdIsUsedOnce() {
        var pending = new PendingReplies();
        pending.register("c1", KEY, Duration.ofSeconds(5));

        assertThatThrownBy(() -> pending.register("c1", KEY, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class);
    }
}
