package io.github.quyvu01.jfxmap.supervision;

import io.github.quyvu01.jfxmap.RequestServer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Starts the {@link RequestServer}s of a service and starts them again when they fail (FxMap's
 * {@code ServerSupervisor}): a server that cannot start, or that stops on its own because of a failure, gets the
 * {@link SupervisorDirective} of its failure. A restart waits for a backoff that grows after each restart; the restarts
 * are limited within a window, and failures in a row open a circuit breaker that holds the restarts for a while.
 *
 * <pre>{@code
 * ServerSupervisor supervisor = new ServerSupervisor(SupervisorOptions.defaults())
 *         .register("kafka", kafkaServer)
 *         .register("rabbitmq", rabbitMqServer);
 * supervisor.start();
 * }</pre>
 *
 * <p>Everything the supervisor does runs on one thread of its own, so a server is never started and stopped at the
 * same time.
 */
public final class ServerSupervisor implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(ServerSupervisor.class.getName());

    private final SupervisorOptions options;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    // In the order of registration, which RestForOne follows; changed on the supervisor's thread only
    private final Map<String, Supervised> servers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Consumer<ServerHealthChange>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile boolean closed;

    public ServerSupervisor(SupervisorOptions options) {
        this(options, Clock.systemUTC());
    }

    ServerSupervisor(SupervisorOptions options, Clock clock) {
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon()
                .name("jfxmap-supervisor").unstarted(task));
    }

    public SupervisionStrategy strategy() {
        return options.strategy();
    }

    /**
     * Supervises a server; one registered while the supervisor runs is started at once.
     *
     * @throws IllegalArgumentException when the id is already registered
     */
    public ServerSupervisor register(String serverId, RequestServer server) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(server, "server");
        await(() -> {
            if (servers.containsKey(serverId))
                throw new IllegalArgumentException("A server is already registered as '" + serverId + "'.");
            var state = new Supervised(serverId, server, options.initialBackoff());
            servers.put(serverId, state);
            if (running) startServer(state);
        });
        return this;
    }

    /** Called with each change of health, on the supervisor's thread. */
    public ServerSupervisor onHealthChange(Consumer<ServerHealthChange> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Starts the servers, and waits until each one has started or failed: one that cannot start is restarted in the
     * background, as its directive says. Does nothing when the supervisor already runs.
     */
    public void start() {
        if (closed) throw new IllegalStateException("The supervisor is closed.");
        await(() -> {
            if (running) return;
            running = true;
            LOGGER.log(System.Logger.Level.INFO, "Starting {0} server(s) with the {1} strategy.", servers.size(),
                    options.strategy());
            for (var state : snapshot()) startServer(state);
        });
    }

    /** Stops the servers and the pending restarts; the supervisor can start again. */
    public void stop() {
        if (closed) return;
        await(() -> {
            if (!running) return;
            running = false;
            for (var state : snapshot()) {
                state.generation++;
                closeQuietly(state);
                state.circuitResetTime = null;
                setHealth(state, ServerHealth.STOPPED);
            }
            LOGGER.log(System.Logger.Level.INFO, "The supervisor stopped.");
        });
    }

    public boolean isRunning() {
        return running;
    }

    /** What the supervisor knows of each server, by id, in the order of registration. */
    public Map<String, ServerState> states() {
        var states = new LinkedHashMap<String, ServerState>();
        for (var state : snapshot()) states.put(state.serverId, state.toState());
        return Collections.unmodifiableMap(states);
    }

    /** Stops the servers and the supervisor's thread. */
    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
        executor.shutdownNow();
    }

    private List<Supervised> snapshot() {
        synchronized (servers) {
            return new ArrayList<>(servers.values());
        }
    }

    // --- On the supervisor's thread

    private void startServer(Supervised state) {
        if (!running) return;
        var generation = ++state.generation;
        setHealth(state, ServerHealth.HEALTHY);
        try {
            state.server.start();
        } catch (RuntimeException e) {
            failed(state, generation, e);
            return;
        }
        state.server.whenStopped().whenComplete((ignored, failure) -> {
            if (failure != null) submit(() -> failed(state, generation, failure));
        });
    }

    private void failed(Supervised state, long generation, Throwable failure) {
        // A failure of a run that was stopped or replaced since is not this run's
        if (!running || generation != state.generation) return;
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        state.lastFailure = cause;
        state.lastFailureTime = clock.instant();
        state.consecutiveFailures++;
        var directive = options.directiveOf(cause);
        LOGGER.log(System.Logger.Level.WARNING, "The server " + state.serverId + " failed (" + state.consecutiveFailures
                                                + " in a row); directive: " + directive + ".", cause);
        switch (directive) {
            case RESUME -> setHealth(state, ServerHealth.UNHEALTHY);
            case RESTART -> restart(state);
            case STOP -> {
                setHealth(state, ServerHealth.STOPPED);
                LOGGER.log(System.Logger.Level.WARNING, "The server {0} is stopped for good.", state.serverId);
            }
            case ESCALATE -> {
                setHealth(state, ServerHealth.STOPPED);
                LOGGER.log(System.Logger.Level.ERROR, "The failure of the server " + state.serverId
                                                      + " needs someone to look at it: it is stopped.", cause);
            }
        }
    }

    private void restart(Supervised state) {
        var now = clock.instant();
        // Stable for longer than the window: the earlier restarts are forgotten
        if (state.windowStart != null
            && Duration.between(state.windowStart, now).compareTo(options.maxRestartWindow()) > 0) {
            state.restartCount = 0;
            state.consecutiveFailures = 1;
            state.windowStart = null;
            state.currentBackoff = options.initialBackoff();
        }
        if (state.windowStart == null) state.windowStart = now;
        state.restartCount++;

        if (state.restartCount > options.maxRestarts()) {
            setHealth(state, ServerHealth.STOPPED);
            LOGGER.log(System.Logger.Level.ERROR, "The server {0} was restarted more than {1} times within {2}: it is "
                                                  + "stopped for good.", state.serverId, options.maxRestarts(),
                    options.maxRestartWindow());
            return;
        }

        if (options.circuitBreaker() && state.consecutiveFailures >= options.circuitBreakerThreshold()) {
            state.circuitResetTime = now.plus(options.circuitBreakerResetTime());
            setHealth(state, ServerHealth.CIRCUIT_OPEN);
            LOGGER.log(System.Logger.Level.WARNING, "The circuit breaker of the server {0} is open until {1}.",
                    state.serverId, state.circuitResetTime);
            var generation = state.generation;
            schedule(options.circuitBreakerResetTime(), () -> {
                if (!running || generation != state.generation) return;
                state.consecutiveFailures = 0;
                state.circuitResetTime = null;
                LOGGER.log(System.Logger.Level.INFO, "The circuit breaker of the server {0} is closed.",
                        state.serverId);
                restartAfterBackoff(state);
            });
            return;
        }
        restartAfterBackoff(state);
    }

    private void restartAfterBackoff(Supervised state) {
        setHealth(state, ServerHealth.DEGRADED);
        var backoff = state.currentBackoff;
        LOGGER.log(System.Logger.Level.INFO, "Restarting the server {0} in {1} ms (restart {2} of {3}).",
                state.serverId, backoff.toMillis(), state.restartCount, options.maxRestarts());
        var generation = state.generation;
        schedule(backoff, () -> {
            if (!running || generation != state.generation) return;
            var next = (long) Math.min(backoff.toMillis() * options.backoffMultiplier(),
                    (double) options.maxBackoff().toMillis());
            state.currentBackoff = Duration.ofMillis(Math.max(next, 0));
            switch (options.strategy()) {
                case ONE_FOR_ONE -> {
                    closeQuietly(state);
                    startServer(state);
                }
                case ONE_FOR_ALL -> restartFrom(0);
                case REST_FOR_ONE -> restartFrom(snapshot().indexOf(state));
            }
        });
    }

    private void restartFrom(int index) {
        if (index < 0) return;
        var all = snapshot();
        var restarted = all.subList(index, all.size());
        LOGGER.log(System.Logger.Level.INFO, "Restarting the servers {0}.",
                restarted.stream().map(s -> s.serverId).toList());
        for (var state : restarted) {
            state.generation++;
            closeQuietly(state);
        }
        for (var state : restarted) startServer(state);
    }

    private void closeQuietly(Supervised state) {
        try {
            state.server.close();
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "The server " + state.serverId + " did not close.", e);
        }
    }

    private void setHealth(Supervised state, ServerHealth health) {
        var previous = state.health;
        if (previous == health) return;
        state.health = health;
        LOGGER.log(System.Logger.Level.INFO, "The server {0} is {1} (was {2}).", state.serverId, health, previous);
        var change = new ServerHealthChange(state.serverId, previous, health, state.lastFailure, clock.instant());
        for (var listener : listeners) {
            try {
                listener.accept(change);
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "A health listener failed.", e);
            }
        }
    }

    // --- The supervisor's thread

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RuntimeException e) {
            // Closed: nothing left to supervise
        }
    }

    private void schedule(Duration delay, Runnable task) {
        try {
            executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // Closed: nothing left to supervise
        }
    }

    /** Runs the task on the supervisor's thread and waits for it, throwing what it throws. */
    private void await(Runnable task) {
        try {
            executor.submit(task).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the supervisor.", e);
        }
    }

    /** The state of a server; changed on the supervisor's thread only, read by {@link #states()}. */
    private static final class Supervised {
        final String serverId;
        final RequestServer server;
        volatile ServerHealth health = ServerHealth.STOPPED;
        volatile int restartCount;
        volatile int consecutiveFailures;
        volatile Instant windowStart;
        volatile Throwable lastFailure;
        volatile Instant lastFailureTime;
        volatile Duration currentBackoff;
        volatile Instant circuitResetTime;
        // Each start is a new run; what an earlier run reports is ignored
        volatile long generation;

        Supervised(String serverId, RequestServer server, Duration initialBackoff) {
            this.serverId = serverId;
            this.server = server;
            this.currentBackoff = initialBackoff;
        }

        ServerState toState() {
            return new ServerState(serverId, health, restartCount, consecutiveFailures, lastFailure, lastFailureTime,
                    currentBackoff, circuitResetTime);
        }
    }
}
