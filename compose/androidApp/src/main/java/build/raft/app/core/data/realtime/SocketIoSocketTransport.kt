package build.raft.app.core.data.realtime

import android.util.Log
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketTransport
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject

class SocketIoSocketTransport(
    private val socketUrl: String = SocketIoRealtimeClient.DEFAULT_SOCKET_URL
) : RealtimeSocketTransport {
    private val listeners = mutableMapOf<String, MutableSet<(Any?) -> Unit>>()
    private val boundListeners = mutableMapOf<BoundEvent, Emitter.Listener>()
    private val generationFence = RealtimeSocketGenerationFence()
    private var socket: Socket? = null

    @Synchronized
    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        build.raft.app.core.data.diagnostics.SlockAppLog.d(
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

    @Synchronized
    override fun disconnect() {
        build.raft.app.core.data.diagnostics.SlockAppLog.d("SlockRealtime", "transport_disconnect")
        generationFence.clear()
        socket?.disconnect()
    }

    @Synchronized
    override fun emit(eventName: String, payload: Map<String, Any>) {
        build.raft.app.core.data.diagnostics.SlockAppLog.d(
            "SlockRealtime",
            "transport_emit eventName=$eventName keys=${payload.keys.joinToString("|")}"
        )
        socket?.emit(eventName, realtimeSocketIoWirePayload(eventName, payload))
    }

    @Synchronized
    override fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription {
        listeners.getOrPut(eventName) { mutableSetOf() } += listener
        val generation = generationFence.currentOrNull()
        if (generation != null) {
            socket?.let { bindListener(it, eventName, listener, generation) }
        }
        return RealtimeSubscription {
            synchronized(this@SocketIoSocketTransport) {
                listeners[eventName]?.remove(listener)
                unbindListener(eventName, listener)
            }
        }
    }

    private fun bindListener(
        socket: Socket,
        eventName: String,
        listener: (Any?) -> Unit,
        generation: RealtimeSocketGeneration,
    ) {
        val key = BoundEvent(eventName, listener)
        unbindListener(eventName, listener)
        val emitter = socket.emitterFor(eventName)
        val socketListener = Emitter.Listener { args ->
            generationFence.runIfCurrent(generation) {
                listener(args.firstOrNull())
            }
        }
        boundListeners[key] = socketListener
        emitter.on(eventName, socketListener)
    }

    private fun unbindListener(eventName: String, listener: (Any?) -> Unit) {
        val key = BoundEvent(eventName, listener)
        val socketListener = boundListeners.remove(key) ?: return
        socket?.emitterFor(eventName)?.off(eventName, socketListener)
    }

    private fun Socket.emitterFor(eventName: String): Emitter =
        if (eventName in ManagerRealtimeEvents) io() else this

    private data class BoundEvent(
        val eventName: String,
        val listener: (Any?) -> Unit
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
    private var nextGenerationId = 0L
    @Volatile
    private var current: RealtimeSocketGeneration? = null

    private fun allocate(context: RealtimeSocketConnectionContext): RealtimeSocketGeneration {
        val next =
            generationIdForOracle?.invoke()
                ?: (nextGenerationId + 1L).also { nextGenerationId = it }
        return RealtimeSocketGeneration(next, context).also { current = it }
    }

    fun <T> replace(
        context: RealtimeSocketConnectionContext,
        block: (RealtimeSocketGeneration) -> T,
    ): T = block(allocate(context))

    fun clear() {
        current = null
    }

    fun currentOrNull(): RealtimeSocketGeneration? = current

    fun accepts(generation: RealtimeSocketGeneration): Boolean = current === generation

    fun runIfCurrent(
        generation: RealtimeSocketGeneration,
        block: () -> Unit,
    ): Boolean {
        if (!accepts(generation)) return false
        beforeCurrentDelivery?.invoke()
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
