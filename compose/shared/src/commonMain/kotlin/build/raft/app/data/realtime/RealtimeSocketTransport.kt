package build.raft.app.data.realtime

import build.raft.app.data.RealtimeSubscription

interface RealtimeSocketTransport {
    fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext)
    fun disconnect()
    fun emit(eventName: String, payload: Map<String, Any> = emptyMap())
    fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription
}
