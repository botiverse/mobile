package build.raft.realtime.socket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Socket.IO protocol v5 packets (socket.io 3+/4.x, carried in Engine.IO `4` message packets):
 * `<type>[<attachments>-][<namespace>,][<ackId>][<json>]`.
 *
 * | type | packet         |                                                                           |
 * |------|----------------|---------------------------------------------------------------------------|
 * | 0    | CONNECT        | out `0[/nsp,]{auth}`, in `0[/nsp,]{"sid","pid"?}`                          |
 * | 1    | DISCONNECT     | either side leaves a namespace                                            |
 * | 2    | EVENT          | `2[name,...args]`; `2<id>[...]` asks the other side for an ack             |
 * | 3    | ACK            | `3<id>[...args]` answers an EVENT that carried `<id>`                      |
 * | 4    | CONNECT_ERROR  | `4{"message","data"?}` when a namespace middleware rejects                 |
 * | 5    | BINARY_EVENT   | EVENT whose args hold `{"_placeholder":true,"num":i}`, then i binary frames |
 * | 6    | BINARY_ACK     | ACK with attachments, same framing                                         |
 *
 * Binary attachments: the JSON keeps the placeholders and the bytes travel in [RaftSocketBinary]
 * order (`attachments[num]`); [RaftSocketBinary.placeholder] builds one for outgoing packets.
 */
sealed interface SocketIoPacket {
    val namespace: String

    data class Connect(override val namespace: String = ROOT_NAMESPACE, val data: JsonObject? = null) : SocketIoPacket

    data class Disconnect(override val namespace: String = ROOT_NAMESPACE) : SocketIoPacket

    data class Event(
        val name: String,
        val args: List<JsonElement> = emptyList(),
        val ackId: Long? = null,
        override val namespace: String = ROOT_NAMESPACE,
        /** Non-empty makes it a BINARY_EVENT; `args` refer to them by placeholder `num`. */
        val attachments: List<RaftSocketBinary> = emptyList(),
    ) : SocketIoPacket

    data class Ack(
        val ackId: Long,
        val args: List<JsonElement> = emptyList(),
        override val namespace: String = ROOT_NAMESPACE,
        val attachments: List<RaftSocketBinary> = emptyList(),
    ) : SocketIoPacket

    /** [message] is the server's `Error.message`; [data] its optional `data`. */
    data class ConnectError(
        val message: String,
        val data: JsonElement? = null,
        override val namespace: String = ROOT_NAMESPACE,
    ) : SocketIoPacket

    companion object {
        const val ROOT_NAMESPACE: String = "/"
    }
}

/** Bytes of one binary attachment; equal by content. */
class RaftSocketBinary(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is RaftSocketBinary && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "RaftSocketBinary(size=${bytes.size})"

    companion object {
        /** The JSON stand-in socket.io puts where attachment [num] goes. */
        fun placeholder(num: Int): JsonObject =
            JsonObject(mapOf(PLACEHOLDER_KEY to JsonPrimitive(true), NUM_KEY to JsonPrimitive(num)))

        /** The attachment index of a placeholder object, or null when [element] is not one. */
        fun placeholderIndex(element: JsonElement): Int? {
            val obj = element as? JsonObject ?: return null
            if ((obj[PLACEHOLDER_KEY] as? JsonPrimitive)?.booleanOrNull != true) return null
            return (obj[NUM_KEY] as? JsonPrimitive)?.intOrNull
        }

        private const val PLACEHOLDER_KEY = "_placeholder"
        private const val NUM_KEY = "num"
    }
}

sealed interface SocketIoDecodeResult {
    /**
     * A complete packet, or (when [attachmentsExpected] > 0) the header of a binary packet whose
     * attachments follow as that many binary frames ([SocketIoBinaryReconstructor]).
     */
    data class Packet(val packet: SocketIoPacket, val attachmentsExpected: Int = 0) : SocketIoDecodeResult

    /** [reason] is a stable diagnostic token (`unknown_type`, `json`, `bad_payload`, `attachments`, ...). */
    data class Invalid(val reason: String) : SocketIoDecodeResult
}

/** One encoded packet: the text frame, then its attachments as binary frames, in order. */
data class SocketIoEncoded(val text: String, val attachments: List<RaftSocketBinary> = emptyList())

object SocketIoCodec {
    const val PROTOCOL_VERSION: Int = 5

    private const val CONNECT = '0'
    private const val DISCONNECT = '1'
    private const val EVENT = '2'
    private const val ACK = '3'
    private const val CONNECT_ERROR = '4'
    private const val BINARY_EVENT = '5'
    private const val BINARY_ACK = '6'

    /** Text of a packet without attachments; use [encodeWithAttachments] for binary packets. */
    fun encode(packet: SocketIoPacket): String = encodeWithAttachments(packet).text

    fun encodeWithAttachments(packet: SocketIoPacket): SocketIoEncoded {
        val attachments =
            when (packet) {
                is SocketIoPacket.Event -> packet.attachments
                is SocketIoPacket.Ack -> packet.attachments
                else -> emptyList()
            }
        val text =
            buildString {
                when (packet) {
                    is SocketIoPacket.Connect -> {
                        append(CONNECT)
                        appendNamespace(packet.namespace)
                        packet.data?.let { append(RaftSocketJson.encodeToString(JsonObject.serializer(), it)) }
                    }
                    is SocketIoPacket.Disconnect -> {
                        append(DISCONNECT)
                        appendNamespace(packet.namespace)
                    }
                    is SocketIoPacket.Event -> {
                        if (attachments.isEmpty()) append(EVENT) else append(BINARY_EVENT).append(attachments.size.toString()).append('-')
                        appendNamespace(packet.namespace)
                        packet.ackId?.let { append(it.toString()) }
                        append(encodeArray(listOf(JsonPrimitive(packet.name)) + packet.args))
                    }
                    is SocketIoPacket.Ack -> {
                        if (attachments.isEmpty()) append(ACK) else append(BINARY_ACK).append(attachments.size.toString()).append('-')
                        appendNamespace(packet.namespace)
                        append(packet.ackId.toString())
                        append(encodeArray(packet.args))
                    }
                    is SocketIoPacket.ConnectError -> {
                        append(CONNECT_ERROR)
                        appendNamespace(packet.namespace)
                        val body =
                            buildMap<String, JsonElement> {
                                put("message", JsonPrimitive(packet.message))
                                packet.data?.let { put("data", it) }
                            }
                        append(RaftSocketJson.encodeToString(JsonObject.serializer(), JsonObject(body)))
                    }
                }
            }
        return SocketIoEncoded(text, attachments)
    }

    fun decode(text: String): SocketIoDecodeResult {
        if (text.isEmpty()) return SocketIoDecodeResult.Invalid("empty")
        val type = text[0]
        if (type !in CONNECT..BINARY_ACK) return SocketIoDecodeResult.Invalid("unknown_type")
        var index = 1
        var attachments = 0
        if (type == BINARY_EVENT || type == BINARY_ACK) {
            val dash = text.indexOf('-', index)
            if (dash < 0) return SocketIoDecodeResult.Invalid("attachments")
            attachments = text.substring(index, dash).toIntOrNull()?.takeIf { it in 0..MAX_ATTACHMENTS }
                ?: return SocketIoDecodeResult.Invalid("attachments")
            index = dash + 1
        }
        var namespace = SocketIoPacket.ROOT_NAMESPACE
        if (index < text.length && text[index] == '/') {
            val end = text.indexOf(',', index).let { if (it < 0) text.length else it }
            namespace = text.substring(index, end)
            index = if (end < text.length) end + 1 else end
        }
        var ackId: Long? = null
        val digitsStart = index
        while (index < text.length && text[index].isDigit()) index++
        if (index > digitsStart) {
            ackId = text.substring(digitsStart, index).toLongOrNull()?.takeIf { it <= MAX_ACK_ID }
                ?: return SocketIoDecodeResult.Invalid("ack_id")
        }
        val body = text.substring(index)
        val payload: JsonElement? =
            if (body.isEmpty()) {
                null
            } else {
                runCatching { RaftSocketJson.parseToJsonElement(body) }.getOrNull()
                    ?: return SocketIoDecodeResult.Invalid("json")
            }
        val packet =
            when (type) {
                CONNECT -> {
                    if (ackId != null) return SocketIoDecodeResult.Invalid("bad_payload")
                    when (payload) {
                        null -> SocketIoPacket.Connect(namespace)
                        is JsonObject -> SocketIoPacket.Connect(namespace, payload)
                        else -> return SocketIoDecodeResult.Invalid("bad_payload")
                    }
                }
                DISCONNECT -> {
                    if (payload != null || ackId != null) return SocketIoDecodeResult.Invalid("bad_payload")
                    SocketIoPacket.Disconnect(namespace)
                }
                EVENT, BINARY_EVENT -> {
                    val array = payload as? JsonArray ?: return SocketIoDecodeResult.Invalid("bad_payload")
                    val name = array.firstStringOrNull() ?: return SocketIoDecodeResult.Invalid("bad_payload")
                    SocketIoPacket.Event(name, array.drop(1), ackId, namespace)
                }
                ACK, BINARY_ACK -> {
                    val id = ackId ?: return SocketIoDecodeResult.Invalid("bad_payload")
                    val array = payload as? JsonArray ?: return SocketIoDecodeResult.Invalid("bad_payload")
                    SocketIoPacket.Ack(id, array.toList(), namespace)
                }
                CONNECT_ERROR -> {
                    if (ackId != null) return SocketIoDecodeResult.Invalid("bad_payload")
                    when (payload) {
                        is JsonObject -> {
                            val message =
                                (payload["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                                    ?: return SocketIoDecodeResult.Invalid("bad_payload")
                            SocketIoPacket.ConnectError(message, payload["data"]?.takeUnless { it is JsonNull }, namespace)
                        }
                        // socket.io 2 (protocol v4) sent the bare message.
                        is JsonPrimitive ->
                            if (payload.isString) {
                                SocketIoPacket.ConnectError(payload.content, null, namespace)
                            } else {
                                return SocketIoDecodeResult.Invalid("bad_payload")
                            }
                        else -> return SocketIoDecodeResult.Invalid("bad_payload")
                    }
                }
                else -> return SocketIoDecodeResult.Invalid("unknown_type")
            }
        if (attachments > 0 || type == BINARY_EVENT || type == BINARY_ACK) {
            val args = (packet as? SocketIoPacket.Event)?.args ?: (packet as SocketIoPacket.Ack).args
            val indexes = mutableListOf<Int>()
            args.forEach { collectPlaceholders(it, indexes) }
            if (indexes.any { it !in 0 until attachments }) return SocketIoDecodeResult.Invalid("attachments")
        }
        return SocketIoDecodeResult.Packet(packet, attachments)
    }

    /** Ack ids are non-negative 32-bit ints in every socket.io client; larger ids are rejected. */
    const val MAX_ACK_ID: Long = Int.MAX_VALUE.toLong()

    /** More attachments than this in one packet is treated as malformed. */
    const val MAX_ATTACHMENTS: Int = 1_000

    private fun collectPlaceholders(element: JsonElement, into: MutableList<Int>) {
        RaftSocketBinary.placeholderIndex(element)?.let {
            into += it
            return
        }
        when (element) {
            is JsonArray -> element.forEach { collectPlaceholders(it, into) }
            is JsonObject -> element.values.forEach { collectPlaceholders(it, into) }
            else -> Unit
        }
    }

    private fun StringBuilder.appendNamespace(namespace: String) {
        if (namespace != SocketIoPacket.ROOT_NAMESPACE) {
            append(namespace)
            // socket.io-parser always writes the comma after a non-root namespace.
            append(',')
        }
    }

    private fun encodeArray(elements: List<JsonElement>): String =
        RaftSocketJson.encodeToString(JsonArray.serializer(), JsonArray(elements))
}

/**
 * Collects the binary frames that follow a BINARY_EVENT / BINARY_ACK header (Engine.IO v4 over
 * WebSocket sends each attachment as its own binary frame, right after the text frame).
 */
class SocketIoBinaryReconstructor {
    private var header: SocketIoPacket? = null
    private var expected = 0
    private val received = mutableListOf<RaftSocketBinary>()

    val pending: Boolean get() = header != null

    /** Starts collecting for [packet]; a previous unfinished packet is discarded (returned). */
    fun start(packet: SocketIoPacket, attachments: Int): SocketIoPacket? {
        val discarded = header
        header = packet
        expected = attachments
        received.clear()
        return discarded
    }

    /** Adds one attachment; returns the completed packet once all arrived, else null. */
    fun add(bytes: ByteArray): SocketIoPacket? {
        val packet = header ?: return null
        received += RaftSocketBinary(bytes)
        if (received.size < expected) return null
        val attachments = received.toList()
        header = null
        received.clear()
        return when (packet) {
            is SocketIoPacket.Event -> packet.copy(attachments = attachments)
            is SocketIoPacket.Ack -> packet.copy(attachments = attachments)
            else -> packet
        }
    }

    fun reset() {
        header = null
        received.clear()
    }
}
