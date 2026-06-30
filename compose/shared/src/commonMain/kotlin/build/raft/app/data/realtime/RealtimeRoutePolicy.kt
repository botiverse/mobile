package build.raft.app.data.realtime

import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.RealtimeEvent

/**
 * Classifies app-shell realtime events before route-specific UI signals and
 * route-level performance markers. This policy does not deduplicate or drop
 * realtime facts; stores still receive accepted events and decide their own
 * no-op/reducer behavior.
 */
internal object RealtimeRoutePolicy {
    fun shouldForwardToActiveThread(event: RealtimeEvent): Boolean =
        event !is MemberPresenceUpdated && event !is Heartbeat

    fun shouldEmitRouteMarker(event: RealtimeEvent, activeThread: Boolean): Boolean =
        event !is Heartbeat && (!activeThread || event !is MemberPresenceUpdated)
}
