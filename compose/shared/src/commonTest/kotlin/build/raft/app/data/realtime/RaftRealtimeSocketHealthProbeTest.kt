package build.raft.app.data.realtime

import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.network.RaftTransportFailureKind
import build.raft.realtime.socket.RaftSocketDisconnectReason
import build.raft.realtime.socket.RaftSocketHandshakeErrorKind
import build.raft.realtime.socket.RaftWebSocketConnector
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The socket side of `mobile.realtime.session_health` (RaftRealtimeSessionHealth.kt): the iOS facade
 * ([RaftKmpRealtimeSocket]) on the real common client, against a scripted server, reporting through
 * its [RaftRealtimeSocketHealthProbe] into a collector on the test's virtual clock. What the client
 * goes through becomes the window's counts and its `last_error` code.
 */
class RaftRealtimeSocketHealthProbeTest {
    private object Silent : RaftKmpRealtimeSocketListener {
        override fun onConnected(socketId: String, reconnect: Boolean) = Unit

        override fun onDisconnected(reason: String) = Unit

        override fun onEvent(eventName: String, rawPayload: String, ackId: Long) = Unit

        override fun onConnectError(kind: String, message: String, terminal: Boolean, requiresTokenRefresh: Boolean) = Unit

        override fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: String) = Unit

        override fun onLog(message: String) = Unit
    }

    /** [openFailures]: what the WebSocket connector throws, one per attempt, before it connects. */
    private class Harness(test: TestScope, private val openFailures: MutableList<Throwable> = mutableListOf()) {
        val sessions = mutableListOf<RaftScriptedWebSocketSession>()
        val spans = mutableListOf<RaftWebSpanRecord>()
        val collector =
            RaftRealtimeSessionHealthCollector(
                nowMillis = { test.testScheduler.currentTime },
                nowEpochMillis = { 1_794_391_200_000L + test.testScheduler.currentTime },
                emit = { _, span -> spans += span },
            )
        val socket =
            RaftKmpRealtimeSocket(
                socketUrl = "https://api.raft.build",
                serverId = "server-1",
                token = "token-1",
                clientKind = "mobile",
                capabilities = listOf("agent_activity_subscriptions_v1"),
                allowedEvents = listOf("rooms:joined", "message:new", "sync:resume:response"),
                listener = Silent,
                connector =
                    RaftWebSocketConnector { _, _ ->
                        openFailures.removeFirstOrNull()?.let { throw it }
                        RaftScriptedWebSocketSession().also { sessions += it }
                    },
                parentScope = test.backgroundScope,
                dispatcher = StandardTestDispatcher(test.testScheduler),
                refreshWaitMillis = 10_000,
                health = collector,
            )

        init {
            collector.onSignedIn("server-1", "user-1")
        }

        /** Signs out (the window ends) and returns the window's attributes. */
        fun endWindow(): JsonObject {
            collector.onSignedOut()
            return spans.single().attrs
        }
    }

    /** The server opens the Engine.IO session on [session] and accepts its CONNECT. */
    private fun TestScope.serve(session: RaftScriptedWebSocketSession, sid: String) {
        session.push(OpenPacket)
        runCurrent()
        session.push("40{\"sid\":\"$sid\"}")
        runCurrent()
    }

    /**
     * Virtual time until the client has opened [count] WebSockets (each retry waits out its backoff), so
     * the next frame is served long before the client's 20 s open timeout.
     */
    private fun TestScope.awaitSessions(h: Harness, count: Int) {
        repeat(600) {
            if (h.sessions.size >= count) return
            testScheduler.advanceTimeBy(100)
            runCurrent()
        }
        error("waited 60 s for $count WebSockets, the client opened ${h.sessions.size}")
    }

    @Test
    fun aServerCloseIsADropAndEachReconnectCountsItsFirstResumeAnswer() = runTest {
        val h = Harness(this)
        h.socket.connect()
        runCurrent()
        serve(h.sessions[0], "s-1")
        h.sessions[0].push("42[\"message:new\",{\"seq\":5}]")
        // Not subscribed, then a packet that does not decode: both dropped by the client.
        h.sessions[0].push("42[\"agent:seen\",{}]")
        h.sessions[0].push("42[\"message:new\",")
        testScheduler.advanceTimeBy(10_000)
        runCurrent()
        h.sessions[0].closeFromServer()
        runCurrent()
        awaitSessions(h, 2)
        serve(h.sessions[1], "s-2")
        h.sessions[1].push("42[\"sync:resume:response\",{\"messages\":[],\"currentSeq\":7,\"hasMore\":false,\"horizonReset\":true}]")
        h.sessions[1].push("42[\"sync:resume:response\",{\"messages\":[],\"currentSeq\":7,\"hasMore\":false}]")
        runCurrent()
        h.sessions[1].closeFromServer()
        runCurrent()
        awaitSessions(h, 3)
        serve(h.sessions[2], "s-3")
        h.sessions[2].push("42[\"sync:resume:response\",{\"messages\":[],\"currentSeq\":9,\"hasMore\":false}]")
        runCurrent()

        val attrs = h.endWindow()
        assertEquals(3L, attrs.number("connects"))
        assertEquals(2L, attrs.number("reconnects"))
        assertEquals(2L, attrs.number("reconnect_attempts"))
        assertEquals(0L, attrs.number("handshake_failures"), "a retry after a drop is no failed attempt")
        assertEquals("closed_by_server", attrs.text("last_error"))
        assertEquals(1L, attrs.number("resume_fallback"), "horizonReset; the second answer on that socket does not count")
        assertEquals(1L, attrs.number("resume_ok"))
        assertEquals(6L, attrs.number("events_received"))
        assertEquals(2L, attrs.number("events_dropped"))
        assertEquals(attrs.number("window_ms"), attrs.number("connected_ms") + attrs.number("disconnected_ms"))
        assertTrue(attrs.number("disconnected_ms") > 0L, "the reconnect backoff is time without a connection")
    }

    @Test
    fun aRefusedHandshakeIsAFailedAttemptNamedByWhatTheServerSaid() = runTest {
        val h = Harness(this)
        h.socket.connect()
        runCurrent()
        val session = h.sessions.single()
        session.push(OpenPacket)
        runCurrent()
        session.push("44{\"message\":\"Invalid or expired token\"}")
        runCurrent()
        // The immediate retry waits for the host's refreshed token.
        h.socket.updateToken("token-2")
        runCurrent()
        session.push("44{\"message\":\"Not a member of this server\"}")
        runCurrent()

        val attrs = h.endWindow()
        assertEquals(2L, attrs.number("handshake_failures"))
        assertEquals(1L, attrs.number("reconnect_attempts"), "the token retry; a terminal refusal waits")
        assertEquals(0L, attrs.number("connects"))
        assertEquals("http_4xx", attrs.text("last_error"))
    }

    @Test
    fun aWebSocketThatCannotOpenIsAFailedAttemptNamedByTheTransport() = runTest {
        val h =
            Harness(
                this,
                mutableListOf<Throwable>(
                    IllegalStateException("upgrade refused"),
                    HttpRequestTimeoutException("wss://api.raft.build/socket.io/", 10_000L),
                ),
            )
        h.socket.connect()
        runCurrent()
        awaitSessions(h, 1)
        serve(h.sessions.single(), "s-1")

        val attrs = h.endWindow()
        assertEquals(2L, attrs.number("handshake_failures"))
        assertEquals(2L, attrs.number("reconnect_attempts"))
        assertEquals("timeout", attrs.text("last_error"), "the last of unknown, then timeout")
        assertEquals(1L, attrs.number("connects"))
        assertEquals(0L, attrs.number("reconnects"), "failures before the first connect are no drop")
    }

    @Test
    fun everyClientSignalHasOneLastErrorCode() {
        assertEquals(
            mapOf(
                RaftSocketHandshakeErrorKind.AuthenticationRequired to "http_401",
                RaftSocketHandshakeErrorKind.InvalidTokenType to "http_401",
                RaftSocketHandshakeErrorKind.InvalidOrExpiredToken to "http_401",
                RaftSocketHandshakeErrorKind.AuthenticationChanged to "http_401",
                RaftSocketHandshakeErrorKind.NotServerMember to "http_4xx",
                RaftSocketHandshakeErrorKind.SsoRequired to "http_4xx",
                RaftSocketHandshakeErrorKind.Unknown to "unknown",
            ),
            RaftSocketHandshakeErrorKind.entries.associateWith { it.realtimeHealthError().wire },
        )
        assertEquals(
            mapOf(
                RaftSocketDisconnectReason.TransportError to "network",
                RaftSocketDisconnectReason.TransportClosed to "closed_by_server",
                RaftSocketDisconnectReason.PingTimeout to "timeout",
                RaftSocketDisconnectReason.ServerDisconnect to "closed_by_server",
                RaftSocketDisconnectReason.EngineClose to "closed_by_server",
                RaftSocketDisconnectReason.ConnectTimeout to "timeout",
                RaftSocketDisconnectReason.HandshakeRejected to "unknown",
                RaftSocketDisconnectReason.ProtocolError to "unknown",
                RaftSocketDisconnectReason.Forced to null,
                RaftSocketDisconnectReason.ClientDisconnect to null,
                RaftSocketDisconnectReason.Stopped to null,
                RaftSocketDisconnectReason.NoCredentials to null,
            ),
            RaftSocketDisconnectReason.entries.associateWith { it.realtimeHealthError()?.wire },
        )
        assertEquals(
            mapOf(
                RaftTransportFailureKind.TIMEOUT to "timeout",
                RaftTransportFailureKind.DNS to "dns",
                RaftTransportFailureKind.CONNECT to "network",
                RaftTransportFailureKind.TLS to "tls",
                RaftTransportFailureKind.CANCELLED to null,
                RaftTransportFailureKind.UNKNOWN to "unknown",
            ),
            RaftTransportFailureKind.entries.associateWith { it.realtimeHealthError()?.wire },
        )
    }
}

private const val OpenPacket = "0{\"sid\":\"e\",\"upgrades\":[],\"pingInterval\":60000,\"pingTimeout\":60000,\"maxPayload\":1000000}"

private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
