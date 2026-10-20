package io.github.quyvu01.jfxmap.supervision;

import java.time.Instant;

/**
 * A server of a {@link ServerSupervisor} changed health.
 *
 * @param serverId the id the server was registered with
 * @param previous its health before
 * @param current  its health now
 * @param failure  its last failure, or {@code null} when it has not failed
 * @param at       when it changed
 */
public record ServerHealthChange(String serverId, ServerHealth previous, ServerHealth current, Throwable failure,
                                 Instant at) {
}
