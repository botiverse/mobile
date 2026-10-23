package build.raft.app.core.data.realtime

import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeLocalEvents
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A socket that replaces a dropped one must replay what it missed: `sync:resume` from the applied
 * watermark on the new socket's `rooms:joined`, or, when there is no watermark, a Home/Activity
 * catch-up request (typed for the app-scope owner, raw for the shared stores).
 */
class SocketIoRealtimeClientReconnectResumeTest {
    private class Harness(clockMillis: () -> Long = { 1_000L }) {
        val transport = FakeRealtimeSocketTransport()
        // No timer: the heartbeat-silence check runs only when a test calls it.
        val client = SocketIoRealtimeClient(transport, clockMillis = clockMillis, watchdogScheduler = { {} })
        val events = mutableListOf<RealtimeEvent>()
        val raw = mutableListOf<Pair<String, Any?>>()

        init {
            client.observeEvents { events += it }
            client.observeRawEvents { _, eventName, payload -> raw += eventName to payload }
        }

        fun connect(serverId: String, lastSeq: Long = 0L) {
            client.connect(
                RealtimeConnectRequest(
                    auth = RealtimeAuth(token = "sample-access", serverId = ServerId(serverId)),
                    lastSeq = lastSeq,
                )
            )
            transport.receive(RealtimeSocketEvents.Connect)
        }

        fun receiveMessage(seq: Long) {
            transport.receive(
                RealtimeSocketEvents.MessageNew,
                mapOf(
                    "id" to "message-$seq",
                    "threadId" to "channel-1",
                    "channelId" to "channel-1",
                    "seq" to seq,
                    "content" to "Realtime body",
                    "messageType" to "chat",
                    "senderType" to "user",
                    "senderId" to "user-1",
                    "senderName" to "Artin",
                )
            )
        }

        fun resumeSeqs(): List<Any?> =
            transport.emitted.filter { it.first == RealtimeSocketEvents.SyncResume }.map { it.second["lastSeq"] }

        fun catchUps(): List<RealtimeRefreshRequired> = events.filterIsInstance<RealtimeRefreshRequired>()

        fun rawCatchUps(): List<JSONObject> =
            raw.filter { it.first == RealtimeLocalEvents.CatchUpRequired }.map { JSONObject(it.second as String) }
    }

    @Test
    fun transportReconnectResumesFromTheAppliedSeqOnRoomsJoined() {
        val harness = Harness()
        harness.connect("server-1")
        harness.client.joinChannel(ChannelId("channel-1"))
        harness.receiveMessage(seq = 89)
        harness.transport.emitted.clear()

        harness.transport.receive(RealtimeSocketEvents.Reconnect)

        // The rooms are not joined yet: resuming now could miss frames sent before the joins land.
        assertEquals(emptyList<Any?>(), harness.resumeSeqs())
        assertEquals(
            listOf("channel-1"),
            harness.transport.emitted.filter { it.first == RealtimeSocketEvents.JoinChannel }.map { it.second["channelId"] },
        )

        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertEquals(listOf<Any?>(89L), harness.resumeSeqs())
        assertEquals(emptyList<RealtimeRefreshRequired>(), harness.catchUps())
        assertEquals(emptyList<JSONObject>(), harness.rawCatchUps())
    }

    @Test
    fun heartbeatSilenceReconnectResumesFromTheAppliedSeq() {
        var now = 1_000L
        val harness = Harness(clockMillis = { now })
        harness.connect("server-1", lastSeq = 40)
        harness.receiveMessage(seq = 41)
        harness.transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 41))
        harness.transport.emitted.clear()

        now += 121_000L
        assertTrue("heartbeat silence must force a reconnect", harness.client.checkHeartbeatSilence(now))
        harness.transport.receive(RealtimeSocketEvents.Connect)
        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertEquals(listOf<Any?>(41L), harness.resumeSeqs())
        assertEquals(emptyList<RealtimeRefreshRequired>(), harness.catchUps())
    }

    @Test
    fun reconnectWithoutAnAppliedSeqRequestsOneHomeAndActivityCatchUp() {
        val harness = Harness()
        harness.connect("server-1")
        // A heartbeat is not an applied event: it must not become a resume cursor.
        harness.transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 88))

        harness.transport.receive(RealtimeSocketEvents.Reconnect)
        assertEquals("catch-up waits for rooms:joined", emptyList<RealtimeRefreshRequired>(), harness.catchUps())
        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)
        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertEquals(emptyList<Any?>(), harness.resumeSeqs())
        val catchUp = harness.catchUps().single()
        assertEquals(RealtimeRefreshScope.Home, catchUp.scope)
        assertEquals(ServerId("server-1"), catchUp.serverId)
        assertEquals("transport_reconnect_resume_unavailable", catchUp.reason)
        val rawCatchUp = harness.rawCatchUps().single()
        assertEquals("server-1", rawCatchUp.getString("serverId"))
        assertEquals("transport_reconnect_resume_unavailable", rawCatchUp.getString("reason"))
    }

    @Test
    fun serverSwitchNeverResumesWithThePreviousServersSeq() {
        val harness = Harness()
        harness.connect("server-1")
        harness.receiveMessage(seq = 89)

        harness.connect("server-2")
        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)
        harness.transport.receive(RealtimeSocketEvents.Reconnect)
        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertFalse("server-1 seq 89 leaked into server-2", harness.resumeSeqs().contains(89L))
        assertEquals(emptyList<Any?>(), harness.resumeSeqs())
        assertEquals(listOf(ServerId("server-2")), harness.catchUps().map { it.serverId })
    }

    @Test
    fun firstConnectRoomsJoinedNeverRequestsACatchUp() {
        // The initial load (or the foreground refresh that comes with a forced connect) owns that catch-up.
        val harness = Harness()
        harness.connect("server-1")

        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertEquals(emptyList<RealtimeRefreshRequired>(), harness.catchUps())
        assertEquals(emptyList<JSONObject>(), harness.rawCatchUps())
    }
}
