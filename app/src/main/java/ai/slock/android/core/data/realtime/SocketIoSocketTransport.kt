package ai.slock.android.core.data.realtime

import android.util.Log
import ai.slock.android.core.data.repository.RealtimeSubscription
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject

class SocketIoSocketTransport(
    private val socketUrl: String = SocketIoRealtimeClient.DEFAULT_SOCKET_URL
) : RealtimeSocketTransport {
    private val listeners = mutableMapOf<String, MutableSet<(Any?) -> Unit>>()
    private var socket: Socket? = null

    override fun connect(auth: Map<String, String>) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockRealtime",
            "transport_connect_start serverId=${auth["serverId"].orEmpty()} hasToken=${auth["token"].isNullOrBlank().not()}"
        )
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
                    newSocket.on(eventName) { args ->
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRealtime",
                            "transport_event eventName=$eventName payloadType=${args.firstOrNull()?.javaClass?.simpleName.orEmpty()}"
                        )
                        listener(args.firstOrNull())
                    }
                }
            }
            newSocket.connect()
        }
    }

    override fun disconnect() {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d("SlockRealtime", "transport_disconnect")
        socket?.disconnect()
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockRealtime",
            "transport_emit eventName=$eventName keys=${payload.keys.joinToString("|")}"
        )
        socket?.emit(eventName, JSONObject(payload))
    }

    override fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription {
        listeners.getOrPut(eventName) { mutableSetOf() } += listener
        socket?.on(eventName) { args ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockRealtime",
                "transport_event eventName=$eventName payloadType=${args.firstOrNull()?.javaClass?.simpleName.orEmpty()}"
            )
            listener(args.firstOrNull())
        }
        return RealtimeSubscription {
            listeners[eventName]?.remove(listener)
            socket?.off(eventName)
            listeners[eventName]?.forEach { remaining ->
                socket?.on(eventName) { args ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockRealtime",
                        "transport_event eventName=$eventName payloadType=${args.firstOrNull()?.javaClass?.simpleName.orEmpty()}"
                    )
                    remaining(args.firstOrNull())
                }
            }
        }
    }
}
