package build.raft.app.desktop.realtime

import build.raft.app.desktop.RaftDesktopLog
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketTransport
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import io.socket.engineio.client.transports.WebSocket
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONObject

class SocketIoSocketTransport internal constructor(
    private val socketUrl: String,
    private val generationFence: RealtimeSocketGenerationFence,
) : RealtimeSocketTransport {
    constructor(
        socketUrl: String = SocketIoRealtimeClient.DEFAULT_SOCKET_URL
    ) : this(socketUrl, RealtimeSocketGenerationFence())

    private val listeners =
        mutableMapOf<String, MutableSet<(RealtimeSocketConnectionContext, Any?) -> Unit>>()
    private val boundListeners = mutableMapOf<BoundEvent, Emitter.Listener>()
    private var socket: Socket? = null

    override fun <T> linearize(block: () -> T): T = generationFence.linearize(block)

    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        RaftDesktopLog.d(
            "SlockRealtime",
            "transport_connect_start serverId=${auth["serverId"].orEmpty()} hasToken=${auth["token"].isNullOrBlank().not()}"
        )
        generationFence.replace(context) { generation ->
            socket?.disconnect()
            val newSocket = IO.socket(
                socketUrl,
                IO.Options().apply {
                    transports = arrayOf(WebSocket.NAME)
                    reconnection = true
                    this.auth = auth
                    // artin 2026-10-23: one OkHttp client with a WebSocket ping, so a dead socket is
                    // noticed within ~2 pings instead of after Engine.IO's 45 s heartbeat window.
                    callFactory = RaftRealtimeOkHttp.client
                    webSocketFactory = RaftRealtimeOkHttp.client
                }
            )
            listeners.forEach { (eventName, eventListeners) ->
                eventListeners.forEach { listener ->
                    bindListener(newSocket, eventName, listener, generation)
                }
            }
            // Publish the exact socket before connect(): Socket.IO may synchronously invoke a
            // manager listener that re-enters connect(). A later outer assignment would otherwise
            // overwrite that successor socket after the nested connect returns.
            socket = newSocket
            newSocket.connect()
        }
    }

    override fun disconnect() {
        generationFence.clear {
            RaftDesktopLog.d("SlockRealtime", "transport_disconnect")
            socket?.disconnect()
        }
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        generationFence.withCurrent {
            RaftDesktopLog.d(
                "SlockRealtime",
                "transport_emit eventName=$eventName keys=${payload.keys.joinToString("|")}"
            )
            socket?.emit(eventName, realtimeSocketIoWirePayload(eventName, payload))
        }
    }

    override fun emitWithAck(
        eventName: String,
        onAck: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ): Boolean =
        generationFence.withCurrent { generation ->
            val current = socket
            if (generation == null || current == null) return@withCurrent false
            RaftDesktopLog.d("SlockRealtime", "transport_emit_ack eventName=$eventName")
            // No payload: the server reads the ack as the last argument (`emit("sync:horizon", ack)`).
            current.emit(
                eventName,
                Ack { args ->
                    generationFence.runIfCurrent(generation) { onAck(generation.context, args.firstOrNull()) }
                },
            )
            true
        }

    override fun on(
        eventName: String,
        listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ): RealtimeSubscription =
        generationFence.withCurrent { generation ->
            listeners.getOrPut(eventName) { mutableSetOf() } += listener
            if (generation != null) {
                socket?.let { bindListener(it, eventName, listener, generation) }
            }
            RealtimeSubscription {
                generationFence.withCurrent {
                    listeners[eventName]?.remove(listener)
                    unbindListener(eventName, listener)
                }
            }
        }

    private fun bindListener(
        socket: Socket,
        eventName: String,
        listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
        generation: RealtimeSocketGeneration,
    ) {
        val key = BoundEvent(eventName, listener)
        unbindListener(eventName, listener)
        val emitter = socket.emitterFor(eventName)
        val socketListener = Emitter.Listener { args ->
            generationFence.runIfCurrent(generation) {
                listener(generation.context, args.firstOrNull())
            }
        }
        boundListeners[key] = socketListener
        emitter.on(eventName, socketListener)
    }

    private fun unbindListener(
        eventName: String,
        listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ) {
        val key = BoundEvent(eventName, listener)
        val socketListener = boundListeners.remove(key) ?: return
        socket?.emitterFor(eventName)?.off(eventName, socketListener)
    }

    private fun Socket.emitterFor(eventName: String): Emitter =
        if (eventName in ManagerRealtimeEvents) io() else this

    private data class BoundEvent(
        val eventName: String,
        val listener: (RealtimeSocketConnectionContext, Any?) -> Unit
    )

    private companion object {
        val ManagerRealtimeEvents = setOf(
            Manager.EVENT_RECONNECT,
            Manager.EVENT_RECONNECT_ATTEMPT,
            Manager.EVENT_RECONNECT_ERROR,
            Manager.EVENT_RECONNECT_FAILED
        )
    }
}

internal class RealtimeSocketGeneration(
    val generationId: Long,
    val context: RealtimeSocketConnectionContext,
)

internal class RealtimeSocketGenerationFence(
    private val generationIdForOracle: (() -> Long)? = null,
    private val beforeCurrentDelivery: (() -> Unit)? = null,
) {
    private val linearizationLock = Any()
    private var nextGenerationId = 0L
    @Volatile
    private var current: RealtimeSocketGeneration? = null

    fun <T> linearize(block: () -> T): T = synchronized(linearizationLock, block)

    private fun allocate(context: RealtimeSocketConnectionContext): RealtimeSocketGeneration {
        val next =
            generationIdForOracle?.invoke()
                ?: (nextGenerationId + 1L).also { nextGenerationId = it }
        return RealtimeSocketGeneration(next, context).also { current = it }
    }

    fun <T> replace(
        context: RealtimeSocketConnectionContext,
        block: (RealtimeSocketGeneration) -> T,
    ): T =
        linearize {
            block(allocate(context))
        }

    fun <T> clear(block: () -> T): T =
        linearize {
            current = null
            block()
        }

    fun <T> withCurrent(block: (RealtimeSocketGeneration?) -> T): T =
        linearize {
            block(current)
        }

    private fun accepts(generation: RealtimeSocketGeneration): Boolean = current === generation

    fun runIfCurrent(
        generation: RealtimeSocketGeneration,
        block: () -> Unit,
    ): Boolean {
        val accepted = linearize { accepts(generation) }
        if (!accepted) return false
        beforeCurrentDelivery?.invoke()
        // Replacement is allowed to proceed while the pre-delivery hook runs. Re-check before
        // entering user code so a callback that lost the race cannot publish after replacement.
        if (!linearize { accepts(generation) }) return false
        // The fence only owns the generation check. Listener callbacks can re-enter the
        // realtime client and may perform blocking work (for example token refresh); keeping
        // them under the fence would let lifecycle calls such as setReconnectAllowed deadlock
        // behind a socket callback. The second check makes replacement during the hand-off win;
        // once this point is passed, the callback is linearized for this generation.
        block()
        return true
    }
}

internal fun realtimeSocketIoWirePayload(eventName: String, payload: Map<String, Any>): Any =
    if (eventName == RealtimeSocketEvents.JoinChannel || eventName == RealtimeSocketEvents.LeaveChannel) {
        payload["channelId"] as? String ?: JSONObject(payload)
    } else {
        JSONObject(payload)
    }

/**
 * The OkHttp client under socket.io-client-java (the default Android realtime stack since the Ktor
 * switch; curl Socket.IO is a Developer opt-in). [RAFT_REALTIME_PING_INTERVAL_SECONDS] WebSocket pings
 * fail a half-open socket quickly so the Socket.IO manager reconnects; the read timeout is left to the
 * ping (a quiet socket is healthy while pongs arrive).
 */
internal const val RAFT_REALTIME_PING_INTERVAL_SECONDS = 15L
internal const val RAFT_REALTIME_CONNECT_TIMEOUT_SECONDS = 10L

internal object RaftRealtimeOkHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(RAFT_REALTIME_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(RAFT_REALTIME_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0L, TimeUnit.MILLISECONDS)
            .writeTimeout(20L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
