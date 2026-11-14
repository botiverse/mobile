package build.raft.realtime.socket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Engine-level (Manager) settings: one Engine.IO connection, shared by every namespace socket. */
data class RaftSocketIoConfig(
    /** `https://api.raft.build` (http(s) or ws(s)); its path is ignored (namespaces are per socket). */
    val baseUrl: String,
    /** Engine.IO path; socket.io servers default to `/socket.io`. */
    val path: String = EngineIoCodec.DEFAULT_PATH,
    /** WebSocket open + Engine.IO open must finish within this; also bounds each namespace CONNECT (socket.io-client: 20 s). */
    val connectTimeoutMillis: Long = 20_000L,
    val reconnect: RaftReconnectPolicy = RaftReconnectPolicy(),
    /** Extra HTTP headers on the WebSocket upgrade request. */
    val headers: Map<String, String> = emptyMap(),
)

/** Passed to a namespace's auth provider before each CONNECT attempt. */
data class RaftSocketAuthRequest(
    /** 1 for the first attempt after connect, kick or a successful connect; counts up across retries. */
    val attempt: Int,
    /** The previous attempt's rejection, if it was one. */
    val lastError: RaftSocketHandshakeError?,
) {
    /** The server rejected the previous credentials: return refreshed ones, not cached ones. */
    val forceRefresh: Boolean get() = lastError?.requiresTokenRefresh == true
}

/** Options of one namespace socket ([RaftSocketIoClient.socket]). */
class RaftSocketNamespaceOptions(
    /**
     * The CONNECT `auth` object, asked again before every attempt (so a rotated token is always used).
     * Null means no credentials: the socket waits for [RaftSocketIoClient.kick]. A namespace without auth
     * returns an empty object.
     */
    val authProvider: suspend (RaftSocketAuthRequest) -> JsonObject?,
    val listener: RaftSocketIoListener,
    /**
     * Events handed to the listener; null delivers every event. Anything else is dropped and logged once
     * per name as `projection_value_unknown source=realtime_socket field=event_name` (plus a counter).
     */
    val allowedEvents: Set<String>? = null,
    /** Retry a non-terminal `connect_error` with backoff (socket.io-client leaves that to the app). */
    val retryOnConnectError: Boolean = true,
    /** Reconnect after the server disconnected the socket (`io server disconnect`); socket.io-client does not. */
    val reconnectOnServerDisconnect: Boolean = true,
    /**
     * Connection state recovery: when the server sent a `pid`, the next CONNECT carries `pid` and the last
     * event `offset` so a recovery-enabled server replays what was missed ([RaftSocketConnection.recovered]).
     */
    val stateRecovery: Boolean = true,
)

sealed interface RaftSocketState {
    /** Not connected and not trying (never connected, or [RaftSocketIoNamespace.disconnect]). */
    data object Idle : RaftSocketState

    data class Connecting(val attempt: Int) : RaftSocketState

    data class Connected(val connection: RaftSocketConnection) : RaftSocketState

    data class WaitingToReconnect(val attempt: Int, val delayMillis: Long, val reason: String) : RaftSocketState

    /** A terminal rejection (or no credentials); nothing is retried until [RaftSocketIoClient.kick]. */
    data class Failed(val error: RaftSocketHandshakeError?) : RaftSocketState
}

sealed interface RaftSocketEngineState {
    data object Idle : RaftSocketEngineState

    data class Connecting(val attempt: Int) : RaftSocketEngineState

    data class Open(val open: EngineIoOpen, val generation: Long) : RaftSocketEngineState

    data class WaitingToReconnect(val attempt: Int, val delayMillis: Long, val reason: String) : RaftSocketEngineState

    data object Closed : RaftSocketEngineState
}

/** One established namespace connection. */
data class RaftSocketConnection(
    val namespace: String,
    /** The Engine.IO connection it rides on; increases for every WebSocket the client opens. */
    val generation: Long,
    /** The Socket.IO socket id (`0{"sid"}`), what the server sees as `socket.id`. */
    val socketId: String,
    /** The Engine.IO session id from the open packet. */
    val engineSid: String,
    val attempt: Int,
    /** False for the first connection after [RaftSocketIoNamespace.connect], true afterwards. */
    val reconnect: Boolean,
    /** The server restored this socket's session (connection state recovery): missed events follow. */
    val recovered: Boolean,
)

enum class RaftSocketDisconnectReason(val token: String) {
    TransportError("transport_error"),
    TransportClosed("transport_closed"),
    PingTimeout("ping_timeout"),
    /** The server disconnected the namespace socket (`1`): revoked access, SSO expiry, auth changed. */
    ServerDisconnect("io_server_disconnect"),
    /** Engine.IO close packet (`1`). */
    EngineClose("engine_close"),
    ConnectTimeout("connect_timeout"),
    HandshakeRejected("handshake_rejected"),
    ProtocolError("protocol_error"),
    Forced("forced"),
    ClientDisconnect("io_client_disconnect"),
    Stopped("client_stopped"),
    NoCredentials("no_credentials"),
}

/** A received event: JSON args (binary placeholders kept) and the attachments they point to. */
data class RaftSocketEvent(
    val name: String,
    val args: List<JsonElement>,
    val attachments: List<RaftSocketBinary> = emptyList(),
)

sealed interface RaftAckResult {
    data class Acked(val args: List<JsonElement>, val attachments: List<RaftSocketBinary> = emptyList()) : RaftAckResult

    /** No ack within the timeout. */
    data object Timeout : RaftAckResult

    /** Never sent, or the connection ended before the ack arrived. [reason] is a diagnostic token. */
    data class NotDelivered(val reason: String) : RaftAckResult
}

/**
 * Callbacks of one namespace socket. All run on the client's serial dispatcher, in frame order, never
 * concurrently; a throwing callback is logged and does not stop the client.
 */
interface RaftSocketIoListener {
    fun onConnecting(attempt: Int) {}

    fun onConnected(connection: RaftSocketConnection) {}

    /**
     * An allowed event. [ack] is non-null when the server asked for an acknowledgement (`2<id>[...]`,
     * e.g. Raft's `notification:desktop`); call [RaftSocketIncomingAck.send] once to answer.
     */
    fun onEvent(connection: RaftSocketConnection, event: RaftSocketEvent, ack: RaftSocketIncomingAck?) {}

    /** The CONNECT was rejected. [willRetry] is false for a terminal error or a socket that does not retry. */
    fun onConnectError(error: RaftSocketHandshakeError, willRetry: Boolean) {}

    /** An established connection ended. */
    fun onDisconnected(connection: RaftSocketConnection, reason: RaftSocketDisconnectReason, detail: String) {}

    /** The next CONNECT (or engine reconnect) starts after [delayMillis] unless [RaftSocketIoClient.kick] comes first. */
    fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: RaftSocketDisconnectReason) {}

    /** A frame or event of this namespace was dropped; [reason] is the logged diagnostic token. */
    fun onEventDropped(name: String, reason: String) {}
}

/** Engine-level callbacks (optional), on the client's serial dispatcher. */
interface RaftSocketEngineListener {
    fun onOpen(open: EngineIoOpen, generation: Long) {}

    fun onClose(generation: Long, reason: RaftSocketDisconnectReason, detail: String) {}

    /** A frame not owned by any namespace (malformed, unknown namespace, stray binary) was dropped. */
    fun onFrameDropped(reason: String) {}
}

/** Answers one server event that asked for an ack, on the namespace connection it arrived on only. */
class RaftSocketIncomingAck internal constructor(
    private val socket: RaftSocketIoNamespace,
    val connectionSeq: Long,
    val ackId: Long,
    val eventName: String,
) {
    /** Sends `3<id>[args]` (BINARY_ACK with [attachments]). Only the first call counts. */
    fun send(args: List<JsonElement> = emptyList(), attachments: List<RaftSocketBinary> = emptyList()) {
        socket.sendIncomingAck(this, args, attachments)
    }

    fun send(vararg args: JsonElement) = send(args.toList())

    internal var sent: Boolean = false
}

/** Drop counters and unlisted event names, for the diagnostics bundle. Never contains payloads. */
data class RaftSocketDiagnostics(
    val droppedByReason: Map<String, Int>,
    val unlistedEvents: Map<String, Int>,
    val engineOpens: Int,
    val connects: Map<String, Int>,
    val handshakeErrors: Map<String, Int>,
)

/**
 * The Raft server's handshake `auth` (botiverse/slock socket auth middleware):
 * `{ token, serverId?, clientKind, capabilities, ...extra }`. Extra keys are ignored by the server.
 */
fun raftHandshakeAuth(
    token: String,
    serverId: String?,
    clientKind: String,
    capabilities: List<String>,
    extra: Map<String, String> = mapOf("clientImpl" to RAFT_CLIENT_IMPL),
): JsonObject =
    JsonObject(
        buildMap {
            extra.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("token", JsonPrimitive(token))
            serverId?.let { put("serverId", JsonPrimitive(it)) }
            put("clientKind", JsonPrimitive(clientKind))
            put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
        },
    )

const val RAFT_CLIENT_IMPL: String = "kmp"
