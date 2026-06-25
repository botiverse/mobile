package ai.slock.compose.data.realtime

import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.RealtimeEvent

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
