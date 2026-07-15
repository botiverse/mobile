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

    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        build.raft.app.core.data.diagnostics.SlockAppLog.d(
            "SlockRealtime",
            "transport_connect_start serverId=${auth["serverId"].orEmpty()} hasToken=${auth["token"].isNullOrBlank().not()}"
        )
        val generation = generationFence.begin(context)
        socket?.disconnect()
        socket = IO.socket(
            socketUrl,
            IO.Options().apply {
                transports = arrayOf(WebSocket.NAME)
                reconnection = true
                this.auth = auth
            }
        ).also { newSocket ->
            listeners.forEach { (eventName, eventListeners) ->
                eventListeners.forEach { listener ->
                    bindListener(newSocket, eventName, listener, generation)
                }
            }
            newSocket.connect()
        }
    }

    override fun disconnect() {
        build.raft.app.core.data.diagnostics.SlockAppLog.d("SlockRealtime", "transport_disconnect")
        generationFence.clear()
        socket?.disconnect()
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        build.raft.app.core.data.diagnostics.SlockAppLog.d(
            "SlockRealtime",
            "transport_emit eventName=$eventName keys=${payload.keys.joinToString("|")}"
        )
        socket?.emit(eventName, realtimeSocketIoWirePayload(eventName, payload))
    }

    override fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription {
        listeners.getOrPut(eventName) { mutableSetOf() } += listener
        val generation = generationFence.currentOrNull()
        if (generation != null) {
            socket?.let { bindListener(it, eventName, listener, generation) }
        }
        return RealtimeSubscription {
            listeners[eventName]?.remove(listener)
            unbindListener(eventName, listener)
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
            if (!generationFence.accepts(generation)) return@Listener
            listener(args.firstOrNull())
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

internal data class RealtimeSocketGeneration(
    val context: RealtimeSocketConnectionContext,
)

internal class RealtimeSocketGenerationFence {
    private var current: RealtimeSocketGeneration? = null

    fun begin(context: RealtimeSocketConnectionContext): RealtimeSocketGeneration =
        RealtimeSocketGeneration(context).also { current = it }

    fun clear() {
        current = null
    }

    fun currentOrNull(): RealtimeSocketGeneration? = current

    fun accepts(generation: RealtimeSocketGeneration): Boolean = current == generation
}

internal fun realtimeSocketIoWirePayload(eventName: String, payload: Map<String, Any>): Any =
    if (eventName == RealtimeSocketEvents.JoinChannel || eventName == RealtimeSocketEvents.LeaveChannel) {
        payload["channelId"] as? String ?: JSONObject(payload)
    } else {
        JSONObject(payload)
    }
