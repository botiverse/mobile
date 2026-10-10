package build.raft.app.core.data.realtime

import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.RealtimeSocketTransport
import build.raft.app.network.SlockAndroidCurlSocketIo
import build.raft.app.network.SlockCurlSocketIoListener
import build.raft.app.network.SlockCurlSocketIoSession
import build.raft.app.network.SlockCurlSocketIoState
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Task #154: [RealtimeSocketTransport] over NetworkKMM's curl Socket.IO client. Drop-in for
 * [SocketIoSocketTransport]: the realtime client sees the same event names and payload types
 * (`JSONObject` / `JSONArray` / String / Number / Boolean / null), including the connection
 * lifecycle events the old library produced from its Manager:
 *
 * - `connect` when the server accepts the namespace connect; after a reconnect cycle it is preceded
 *   by `reconnect`, like the old library.
 * - `reconnect_attempt` (attempt number) each time the native loop starts another connection.
 * - `connect_error` / `reconnect_error` when the WebSocket cannot be opened (first attempt vs later);
 *   a server CONNECT_ERROR packet is always `connect_error` with the server's payload, so the auth
 *   refresh path sees the same `{"message": ...}` it saw before.
 * - `disconnect` when an established connection drops.
 *
 * The native client reconnects by itself with backoff and never gives up, so `reconnect_failed`
 * never fires. Callbacks arrive on the native connection thread and go through the same generation
 * fence as the old transport, so a replaced or cleared connection never delivers.
 *
 * Closing a native client joins its thread, and that thread may be waiting on the fence inside a
 * callback, so replaced clients are closed on [closeExecutor], never under the fence lock.
 */
internal class CurlSocketIoSocketTransport(
    private val socketUrl: String = SocketIoRealtimeClient.DEFAULT_SOCKET_URL,
    private val generationFence: RealtimeSocketGenerationFence = RealtimeSocketGenerationFence(),
    private val sessionFactory: (
        serverUrl: String,
        authJson: String,
        listener: SlockCurlSocketIoListener,
    ) -> SlockCurlSocketIoSession? = SlockAndroidCurlSocketIo::create,
    private val closeExecutor: Executor = DefaultCloseExecutor,
    private val logger: (String) -> Unit = { message ->
        build.raft.app.core.data.diagnostics.SlockAppLog.d("SlockRealtime", message)
    },
) : RealtimeSocketTransport {
    private val listeners =
        mutableMapOf<String, MutableSet<(RealtimeSocketConnectionContext, Any?) -> Unit>>()
    private var session: SlockCurlSocketIoSession? = null

    override fun <T> linearize(block: () -> T): T = generationFence.linearize(block)

    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        logger(
            "transport_connect_start stack=curl serverId=${auth["serverId"].orEmpty()} " +
                "hasToken=${auth["token"].isNullOrBlank().not()}"
        )
        generationFence.replace(context) { generation ->
            retireSession()
            val lifecycle = ConnectionLifecycle(generation)
            val created = sessionFactory(socketUrl, JSONObject(auth).toString(), lifecycle)
            if (created == null) {
                logger("transport_connect_unavailable stack=curl")
                lifecycle.deliver(RealtimeSocketEvents.ConnectError, "curl_socket_io_unavailable")
                return@replace
            }
            // Publish before start(): a callback may re-enter connect() and replace this session.
            session = created
            if (!created.start()) {
                logger("transport_connect_start_failed stack=curl")
            }
        }
    }

    override fun disconnect() {
        generationFence.clear {
            logger("transport_disconnect stack=curl")
            retireSession()
        }
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        generationFence.withCurrent {
            logger("transport_emit stack=curl eventName=$eventName keys=${payload.keys.joinToString("|")}")
            val wire = realtimeSocketIoWirePayload(eventName, payload)
            val payloadJson = if (wire is String) JSONObject.quote(wire) else wire.toString()
            if (session?.emit(eventName, payloadJson) == false) {
                logger("transport_emit_dropped stack=curl eventName=$eventName")
            }
        }
    }

    override fun on(
        eventName: String,
        listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ): RealtimeSubscription =
        generationFence.withCurrent {
            listeners.getOrPut(eventName) { mutableSetOf() } += listener
            RealtimeSubscription {
                generationFence.withCurrent { listeners[eventName]?.remove(listener) }
            }
        }

    /** Caller holds the fence. */
    private fun retireSession() {
        val previous = session ?: return
        session = null
        closeExecutor.execute { runCatching { previous.close() } }
    }

    private inner class ConnectionLifecycle(
        private val generation: RealtimeSocketGeneration,
    ) : SlockCurlSocketIoListener {
        // Touched only inside runIfCurrent (fence lock), and only from this session's native thread.
        private var reconnecting = false
        private var attempts = 0

        override fun onState(state: SlockCurlSocketIoState, code: Int, detail: String) {
            generationFence.runIfCurrent(generation) {
                when (state) {
                    SlockCurlSocketIoState.Reconnecting -> reconnecting = true
                    SlockCurlSocketIoState.Connecting ->
                        if (reconnecting) {
                            attempts += 1
                            deliverLocked(RealtimeSocketEvents.ReconnectAttempt, attempts)
                        }
                    SlockCurlSocketIoState.EngineOpen -> Unit
                    SlockCurlSocketIoState.Connected -> {
                        if (reconnecting) {
                            deliverLocked(RealtimeSocketEvents.Reconnect, attempts)
                            reconnecting = false
                            attempts = 0
                        }
                        deliverLocked(RealtimeSocketEvents.Connect, null)
                    }
                    SlockCurlSocketIoState.Disconnected ->
                        // "closed" is the final state after close(); only a dropped connection is a disconnect.
                        if (detail == NATIVE_DETAIL_TRANSPORT_CLOSED) {
                            deliverLocked(RealtimeSocketEvents.Disconnect, "transport close")
                        }
                    SlockCurlSocketIoState.Error ->
                        when (detail) {
                            NATIVE_DETAIL_WEBSOCKET_CONNECT ->
                                deliverLocked(
                                    if (reconnecting) RealtimeSocketEvents.ReconnectError else RealtimeSocketEvents.ConnectError,
                                    "websocket_connect curlCode=$code",
                                )
                            NATIVE_DETAIL_BINARY_FRAME -> logger("transport_error stack=curl reason=binary_frame_unsupported")
                            // Anything else is the payload of a server CONNECT_ERROR packet.
                            else -> deliverLocked(RealtimeSocketEvents.ConnectError, decodeSocketIoPayload(detail))
                        }
                }
            }
        }

        override fun onEvent(eventName: String, payloadJson: String) {
            generationFence.runIfCurrent(generation) {
                deliverLocked(eventName, decodeSocketIoPayload(payloadJson))
            }
        }

        /** For deliveries made while the caller already holds the fence for this generation. */
        fun deliver(eventName: String, payload: Any?) {
            generationFence.runIfCurrent(generation) { deliverLocked(eventName, payload) }
        }

        private fun deliverLocked(eventName: String, payload: Any?) {
            listeners[eventName]?.toList()?.forEach { it(generation.context, payload) }
        }
    }

    private companion object {
        const val NATIVE_DETAIL_TRANSPORT_CLOSED = "transport_closed"
        const val NATIVE_DETAIL_WEBSOCKET_CONNECT = "websocket_connect"
        const val NATIVE_DETAIL_BINARY_FRAME = "binary_frame_unsupported"

        val DefaultCloseExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "SlockCurlSocketIoClose").apply { isDaemon = true }
            }
        }
    }
}

/**
 * The payload types the old library handed listeners: JSON objects and arrays as `org.json`,
 * strings, numbers and booleans as themselves, JSON null (and an event without arguments) as null.
 * Text that is not JSON is passed through as the string itself.
 */
internal fun decodeSocketIoPayload(payloadJson: String): Any? {
    if (payloadJson.isBlank()) return null
    val value = runCatching { JSONTokener(payloadJson).nextValue() }.getOrElse { return payloadJson }
    return if (value == JSONObject.NULL) null else value
}
