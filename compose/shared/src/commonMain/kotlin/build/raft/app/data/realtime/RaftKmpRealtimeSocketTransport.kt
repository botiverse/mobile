package build.raft.app.data.realtime

import build.raft.app.data.RealtimeSubscription
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * [RealtimeSocketTransport] on the common Socket.IO client (`:realtime-socket`): the realtime socket of the
 * Android and desktop hosts, under the transport-agnostic SocketIoRealtimeClient (cursor, sync horizon,
 * resume, rejoin, presence).
 *
 * Contract: callbacks only for the current connection context, in socket order, checked under the client's
 * lock and delivered outside it; `reconnect` (with `reconnect_attempt` before each retry) and then `connect`
 * when a dropped socket comes back; `connect_error` carrying the server's message; `join:channel` /
 * `leave:channel` with the bare channel id; payloads as plain Kotlin maps, lists and primitives.
 *
 * The primary constructor takes the connector and dispatchers (tests script a server through it); hosts
 * use [raftRealtimeSocketTransport]. [linearizer] is the lock every call and callback runs under.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftKmpRealtimeSocketTransport(
    private val socketUrl: String,
    private val clientKind: String,
    private val connector: RaftWebSocketConnector,
    private val parentScope: CoroutineScope,
    private val dispatcherFactory: () -> CoroutineDispatcher,
    private val logger: (String) -> Unit,
    private val linearizer: RealtimeTransportLinearizer = ReentrantRealtimeTransportLinearizer(),
) : RealtimeSocketTransport {
    constructor(
        socketUrl: String,
        clientKind: String = "mobile",
        logger: (String) -> Unit = {},
        linearizer: RealtimeTransportLinearizer = ReentrantRealtimeTransportLinearizer(),
    ) : this(
        socketUrl = socketUrl,
        clientKind = clientKind,
        connector = KtorRaftWebSocketConnector(createRaftRealtimeHttpClient()),
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        dispatcherFactory = { Dispatchers.Default.limitedParallelism(1) },
        logger = logger,
        linearizer = linearizer,
    )

    private val listeners = linkedMapOf<String, MutableList<(RealtimeSocketConnectionContext, Any?) -> Unit>>()
    private var current: Connection? = null

    override fun <T> linearize(block: () -> T): T = linearizer.linearize(block)

    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        linearize {
            current?.close()
            val token = auth["token"].orEmpty()
            val serverId = auth["serverId"]
            logger("transport_connect_start serverId=${serverId.orEmpty()} hasToken=${token.isNotBlank()}")
            val client =
                RaftSocketIoClient(
                    config = RaftSocketIoConfig(baseUrl = socketUrl),
                    connector = connector,
                    parentScope = parentScope,
                    dispatcher = dispatcherFactory(),
                    logger = logger,
                )
            val connection = Connection(context, client)
            current = connection
            connection.socket =
                client.socket(
                    "/",
                    RaftSocketNamespaceOptions(
                        authProvider = {
                            raftHandshakeAuth(token, serverId, auth["clientKind"] ?: clientKind, RealtimeSocketEvents.HandshakeCapabilities)
                        },
                        listener = connection,
                    ),
                )
            connection.socket?.connect()
        }
    }

    override fun disconnect() {
        linearize {
            current?.close()
            current = null
        }
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        linearize {
            val socket = current?.socket ?: return@linearize
            val arg: JsonElement =
                if (eventName == RealtimeSocketEvents.JoinChannel || eventName == RealtimeSocketEvents.LeaveChannel) {
                    (payload["channelId"] as? String)?.let(::JsonPrimitive) ?: payload.toTransportJson()
                } else {
                    payload.toTransportJson()
                }
            socket.emit(eventName, listOf(arg))
        }
    }

    override fun emitWithAck(
        eventName: String,
        onAck: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
    ): Boolean =
        linearize {
            val connection = current ?: return@linearize false
            val socket = connection.socket ?: return@linearize false
            // No payload: the server reads the ack as the last argument (`emit("sync:horizon", ack)`).
            socket.emitWithAck(eventName) { result ->
                if (result !is RaftAckResult.Acked) return@emitWithAck // May never run (an older server does not answer).
                if (linearize { current === connection }) onAck(connection.context, result.args.firstOrNull()?.toTransportPlain())
            }
            true
        }

    override fun on(
        eventName: String,
        listener: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
    ): RealtimeSubscription =
        linearize {
            listeners.getOrPut(eventName) { mutableListOf() } += listener
            RealtimeSubscription { linearize { listeners[eventName]?.remove(listener) } }
        }

    /**
     * Delivers to [connection]'s listeners while it is current. The lock only owns the current-connection check: listeners run outside it (they re-enter the client,
     * which takes [linearize] itself), so a slow listener never holds a lifecycle call (setReconnectAllowed
     * on the main thread) behind a socket callback. Callbacks of one connection still arrive in socket order
     * (one serial dispatcher per connection).
     */
    private fun fire(connection: Connection, eventName: String, payload: Any?): Boolean {
        val handlers =
            linearize {
                if (current !== connection) null else listeners[eventName]?.toList().orEmpty()
            } ?: return true
        handlers.forEach { handler ->
            // Re-checked per handler: a replacement during delivery wins.
            if (linearize { current === connection }) handler(connection.context, payload)
        }
        return handlers.isNotEmpty()
    }

    private inner class Connection(
        val context: RealtimeSocketConnectionContext,
        val client: RaftSocketIoClient,
    ) : RaftSocketIoListener {
        var socket: build.raft.realtime.socket.RaftSocketIoNamespace? = null
        private val unlisted = mutableSetOf<String>()

        fun close() {
            socket?.disconnect()
            client.close()
        }

        override fun onConnected(connection: RaftSocketConnection) {
            logger("transport_connected serverId=${context.serverId.value} reconnect=${connection.reconnect}")
            if (connection.reconnect) fire(this, RealtimeSocketEvents.Reconnect, null)
            fire(this, RealtimeSocketEvents.Connect, null)
        }

        override fun onEvent(connection: RaftSocketConnection, event: RaftSocketEvent, ack: RaftSocketIncomingAck?) {
            val delivered = fire(this, event.name, event.args.firstOrNull()?.toTransportPlain())
            if (!delivered && unlisted.add(event.name)) {
                logger("projection_value_unknown source=realtime_socket_transport field=event_name value=${event.name}")
            }
        }

        override fun onConnectError(error: RaftSocketHandshakeError, willRetry: Boolean) {
            logger("transport_connect_error serverId=${context.serverId.value} kind=${error.kind.name} willRetry=$willRetry")
            fire(this, RealtimeSocketEvents.ConnectError, error.message)
        }

        override fun onDisconnected(connection: RaftSocketConnection, reason: RaftSocketDisconnectReason, detail: String) {
            logger("transport_disconnected serverId=${context.serverId.value} reason=${reason.token}")
            fire(this, RealtimeSocketEvents.Disconnect, reason.token)
        }

        override fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: RaftSocketDisconnectReason) {
            logger("transport_reconnect_scheduled serverId=${context.serverId.value} attempt=$attempt delayMs=$delayMillis reason=${reason.token}")
            fire(this, RealtimeSocketEvents.ReconnectAttempt, attempt)
        }
    }
}

/**
 * A host's realtime socket: the common client with Raft's handshake ([clientKind]; the client adds
 * `clientImpl: "kmp"`). [log] gets every client line prefixed `realtime_kmp `; `verbose` is false for the
 * connection lifecycle lines (`transport_*`, a few per connection, always kept) and true for the rest.
 */
fun raftRealtimeSocketTransport(
    socketUrl: String,
    clientKind: String = "mobile",
    log: (message: String, verbose: Boolean) -> Unit,
): RealtimeSocketTransport =
    RaftKmpRealtimeSocketTransport(
        socketUrl = socketUrl,
        clientKind = clientKind,
        logger = { message -> log("realtime_kmp $message", !message.startsWith("transport_")) },
    )

/**
 * A raw socket payload (what [RaftKmpRealtimeSocketTransport] delivers: plain maps, lists, primitives, or a
 * JSON string) as JSON text for the app-scope raw event bus; `{}` when there is none.
 */
fun realtimeRawPayloadJson(payload: Any?): String =
    when (payload) {
        null -> "{}"
        is String -> payload
        is Map<*, *>, is Iterable<*>, is Array<*>, is JsonElement -> payload.toTransportJson().toString()
        else -> payload.toString()
    }

/** Plain Kotlin values (String, Long, Double, Boolean, null, List, Map) for transport consumers. */
private fun JsonElement.toTransportPlain(): Any? =
    when (this) {
        JsonNull -> null
        is JsonPrimitive ->
            when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                else -> doubleOrNull
            }
        is JsonArray -> map { it.toTransportPlain() }
        is JsonObject -> mapValues { (_, value) -> value.toTransportPlain() }
    }

/** The inverse of [toTransportPlain] for outgoing transport payloads. */
private fun Any?.toTransportJson(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toTransportJson() })
        is Iterable<*> -> JsonArray(map { it.toTransportJson() })
        is Array<*> -> JsonArray(map { it.toTransportJson() })
        else -> JsonPrimitive(toString())
    }
