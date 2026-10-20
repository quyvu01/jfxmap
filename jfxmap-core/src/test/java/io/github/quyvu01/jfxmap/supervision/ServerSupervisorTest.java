package io.github.quyvu01.jfxmap.supervision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ServerSupervisorTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final List<ServerSupervisor> supervisors = new ArrayList<>();
    private final List<ServerHealthChange> changes = Collections.synchronizedList(new ArrayList<>());

    static final class QueueDeleted extends RuntimeException {
        QueueDeleted() {
            super("The queue was deleted.");
        }
    }

    /** Fast options: a 10 ms backoff, no circuit breaker unless a test turns it on. */
    private static SupervisorOptions.Builder fast() {
        return SupervisorOptions.builder().initialBackoff(Duration.ofMillis(10)).maxBackoff(Duration.ofMillis(40))
                .circuitBreaker(false);
    }

    private ServerSupervisor supervisor(SupervisorOptions options) {
        var supervisor = new ServerSupervisor(options).onHealthChange(changes::add);
        supervisors.add(supervisor);
        return supervisor;
    }

    @AfterEach
    void closeSupervisors() {
        supervisors.forEach(ServerSupervisor::close);
    }

    private static void eventually(BooleanSupplier condition) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("The condition did not hold within " + WAIT);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static ServerHealth health(ServerSupervisor supervisor, String id) {
        return supervisor.states().get(id).health();
    }

    @Test
    void startsEveryServer() {
        var nats = new FakeServer();
        var kafka = new FakeServer();
        var supervisor = supervisor(fast().build()).register("nats", nats).register("kafka", kafka);

        supervisor.start();

        assertThat(supervisor.isRunning()).isTrue();
        assertThat(nats.isRunning()).isTrue();
        assertThat(kafka.isRunning()).isTrue();
        assertThat(supervisor.states().keySet()).containsExactly("nats", "kafka");
        assertThat(supervisor.states().values()).extracting(ServerState::health).containsOnly(ServerHealth.HEALTHY);
        assertThat(supervisor.strategy()).isEqualTo(SupervisionStrategy.ONE_FOR_ONE);
    }

    @Test
    void restartsAServerThatFailsOnItsOwn() {
        var nats = new FakeServer();
        var kafka = new FakeServer();
        var supervisor = supervisor(fast().build()).register("nats", nats).register("kafka", kafka);
        supervisor.start();

        kafka.fail(new QueueDeleted());

        eventually(() -> kafka.starts.get() == 2 && kafka.isRunning());
        eventually(() -> health(supervisor, "kafka") == ServerHealth.HEALTHY);
        assertThat(nats.starts).hasValue(1);
        var state = supervisor.states().get("kafka");
        assertThat(state.restartCount()).isEqualTo(1);
        assertThat(state.consecutiveFailures()).isEqualTo(1);
        assertThat(state.lastFailure()).isInstanceOf(QueueDeleted.class);
        assertThat(state.lastFailureTime()).isNotNull();
        assertThat(changes).extracting(ServerHealthChange::serverId, ServerHealthChange::current)
                .containsSubsequence(tuple("kafka", ServerHealth.DEGRADED),
                        tuple("kafka", ServerHealth.HEALTHY));
    }

    @Test
    void aServerThatCannotStartIsRestartedInTheBackground() {
        var kafka = new FakeServer().failNextStarts(new IllegalStateException("broker down"),
                new IllegalStateException("broker down"));
        var supervisor = supervisor(fast().build()).register("kafka", kafka);

        supervisor.start();

        eventually(kafka::isRunning);
        assertThat(kafka.starts).hasValue(3);
        eventually(() -> health(supervisor, "kafka") == ServerHealth.HEALTHY);
    }

    @Test
    void theBackoffGrowsUpToItsMaximum() {
        var kafka = new FakeServer().failNextStarts(new IllegalStateException("1"), new IllegalStateException("2"),
                new IllegalStateException("3"), new IllegalStateException("4"));
        var supervisor = supervisor(fast().build()).register("kafka", kafka);

        supervisor.start();

        eventually(kafka::isRunning);
        assertThat(supervisor.states().get("kafka").currentBackoff()).isEqualTo(Duration.ofMillis(40));
    }

    @Test
    void oneForAllRestartsEveryServer() {
        var nats = new FakeServer();
        var kafka = new FakeServer();
        var supervisor = supervisor(fast().strategy(SupervisionStrategy.ONE_FOR_ALL).build())
                .register("nats", nats).register("kafka", kafka);
        supervisor.start();

        kafka.fail(new QueueDeleted());

        eventually(() -> nats.starts.get() == 2 && kafka.starts.get() == 2);
        eventually(() -> nats.isRunning() && kafka.isRunning());
        assertThat(nats.closes.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void restForOneRestartsTheFailedServerAndThoseRegisteredAfterIt() {
        var first = new FakeServer();
        var second = new FakeServer();
        var third = new FakeServer();
        var supervisor = supervisor(fast().strategy(SupervisionStrategy.REST_FOR_ONE).build())
                .register("first", first).register("second", second).register("third", third);
        supervisor.start();

        second.fail(new QueueDeleted());

        eventually(() -> second.starts.get() == 2 && third.starts.get() == 2);
        eventually(() -> second.isRunning() && third.isRunning());
        assertThat(first.starts).hasValue(1);
        assertThat(first.isRunning()).isTrue();
    }

    @Test
    void stopsAServerForGoodAfterTooManyRestartsInTheWindow() {
        var kafka = new FakeServer();
        for (int i = 0; i < 10; i++) kafka.failNextStarts(new IllegalStateException("broker down"));
        var supervisor = supervisor(fast().maxRestarts(2).build()).register("kafka", kafka);

        supervisor.start();

        eventually(() -> health(supervisor, "kafka") == ServerHealth.STOPPED);
        assertThat(kafka.starts).hasValue(3);
        assertThat(kafka.isRunning()).isFalse();
    }

    @Test
    void forgetsTheRestartsAfterAStableWindow() throws InterruptedException {
        var kafka = new FakeServer();
        var supervisor = supervisor(fast().maxRestarts(1).maxRestartWindow(Duration.ofMillis(100)).build())
                .register("kafka", kafka);
        supervisor.start();

        kafka.fail(new QueueDeleted());
        eventually(() -> kafka.starts.get() == 2 && kafka.isRunning());
        Thread.sleep(200);
        kafka.fail(new QueueDeleted());

        eventually(() -> kafka.starts.get() == 3 && kafka.isRunning());
        assertThat(supervisor.states().get("kafka").restartCount()).isEqualTo(1);
    }

    @Test
    void failuresInARowOpenTheCircuitBreakerUntilItsResetTime() {
        var kafka = new FakeServer().failNextStarts(new IllegalStateException("1"), new IllegalStateException("2"));
        var supervisor = supervisor(fast().circuitBreaker(true).circuitBreakerThreshold(2)
                .circuitBreakerResetTime(Duration.ofMillis(300)).build()).register("kafka", kafka);

        supervisor.start();

        eventually(() -> health(supervisor, "kafka") == ServerHealth.CIRCUIT_OPEN);
        assertThat(supervisor.states().get("kafka").circuitResetTime()).isNotNull();
        assertThat(kafka.starts).hasValue(2);
        eventually(kafka::isRunning);
        assertThat(kafka.starts).hasValue(3);
        assertThat(supervisor.states().get("kafka").consecutiveFailures()).isZero();
        assertThat(supervisor.states().get("kafka").circuitResetTime()).isNull();
    }

    @Test
    void theDirectiveOfAFailureCanStopOrEscalateOrResume() {
        var stopped = new FakeServer();
        var escalated = new FakeServer();
        var resumed = new FakeServer();
        var supervisor = supervisor(fast()
                .directive(QueueDeleted.class, SupervisorDirective.STOP)
                .directive(SecurityException.class, SupervisorDirective.ESCALATE)
                .directive(UnsupportedOperationException.class, SupervisorDirective.RESUME).build())
                .register("stopped", stopped).register("escalated", escalated).register("resumed", resumed);
        supervisor.start();

        stopped.fail(new QueueDeleted());
        escalated.fail(new SecurityException("denied"));
        resumed.fail(new UnsupportedOperationException("ignored"));

        eventually(() -> health(supervisor, "stopped") == ServerHealth.STOPPED);
        eventually(() -> health(supervisor, "escalated") == ServerHealth.STOPPED);
        eventually(() -> health(supervisor, "resumed") == ServerHealth.UNHEALTHY);
        assertThat(List.of(stopped, escalated, resumed)).allSatisfy(server -> {
            assertThat(server.starts).hasValue(1);
            assertThat(server.isRunning()).isFalse();
        });
    }

    @Test
    void stopClosesTheServersAndIgnoresTheirLaterFailures() {
        var kafka = new FakeServer();
        var supervisor = supervisor(fast().build()).register("kafka", kafka);
        supervisor.start();

        supervisor.stop();
        kafka.fail(new QueueDeleted());

        assertThat(supervisor.isRunning()).isFalse();
        assertThat(kafka.isRunning()).isFalse();
        assertThat(health(supervisor, "kafka")).isEqualTo(ServerHealth.STOPPED);
        supervisor.start();
        assertThat(kafka.isRunning()).isTrue();
        assertThat(kafka.starts).hasValue(2);
    }

    @Test
    void stopCancelsAPendingRestart() throws InterruptedException {
        var kafka = new FakeServer().failNextStarts(new IllegalStateException("broker down"));
        var supervisor = supervisor(fast().initialBackoff(Duration.ofMillis(200)).build()).register("kafka", kafka);
        supervisor.start();

        supervisor.stop();
        Thread.sleep(400);

        assertThat(kafka.starts).hasValue(1);
        assertThat(kafka.isRunning()).isFalse();
    }

    @Test
    void aServerRegisteredWhileRunningStartsAtOnce() {
        var supervisor = supervisor(fast().build());
        supervisor.start();
        var nats = new FakeServer();

        supervisor.register("nats", nats);

        assertThat(nats.isRunning()).isTrue();
        assertThatThrownBy(() -> supervisor.register("nats", new FakeServer()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() {
        var supervisor = supervisor(fast().build());
        supervisor.onHealthChange(change -> {
            throw new IllegalStateException("listener");
        });
        var kafka = new FakeServer();

        supervisor.register("kafka", kafka).start();

        assertThat(changes).extracting(ServerHealthChange::current).contains(ServerHealth.HEALTHY);
    }

    @Test
    void aClosedSupervisorCannotStart() {
        var kafka = new FakeServer();
        var supervisor = supervisor(fast().build()).register("kafka", kafka);
        supervisor.start();

        supervisor.close();
        supervisor.close();

        assertThat(kafka.isRunning()).isFalse();
        assertThatThrownBy(supervisor::start).isInstanceOf(IllegalStateException.class);
    }
}
