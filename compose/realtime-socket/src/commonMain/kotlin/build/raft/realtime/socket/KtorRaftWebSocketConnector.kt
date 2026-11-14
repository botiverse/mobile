package build.raft.realtime.socket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce

/**
 * [RaftWebSocketConnector] over a Ktor [HttpClient] with the WebSockets plugin installed
 * ([createRaftRealtimeHttpClient]: OkHttp on Android and desktop, Darwin / NSURLSessionWebSocketTask on
 * iOS). Ktor answers WebSocket-level pings and close frames itself; only data frames reach the client.
 */
class KtorRaftWebSocketConnector(private val httpClient: HttpClient) : RaftWebSocketConnector {
    override suspend fun connect(url: String, headers: Map<String, String>): RaftWebSocketSession {
        val session =
            httpClient.webSocketSession(urlString = url) {
                headers.forEach { (name, value) -> header(name, value) }
            }
        return KtorRaftWebSocketSession(session)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class KtorRaftWebSocketSession(private val session: DefaultClientWebSocketSession) : RaftWebSocketSession {
    override val incoming: ReceiveChannel<RaftWebSocketFrame> =
        session.produce {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> send(RaftWebSocketFrame.Text(frame.readText()))
                    is Frame.Binary -> send(RaftWebSocketFrame.Binary(frame.data))
                    else -> Unit
                }
            }
        }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    override suspend fun close(code: Int, reason: String) {
        try {
            session.close(CloseReason(code.toShort(), reason))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // Already gone.
        } finally {
            session.cancel()
        }
    }
}
