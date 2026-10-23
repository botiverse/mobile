package build.raft.app.core.data.realtime

import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketTransport

/**
 * Task #154: picks the Socket.IO stack at every connect (the realtime client's reconnects and auth
 * refreshes come through connect too): [curl] when [useCurl] says so for the connection's server,
 * otherwise [legacy]. Both share [fence], so the realtime client's linearization, the generation
 * check on every callback and a switch between stacks are one lock; the stack being left is
 * disconnected before the other connects, and its late callbacks fail the generation check.
 * Listeners are registered on both stacks so either can carry the next connection.
 */
internal class SelectableRealtimeSocketTransport(
    private val legacy: RealtimeSocketTransport,
    private val curl: RealtimeSocketTransport,
    private val fence: RealtimeSocketGenerationFence,
    private val useCurl: (RealtimeSocketConnectionContext) -> Boolean,
    private val logger: (String) -> Unit,
) : RealtimeSocketTransport {
    // Guarded by fence.
    private var active: RealtimeSocketTransport? = null

    override fun <T> linearize(block: () -> T): T = fence.linearize(block)

    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        fence.linearize {
            val selectCurl = runCatching { useCurl(context) }.getOrDefault(false)
            val next = if (selectCurl) curl else legacy
            val previous = active
            if (previous != null && previous !== next) previous.disconnect()
            active = next
            logger(
                "transport_select stack=${if (selectCurl) "curl" else "socket_io_java"} " +
                    "serverId=${context.serverId.value} switched=${previous != null && previous !== next}"
            )
            next.connect(auth, context)
        }
    }

    override fun disconnect() {
        fence.linearize { (active ?: legacy).disconnect() }
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        fence.linearize { active?.emit(eventName, payload) }
    }

    override fun emitWithAck(
        eventName: String,
        onAck: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ): Boolean = fence.linearize { active?.emitWithAck(eventName, onAck) ?: false }

    override fun on(
        eventName: String,
        listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ): RealtimeSubscription =
        fence.linearize {
            val legacySubscription = legacy.on(eventName, listener)
            val curlSubscription = curl.on(eventName, listener)
            RealtimeSubscription {
                legacySubscription.close()
                curlSubscription.close()
            }
        }
}
