package build.raft.realtime.socket

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Conformance against a real socket.io 4.8.3 server (scripts/socketio-interop/server.mjs) through the
 * production Ktor OkHttp WebSocket. CI starts the server and sets RAFT_SOCKETIO_INTEROP_URL /
 * RAFT_SOCKETIO_INTEROP_RECOVERY_URL; without them the test is skipped (assumption), never green by default.
 *
 * Real time and real sockets, so no virtual clock: every wait is bounded and fails with what was
 * received so far (AGENTS.md unit-test rule: real-concurrency interop, justified here and in the PR).
 */
class RaftSocketIoInteropTest {
    private val url: String? = System.getenv("RAFT_SOCKETIO_INTEROP_URL")?.takeIf { it.isNotBlank() }
    private val recoveryUrl: String? = System.getenv("RAFT_SOCKETIO_INTEROP_RECOVERY_URL")?.takeIf { it.isNotBlank() }

    private class Probe(name: String) : RaftSocketIoListener, RaftSocketEngineListener {
        val label = name
        val signals = Channel<String>(Channel.UNLIMITED)
        val events = Channel<Pair<RaftSocketEvent, RaftSocketIncomingAck?>>(Channel.UNLIMITED)
        val opens = Channel<EngineIoOpen>(Channel.UNLIMITED)
        val connections = Channel<RaftSocketConnection>(Channel.UNLIMITED)
        val seen = mutableListOf<String>()

        override fun onConnected(connection: RaftSocketConnection) {
            connections.trySend(connection)
            record("connected")
        }

        override fun onEvent(connection: RaftSocketConnection, event: RaftSocketEvent, ack: RaftSocketIncomingAck?) {
            seen += "event:${event.name}"
            events.trySend(event to ack)
        }

        override fun onConnectError(error: RaftSocketHandshakeError, willRetry: Boolean) = record("connect_error:${error.kind.name}:${error.message}")

        override fun onDisconnected(connection: RaftSocketConnection, reason: RaftSocketDisconnectReason, detail: String) =
            record("disconnected:${reason.token}")

        override fun onOpen(open: EngineIoOpen, generation: Long) {
            opens.trySend(open)
        }

        private fun record(signal: String) {
            seen += signal
            signals.trySend(signal)
        }

        suspend fun signal(prefix: String): String =
            withTimeoutOrFail("$label signal $prefix") {
                while (true) {
                    val next = signals.receive()
                    if (next.startsWith(prefix)) return@withTimeoutOrFail next
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }

        suspend fun event(name: String): Pair<RaftSocketEvent, RaftSocketIncomingAck?> =
            withTimeoutOrFail("$label event $name") {
                while (true) {
                    val next = events.receive()
                    if (next.first.name == name) return@withTimeoutOrFail next
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }

        suspend fun <T> withTimeoutOrFail(what: String, block: suspend () -> T): T =
            try {
                withTimeout(WAIT) { block() }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("waited ${WAIT} for $what; seen=$seen", timeout)
            }
    }

    private fun session(
        baseUrl: String?,
        token: () -> String = { "good" },
        block: suspend Scenario.() -> Unit,
    ) {
        assumeTrue("RAFT_SOCKETIO_INTEROP_URL not set (CI starts scripts/socketio-interop/server.mjs)", baseUrl != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = createRaftRealtimeHttpClient()
        val probe = Probe("root")
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client =
            RaftSocketIoClient(
                config = RaftSocketIoConfig(baseUrl = baseUrl!!, reconnect = RaftReconnectPolicy(initialDelayMillis = 200, maxDelayMillis = 1_000)),
                connector = KtorRaftWebSocketConnector(http),
                parentScope = scope,
                // Printed so a red CI run shows the client's side of the conversation.
                logger = { line ->
                    logs += line
                    println("[interop] $line")
                },
                engineListener = probe,
            )
        val root =
            client.socket(
                "/",
                RaftSocketNamespaceOptions(
                    authProvider = { raftHandshakeAuth(token(), "server-1", "mobile", listOf("agent_activity_subscriptions_v1")) },
                    listener = probe,
                ),
            )
        try {
            runBlocking { Scenario(client, root, probe, logs).block() }
        } finally {
            client.close()
            runBlocking { delay(100) }
            http.close()
            scope.cancel()
        }
    }

    private class Scenario(
        val client: RaftSocketIoClient,
        val root: RaftSocketIoNamespace,
        val probe: Probe,
        val logs: List<String>,
    ) {
        suspend fun ack(block: ((RaftAckResult) -> Unit) -> Unit): RaftAckResult {
            val result = Channel<RaftAckResult>(1)
            block { result.trySend(it) }
            return probe.withTimeoutOrFail("ack") { result.receive() }
        }
    }

    @Test
    fun handshakeTakesHeartbeatAndMaxPayloadFromTheServerAndStaysAlive() = session(url) {
        root.connect()
        val open = probe.withTimeoutOrFail("open") { probe.opens.receive() }
        assertEquals(400L, open.pingIntervalMillis)
        assertEquals(300L, open.pingTimeoutMillis)
        assertEquals(10_000L, open.maxPayload)
        assertEquals(emptyList(), open.fallbacks)
        val connection = probe.withTimeoutOrFail("connect") { probe.connections.receive() }
        val (welcome, _) = probe.event("welcome")
        val payload = welcome.args.single().jsonObject
        assertEquals(connection.socketId, payload["id"]!!.jsonPrimitive.content, "the sid of 40{sid} is the server's socket.id")
        val auth = payload["auth"]!!.jsonObject
        assertEquals("good", auth["token"]!!.jsonPrimitive.content)
        assertEquals("mobile", auth["clientKind"]!!.jsonPrimitive.content)
        assertEquals("kmp", auth["clientImpl"]!!.jsonPrimitive.content)

        // Several heartbeat windows (400 + 300 ms): the server closes a client that does not pong.
        delay(2_500)
        assertIs<RaftSocketState.Connected>(root.state.value, "seen=${probe.seen}")
        assertFalse(probe.seen.any { it.startsWith("disconnected") }, "seen=${probe.seen}")
    }

    @Test
    fun eventsAndAcksInBothDirections() = session(url) {
        root.connect()
        probe.event("welcome")
        root.emit("echo", listOf(JsonPrimitive("hi"), buildJsonObject { put("n", 1) }))
        val (echo, _) = probe.event("echo")
        assertEquals(listOf<JsonElement>(JsonPrimitive("hi"), buildJsonObject { put("n", 1) }), echo.args)

        val acked = ack { done -> root.emitWithAck("echo", listOf(JsonPrimitive(7), JsonPrimitive("x")), onResult = done) }
        assertEquals(RaftAckResult.Acked(listOf(JsonPrimitive(7), JsonPrimitive("x"))), acked)

        // The server asks the client for an ack (like Raft's notification:desktop) and reports what it got.
        root.emit("ask-ack")
        val (question, ackHandle) = probe.event("needs-ack")
        assertEquals(42, question.args.single().jsonObject["question"]!!.jsonPrimitive.int)
        ackHandle!!.send(buildJsonObject { put("socketId", "answer") })
        val (result, _) = probe.event("ack-result")
        val body = result.args.single().jsonObject
        assertEquals(false, body["timedOut"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("answer", body["response"]!!.jsonObject["socketId"]!!.jsonPrimitive.content)
    }

    @Test
    fun binaryEventsAndAcksRoundTrip() = session(url) {
        root.connect()
        probe.event("welcome")
        val bytes = byteArrayOf(10, 20, 30, -1)
        val acked =
            ack { done ->
                root.emitWithAck(
                    "binary-echo",
                    listOf(buildJsonObject { put("data", RaftSocketBinary.placeholder(0)) }),
                    listOf(RaftSocketBinary(bytes)),
                    onResult = done,
                )
            }
        val binaryAck = assertIs<RaftAckResult.Acked>(acked)
        assertEquals(4, binaryAck.args[0].jsonObject["received"]!!.jsonPrimitive.int)
        assertEquals(0, RaftSocketBinary.placeholderIndex(binaryAck.args[1]))
        assertEquals(RaftSocketBinary(bytes.reversedArray()), binaryAck.attachments.single())

        val (binary, _) = probe.event("binary")
        val data = binary.args.single().jsonObject["data"]!!
        assertEquals(RaftSocketBinary(byteArrayOf(1, 2, 3, 250.toByte())), binary.attachments[RaftSocketBinary.placeholderIndex(data)!!])
    }

    @Test
    fun namespacesHaveTheirOwnAuthAndRejections() = session(url) {
        root.connect()
        probe.event("welcome")
        val rejected = Probe("admin-rejected")
        val denied =
            client.socket("/admin", RaftSocketNamespaceOptions(authProvider = { JsonObject(emptyMap()) }, listener = rejected, retryOnConnectError = false))
        denied.connect()
        assertEquals("connect_error:Unknown:Not authorized", rejected.signal("connect_error"))
        assertIs<RaftSocketState.Connected>(root.state.value, "the root namespace is unaffected")
        denied.disconnect()

        // A second client, so the namespace is fresh: /admin with the right auth.
        val admin = Probe("admin")
        val adminClient =
            RaftSocketIoClient(RaftSocketIoConfig(baseUrl = url!!), KtorRaftWebSocketConnector(createRaftRealtimeHttpClient()), CoroutineScope(Dispatchers.Default))
        try {
            val adminSocket =
                adminClient.socket("/admin", RaftSocketNamespaceOptions(authProvider = { buildJsonObject { put("role", "admin") } }, listener = admin))
            adminSocket.connect()
            admin.event("admin-welcome")
            val result = Channel<RaftAckResult>(1)
            adminSocket.emitWithAck("echo", listOf(JsonPrimitive("x"))) { result.trySend(it) }
            assertEquals(
                RaftAckResult.Acked(listOf(buildJsonObject { put("admin", "x") })),
                admin.withTimeoutOrFail("admin ack") { result.receive() },
            )
        } finally {
            adminClient.close()
        }
    }

    @Test
    fun raftHandshakeErrorsAreTypedAndTerminalOnesStop() {
        var token = "expired"
        session(url, token = { token }) {
            root.connect()
            assertEquals("connect_error:InvalidOrExpiredToken:Invalid or expired token", probe.signal("connect_error"))
            token = "good"
            probe.event("welcome") // The immediate refresh retry used the new token.
        }
        session(url, token = { "sso" }) {
            root.connect()
            assertEquals("connect_error:SsoRequired:SSO_REQUIRED", probe.signal("connect_error"))
            delay(1_000)
            assertIs<RaftSocketState.Failed>(root.state.value)
            assertEquals(1, probe.seen.count { it.startsWith("connect_error") }, "terminal: no retry; seen=${probe.seen}")
        }
    }

    @Test
    fun serverDisconnectAndTransportLossReconnect() = session(url) {
        root.connect()
        probe.event("welcome")
        root.emit("disconnect-me")
        assertEquals("disconnected:io_server_disconnect", probe.signal("disconnected"))
        probe.event("welcome")
        root.emit("drop-transport")
        probe.signal("disconnected")
        val again = probe.event("welcome")
        assertTrue(probe.seen.count { it == "connected" } >= 3, "seen=${probe.seen}")
        assertIs<RaftSocketState.Connected>(root.state.value, "${again.first}")
    }

    @Test
    fun connectionStateRecoveryReplaysMissedEventsOverDeflate() = session(recoveryUrl) {
        root.connect()
        val first = probe.withTimeoutOrFail("connect") { probe.connections.receive() }
        assertFalse(first.recovered)
        probe.event("welcome")
        root.emit("drop-transport") // The server emits "missed" while this socket is away.
        probe.signal("disconnected")
        val second = probe.withTimeoutOrFail("reconnect") { probe.connections.receive() }
        assertTrue(second.recovered, "seen=${probe.seen} logs=${logs.takeLast(20)}")
        assertEquals(first.socketId, second.socketId, "a recovered session keeps its socket id")
        val (missed, _) = probe.event("missed")
        assertEquals(1, missed.args.first().jsonObject["n"]!!.jsonPrimitive.int)
    }

    private companion object {
        val WAIT = 10.seconds
    }
}
