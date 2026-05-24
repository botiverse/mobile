package ai.slock.compose.data.realtime

import ai.slock.compose.data.RealtimeSubscription

interface RealtimeSocketTransport {
    fun connect(auth: Map<String, String>)
    fun disconnect()
    fun emit(eventName: String, payload: Map<String, Any> = emptyMap())
    fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription
}
