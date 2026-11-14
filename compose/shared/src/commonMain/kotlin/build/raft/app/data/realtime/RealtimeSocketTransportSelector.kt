package build.raft.app.data.realtime

import build.raft.app.data.RealtimeSubscription
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/** The re-entrant lock a [RealtimeSocketTransport] runs its calls under ([RealtimeSocketTransport.linearize]). */
interface RealtimeTransportLinearizer {
    fun <T> linearize(block: () -> T): T
}

class ReentrantRealtimeTransportLinearizer : RealtimeTransportLinearizer {
    private val lock = reentrantLock()

    override fun <T> linearize(block: () -> T): T = lock.withLock(block)
}

/**
 * Picks the socket implementation per connection from `realtime_kmp_client_v1`
 * ([RaftRealtimeKmpClientFlag]): [kmpFactory] (the common Socket.IO client, [RaftKmpRealtimeSocketTransport])
 * when [decision] reads on at [connect], [legacy] (socket.io-client-java on Android) otherwise. The choice is
 * made only when a socket is created, never mid-connection, so turning the flag off rolls back on the next
 * connect. Switching disconnects the other implementation first.
 *
 * Both implementations must run under [linearizer] (the client's lock): the realtime client calls in from
 * its own thread and from socket callbacks, and two locks taken in opposite orders would deadlock.
 * Listeners are registered on both (the common one when it is first created); only the active one has a
 * current connection, so only it delivers.
 */
class RealtimeSocketTransportSelector(
    private val linearizer: RealtimeTransportLinearizer,
    private val legacy: RealtimeSocketTransport,
    private val kmpFactory: () -> RealtimeSocketTransport,
    private val decision: () -> RaftRealtimeKmpClientFlag.Decision,
    private val logger: (String) -> Unit = {},
) : RealtimeSocketTransport {
    private class Registration(
        val eventName: String,
        val listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
    ) {
        var legacySubscription: RealtimeSubscription? = null
        var kmpSubscription: RealtimeSubscription? = null
    }

    private val registrations = mutableListOf<Registration>()
    private var kmp: RealtimeSocketTransport? = null
    private var active: RealtimeSocketTransport? = null

    /** The implementation of the current (or last) connection: `kmp`, `legacy`, or `none` before the first. */
    val activeImplementation: String
        get() = linearize { implementationName(active) }

    override fun <T> linearize(block: () -> T): T = linearizer.linearize(block)

    override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
        linearize {
            val decided = runCatching(decision).getOrElse {
                RaftRealtimeKmpClientFlag.Decision(false, RaftRealtimeKmpClientFlag.Source.Unsupported)
            }
            val next = if (decided.enabled) kmpTransport() else legacy
            val previous = active
            if (previous != null && previous !== next) previous.disconnect()
            active = next
            logger(
                "realtime_kmp_transport selected=${implementationName(next)} source=${decided.source.token} " +
                    "serverId=${auth["serverId"].orEmpty()} switched=${previous != null && previous !== next}"
            )
            next.connect(auth, context)
        }
    }

    override fun disconnect() {
        linearize { active?.disconnect() }
    }

    override fun emit(eventName: String, payload: Map<String, Any>) {
        linearize { active?.emit(eventName, payload) }
    }

    override fun emitWithAck(
        eventName: String,
        onAck: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
    ): Boolean = linearize { active?.emitWithAck(eventName, onAck) ?: false }

    override fun on(
        eventName: String,
        listener: (context: RealtimeSocketConnectionContext, payload: Any?) -> Unit,
    ): RealtimeSubscription =
        linearize {
            val registration = Registration(eventName, listener)
            registration.legacySubscription = legacy.on(eventName, listener)
            registration.kmpSubscription = kmp?.on(eventName, listener)
            registrations += registration
            RealtimeSubscription {
                linearize {
                    registrations -= registration
                    registration.legacySubscription?.close()
                    registration.kmpSubscription?.close()
                }
            }
        }

    private fun kmpTransport(): RealtimeSocketTransport =
        kmp ?: kmpFactory().also { created ->
            kmp = created
            registrations.forEach { it.kmpSubscription = created.on(it.eventName, it.listener) }
        }

    private fun implementationName(transport: RealtimeSocketTransport?): String =
        when {
            transport == null -> "none"
            transport === legacy -> "legacy"
            else -> "kmp"
        }
}
