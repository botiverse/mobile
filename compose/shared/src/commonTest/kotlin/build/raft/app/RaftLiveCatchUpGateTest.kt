package build.raft.app

import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftLiveCatchUpGateTest {
    private fun lifecycle(event: String, epoch: Long? = null): JsonObject =
        buildJsonObject {
            put(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionAppLifecycle)
            put(RaftRealtimeNotifyContract.KeyLifecycleEvent, event)
            epoch?.let { put(RaftRealtimeNotifyContract.KeyForegroundEpoch, it) }
        }

    private fun connected(serverId: String, epoch: Long? = null): JsonObject =
        buildJsonObject {
            put(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionPresenceTransportConnected)
            put(RaftRealtimeNotifyContract.KeyServerId, serverId)
            epoch?.let { put(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, it) }
        }

    @Test
    fun foregroundCatchesUpOncePerForeground() {
        val gate = RaftLiveCatchUpGate()
        val cases =
            listOf(
                Triple("resume", lifecycle(RaftRealtimeNotifyContract.LifecycleVisibleResume, 1), RaftLiveCatchUpTrigger.Foreground),
                Triple("show of the same foreground", lifecycle(RaftRealtimeNotifyContract.LifecycleVisibleShow, 1), null),
                Triple("pause", lifecycle(RaftRealtimeNotifyContract.LifecycleHiddenPause, 2), null),
                Triple("next foreground", lifecycle(RaftRealtimeNotifyContract.LifecycleVisibleShow, 2), RaftLiveCatchUpTrigger.Foreground),
                Triple("no epoch", lifecycle(RaftRealtimeNotifyContract.LifecycleVisibleResume), RaftLiveCatchUpTrigger.Foreground),
            )
        cases.forEach { (label, event, expected) ->
            assertEquals(expected, gate.onRealtimeSignal(event, serverId = "server-1", visible = true), label)
        }
    }

    @Test
    fun reconnectCatchesUpOncePerSocketOfThePagesServer() {
        val gate = RaftLiveCatchUpGate()
        val cases =
            listOf(
                Triple("other server", connected("server-2", 1), null),
                Triple("connected", connected("server-1", 1), RaftLiveCatchUpTrigger.Reconnect),
                Triple("same socket again", connected("server-1", 1), null),
                Triple("reconnected", connected("server-1", 2), RaftLiveCatchUpTrigger.Reconnect),
                Triple("late old socket", connected("server-1", 1), null),
            )
        cases.forEach { (label, event, expected) ->
            assertEquals(expected, gate.onRealtimeSignal(event, serverId = "server-1", visible = true), label)
        }
        assertNull(RaftLiveCatchUpGate().onRealtimeSignal(connected("server-1", 3), serverId = null, visible = true), "no server")
    }

    @Test
    fun aSignalWhileHiddenFiresOnceThePageShowsAgain() {
        val gate = RaftLiveCatchUpGate()
        assertNull(gate.onRealtimeSignal(connected("server-1", 1), serverId = "server-1", visible = false))
        assertNull(gate.onRealtimeSignal(lifecycle(RaftRealtimeNotifyContract.LifecycleVisibleResume, 1), "server-1", visible = false))
        assertNull(gate.onVisibilityChanged(visible = false))
        assertEquals(RaftLiveCatchUpTrigger.Reconnect, gate.onVisibilityChanged(visible = true), "the first missed signal")
        assertNull(gate.onVisibilityChanged(visible = true), "only once")
    }

    @Test
    fun aRouteThatAlreadyRefreshesOnForegroundOnlyTakesReconnects() {
        val gate = RaftLiveCatchUpGate(foreground = false)
        assertNull(gate.onRealtimeSignal(lifecycle(RaftRealtimeNotifyContract.LifecycleVisibleResume, 1), "server-1", visible = true))
        assertEquals(RaftLiveCatchUpTrigger.Reconnect, gate.onRealtimeSignal(connected("server-1", 1), "server-1", visible = true))
    }

    @Test
    fun otherRealtimeFramesNeverCatchUp() {
        val gate = RaftLiveCatchUpGate()
        val frame = buildJsonObject { put(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionRealtimeRoomsJoined) }
        assertNull(gate.onRealtimeSignal(frame, "server-1", visible = true))
        assertNull(gate.onRealtimeSignal(buildJsonObject { put("eventName", "message:new") }, "server-1", visible = true))
    }
}
