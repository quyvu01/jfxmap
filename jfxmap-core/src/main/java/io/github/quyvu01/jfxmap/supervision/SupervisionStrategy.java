package io.github.quyvu01.jfxmap.supervision;

/** Which servers a {@link ServerSupervisor} starts again when one fails, as in Erlang/OTP and Akka. */
public enum SupervisionStrategy {
    /** Only the server that failed: for independent servers. */
    ONE_FOR_ONE,
    /** Every server: for servers that only work together. */
    ONE_FOR_ALL,
    /** The server that failed and those registered after it: for servers that depend on the earlier ones. */
    REST_FOR_ONE
}
