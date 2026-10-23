package build.raft.app.core.data.realtime

import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketTransport

/** Records emits and delivers frames to the current connection context; shared by the realtime client tests. */
internal class FakeRealtimeSocketTransport : RealtimeSocketTransport {
    private val linearizationLock = Any()
    val handlers =
        mutableMapOf<String, MutableList<(RealtimeSocketConnectionContext, Any?) -> Unit>>()
    var lastAuth: Map<String, String> = emptyMap()
    val emitted = mutableListOf<Pair<String, Map<String, Any>>>()
    var connectCount = 0
    var disconnectCount = 0
    private var currentContext: RealtimeSocketConnectionContext? = null

    override fun <T> linearize(block: () -> T): T = synchronized(linearizationLock, block)

    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        linearize {
            connectCount += 1
            lastAuth = auth
            currentContext = context
        }
    }

    override fun disconnect() {
        linearize {
            disconnectCount += 1
            currentContext = null
        }
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        linearize { emitted += eventName to payload }
    }

    override fun on(
        eventName: String,
        listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ): RealtimeSubscription =
        linearize {
            handlers.getOrPut(eventName) { mutableListOf() } += listener
            RealtimeSubscription {
                linearize { handlers[eventName]?.remove(listener) }
            }
        }

    fun receive(eventName: String, payload: Any? = null) {
        linearize {
            val context = checkNotNull(currentContext)
            handlers[eventName].orEmpty().forEach { it(context, payload) }
        }
    }
}
