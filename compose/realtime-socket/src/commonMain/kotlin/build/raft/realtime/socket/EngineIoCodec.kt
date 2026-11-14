package build.raft.realtime.socket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Engine.IO protocol v4 (`EIO=4`), the subset a WebSocket-only client needs.
 *
 * Over a WebSocket every text frame carries exactly one packet: a one-digit type followed by its data
 * (no length prefix, no `\u001e` batching; that is the HTTP long-polling framing). Binary frames carry
 * binary attachments, which this client never asks for and rejects.
 */
enum class EngineIoPacketType(val code: Char) {
    Open('0'),
    Close('1'),
    Ping('2'),
    Pong('3'),
    Message('4'),
    Upgrade('5'),
    Noop('6');

    companion object {
        fun fromCode(code: Char): EngineIoPacketType? = entries.firstOrNull { it.code == code }
    }
}

data class EngineIoPacket(val type: EngineIoPacketType, val data: String = "")

/**
 * The server's handshake (`0{"sid","upgrades","pingInterval","pingTimeout","maxPayload"}`). The server
 * pings every [pingIntervalMillis] and expects the pong within [pingTimeoutMillis]; a client that hears
 * no ping for `pingInterval + pingTimeout` treats the link as dead (engine.io-client does the same).
 *
 * The heartbeat timers and [maxPayload] always come from this packet; the server's options are never
 * hardcoded on the client. A field the packet lacks falls back to the socket.io server default and is
 * named in [fallbacks] so the client logs it. `upgrades` is ignored (the client is WebSocket-only and
 * never upgrades), and nothing depends on per-message deflate.
 */
data class EngineIoOpen(
    val sid: String,
    val pingIntervalMillis: Long,
    val pingTimeoutMillis: Long,
    val maxPayload: Long,
    /** Open-packet fields that were absent or invalid and took the server default (`pingInterval`, ...). */
    val fallbacks: List<String> = emptyList(),
)

object EngineIoCodec {
    const val PROTOCOL_VERSION: Int = 4

    fun encode(packet: EngineIoPacket): String = "${packet.type.code}${packet.data}"

    /** Null for an empty frame or an unknown packet type. */
    fun decode(frame: String): EngineIoPacket? {
        if (frame.isEmpty()) return null
        val type = EngineIoPacketType.fromCode(frame[0]) ?: return null
        return EngineIoPacket(type, frame.substring(1))
    }

    /**
     * Null when the open payload is not an object with a session id. Missing or invalid timers / max
     * payload take the server defaults and are listed in [EngineIoOpen.fallbacks].
     */
    fun parseOpen(data: String): EngineIoOpen? {
        val payload = runCatching { RaftSocketJson.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: return null
        val sid = (payload["sid"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val fallbacks = mutableListOf<String>()
        fun positive(key: String, default: Long): Long =
            (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
                ?: default.also { fallbacks += key }
        val pingInterval = positive("pingInterval", DEFAULT_PING_INTERVAL_MILLIS)
        val pingTimeout = positive("pingTimeout", DEFAULT_PING_TIMEOUT_MILLIS)
        val maxPayload = positive("maxPayload", DEFAULT_MAX_PAYLOAD)
        return EngineIoOpen(sid, pingInterval, pingTimeout, maxPayload, fallbacks)
    }

    /** engine.io server default `pingInterval`; used only when the open packet lacks it. */
    const val DEFAULT_PING_INTERVAL_MILLIS: Long = 25_000L

    /** engine.io server default `pingTimeout`; used only when the open packet lacks it. */
    const val DEFAULT_PING_TIMEOUT_MILLIS: Long = 20_000L

    /** engine.io server default `maxHttpBufferSize` (1e6 bytes); used only when the open packet lacks `maxPayload`. */
    const val DEFAULT_MAX_PAYLOAD: Long = 1_000_000L

    /** The WebSocket URL of [baseUrl] (http(s) or ws(s)) for Engine.IO v4 at [path]. */
    fun webSocketUrl(baseUrl: String, path: String = DEFAULT_PATH): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        val rest = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
        val wsScheme =
            when (scheme) {
                "https", "wss" -> "wss"
                "http", "ws" -> "ws"
                else -> throw IllegalArgumentException("unsupported socket url scheme: ${scheme.ifEmpty { "none" }}")
            }
        // The base URL's own path is the Socket.IO namespace in socket.io clients; only `/` exists here,
        // so it is dropped like the other clients do for `https://host/`.
        val host = rest.substringBefore('/')
        require(host.isNotEmpty()) { "socket url without host" }
        val normalizedPath = "/" + path.trim('/')
        return "$wsScheme://$host$normalizedPath/?EIO=$PROTOCOL_VERSION&transport=websocket"
    }

    const val DEFAULT_PATH: String = "/socket.io"
}

/** Lenient enough for server payloads, strict about structure. Shared by the codecs. */
internal val RaftSocketJson: Json = Json { isLenient = false; ignoreUnknownKeys = true }

internal fun JsonArray.firstStringOrNull(): String? =
    (firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
