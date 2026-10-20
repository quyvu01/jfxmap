package io.github.quyvu01.jfxmap.supervision;

/** What a {@link ServerSupervisor} does with a server that failed. */
public enum SupervisorDirective {
    /** Nothing: the failure is only logged. */
    RESUME,
    /** Start it again, after a backoff. */
    RESTART,
    /** Leave it stopped. */
    STOP,
    /** Leave it stopped and report the failure as one that needs someone to look at it. */
    ESCALATE
}
