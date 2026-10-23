package build.raft.app.data.realtime

import build.raft.app.data.RealtimeSubscription

interface RealtimeSocketTransport {
    fun <T> linearize(block: () -> T): T
    fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext)
    fun disconnect()
    fun emit(eventName: String, payload: Map<String, Any> = emptyMap())

    /**
     * Emits [eventName] with no payload and a Socket.IO ack on the current socket. [onAck] gets that
     * socket's context and the ack's first argument, only while that socket is still current; it may
     * never run (an older server does not answer). Returns false when this transport cannot carry an
     * ack (nothing was sent).
     */
    fun emitWithAck(
        eventName: String,
        onAck: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
    ): Boolean = false
    fun on(
        eventName: String,
        listener: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
    ): RealtimeSubscription
}
