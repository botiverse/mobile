package build.raft.app.core.data.realtime

import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeLocalEvents
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.RealtimeSyncHorizon
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
 * Exact catch-up after reconnect (botiverse/slock #10982, pinned a476332d1fe6addab21bc92e1edd2d3fa96a553b:
 * `socket/syncResume.ts`): the socket asks `sync:horizon` on connect without waiting for it, uses that
 * starting horizon only if its ack beat every baseline history request, sends the held horizon as
 * `sinceHorizon`, and takes each `sync:resume:response`'s horizon for the next round.
 */
class SocketIoRealtimeClientSyncHorizonTest {
    private class Harness {
        val transport = FakeRealtimeSocketTransport().apply { supportsAck = true }
        var historySent = false
        val client =
            SocketIoRealtimeClient(
                transport,
                clockMillis = { 1_000L },
                watchdogScheduler = { {} },
                baselineHistorySent = { historySent },
            )
        val events = mutableListOf<RealtimeEvent>()
        val raw = mutableListOf<Pair<String, Any?>>()

        init {
            client.observeEvents { events += it }
            client.observeRawEvents { _, eventName, payload -> raw += eventName to payload }
        }

        fun connect(serverId: String = "server-1", lastSeq: Long = 0L) {
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

        /** The wire shape of `sync:resume:response` (socket/syncResume.ts); [horizon] null = an older server. */
        fun receiveResume(currentSeq: Long, horizon: String?, horizonReset: Boolean = false) {
            val payload = JSONObject().put("messages", org.json.JSONArray()).put("currentSeq", currentSeq).put("hasMore", false)
            if (horizon != null) payload.put("horizon", horizon).put("horizonReset", horizonReset)
            transport.receive(RealtimeSocketEvents.SyncResumeResponse, payload)
        }

        fun reconnect() {
            transport.receive(RealtimeSocketEvents.Reconnect)
            transport.receive(RealtimeSocketEvents.RoomsJoined)
        }

        fun resumes(): List<Map<String, Any>> =
            transport.emitted.filter { it.first == RealtimeSocketEvents.SyncResume }.map { it.second }

        fun emittedNames(): List<String> = transport.emitted.map { it.first }
    }

    @Test
    fun startingHorizonIsAskedOnConnectBeforeRoomsAndResumeWithoutWaiting() {
        val harness = Harness()
        harness.connect(lastSeq = 40L)
        harness.client.joinChannel(ChannelId("channel-1"))
        harness.transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertEquals(
            listOf(RealtimeSocketEvents.SyncHorizon, RealtimeSocketEvents.JoinChannel, RealtimeSocketEvents.SyncResume),
            harness.emittedNames(),
        )
        assertEquals("sync:horizon", RealtimeSocketEvents.SyncHorizon)
        // Unanswered ack: the first resume did not wait for it and runs as before.
        assertEquals(listOf(mapOf<String, Any>("lastSeq" to 40L)), harness.resumes())
    }

    @Test
    fun ackBeforeAnyHistoryRequestIsUsedOnTheFirstReconnect() {
        val harness = Harness()
        harness.connect()
        harness.transport.answerAck(RealtimeSocketEvents.SyncHorizon, JSONObject().put("horizon", "752"))
        harness.historySent = true // the history load starts after the ack
        harness.receiveMessage(seq = 89L)

        harness.reconnect()

        assertEquals(listOf(mapOf<String, Any>("lastSeq" to 89L, "sinceHorizon" to "752")), harness.resumes())
    }

    @Test
    fun ackAfterAHistoryRequestWasSentIsDiscarded() {
        val harness = Harness()
        harness.connect()
        harness.historySent = true
        harness.transport.answerAck(RealtimeSocketEvents.SyncHorizon, JSONObject().put("horizon", "752"))
        harness.receiveMessage(seq = 89L)

        harness.reconnect()

        assertEquals(listOf(mapOf<String, Any>("lastSeq" to 89L)), harness.resumes())
    }

    @Test
    fun aResumeWithoutHorizonStillStartsTheNextRoundWithItsHorizon() {
        val harness = Harness()
        harness.historySent = true
        harness.connect()
        harness.receiveMessage(seq = 89L)

        harness.reconnect()
        harness.receiveResume(currentSeq = 95L, horizon = "800")
        harness.reconnect()
        harness.receiveResume(currentSeq = 95L, horizon = "900")
        harness.reconnect()

        assertEquals(
            listOf(
                mapOf<String, Any>("lastSeq" to 89L),
                mapOf<String, Any>("lastSeq" to 95L, "sinceHorizon" to "800"),
                mapOf<String, Any>("lastSeq" to 95L, "sinceHorizon" to "900"),
            ),
            harness.resumes(),
        )
        // A horizon is held: a new socket does not ask for a starting one again.
        val asked = harness.emittedNames().count { it == RealtimeSocketEvents.SyncHorizon }
        harness.transport.receive(RealtimeSocketEvents.Connect)
        assertEquals(asked, harness.emittedNames().count { it == RealtimeSocketEvents.SyncHorizon })
    }

    @Test
    fun horizonResetRequestsTheHomeAndActivityRefetchAfterApplyingTheResponse() {
        val harness = Harness()
        harness.connect()
        harness.transport.answerAck(RealtimeSocketEvents.SyncHorizon, JSONObject().put("horizon", "752"))
        harness.receiveMessage(seq = 89L)
        harness.reconnect()

        harness.receiveResume(currentSeq = 89L, horizon = "800", horizonReset = true)

        val refresh = harness.events.filterIsInstance<RealtimeRefreshRequired>().single()
        assertEquals(RealtimeRefreshScope.Home, refresh.scope)
        assertEquals(RealtimeSyncHorizon.ResetReason, refresh.reason)
        assertEquals(ServerId("server-1"), refresh.serverId)
        val rawNames = harness.raw.map { it.first }
        assertEquals(
            "the resume rows reach the stores before the re-fetch request",
            listOf(RealtimeSocketEvents.SyncResumeResponse, RealtimeLocalEvents.CatchUpRequired),
            rawNames.filter { it == RealtimeSocketEvents.SyncResumeResponse || it == RealtimeLocalEvents.CatchUpRequired },
        )
        val rawCatchUp = JSONObject(harness.raw.single { it.first == RealtimeLocalEvents.CatchUpRequired }.second as String)
        assertEquals(RealtimeSyncHorizon.ResetReason, rawCatchUp.getString("reason"))
        harness.reconnect()
        assertEquals("800", harness.resumes().last()["sinceHorizon"])
    }

    @Test
    fun olderServerWithoutHorizonBehavesExactlyAsBefore() {
        val harness = Harness()
        harness.connect()
        // An older server never answers `sync:horizon`, and its resumes carry no horizon.
        harness.receiveMessage(seq = 89L)
        harness.reconnect()
        harness.receiveResume(currentSeq = 95L, horizon = null)
        harness.reconnect()

        assertEquals(
            listOf(mapOf<String, Any>("lastSeq" to 89L), mapOf<String, Any>("lastSeq" to 95L)),
            harness.resumes(),
        )
        assertTrue(harness.events.filterIsInstance<RealtimeRefreshRequired>().isEmpty())
    }

    @Test
    fun errorAckOrNumericHorizonOrTransportWithoutAcksLeavesNoHorizon() {
        listOf<Any>(JSONObject().put("error", "horizon_unavailable"), JSONObject().put("horizon", 752)).forEach { ack ->
            val harness = Harness()
            harness.connect()
            harness.transport.answerAck(RealtimeSocketEvents.SyncHorizon, ack)
            harness.receiveMessage(seq = 89L)
            harness.reconnect()
            assertEquals(ack.toString(), listOf(mapOf<String, Any>("lastSeq" to 89L)), harness.resumes())
        }

        val curl = Harness()
        curl.transport.supportsAck = false
        curl.connect()
        curl.receiveMessage(seq = 89L)
        curl.reconnect()
        assertFalse(RealtimeSocketEvents.SyncHorizon in curl.emittedNames())
        assertEquals(listOf(mapOf<String, Any>("lastSeq" to 89L)), curl.resumes())
    }

    @Test
    fun horizonNeverCrossesServersAndAStaleSocketsAckIsIgnored() {
        val harness = Harness()
        harness.connect("server-1")
        harness.receiveMessage(seq = 89L)
        harness.receiveResume(currentSeq = 89L, horizon = "800")

        harness.connect("server-2")
        // server-1's socket is gone: nothing it answers can reach server-2's cursor.
        harness.transport.answerAck(RealtimeSocketEvents.SyncHorizon, JSONObject().put("horizon", "752"))
        harness.receiveMessage(seq = 10L)
        harness.transport.answerAck(RealtimeSocketEvents.SyncHorizon, JSONObject().put("horizon", "999"))
        harness.reconnect()

        assertEquals(mapOf<String, Any>("lastSeq" to 10L, "sinceHorizon" to "999"), harness.resumes().last())
        assertFalse(harness.resumes().any { it["sinceHorizon"] == "800" || it["sinceHorizon"] == "752" })
    }
}
