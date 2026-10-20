package io.github.quyvu01.jfxmap.supervision;

/** The health of a supervised server. */
public enum ServerHealth {
    /** Running. */
    HEALTHY,
    /** Failed and about to be started again. */
    DEGRADED,
    /** Failed; not started again (the failure was resumed). */
    UNHEALTHY,
    /** Failed too many times in a row: not started again before the circuit breaker closes. */
    CIRCUIT_OPEN,
    /** Stopped for good: its directive said so, or it was restarted too many times. */
    STOPPED
}
