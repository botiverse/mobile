package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class RaftSocketIoClientHandshakeTest {
    @Test
    fun handshakeSendsTheRaftAuthOnlyAfterTheOpenPacket() = runTest {
        val harness = SocketHarness(this)
        harness.root.connect()
        runCurrent()

        val session = harness.server.last
        assertEquals("wss://api.raft.build/socket.io/?EIO=4&transport=websocket", session.url)
        assertEquals(emptyList(), session.receivedTexts(), "nothing is sent before the Engine.IO open packet")

        session.open(sid = "eio-7")
        runCurrent()
        val auth = session.connectAuth(session.receivedTexts())
        assertEquals("token-1", harness.token(auth))
        assertEquals(JsonPrimitive("server-1"), auth["serverId"])
        assertEquals(JsonPrimitive("mobile"), auth["clientKind"])
        assertEquals(JsonArray(listOf(JsonPrimitive("agent_activity_subscriptions_v1"))), auth["capabilities"])
        assertEquals(JsonPrimitive("kmp"), auth["clientImpl"])

        session.acceptConnect(sid = "sio-42")
        runCurrent()
        val connection = assertIs<RaftSocketState.Connected>(harness.root.state.value).connection
        assertEquals(RaftSocketConnection("/", 1, "sio-42", "eio-7", attempt = 1, reconnect = false, recovered = false), connection)
        assertEquals(listOf("connecting:1", "connected:sio-42:reconnect=false"), harness.listener.calls)
        assertIs<RaftSocketEngineState.Open>(harness.client.engineState.value)
    }

    @Test
    fun everyServerPingIsAnsweredWithAPongCarryingItsData() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        session.push("2")
        session.push("2probe")
        runCurrent()
        assertEquals(listOf("3", "3probe"), session.receivedTexts())
    }

    @Test
    fun theHeartbeatDeadlineIsPingIntervalPlusPingTimeoutFromTheOpenPacket() = runTest {
        // Two different servers: the deadline follows each open packet, not a local constant.
        for ((interval, timeout) in listOf(4_000L to 2_000L, 300L to 200L)) {
            val harness = SocketHarness(this)
            val session = harness.connectRoot { open(pingInterval = interval, pingTimeout = timeout) }
            session.push("2")
            runCurrent()
            advanceTimeBy(interval + timeout - 1)
            runCurrent()
            assertIs<RaftSocketState.Connected>(harness.root.state.value, "interval=$interval: still alive just before the deadline")
            session.push("2") // A ping restarts the deadline.
            runCurrent()
            advanceTimeBy(interval + timeout - 1)
            runCurrent()
            assertFalse("disconnected:ping_timeout" in harness.listener.calls)
            advanceTimeBy(2)
            runCurrent()
            assertTrue("disconnected:ping_timeout" in harness.listener.calls, "interval=$interval calls=${harness.listener.calls}")
            assertTrue(session.closed)
            harness.client.close()
            runCurrent()
        }
    }

    @Test
    fun aMissingOpenFieldFallsBackToTheServerDefaultAndIsLogged() = runTest {
        val harness = SocketHarness(this)
        harness.connectRoot { push("0{\"sid\":\"eio-x\",\"upgrades\":[\"websocket\"]}") }
        assertTrue(
            harness.logs.any { it.startsWith("realtime_socket_open_fallback") && it.endsWith("fields=pingInterval,pingTimeout,maxPayload") },
            harness.logs.toString(),
        )
        advanceTimeBy(EngineIoCodec.DEFAULT_PING_INTERVAL_MILLIS + EngineIoCodec.DEFAULT_PING_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertIs<RaftSocketState.Connected>(harness.root.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertTrue("disconnected:ping_timeout" in harness.listener.calls)
    }

    @Test
    fun maxPayloadFromTheOpenPacketBoundsEveryFrame() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot { open(maxPayload = 300) }
        harness.root.emit("small", listOf(JsonPrimitive("ok")))
        harness.root.emit("big", listOf(JsonPrimitive("x".repeat(320))))
        harness.root.emit("bin", listOf(RaftSocketBinary.placeholder(0)), listOf(RaftSocketBinary(ByteArray(301))))
        runCurrent()
        assertEquals(listOf("42[\"small\",\"ok\"]"), session.receivedTexts())
        assertTrue("dropped:big:oversize" in harness.listener.calls)
        assertTrue("dropped:bin:oversize" in harness.listener.calls)
    }

    @Test
    fun openOrConnectThatNeverComesTimesOut() = runTest {
        val harness = SocketHarness(this)
        harness.root.connect()
        runCurrent()
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue(harness.logs.any { it.contains("reason=connect_timeout") && it.contains("no_open_packet") }, harness.logs.toString())

        // The next engine opens but the namespace CONNECT is never answered: the namespace retries on it.
        advanceTimeBy(2_000)
        runCurrent()
        val session = harness.server.last
        session.open(pingInterval = 100_000, pingTimeout = 100_000)
        runCurrent()
        assertEquals(1, session.receivedTexts().count { it.startsWith("40") })
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue("scheduled:connect_timeout" in harness.listener.calls, harness.listener.calls.toString())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, session.receivedTexts().count { it.startsWith("40") }, "the CONNECT is retried on the same WebSocket")
    }

    @Test
    fun aBadOpenPacketIsAProtocolError() = runTest {
        val harness = SocketHarness(this)
        harness.root.connect()
        runCurrent()
        harness.server.last.push("0{\"upgrades\":[]}")
        runCurrent()
        assertTrue(harness.logs.any { it.contains("reason=protocol_error") && it.contains("bad_open_packet") }, harness.logs.toString())
        assertTrue(harness.server.last.closed)
    }

    @Test
    fun engineClosePacketEndsTheConnection() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        session.push("1")
        runCurrent()
        assertTrue("disconnected:engine_close" in harness.listener.calls)
        assertTrue(session.closed)
    }
}
