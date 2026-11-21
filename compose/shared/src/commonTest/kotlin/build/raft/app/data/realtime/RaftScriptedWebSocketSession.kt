package build.raft.app.data.realtime

import build.raft.realtime.socket.RaftWebSocketFrame
import build.raft.realtime.socket.RaftWebSocketSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A WebSocket the test plays the server of, for the facades over the common Socket.IO client
 * ([RaftKmpRealtimeSocket], [RaftKmpRealtimeSocketTransport]): [push] a frame to the client, read what
 * it [sent], [closeFromServer] to end the connection as the server would.
 */
internal class RaftScriptedWebSocketSession : RaftWebSocketSession {
    private val toClient = Channel<RaftWebSocketFrame>(Channel.UNLIMITED)
    private val fromClient = Channel<String>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<RaftWebSocketFrame> = toClient

    override suspend fun send(text: String) {
        fromClient.send(text)
    }

    override suspend fun sendBinary(bytes: ByteArray) = Unit

    override suspend fun close(code: Int, reason: String) {
        toClient.close()
    }

    fun push(text: String) {
        toClient.trySend(RaftWebSocketFrame.Text(text))
    }

    /** The server closed the WebSocket: the client's frames end without an error. */
    fun closeFromServer() {
        toClient.close()
    }

    fun sent(): List<String> {
        val frames = mutableListOf<String>()
        while (true) frames += fromClient.tryReceive().getOrNull() ?: break
        return frames
    }
}
