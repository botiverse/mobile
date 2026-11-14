package build.raft.app.core.data.realtime

import build.raft.app.data.realtime.RaftKmpRealtimeSocketTransport
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeSyncHorizon
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.realtime.socket.RaftWebSocketConnector
import build.raft.realtime.socket.RaftWebSocketFrame
import build.raft.realtime.socket.RaftWebSocketSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect / resume and sync-horizon cases of SocketIoRealtimeClientReconnectResumeTest and
 * SocketIoRealtimeClientSyncHorizonTest, run on the common Socket.IO client (RaftKmpRealtimeSocketTransport
 * over :realtime-socket) instead of a fake transport: the same client logic, real Engine.IO / Socket.IO
 * frames in and out (a scripted WebSocket), virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocketIoRealtimeClientOverKmpTransportTest {
    private class Session : RaftWebSocketSession {
        private val toClient = Channel<RaftWebSocketFrame>(Channel.UNLIMITED)
        private val fromClient = Channel<String>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<RaftWebSocketFrame> = toClient

        override suspend fun send(text: String) {
            fromClient.send(text)
        }

        override suspend fun sendBinary(bytes: ByteArray) = Unit

        override suspend fun close(code: Int, reason: String) {
            toClient.close()
        }

        fun push(text: String) {
            toClient.trySend(RaftWebSocketFrame.Text(text))
        }

        fun fail() {
            toClient.close(IllegalStateException("reset"))
        }

        fun sent(): List<String> {
            val frames = mutableListOf<String>()
            while (true) frames += fromClient.tryReceive().getOrNull() ?: break
            return frames
        }
    }

    private class Harness(private val test: TestScope) {
        val sessions = mutableListOf<Session>()
        var historySent = false
        val transport =
            RaftKmpRealtimeSocketTransport(
                socketUrl = "https://api.raft.build",
                clientKind = "mobile",
                connector = RaftWebSocketConnector { _, _ -> Session().also { sessions += it } },
                parentScope = test.backgroundScope,
                dispatcherFactory = { StandardTestDispatcher(test.testScheduler) },
                logger = {},
            )
        // No timer: the heartbeat-silence check runs only when a test calls it.
        val client =
            SocketIoRealtimeClient(
                transport,
                clockMillis = { 1_000L },
                watchdogScheduler = { {} },
                baselineHistorySent = { historySent },
            )
        val events = mutableListOf<RealtimeEvent>()
        val raw = mutableListOf<String>()
        /** Every frame the client sent, across sockets, in order. */
        val sent = mutableListOf<String>()

        init {
            client.observeEvents { events += it }
            client.observeRawEvents { _, eventName, _ -> raw += eventName }
        }

        val session: Session get() = sessions.last()

        fun pump() {
            test.runCurrent()
            sessions.forEach { sent += it.sent() }
        }

        /** Opens the Engine.IO session and accepts the CONNECT (long heartbeat: only the test drops it). */
        fun handshake(sid: String) {
            pump()
            session.push("0{\"sid\":\"e-$sid\",\"upgrades\":[],\"pingInterval\":600000,\"pingTimeout\":600000,\"maxPayload\":1000000}")
            pump()
            assertTrue("the client sent its CONNECT: $sent", sent.any { it.startsWith("40{") && it.contains("\"token\":\"sample-access\"") })
            session.push("40{\"sid\":\"$sid\"}")
            pump()
        }

        fun connect(serverId: String = "server-1", lastSeq: Long = 0L) {
            client.connect(
                RealtimeConnectRequest(auth = RealtimeAuth(token = "sample-access", serverId = ServerId(serverId)), lastSeq = lastSeq),
            )
            handshake("s-${sessions.size + 1}")
        }

        /** The server drops the socket; the client reconnects after its backoff. */
        fun dropAndReconnect() {
            session.fail()
            pump()
            test.advanceTimeBy(2_000)
            handshake("s-${sessions.size + 1}")
        }

        fun receive(event: String, payload: JSONObject? = null) {
            session.push("42[\"$event\"${payload?.let { ",$it" }.orEmpty()}]")
            pump()
        }

        fun receiveMessage(seq: Long, serverId: String? = null) {
            val payload =
                JSONObject()
                    .put("id", "message-$seq")
                    .put("threadId", "channel-1")
                    .put("channelId", "channel-1")
                    .put("seq", seq)
                    .put("content", "Realtime body")
                    .put("messageType", "chat")
                    .put("senderType", "user")
                    .put("senderId", "user-1")
                    .put("senderName", "Artin")
            serverId?.let { payload.put("serverId", it) }
            receive("message:new", payload)
        }

        fun resumes(): List<JSONObject> =
            sent.filter { it.startsWith("42[\"sync:resume\"") }.map { JSONArray(it.removePrefix("42")).getJSONObject(1) }

        fun horizonAckIds(): List<String> =
            sent.filter { it.contains("[\"sync:horizon\"]") }.map { it.removePrefix("42").substringBefore("[") }
    }

    @Test
    fun aReconnectedSocketRejoinsAndResumesFromTheAppliedSeqOnRoomsJoined() = runTest {
        val harness = Harness(this)
        harness.connect()
        harness.client.joinChannel(ChannelId("channel-1"))
        harness.pump()
        assertTrue(harness.sent.contains("42[\"join:channel\",\"channel-1\"]"))
        harness.receiveMessage(seq = 89)
        assertEquals(89L, (harness.events.filterIsInstance<MessageNew>().single()).message.seq)

        harness.sent.clear()
        harness.dropAndReconnect()
        assertTrue("rejoined with the bare channel id: ${harness.sent}", harness.sent.contains("42[\"join:channel\",\"channel-1\"]"))
        assertEquals("no resume before rooms:joined", emptyList<JSONObject>(), harness.resumes())

        harness.receive("rooms:joined")
        assertEquals(89L, harness.resumes().single().getLong("lastSeq"))
        assertTrue(harness.events.filterIsInstance<RealtimeRefreshRequired>().isEmpty())
    }

    @Test
    fun aReconnectWithoutAnAppliedSeqRequestsOneHomeCatchUp() = runTest {
        val harness = Harness(this)
        harness.connect()
        harness.receive("heartbeat", JSONObject().put("seq", 88)) // Not an applied event.
        harness.dropAndReconnect()
        harness.receive("rooms:joined")

        assertEquals(emptyList<JSONObject>(), harness.resumes())
        val catchUp = harness.events.filterIsInstance<RealtimeRefreshRequired>().single()
        assertEquals(RealtimeRefreshScope.Home, catchUp.scope)
        assertEquals(ServerId("server-1"), catchUp.serverId)
    }

    @Test
    fun theStartingHorizonAckFeedsTheNextResumeAndTheServerGateUsesThePlainPayload() = runTest {
        val harness = Harness(this)
        harness.connect()
        val ackId = harness.horizonAckIds().single()
        harness.session.push("43$ackId[{\"horizon\":\"752\"}]")
        harness.pump()
        harness.historySent = true
        harness.receiveMessage(seq = 89, serverId = "server-1")
        harness.receiveMessage(seq = 95, serverId = "server-2") // Another server's frame never moves this cursor.

        harness.dropAndReconnect()
        harness.receive("rooms:joined")

        val resume = harness.resumes().single()
        assertEquals(89L, resume.getLong("lastSeq"))
        assertEquals("752", resume.getString(RealtimeSyncHorizon.SinceHorizonKey))
        assertEquals("a held horizon is not asked for again", 1, harness.horizonAckIds().size)
    }

    @Test
    fun anAckAfterAHistoryRequestOrAnErrorAckLeavesNoHorizon() = runTest {
        for (ack in listOf("{\"horizon\":\"752\"}", "{\"error\":\"horizon_unavailable\"}")) {
            val harness = Harness(this)
            harness.historySent = true
            harness.connect()
            harness.session.push("43${harness.horizonAckIds().single()}[$ack]")
            harness.pump()
            harness.receiveMessage(seq = 89)
            harness.dropAndReconnect()
            harness.receive("rooms:joined")
            val resume = harness.resumes().single()
            assertFalse(ack, resume.has(RealtimeSyncHorizon.SinceHorizonKey))
        }
    }

    @Test
    fun horizonResetRequestsTheHomeRefetchAfterApplyingTheResponse() = runTest {
        val harness = Harness(this)
        harness.connect()
        harness.receiveMessage(seq = 89)
        harness.dropAndReconnect()
        harness.receive("rooms:joined")
        harness.receive(
            "sync:resume:response",
            JSONObject().put("messages", JSONArray()).put("currentSeq", 89).put("hasMore", false).put("horizon", "800").put("horizonReset", true),
        )

        val refresh = harness.events.filterIsInstance<RealtimeRefreshRequired>().single()
        assertEquals(RealtimeSyncHorizon.ResetReason, refresh.reason)
        assertTrue(harness.raw.indexOf("sync:resume:response") < harness.raw.indexOf("raft:realtime:catch_up_required"))

        harness.dropAndReconnect()
        harness.receive("rooms:joined")
        assertEquals("800", harness.resumes().last().getString(RealtimeSyncHorizon.SinceHorizonKey))
    }
}
