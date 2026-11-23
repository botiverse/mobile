package build.raft.app.data.realtime

import build.raft.realtime.socket.RaftSocketConnection
import build.raft.realtime.socket.RaftSocketDisconnectReason
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Whether each server's realtime socket is connected again after a drop the app did not cause, for what
 * reads again when the socket connects or rejoins its rooms (RaftFreshnessSignal.onReconnected). A deploy
 * or server restart drops every client at once, and they all come back within seconds; a read on that
 * return waits a random moment first, as the web's reconnect reload does (slock socketBridge.ts
 * `RECONNECT_RELOAD_JITTER_MS_DEFAULT`). A return the app made itself (back in the foreground after it
 * closed the socket in the background) reads at once.
 *
 * Both platforms' sockets are the common client (`:realtime-socket`) under shared facades that report
 * here: [RaftKmpRealtimeSocket] (iOS) and [RaftKmpRealtimeSocketTransport] (Android, desktop).
 * - The client marks a connection it made again by itself: [RaftSocketConnection.reconnect]
 *   (RaftSocketIoClient `reconnect = everConnected`, reset by the facade's `connect()`).
 * - It reconnects by itself only after an end it did not choose: the transport closed or failed, an
 *   Engine.IO close, a ping timeout, the server's `io server disconnect`. The end's
 *   [RaftSocketDisconnectReason] tells those ([realtimeHealthError] not null) from the app's own
 *   ([RaftSocketDisconnectReason.Forced]).
 * - A socket the app closes (in the background, on sign-out or a server switch, a quiet one the heartbeat
 *   watchdog replaces) stops reconnecting, and the app's next connection is a new socket: not after a drop.
 */
object RaftRealtimeConnectionCauses {
    private val lock = SynchronizedObject()
    private val afterDrop = mutableSetOf<String>()

    /** [serverId]'s socket connected; [afterDrop]: by itself, after a drop the app did not cause. */
    internal fun onConnected(serverId: String, afterDrop: Boolean) {
        synchronized(lock) {
            if (afterDrop) this.afterDrop += serverId else this.afterDrop -= serverId
        }
    }

    /** Whether [serverId]'s socket, as last connected, came back by itself after a drop the app did not cause. */
    fun connectedAfterDrop(serverId: String): Boolean = synchronized(lock) { serverId in afterDrop }
}

/**
 * One socket's side of [RaftRealtimeConnectionCauses]: remembers how its last connection ended, so the next
 * connection can say whether it follows a drop the app did not cause. Called on the socket's serial
 * dispatcher, one instance per socket.
 */
internal class RaftRealtimeDropTracker(private val serverId: String) {
    private var lastEnd: RaftSocketDisconnectReason? = null

    fun onDisconnected(reason: RaftSocketDisconnectReason) {
        lastEnd = reason
    }

    fun onConnected(connection: RaftSocketConnection) {
        val end = lastEnd
        lastEnd = null
        RaftRealtimeConnectionCauses.onConnected(
            serverId,
            afterDrop = connection.reconnect && end?.realtimeHealthError() != null,
        )
    }
}
