package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class RaftSocketIoClientReconnectTest {
    @Test
    fun aRejectedTokenIsRefreshedAndRetriedAtOnceThenWithBackoff() = runTest {
        val harness = SocketHarness(this)
        harness.root.connect()
        runCurrent()
        val session = harness.server.last
        session.open()
        runCurrent()
        assertEquals("token-1", harness.token(session.connectAuth(session.receivedTexts())))

        session.push("44{\"message\":\"Invalid or expired token\"}")
        runCurrent()
        assertEquals(RaftSocketHandshakeErrorKind.InvalidOrExpiredToken, harness.listener.errors.single().kind)
        assertTrue("connect_error:InvalidOrExpiredToken:retry=true" in harness.listener.calls)
        // Immediately, on the same WebSocket, with a token the provider was told to refresh.
        assertEquals("token-2", harness.token(session.connectAuth(session.receivedTexts())))
        val refreshRequest = harness.authRequests[1]
        assertTrue(refreshRequest.forceRefresh)
        assertEquals(2, refreshRequest.attempt)

        session.push("44{\"message\":\"Invalid or expired token\"}")
        runCurrent()
        assertEquals(emptyList(), session.receivedTexts(), "a second rejection backs off")
        val delay = harness.listener.scheduledDelays.last()
        assertTrue(delay in 500L..1_500L, "delay=$delay")
        advanceTimeBy(delay + 1)
        runCurrent()
        assertEquals("token-3", harness.token(session.connectAuth(session.receivedTexts())))
        session.acceptConnect()
        runCurrent()
        assertIs<RaftSocketState.Connected>(harness.root.state.value)
        assertEquals(1, harness.server.sessions.size, "auth retries never reopen the WebSocket")
    }

    @Test
    fun terminalRejectionsWaitForAKickAndCloseTheEngine() = runTest {
        for (message in listOf("SSO_REQUIRED", "Not a member of this server")) {
            val harness = SocketHarness(this)
            harness.root.connect()
            runCurrent()
            val session = harness.server.last
            session.open()
            runCurrent()
            session.push("44{\"message\":\"$message\"}")
            runCurrent()
            assertIs<RaftSocketState.Failed>(harness.root.state.value)
            assertTrue(harness.listener.calls.any { it.startsWith("connect_error:") && it.endsWith("retry=false") })
            assertTrue(session.closed, "$message: nothing wants the engine any more")
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(1, harness.server.sessions.size, "$message: no retry without a kick")

            harness.client.kick("foreground")
            runCurrent()
            val retry = harness.server.last
            retry.open()
            runCurrent()
            assertEquals("token-2", harness.token(retry.connectAuth(retry.receivedTexts())))
            assertNull(harness.authRequests.last().lastError, "a kick starts over")
            retry.acceptConnect()
            runCurrent()
            assertIs<RaftSocketState.Connected>(harness.root.state.value)
            harness.client.close()
            runCurrent()
        }
    }

    @Test
    fun transportLossReconnectsWithGrowingBackoffAndAFreshTokenEachTime() = runTest {
        val harness = SocketHarness(this)
        val first = harness.connectRoot()
        harness.server.failNextConnects = 3
        first.fail()
        runCurrent()
        assertTrue("disconnected:transport_error" in harness.listener.calls)

        repeat(4) {
            val delay = harness.listener.scheduledDelays.last()
            advanceTimeBy(delay + 1)
            runCurrent()
        }
        val delays = harness.listener.scheduledDelays.take(4)
        assertEquals(4, delays.size, harness.listener.calls.toString())
        val bases = listOf(1_000L, 2_000L, 4_000L, 8_000L)
        delays.zip(bases).forEach { (delay, base) -> assertTrue(delay in base / 2..base * 3 / 2, "delay=$delay base=$base") }

        val second = harness.server.last
        second.open()
        runCurrent()
        assertEquals("token-2", harness.token(second.connectAuth(second.receivedTexts())))
        second.acceptConnect(sid = "sio-2")
        runCurrent()
        assertEquals("connected:sio-2:reconnect=true", harness.listener.calls.last())

        // After a success the backoff starts over.
        second.fail()
        runCurrent()
        assertTrue(harness.listener.scheduledDelays.last() in 500L..1_500L)
    }

    @Test
    fun forceReconnectReplacesTheLiveConnectionWithoutBackoff() = runTest {
        val harness = SocketHarness(this)
        val first = harness.connectRoot()
        harness.client.forceReconnect("heartbeat_silent")
        runCurrent()
        assertTrue(first.closed)
        assertTrue("disconnected:forced" in harness.listener.calls)
        assertEquals(2, harness.server.sessions.size)
    }

    @Test
    fun connectionStateRecoverySendsPidAndOffsetAndReportsRecovery() = runTest {
        val harness = SocketHarness(this)
        val first = harness.connectRoot(pid = "pid-1")
        first.push("42[\"message:new\",{\"seq\":1},\"offset-1\"]")
        first.push("42[\"message:new\",{\"seq\":2},\"offset-2\"]")
        runCurrent()
        first.fail()
        runCurrent()
        advanceTimeBy(harness.listener.scheduledDelays.last() + 1)
        runCurrent()
        val second = harness.server.last
        second.open()
        runCurrent()
        val auth = second.connectAuth(second.receivedTexts())
        assertEquals(JsonPrimitive("pid-1"), auth["pid"])
        assertEquals(JsonPrimitive("offset-2"), auth["offset"])
        assertEquals("token-2", harness.token(auth))
        second.acceptConnect(sid = "sio-r", pid = "pid-1")
        runCurrent()
        assertTrue(harness.listener.connections.last().recovered)

        // A server that could not recover hands out a new pid: not recovered, offset starts over.
        second.fail()
        runCurrent()
        advanceTimeBy(harness.listener.scheduledDelays.last() + 1)
        runCurrent()
        val third = harness.server.last
        third.open()
        runCurrent()
        third.received()
        third.acceptConnect(sid = "sio-n", pid = "pid-2")
        runCurrent()
        assertFalse(harness.listener.connections.last().recovered)
    }

    @Test
    fun eventsSentBeforeTheConnectAnswerAreDeliveredRightAfterIt() = runTest {
        // A recovering socket.io server replays the missed packets before its CONNECT answer.
        val harness = SocketHarness(this)
        val first = harness.connectRoot(pid = "pid-1")
        first.fail()
        runCurrent()
        advanceTimeBy(harness.listener.scheduledDelays.last() + 1)
        runCurrent()
        val second = harness.server.last
        second.open()
        runCurrent()
        second.push("42[\"message:new\",{\"seq\":7},\"offset-7\"]")
        second.push("42[\"heartbeat\",{\"seq\":7}]")
        runCurrent()
        assertEquals(emptyList(), harness.listener.events, "held until the socket is connected")
        second.acceptConnect(sid = "sio-1", pid = "pid-1")
        runCurrent()
        assertEquals(listOf("message:new", "heartbeat"), harness.listener.events.map { it.name })
        val connectedAt = harness.listener.calls.indexOfLast { it.startsWith("connected:") }
        assertTrue(connectedAt < harness.listener.calls.indexOf("event:message:new"), harness.listener.calls.toString())
        assertTrue(harness.listener.connections.last().recovered)
    }

    @Test
    fun closeStopsEverythingForGood() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        harness.client.close()
        runCurrent()
        assertTrue(session.closed)
        assertEquals(RaftSocketEngineState.Closed, harness.client.engineState.value)
        harness.root.connect()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, harness.server.sessions.size)
    }
}
