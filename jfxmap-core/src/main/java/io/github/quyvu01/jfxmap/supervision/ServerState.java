package io.github.quyvu01.jfxmap.supervision;

import java.time.Duration;
import java.time.Instant;

/**
 * What a {@link ServerSupervisor} knows of a server, at one moment.
 *
 * @param serverId            the id the server was registered with
 * @param health              its health
 * @param restartCount        its restarts within the current window
 * @param consecutiveFailures its failures since the circuit breaker or the window last reset
 * @param lastFailure         its last failure, or {@code null}
 * @param lastFailureTime     when it last failed, or {@code null}
 * @param currentBackoff      the delay before its next restart
 * @param circuitResetTime    when its circuit breaker closes, or {@code null} when it is not open
 */
public record ServerState(String serverId, ServerHealth health, int restartCount, int consecutiveFailures,
                          Throwable lastFailure, Instant lastFailureTime, Duration currentBackoff,
                          Instant circuitResetTime) {
}
