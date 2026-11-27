package build.raft.realtime.socket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** RFC 6455 §5.5 maximum UTF-8 byte length for a WebSocket close frame reason (125 - 2 byte code). */
internal const val MAX_WEBSOCKET_CLOSE_REASON_UTF8_BYTES: Int = 123

/**
 * Bounded wait for Ktor's engine writer job (e.g. `OkHttpWebsocketSession.writeJob` or
 * `DarwinWebsocketSession`'s outgoing loop) to dequeue `Frame.Close` and invoke the platform
 * socket close before cancelling the session scope.
 */
internal const val DEFAULT_WEBSOCKET_CLOSE_FLUSH_TIMEOUT_MILLIS: Long = 1_000L

/**
 * Coerces reserved or out-of-range RFC 6455 close codes to [RaftWebSocketSession.NORMAL_CLOSURE]
 * so platform engines (OkHttp/Darwin) never reject them and fall back to `1011 Client failure`.
 */
internal fun sanitizeWebSocketCloseCode(code: Int): Short =
    when {
        code == 1000 || code in 1001..1003 || code in 1007..1014 || code in 3000..4999 -> code.toShort()
        else -> RaftWebSocketSession.NORMAL_CLOSURE.toShort()
    }

/**
 * Truncates [reason] on UTF-8 code-point boundaries so its encoded byte length never exceeds
 * [maxBytes] (RFC 6455's 123-byte limit; OkHttp throws `IllegalArgumentException` if exceeded).
 */
internal fun truncateWebSocketCloseReasonUtf8(
    reason: String,
    maxBytes: Int = MAX_WEBSOCKET_CLOSE_REASON_UTF8_BYTES,
): String {
    if (reason.isEmpty() || maxBytes <= 0) return ""
    if (reason.encodeToByteArray().size <= maxBytes) return reason
    var bytes = 0
    var i = 0
    while (i < reason.length) {
        val ch = reason[i]
        val step: Int
        val charBytes: Int
        if (ch.isHighSurrogate() && i + 1 < reason.length && reason[i + 1].isLowSurrogate()) {
            step = 2
            charBytes = 4
        } else {
            step = 1
            charBytes =
                when {
                    ch.code <= 0x7F -> 1
                    ch.code <= 0x7FF -> 2
                    else -> 3
                }
        }
        if (bytes + charBytes > maxBytes) break
        bytes += charBytes
        i += step
    }
    return reason.substring(0, i)
}

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

internal class KtorRaftWebSocketSession(
    private val session: DefaultWebSocketSession,
    private val closeFlushTimeoutMillis: Long = DEFAULT_WEBSOCKET_CLOSE_FLUSH_TIMEOUT_MILLIS,
) : RaftWebSocketSession {
    private val closeMutex = Mutex()
    private var closed: Boolean = false

    private val incomingChannel = Channel<RaftWebSocketFrame>(Channel.BUFFERED)
    override val incoming: ReceiveChannel<RaftWebSocketFrame> = incomingChannel

    private val incomingJob: Job =
        session.launch {
            var failure: Throwable? = null
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> incomingChannel.send(RaftWebSocketFrame.Text(frame.readText()))
                        is Frame.Binary -> incomingChannel.send(RaftWebSocketFrame.Binary(frame.data))
                        else -> Unit
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                failure = error
            } finally {
                incomingChannel.close(failure)
            }
        }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    override suspend fun close(code: Int, reason: String) {
        val safeCode = sanitizeWebSocketCloseCode(code)
        val safeReason = truncateWebSocketCloseReasonUtf8(reason)
        withContext(NonCancellable) {
            closeMutex.withLock {
                if (closed) return@withLock
                closed = true
                try {
                    session.close(CloseReason(safeCode, safeReason))
                    awaitOutgoingCloseFlush()
                } catch (_: Throwable) {
                    // Already gone.
                } finally {
                    incomingJob.cancel()
                    incomingChannel.close()
                    runCatching { session.outgoing.close() }
                    session.cancel()
                }
            }
        }
    }

    /**
     * Ktor's `OkHttpWebsocketSession.flush()` and `DarwinWebsocketSession.flush()` are no-ops:
     * `session.close(...)` only enqueues `Frame.Close` onto `session.outgoing`. Cancelling
     * `session` immediately interrupts `OkHttpWebsocketSession.writeJob` before it dequeues
     * `Frame.Close`, causing its `finally` block to send `1011 Client failure`. Join engine-owned
     * child jobs (excluding our [incomingJob]) first so `Frame.Close` is handed to the platform
     * socket cleanly before cancelling `session`.
     */
    private suspend fun awaitOutgoingCloseFlush() {
        withTimeoutOrNull(closeFlushTimeoutMillis) {
            val engineChildren =
                session.coroutineContext[Job]
                    ?.children
                    ?.filter { it !== incomingJob }
                    ?.toList()
                    .orEmpty()
            if (engineChildren.isNotEmpty()) {
                engineChildren.forEach { it.join() }
            } else {
                runCatching { session.closeReason.await() }
            }
        }
    }
}
