package build.raft.realtime.socket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A scripted Engine.IO / Socket.IO server behind [RaftWebSocketConnector], for virtual-time tests.
 * Each client connect creates a [FakeServerSession]; the test reads what the client sent and pushes
 * frames back.
 */
internal class FakeSocketIoServer : RaftWebSocketConnector {
    val sessions = mutableListOf<FakeServerSession>()
    val urls = mutableListOf<String>()

    /** The next [failNextConnects] connects throw (DNS failure, refused). */
    var failNextConnects = 0

    override suspend fun connect(url: String, headers: Map<String, String>): RaftWebSocketSession {
        urls += url
        if (failNextConnects > 0) {
            failNextConnects -= 1
            throw IllegalStateException("connect refused")
        }
        return FakeServerSession(url).also { sessions += it }
    }

    val last: FakeServerSession get() = sessions.lastOrNull() ?: error("the client opened no WebSocket")
}

internal class FakeServerSession(val url: String) : RaftWebSocketSession {
    private val toClient = Channel<RaftWebSocketFrame>(Channel.UNLIMITED)
    private val fromClient = Channel<RaftWebSocketFrame>(Channel.UNLIMITED)
    var closed = false
        private set

    override val incoming: ReceiveChannel<RaftWebSocketFrame> = toClient

    override suspend fun send(text: String) {
        check(!closed) { "send on a closed session" }
        fromClient.send(RaftWebSocketFrame.Text(text))
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        check(!closed) { "send on a closed session" }
        fromClient.send(RaftWebSocketFrame.Binary(bytes))
    }

    override suspend fun close(code: Int, reason: String) {
        closed = true
        toClient.close()
    }

    fun push(text: String) {
        toClient.trySend(RaftWebSocketFrame.Text(text))
    }

    fun pushBinary(bytes: ByteArray) {
        toClient.trySend(RaftWebSocketFrame.Binary(bytes))
    }

    /** The network dies under the client. */
    fun fail() {
        toClient.close(IllegalStateException("connection reset"))
    }

    /** Every frame the client sent since the last call. */
    fun received(): List<RaftWebSocketFrame> {
        val frames = mutableListOf<RaftWebSocketFrame>()
        while (true) frames += fromClient.tryReceive().getOrNull() ?: break
        return frames
    }

    fun receivedTexts(): List<String> = received().map { (it as? RaftWebSocketFrame.Text)?.text ?: "<binary>" }

    /** The Engine.IO open packet; timers default to non-library values so nothing can rely on defaults. */
    fun open(sid: String = "eio-1", pingInterval: Long = 4_000, pingTimeout: Long = 2_000, maxPayload: Long = 100_000) {
        push("0{\"sid\":\"$sid\",\"upgrades\":[],\"pingInterval\":$pingInterval,\"pingTimeout\":$pingTimeout,\"maxPayload\":$maxPayload}")
    }

    /** Decodes the client's CONNECT for [namespace] among [frames]; fails when there is none. */
    fun connectAuth(frames: List<String>, namespace: String = "/"): JsonObject {
        val prefix = if (namespace == "/") "40" else "40$namespace,"
        val frame = frames.lastOrNull { it.startsWith(prefix) && (namespace != "/" || !it.startsWith("40/")) }
            ?: error("no CONNECT for $namespace in $frames")
        val json = frame.removePrefix(prefix)
        return if (json.isEmpty()) JsonObject(emptyMap()) else RaftSocketJson.parseToJsonElement(json).jsonObject
    }

    fun acceptConnect(namespace: String = "/", sid: String = "sio-1", pid: String? = null) {
        val nsp = if (namespace == "/") "" else "$namespace,"
        val pidPart = pid?.let { ",\"pid\":\"$it\"" }.orEmpty()
        push("40$nsp{\"sid\":\"$sid\"$pidPart}")
    }
}
