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
import kotlinx.serialization.json.JsonObject
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
    fun withoutCredentialsTheSocketWaitsForAKick() = runTest {
        val harness = SocketHarness(this, credentialsAvailable = false)
        harness.root.connect()
        runCurrent()
        harness.server.last.open()
        runCurrent()
        assertEquals(RaftSocketState.Failed(null), harness.root.state.value)
        assertTrue(harness.server.last.closed)
        harness.credentialsAvailable = true
        harness.client.kick("signed_in")
        runCurrent()
        harness.server.last.open()
        runCurrent()
        assertEquals("token-1", harness.token(harness.server.last.connectAuth(harness.server.last.receivedTexts())))
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
    fun kickEndsTheBackoffWaitAtOnce() = runTest {
        val harness = SocketHarness(this)
        val first = harness.connectRoot()
        harness.server.failNextConnects = 5
        first.fail()
        runCurrent()
        repeat(3) {
            advanceTimeBy(harness.listener.scheduledDelays.last() + 1)
            runCurrent()
        }
        val opened = harness.server.urls.size
        harness.client.kick("network_available")
        runCurrent()
        assertEquals(opened + 1, harness.server.urls.size, "the kick connects without waiting")
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
    fun aServerDisconnectReconnectsTheNamespaceWithAFreshToken() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        session.push("41")
        runCurrent()
        assertTrue("disconnected:io_server_disconnect" in harness.listener.calls)
        advanceTimeBy(harness.listener.scheduledDelays.last() + 1)
        runCurrent()
        assertEquals("token-2", harness.token(session.connectAuth(session.receivedTexts())))
        session.acceptConnect(sid = "sio-again")
        runCurrent()
        assertEquals("connected:sio-again:reconnect=true", harness.listener.calls.last())
        assertEquals(1, harness.server.sessions.size)
    }

    @Test
    fun disconnectLeavesTheNamespaceAndClosesTheEngine() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        harness.root.disconnect()
        runCurrent()
        assertEquals(listOf("41"), session.receivedTexts())
        assertTrue(session.closed)
        assertEquals(RaftSocketState.Idle, harness.root.state.value)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, harness.server.sessions.size)
        assertEquals("disconnected:io_client_disconnect", harness.listener.calls.last())
    }

    @Test
    fun namespacesConnectWithTheirOwnAuthOverOneEngine() = runTest {
        val harness = SocketHarness(this)
        val adminListener = RecordingSocketListener()
        val admin =
            harness.client.socket(
                "admin",
                RaftSocketNamespaceOptions(
                    authProvider = { JsonObject(mapOf("role" to JsonPrimitive("admin"))) },
                    listener = adminListener,
                    retryOnConnectError = false,
                ),
            )
        assertEquals("/admin", admin.namespace)
        val session = harness.connectRoot()
        admin.connect()
        runCurrent()
        assertEquals(JsonPrimitive("admin"), session.connectAuth(session.receivedTexts(), namespace = "/admin")["role"])
        assertEquals(1, harness.server.sessions.size)

        session.push("44/admin,{\"message\":\"Not authorized\"}")
        runCurrent()
        assertTrue("connect_error:Unknown:retry=false" in adminListener.calls)
        assertIs<RaftSocketState.Failed>(admin.state.value)
        assertIs<RaftSocketState.Connected>(harness.root.state.value, "another namespace's rejection leaves / connected")
        assertFalse(session.closed)

        harness.client.kick("retry_admin")
        runCurrent()
        assertTrue(session.receivedTexts().any { it.startsWith("40/admin,") })
        session.push("40/admin,{\"sid\":\"adm-1\"}")
        session.push("42/admin,[\"admin:event\",1]")
        session.push("42[\"message:new\",{}]")
        runCurrent()
        assertEquals(listOf("admin:event"), adminListener.events.map { it.name })
        assertEquals(listOf("message:new"), harness.listener.events.map { it.name })

        admin.emit("ping:admin")
        harness.root.emit("ping:root")
        runCurrent()
        assertEquals(listOf("42/admin,[\"ping:admin\"]", "42[\"ping:root\"]"), session.receivedTexts())

        admin.disconnect()
        runCurrent()
        assertEquals(listOf("41/admin,"), session.receivedTexts())
        assertFalse(session.closed, "/ still uses the engine")
        assertEquals("disconnected:io_client_disconnect", adminListener.calls.last())
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
    fun eventsBufferedForARejectedConnectAreDroppedWithAReason() = runTest {
        val harness = SocketHarness(this)
        harness.root.connect()
        runCurrent()
        val session = harness.server.last
        session.open()
        runCurrent()
        session.push("42[\"message:new\",{}]")
        session.push("44{\"message\":\"SSO_REQUIRED\"}")
        runCurrent()
        assertEquals(emptyList(), harness.listener.events)
        assertTrue("dropped:message:new:buffered_connect_error" in harness.listener.calls, harness.listener.calls.toString())
    }

    @Test
    fun aSocketWithoutPidNeverSendsRecoveryFields() = runTest {
        val harness = SocketHarness(this)
        val first = harness.connectRoot()
        first.push("42[\"message:new\",{},\"looks-like-offset\"]")
        runCurrent()
        first.fail()
        runCurrent()
        advanceTimeBy(harness.listener.scheduledDelays.last() + 1)
        runCurrent()
        harness.server.last.open()
        runCurrent()
        val auth = harness.server.last.connectAuth(harness.server.last.receivedTexts())
        assertNull(auth["pid"])
        assertNull(auth["offset"])
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
