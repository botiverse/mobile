package build.raft.realtime.socket

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The WebSocket under the Engine.IO client. Per-message deflate is whatever the engine negotiates
 * (OkHttp offers `permessage-deflate`; NSURLSessionWebSocketTask decides on its own); the protocol
 * never depends on it. Production uses [KtorRaftWebSocketConnector] (OkHttp on
 * Android/JVM, Darwin on iOS); tests script a fake server through the same seam.
 */
fun interface RaftWebSocketConnector {
    /** Opens [url] (`ws://` / `wss://`). Throws when the connection cannot be established. */
    suspend fun connect(url: String, headers: Map<String, String>): RaftWebSocketSession
}

interface RaftWebSocketSession {
    /**
     * Data frames in arrival order (control frames are the engine's business). The channel closes when
     * the connection ends: normally after a close frame, with the failure as its cause otherwise.
     */
    val incoming: ReceiveChannel<RaftWebSocketFrame>

    /** Sends one text frame; throws when the connection is gone. */
    suspend fun send(text: String)

    /** Sends one binary frame (a Socket.IO attachment); throws when the connection is gone. */
    suspend fun sendBinary(bytes: ByteArray)

    /** Closes the connection (idempotent). [incoming] ends afterwards. */
    suspend fun close(code: Int = NORMAL_CLOSURE, reason: String = "")

    companion object {
        const val NORMAL_CLOSURE: Int = 1000
    }
}

sealed interface RaftWebSocketFrame {
    data class Text(val text: String) : RaftWebSocketFrame

    /** A Socket.IO binary attachment (Engine.IO v4 sends each as its own binary frame). */
    class Binary(val bytes: ByteArray) : RaftWebSocketFrame {
        override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Binary(size=${bytes.size})"
    }
}
