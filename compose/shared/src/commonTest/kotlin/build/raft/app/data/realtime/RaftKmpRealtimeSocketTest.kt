package build.raft.app.data.realtime

import build.raft.realtime.socket.RaftWebSocketConnector
import build.raft.realtime.socket.RaftWebSocketFrame
import build.raft.realtime.socket.RaftWebSocketSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The host facade over the common Socket.IO client, as the iOS host drives it. */
class RaftKmpRealtimeSocketTest {
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

        fun sent(): List<String> {
            val frames = mutableListOf<String>()
            while (true) frames += fromClient.tryReceive().getOrNull() ?: break
            return frames
        }
    }

    private class Recorder : RaftKmpRealtimeSocketListener {
        val calls = mutableListOf<String>()
        val logs = mutableListOf<String>()

        override fun onConnected(socketId: String, reconnect: Boolean) {
            calls += "connected:$socketId:$reconnect"
        }

        override fun onDisconnected(reason: String) {
            calls += "disconnected:$reason"
        }

        override fun onEvent(eventName: String, rawPayload: String, ackId: Long) {
            calls += "event:$eventName:$rawPayload:$ackId"
        }

        override fun onConnectError(kind: String, message: String, terminal: Boolean, requiresTokenRefresh: Boolean) {
            calls += "connect_error:$kind:$terminal:$requiresTokenRefresh"
        }

        override fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: String) {
            calls += "scheduled:$reason"
        }

        override fun onLog(message: String) {
            logs += message
        }
    }

    private class Harness(test: TestScope) {
        val sessions = mutableListOf<Session>()
        val recorder = Recorder()
        val socket =
            RaftKmpRealtimeSocket(
                socketUrl = "https://api.raft.build",
                serverId = "server-1",
                token = "token-1",
                clientKind = "mobile",
                capabilities = listOf("agent_activity_subscriptions_v1"),
                allowedEvents = listOf("rooms:joined", "message:new", "notification:desktop"),
                listener = recorder,
                connector = RaftWebSocketConnector { _, _ -> Session().also { sessions += it } },
                parentScope = test.backgroundScope,
                dispatcher = StandardTestDispatcher(test.testScheduler),
                refreshWaitMillis = 10_000,
            )

        fun auth(frames: List<String>): JsonObject =
            Json.parseToJsonElement(frames.last { it.startsWith("40") }.removePrefix("40")).jsonObject
    }

    private fun TestScope.connected(): Pair<Harness, Session> {
        val harness = Harness(this)
        harness.socket.connect()
        runCurrent()
        val session = harness.sessions.single()
        session.push("0{\"sid\":\"e\",\"upgrades\":[],\"pingInterval\":60000,\"pingTimeout\":60000,\"maxPayload\":1000000}")
        runCurrent()
        val auth = harness.auth(session.sent())
        assertEquals("token-1", auth["token"]!!.jsonPrimitive.content)
        assertEquals("server-1", auth["serverId"]!!.jsonPrimitive.content)
        assertEquals("mobile", auth["clientKind"]!!.jsonPrimitive.content)
        assertEquals("agent_activity_subscriptions_v1", auth["capabilities"]!!.jsonArray.single().jsonPrimitive.content)
        session.push("40{\"sid\":\"s-1\"}")
        runCurrent()
        assertEquals("connected:s-1:false", harness.recorder.calls.last())
        return harness to session
    }

    @Test
    fun roomCommandsGoOutWithTheBareChannelIdAndObjectsAsObjects() = runTest {
        val (harness, session) = connected()
        harness.socket.emitString("join:channel", "c1")
        harness.socket.emitJson("presence:activity", "{\"active\":true,\"idleSeconds\":0}")
        harness.socket.emitJson("presence:activity", "[1]")
        runCurrent()
        assertEquals(listOf("42[\"join:channel\",\"c1\"]", "42[\"presence:activity\",{\"active\":true,\"idleSeconds\":0}]"), session.sent())
        assertTrue(harness.recorder.logs.any { it.startsWith("realtime_event_dropped reason=bad_emit_payload") })
    }

    @Test
    fun eventsArriveAsRawJsonAndAckRequestsAreAnswered() = runTest {
        val (harness, session) = connected()
        session.push("42[\"message:new\",{\"seq\":5}]")
        session.push("42[\"rooms:joined\"]")
        session.push("427[\"notification:desktop\",{\"title\":\"Hi\"}]")
        session.push("42[\"agent:seen\",{}]")
        runCurrent()
        assertEquals(
            listOf("event:message:new:{\"seq\":5}:-1", "event:rooms:joined:{}:-1", "event:notification:desktop:{\"title\":\"Hi\"}:7"),
            harness.recorder.calls.filter { it.startsWith("event:") },
        )
        assertTrue(harness.recorder.logs.any { it.contains("value=agent:seen") }, "an unlisted event is logged, not delivered")
        harness.socket.answerAck(7, "{\"socketId\":\"s-1\"}")
        harness.socket.answerAck(99, "{}")
        runCurrent()
        assertEquals(listOf("437[{\"socketId\":\"s-1\"}]"), session.sent())
        assertTrue(harness.recorder.logs.any { it.contains("ack_unknown_request ackId=99") })
    }

    @Test
    fun syncHorizonAckReportsItsPayloadOrWhyNot() = runTest {
        val (harness, session) = connected()
        val results = mutableListOf<Pair<String?, String>>()
        harness.socket.emitWithAck(
            "sync:horizon",
            10_000,
            object : RaftKmpRealtimeAckCallback {
                override fun onAckResult(payload: String?, status: String) {
                    results += payload to status
                }
            },
        )
        runCurrent()
        assertEquals(listOf("420[\"sync:horizon\"]"), session.sent())
        session.push("430[{\"horizon\":\"752\"}]")
        harness.socket.emitWithAck(
            "sync:horizon",
            10_000,
            object : RaftKmpRealtimeAckCallback {
                override fun onAckResult(payload: String?, status: String) {
                    results += payload to status
                }
            },
        )
        runCurrent()
        testScheduler.advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf<Pair<String?, String>>("{\"horizon\":\"752\"}" to "acked", null to "timeout"), results)
    }

    @Test
    fun aRejectedTokenWaitsForTheHostsRefreshedToken() = runTest {
        val (harness, session) = connected()
        session.push("41")
        runCurrent()
        testScheduler.advanceTimeBy(2_000)
        runCurrent()
        session.sent()
        session.push("44{\"message\":\"Invalid or expired token\"}")
        runCurrent()
        assertEquals("connect_error:InvalidOrExpiredToken:false:true", harness.recorder.calls.last { it.startsWith("connect_error") })
        assertEquals(emptyList(), session.sent(), "the retry waits for the refreshed token")
        harness.socket.updateToken("token-2")
        runCurrent()
        assertEquals("token-2", harness.auth(session.sent())["token"]!!.jsonPrimitive.content)
    }
}
