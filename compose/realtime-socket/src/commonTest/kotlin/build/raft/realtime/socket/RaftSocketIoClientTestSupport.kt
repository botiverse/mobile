package build.raft.realtime.socket

import kotlin.random.Random
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Records every namespace callback as a short line, plus the acks the server asked for. */
internal class RecordingSocketListener : RaftSocketIoListener {
    val calls = mutableListOf<String>()
    val events = mutableListOf<RaftSocketEvent>()
    val acks = mutableListOf<RaftSocketIncomingAck>()
    val connections = mutableListOf<RaftSocketConnection>()
    val errors = mutableListOf<RaftSocketHandshakeError>()
    val scheduledDelays = mutableListOf<Long>()

    override fun onConnecting(attempt: Int) {
        calls += "connecting:$attempt"
    }

    override fun onConnected(connection: RaftSocketConnection) {
        connections += connection
        calls += "connected:${connection.socketId}:reconnect=${connection.reconnect}"
    }

    override fun onEvent(connection: RaftSocketConnection, event: RaftSocketEvent, ack: RaftSocketIncomingAck?) {
        events += event
        ack?.let { acks += it }
        calls += "event:${event.name}"
    }

    override fun onConnectError(error: RaftSocketHandshakeError, willRetry: Boolean) {
        errors += error
        calls += "connect_error:${error.kind.name}:retry=$willRetry"
    }

    override fun onDisconnected(connection: RaftSocketConnection, reason: RaftSocketDisconnectReason, detail: String) {
        calls += "disconnected:${reason.token}"
    }

    override fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: RaftSocketDisconnectReason) {
        scheduledDelays += delayMillis
        calls += "scheduled:${reason.token}"
    }

    override fun onEventDropped(name: String, reason: String) {
        calls += "dropped:$name:$reason"
    }
}

/** A client on the test scheduler with a root namespace whose auth provider hands out token-1, token-2, ... */
internal class SocketHarness(
    private val test: TestScope,
    allowedEvents: Set<String>? = setOf("message:new", "notification:desktop", "heartbeat", "file"),
    config: RaftSocketIoConfig = RaftSocketIoConfig(baseUrl = "https://api.raft.build"),
    var credentialsAvailable: Boolean = true,
) {
    val server = FakeSocketIoServer()
    val logs = mutableListOf<String>()
    val listener = RecordingSocketListener()
    val authRequests = mutableListOf<RaftSocketAuthRequest>()
    private var nextToken = 1

    val client =
        RaftSocketIoClient(
            config = config,
            connector = server,
            parentScope = test.backgroundScope,
            dispatcher = StandardTestDispatcher(test.testScheduler),
            logger = { logs += it },
            random = Random(7),
        )

    val root: RaftSocketIoNamespace =
        client.socket(
            "/",
            RaftSocketNamespaceOptions(
                authProvider = { request ->
                    authRequests += request
                    if (credentialsAvailable) {
                        raftHandshakeAuth("token-${nextToken++}", "server-1", "mobile", listOf("agent_activity_subscriptions_v1"))
                    } else {
                        null
                    }
                },
                listener = listener,
                allowedEvents = allowedEvents,
            ),
        )

    /** Connects the root namespace through open + CONNECT; returns the session. */
    fun connectRoot(sid: String = "sio-1", pid: String? = null, open: FakeServerSession.() -> Unit = { open() }): FakeServerSession {
        root.connect()
        test.runCurrent()
        val session = server.last
        open.invoke(session)
        test.runCurrent()
        session.acceptConnect(sid = sid, pid = pid)
        test.runCurrent()
        check(root.state.value is RaftSocketState.Connected) { "root did not connect: ${root.state.value}, calls=${listener.calls}" }
        session.received()
        return session
    }

    fun token(auth: JsonObject): String = (auth["token"] as JsonPrimitive).content

    fun diagnostics(): RaftSocketDiagnostics {
        var result: RaftSocketDiagnostics? = null
        client.diagnostics { result = it }
        test.runCurrent()
        return result ?: error("diagnostics never answered")
    }
}
