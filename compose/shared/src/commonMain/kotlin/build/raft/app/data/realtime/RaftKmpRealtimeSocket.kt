package build.raft.app.data.realtime

import build.raft.realtime.socket.KtorRaftWebSocketConnector
import build.raft.realtime.socket.RaftAckResult
import build.raft.realtime.socket.RaftSocketConnection
import build.raft.realtime.socket.RaftSocketDisconnectReason
import build.raft.realtime.socket.RaftSocketEvent
import build.raft.realtime.socket.RaftSocketHandshakeError
import build.raft.realtime.socket.RaftSocketIncomingAck
import build.raft.realtime.socket.RaftSocketIoClient
import build.raft.realtime.socket.RaftSocketIoConfig
import build.raft.realtime.socket.RaftSocketIoListener
import build.raft.realtime.socket.RaftSocketNamespaceOptions
import build.raft.realtime.socket.RaftWebSocketConnector
import build.raft.realtime.socket.createRaftRealtimeHttpClient
import build.raft.realtime.socket.raftHandshakeAuth
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Callbacks of [RaftKmpRealtimeSocket], on the socket's own serial thread (a host hops to its queue).
 * Plain types only, so Swift implements it directly.
 */
interface RaftKmpRealtimeSocketListener {
    fun onConnected(socketId: String, reconnect: Boolean)

    fun onDisconnected(reason: String)

    /** [rawPayload] is the event's first argument as JSON (`{}` when it had none); [ackId] -1 = no ack asked. */
    fun onEvent(eventName: String, rawPayload: String, ackId: Long)

    /** The handshake was rejected; [kind] is a RaftSocketHandshakeErrorKind name. */
    fun onConnectError(kind: String, message: String, terminal: Boolean, requiresTokenRefresh: Boolean)

    fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: String)

    fun onLog(message: String)
}

/** The result of [RaftKmpRealtimeSocket.emitWithAck] (an interface, so Swift implements it as a class). */
interface RaftKmpRealtimeAckCallback {
    /** [payload]: the ack's first argument as JSON, null when none; [status]: `acked`, `timeout` or `not_delivered:<reason>`. */
    fun onAckResult(payload: String?, status: String)
}

/**
 * One server's realtime socket on the common client (`:realtime-socket`), shaped for a platform host
 * (the iOS host, RaftIosRealtimeHost.swift).
 *
 * - Handshake `auth`: Raft's `{ token, serverId, clientKind, capabilities, clientImpl }`, rebuilt from the
 *   latest [updateToken] before every attempt. After a token rejection the attempt waits up to
 *   [refreshWaitMillis] for the host's refreshed token (the host requests the refresh from
 *   [RaftKmpRealtimeSocketListener.onConnectError]).
 * - Events outside [allowedEvents] are logged and counted by the client, never delivered silently.
 * - Reconnects itself (backoff with jitter); [kick] retries now (foreground, network back).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftKmpRealtimeSocket internal constructor(
    socketUrl: String,
    private val serverId: String,
    token: String,
    private val clientKind: String,
    private val capabilities: List<String>,
    allowedEvents: List<String>,
    private val listener: RaftKmpRealtimeSocketListener,
    connector: RaftWebSocketConnector,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val refreshWaitMillis: Long,
) {
    /** Host constructor: production connector (Darwin on iOS, OkHttp on JVM) on its own serial thread. */
    constructor(
        socketUrl: String,
        serverId: String,
        token: String,
        clientKind: String,
        capabilities: List<String>,
        allowedEvents: List<String>,
        listener: RaftKmpRealtimeSocketListener,
    ) : this(
        socketUrl = socketUrl,
        serverId = serverId,
        token = token,
        clientKind = clientKind,
        capabilities = capabilities,
        allowedEvents = allowedEvents,
        listener = listener,
        connector = KtorRaftWebSocketConnector(sharedRealtimeHttpClient),
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        dispatcher = Dispatchers.Default.limitedParallelism(1),
        refreshWaitMillis = DEFAULT_REFRESH_WAIT_MILLIS,
    )

    @Volatile private var token: String = token
    @Volatile private var tokenUpdate: CompletableDeferred<String>? = null
    private val pendingIncomingAcks = linkedMapOf<Long, RaftSocketIncomingAck>()

    private val client =
        RaftSocketIoClient(
            config = RaftSocketIoConfig(baseUrl = socketUrl),
            connector = connector,
            parentScope = parentScope,
            dispatcher = dispatcher,
            logger = { listener.onLog(it) },
        )

    private val socket =
        client.socket(
            "/",
            RaftSocketNamespaceOptions(
                authProvider = { request ->
                    val current =
                        if (request.forceRefresh) {
                            val update = CompletableDeferred<String>().also { tokenUpdate = it }
                            withTimeoutOrNull(refreshWaitMillis) { update.await() } ?: token
                        } else {
                            token
                        }
                    tokenUpdate = null
                    current.takeIf { it.isNotBlank() }?.let { raftHandshakeAuth(it, serverId, clientKind, capabilities) }
                },
                listener = Listener(),
                allowedEvents = allowedEvents.toSet(),
            ),
        )

    fun connect() = socket.connect()

    /** The token for the next attempt; also ends a pending post-rejection wait. */
    fun updateToken(token: String) {
        this.token = token
        tokenUpdate?.complete(token)
    }

    /** Emits `{...}` ([payloadJson] must be a JSON object; anything else is logged and dropped). */
    fun emitJson(eventName: String, payloadJson: String) {
        val payload = parseObject(payloadJson)
        if (payload == null) {
            listener.onLog("realtime_event_dropped reason=bad_emit_payload event=$eventName")
            return
        }
        socket.emit(eventName, listOf(payload))
    }

    /** Emits a bare string argument (`join:channel` / `leave:channel` take the channel id itself). */
    fun emitString(eventName: String, value: String) = socket.emit(eventName, listOf(JsonPrimitive(value)))

    /** Emits with an ack; [callback] runs once (see [RaftKmpRealtimeAckCallback]). */
    fun emitWithAck(eventName: String, timeoutMillis: Long, callback: RaftKmpRealtimeAckCallback) {
        socket.emitWithAck(eventName, timeoutMillis = timeoutMillis) { result ->
            when (result) {
                is RaftAckResult.Acked -> callback.onAckResult(result.args.firstOrNull()?.let(::encode), "acked")
                RaftAckResult.Timeout -> callback.onAckResult(null, "timeout")
                is RaftAckResult.NotDelivered -> callback.onAckResult(null, "not_delivered:${result.reason}")
            }
        }
    }

    /** Answers the server's ack request [ackId] (from [RaftKmpRealtimeSocketListener.onEvent]) with one argument. */
    fun answerAck(ackId: Long, payloadJson: String) {
        val payload: JsonElement = parseObject(payloadJson) ?: JsonObject(emptyMap())
        client.runOnDispatcher {
            val ack = pendingIncomingAcks.remove(ackId)
            if (ack == null) listener.onLog("realtime_event_dropped reason=ack_unknown_request ackId=$ackId") else ack.send(payload)
        }
    }

    fun kick(reason: String) = client.kick(reason)

    fun forceReconnect(reason: String) = client.forceReconnect(reason)

    /** Leaves the server and releases everything; the object is unusable afterwards. */
    fun close() {
        socket.disconnect()
        client.close()
    }

    private inner class Listener : RaftSocketIoListener {
        override fun onConnected(connection: RaftSocketConnection) {
            pendingIncomingAcks.clear()
            listener.onConnected(connection.socketId, connection.reconnect)
        }

        override fun onEvent(connection: RaftSocketConnection, event: RaftSocketEvent, ack: RaftSocketIncomingAck?) {
            ack?.let { pendingIncomingAcks[it.ackId] = it }
            val raw = event.args.firstOrNull()?.let(::encode) ?: "{}"
            listener.onEvent(event.name, raw, ack?.ackId ?: -1L)
        }

        override fun onConnectError(error: RaftSocketHandshakeError, willRetry: Boolean) {
            listener.onConnectError(error.kind.name, error.message, error.terminal, error.requiresTokenRefresh)
        }

        override fun onDisconnected(connection: RaftSocketConnection, reason: RaftSocketDisconnectReason, detail: String) {
            pendingIncomingAcks.clear()
            listener.onDisconnected(reason.token)
        }

        override fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: RaftSocketDisconnectReason) {
            listener.onReconnectScheduled(attempt, delayMillis, reason.token)
        }
    }

    private fun parseObject(json: String): JsonObject? = runCatching { facadeJson.parseToJsonElement(json) as? JsonObject }.getOrNull()

    private fun encode(element: JsonElement): String = facadeJson.encodeToString(JsonElement.serializer(), element)

    companion object {
        /** How long an attempt after a token rejection waits for the host's refreshed token. */
        const val DEFAULT_REFRESH_WAIT_MILLIS: Long = 10_000L

        private val facadeJson = Json { ignoreUnknownKeys = true }

        /** One HttpClient (and its engine threads / NSURLSession) for every server's socket. */
        private val sharedRealtimeHttpClient by lazy { createRaftRealtimeHttpClient() }
    }
}
