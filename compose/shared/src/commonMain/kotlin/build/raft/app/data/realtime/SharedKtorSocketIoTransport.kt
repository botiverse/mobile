package build.raft.app.data.realtime

import build.raft.app.data.RealtimeSubscription
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.createSlockKtorHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared websocket-only Socket.IO transport pilot.
 *
 * This intentionally implements the narrow Engine.IO v4 / Socket.IO v4 subset
 * already used by the OHOS ArkTS transport: websocket transport only, no
 * polling upgrade, and no binary attachments.
 */
class SharedKtorSocketIoTransport(
    private val socketUrl: String,
    private val httpClient: HttpClient,
    private val headersProvider: () -> Map<String, String> = { emptyMap() },
    private val logger: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : RealtimeSocketTransport {
    private val listeners = mutableMapOf<String, MutableSet<(Any?) -> Unit>>()
    private val mutex = Mutex()
    private var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    private var receiveJob: kotlinx.coroutines.Job? = null

    override fun connect(auth: Map<String, String>) {
        scope.launch {
            mutex.withLock {
                closeCurrentSessionLocked(reason = "reconnect")
                logger("shared_socketio_connect_start serverId=${auth["serverId"].orEmpty()} hasToken=${auth["token"].isNullOrBlank().not()}")
                runCatching {
                    val nextSession =
                        httpClient.webSocketSession {
                            url(SharedSocketIoProtocol.websocketUrl(socketUrl))
                            headersProvider().forEach { (key, value) -> header(key, value) }
                        }
                    session = nextSession
                    receiveJob = scope.launch { receiveLoop(nextSession, auth) }
                }.onFailure { error ->
                    logger("shared_socketio_connect_failed message=${error.message.orEmpty()}")
                    notify(RealtimeSocketEvents.ConnectError, error.message.orEmpty())
                }
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            mutex.withLock {
                closeCurrentSessionLocked(reason = "client_disconnect")
            }
            notify(RealtimeSocketEvents.Disconnect, "client_disconnect")
        }
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        val frame = SharedSocketIoProtocol.eventFrame(eventName, payload)
        scope.launch {
            val activeSession = mutex.withLock { session }
            if (activeSession == null) {
                logger("shared_socketio_emit_deferred eventName=$eventName")
                return@launch
            }
            runCatching { activeSession.send(frame) }
                .onFailure { error -> logger("shared_socketio_send_failed eventName=$eventName message=${error.message.orEmpty()}") }
        }
    }

    override fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription {
        listeners.getOrPut(eventName) { mutableSetOf() } += listener
        return RealtimeSubscription {
            listeners[eventName]?.remove(listener)
        }
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }

    private suspend fun receiveLoop(
        activeSession: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        auth: Map<String, String>
    ) {
        try {
            for (frame in activeSession.incoming) {
                val text = when (frame) {
                    is Frame.Text -> frame.readText()
                    else -> continue
                }
                handleFrame(activeSession, text, auth)
            }
            logger("shared_socketio_receive_closed")
            notify(RealtimeSocketEvents.Disconnect, "websocket_closed")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger("shared_socketio_receive_failed message=${error.message.orEmpty()}")
            notify(RealtimeSocketEvents.Disconnect, error.message.orEmpty())
        }
    }

    private suspend fun handleFrame(
        activeSession: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        frame: String,
        auth: Map<String, String>
    ) {
        when (val decoded = SharedSocketIoProtocol.decodeFrame(frame)) {
            SharedSocketIoDecodedFrame.Empty -> Unit
            is SharedSocketIoDecodedFrame.EngineOpen -> {
                logger(
                    "shared_socketio_engine_open pingInterval=${decoded.pingInterval ?: 0L} " +
                        "pingTimeout=${decoded.pingTimeout ?: 0L}"
                )
                activeSession.send(SharedSocketIoProtocol.connectFrame(auth))
            }
            SharedSocketIoDecodedFrame.EngineClose -> {
                logger("shared_socketio_engine_close")
                notify(RealtimeSocketEvents.Disconnect, "engine_io_close")
            }
            SharedSocketIoDecodedFrame.EnginePing -> {
                activeSession.send(SharedSocketIoProtocol.pongFrame())
            }
            SharedSocketIoDecodedFrame.SocketConnect -> {
                logger("shared_socketio_connected")
                notify(RealtimeSocketEvents.Connect, null)
            }
            SharedSocketIoDecodedFrame.SocketDisconnect -> {
                logger("shared_socketio_socket_disconnect")
                notify(RealtimeSocketEvents.Disconnect, "socket_disconnect")
            }
            is SharedSocketIoDecodedFrame.SocketConnectError -> {
                logger("shared_socketio_connect_error message=${decoded.message}")
                notify(RealtimeSocketEvents.ConnectError, decoded.message)
            }
            is SharedSocketIoDecodedFrame.SocketEvent -> {
                notify(decoded.eventName, decoded.payload)
            }
            SharedSocketIoDecodedFrame.InvalidEvent -> logger("shared_socketio_invalid_event")
            SharedSocketIoDecodedFrame.Unknown -> logger("shared_socketio_unknown_frame")
        }
    }

    private suspend fun closeCurrentSessionLocked(reason: String) {
        val activeJob = receiveJob
        val activeSession = session
        receiveJob = null
        session = null
        activeJob?.cancel()
        activeSession?.close()
        if (activeSession != null) {
            logger("shared_socketio_socket_closed reason=$reason")
        }
    }

    private fun notify(eventName: String, payload: Any?) {
        listeners[eventName]?.toList()?.forEach { listener ->
            listener(payload)
        }
    }
}

fun createSharedKtorSocketIoTransport(
    socketUrl: String,
    headersProvider: () -> Map<String, String> = { emptyMap() },
    logger: (String) -> Unit = {},
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): SharedKtorSocketIoTransport =
    SharedKtorSocketIoTransport(
        socketUrl = socketUrl,
        httpClient = createSlockKtorHttpClient(SlockRequestPriority.Background),
        headersProvider = headersProvider,
        logger = logger,
        scope = scope
    )

fun createSharedKtorSocketIoTransportForHost(
    socketUrl: String,
    headersProvider: () -> Map<String, String> = { emptyMap() },
    logger: (String) -> Unit = {}
): SharedKtorSocketIoTransport =
    createSharedKtorSocketIoTransport(
        socketUrl = socketUrl,
        headersProvider = headersProvider,
        logger = logger
    )
