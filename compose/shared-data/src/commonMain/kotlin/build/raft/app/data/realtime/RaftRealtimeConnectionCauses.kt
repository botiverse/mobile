package build.raft.app.data.realtime

import build.raft.app.RaftCoreInternalApi

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
    private var nextConnectionToken = 0L
    private val liveTokens = mutableMapOf<String, Long>()

    /**
     * [serverId]'s socket connected; [afterDrop]: by itself, after a drop the app did not cause.
     * Returns the monotonic live connection token for this socket connection.
     */
    @RaftCoreInternalApi fun onConnected(serverId: String, afterDrop: Boolean): Long =
        synchronized(lock) {
            if (afterDrop) this.afterDrop += serverId else this.afterDrop -= serverId
            val token = ++nextConnectionToken
            liveTokens[serverId] = token
            token
        }

    /**
     * [serverId]'s connection [token] ended (disconnected or closed). Ignored if a newer socket
     * connection has already replaced it.
     */
    @RaftCoreInternalApi fun onDisconnected(serverId: String, token: Long) {
        if (token <= 0L) return
        synchronized(lock) {
            if (liveTokens[serverId] == token) {
                liveTokens.remove(serverId)
            }
        }
    }

    /** Whether [serverId]'s socket, as last connected, came back by itself after a drop the app did not cause. */
    fun connectedAfterDrop(serverId: String): Boolean = synchronized(lock) { serverId in afterDrop }

    /**
     * Monotonic token of [serverId]'s currently connected socket, or null when disconnected /
     * never connected (#proj-mobile:#198, raft-design freshness.md: a foreground return while the
     * same socket stayed continuously connected within 60 s of the last check skips a redundant read).
     */
    fun liveConnectionToken(serverId: String): Long? = synchronized(lock) { liveTokens[serverId] }
}

/**
 * One socket's side of [RaftRealtimeConnectionCauses]: remembers how its last connection ended, so the next
 * connection can say whether it follows a drop the app did not cause, and tracks its live token so callers
 * can tell whether the socket stayed connected without dropping.
 */
@RaftCoreInternalApi class RaftRealtimeDropTracker(private val serverId: String) {
    private val lock = SynchronizedObject()
    private var lastEnd: RaftSocketDisconnectReason? = null
    private var activeToken: Long = 0L

    fun onDisconnected(reason: RaftSocketDisconnectReason) {
        val ended =
            synchronized(lock) {
                lastEnd = reason
                val token = activeToken
                activeToken = 0L
                token
            }
        RaftRealtimeConnectionCauses.onDisconnected(serverId, ended)
    }

    fun onClosed() {
        val ended =
            synchronized(lock) {
                val token = activeToken
                activeToken = 0L
                token
            }
        RaftRealtimeConnectionCauses.onDisconnected(serverId, ended)
    }

    fun onConnected(connection: RaftSocketConnection) {
        val end =
            synchronized(lock) {
                val previous = lastEnd
                lastEnd = null
                previous
            }
        val token =
            RaftRealtimeConnectionCauses.onConnected(
                serverId,
                afterDrop = connection.reconnect && end?.realtimeHealthError() != null,
            )
        synchronized(lock) {
            activeToken = token
        }
    }
}
