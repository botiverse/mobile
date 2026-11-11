package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private object RaftHostVisibilityRegistry {
    private val lock = SynchronizedObject()
    private val visibilityByShell = linkedMapOf<String, Boolean>()
    private var lastVisibleShell: String? = null
    private var lastVisibleShellChanges = 0L
    private var ownerRealtimeServerId: String? = null

    fun update(shellInstanceId: String, visible: Boolean) {
        synchronized(lock) {
            val shell = normalizeShellInstanceId(shellInstanceId)
            visibilityByShell[shell] = visible
            if (visible && lastVisibleShell != shell) {
                lastVisibleShell = shell
                lastVisibleShellChanges += 1
            }
        }
    }

    fun lastVisible(): String? = synchronized(lock) { lastVisibleShell }

    fun lastVisibleChanges(): Long = synchronized(lock) { lastVisibleShellChanges }

    fun noteOwnerServer(serverId: String?) {
        synchronized(lock) { ownerRealtimeServerId = serverId?.trim()?.takeIf(String::isNotEmpty) }
    }

    fun ownerServer(): String? = synchronized(lock) { ownerRealtimeServerId }

    fun isVisible(shellInstanceId: String): Boolean =
        synchronized(lock) {
            visibilityByShell[normalizeShellInstanceId(shellInstanceId)] ?: true
        }

    fun clear() {
        synchronized(lock) {
            visibilityByShell.clear()
            lastVisibleShell = null
            lastVisibleShellChanges = 0L
            ownerRealtimeServerId = null
        }
    }
}

fun updateRaftHostVisibility(shellInstanceId: String, visible: Boolean) {
    RaftHostVisibilityRegistry.update(shellInstanceId, visible)
    raftProcessRealtimeSessionDemand.onVisibility(shellInstanceId, visible, defaultNowMillis())
}

/**
 * The process' one [RaftRealtimeSessionDemand]: shell windows claim their server here, closing a
 * window releases it ([build.raft.app.releaseRaftShellStoreScope]), [updateRaftHostVisibility] reports
 * visibility. Both the shell reconcile and the process reconcile read it, so with multi-session realtime
 * on they send the same set.
 */
internal val raftProcessRealtimeSessionDemand: RaftRealtimeSessionDemand = RaftRealtimeSessionDemand()

fun isRaftHostVisible(shellInstanceId: String): Boolean =
    RaftHostVisibilityRegistry.isVisible(shellInstanceId)

/**
 * Whether this shell window may point the process realtime connection at its own server.
 *
 * The process holds one realtime connection, for one server, and every shell window reconciles it
 * to the server it shows. With workspaces in separate windows the last window to reconcile won, even
 * a hidden one: a cold start that restored two windows could leave the visible window's server with
 * no connection, and its pages drop every other server's frame, so a reply never moved the parent's
 * thread summary (Hands a92fbb13). The window that came to the foreground last owns it; before any
 * window has reported, or with a single window, every shell may reconcile as before.
 */
internal fun raftShellOwnsProcessRealtime(shellInstanceId: String, multiShellHostEnabled: Boolean): Boolean {
    if (!multiShellHostEnabled) return true
    val owner = RaftHostVisibilityRegistry.lastVisible() ?: return true
    return owner == normalizeShellInstanceId(shellInstanceId)
}

/** Counts foreground hand-overs between shell windows; unchanged while one window keeps coming back. */
internal fun raftProcessRealtimeOwnerGeneration(): Long = RaftHostVisibilityRegistry.lastVisibleChanges()

/** The window that owns the process realtime connection records the server it pointed it at. */
internal fun noteRaftShellRealtimeServer(serverId: String?) = RaftHostVisibilityRegistry.noteOwnerServer(serverId)

/**
 * The server the process realtime connection follows: the owning window's server while it is one of
 * the granted servers, else the process' last-used [selectedServerId]. The process-level reconcile
 * (token rotation) reads this, so it does not pull the connection back to the last-used server
 * while another server's window is in front.
 */
internal fun raftProcessRealtimeServerId(selectedServerId: String?, grantedServerIds: Set<String>): String? =
    RaftHostVisibilityRegistry.ownerServer()?.takeIf { it in grantedServerIds } ?: selectedServerId

internal fun clearRaftHostVisibilityForTests() {
    RaftHostVisibilityRegistry.clear()
    raftProcessRealtimeSessionDemand.clearForTests()
}

private fun normalizeShellInstanceId(shellInstanceId: String): String =
    shellInstanceId.trim().ifBlank { RaftShellStoreScope.Legacy.shellInstanceId }
