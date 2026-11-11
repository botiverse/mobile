package build.raft.app.data

import build.raft.app.data.realtime.realtimeCurrentServerScopedServerIds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which servers keep a realtime session when workspaces open in separate app windows (Phase 3,
 * docs/rfc-multi-server-shell.md). The server binds a Socket.IO connection to one server, so a process
 * with one connection leaves every other window without live updates (Hands a92fbb13).
 *
 * Each shell window claims the server it shows ([claim]) and reports when it comes to the front or
 * goes to the back ([onVisibility]); closing it releases the claim ([release]). A server stays wanted
 * while any window claims it. [desiredServerIds] turns the claims into the set to connect:
 *  - granted servers only;
 *  - visible windows first, then the window that was visible most recently;
 *  - a window hidden for longer than [HiddenWindowSessionGraceMillis] stops counting;
 *  - at most [MaxRealtimeSessions] servers, visible windows always win a place over hidden ones;
 *  - no counting claim -> the last-used server, exactly as with one window.
 * With the multi-session setting off the result is today's single current-server set
 * ([realtimeCurrentServerScopedServerIds]) whatever the windows claimed.
 *
 * Time comes in as `nowMillis` from the caller, so tests drive it with a virtual clock. A window
 * whose visibility was never reported counts as visible, like [isRaftHostVisible].
 */
internal class RaftRealtimeSessionDemand {
    private class ShellDemand {
        var serverId: String? = null
        var visible: Boolean = true
        var hiddenSinceMillis: Long? = null
        var lastVisibleOrder: Long = 0L
    }

    private val lock = SynchronizedObject()
    private val shells = linkedMapOf<String, ShellDemand>()
    private var visibleOrder = 0L
    private val revisionState = MutableStateFlow(0L)

    /**
     * Bumped whenever a claim, a release or a visibility report may change [desiredServerIds], and by
     * [notifySettingChanged]. The process realtime reconcile follows it so a window that opens or closes
     * re-reconciles the whole set, not only the window that changed.
     */
    val revision: StateFlow<Long> = revisionState.asStateFlow()

    /** [shellInstanceId] now shows [serverId]; replaces the server it claimed before. A blank server releases. */
    fun claim(shellInstanceId: String, serverId: String) {
        val server = serverId.trim()
        if (server.isEmpty()) {
            release(shellInstanceId)
            return
        }
        val changed =
            synchronized(lock) {
                val shell = shells.getOrPut(normalizeShell(shellInstanceId)) { ShellDemand() }
                val previous = shell.serverId
                shell.serverId = server
                if (shell.lastVisibleOrder == 0L) shell.lastVisibleOrder = ++visibleOrder
                previous != server
            }
        if (changed) bumpRevision()
    }

    /** The window closed: drop its claim and visibility. Other windows on the same server keep it wanted. */
    fun release(shellInstanceId: String) {
        val removed = synchronized(lock) { shells.remove(normalizeShell(shellInstanceId)) }
        if (removed?.serverId != null) bumpRevision()
    }

    /** The multi-session setting changed: the process reconcile re-reads the set (and the mode). */
    fun notifySettingChanged() = bumpRevision()

    /**
     * A hidden window's [HiddenWindowSessionGraceMillis] ran out ([nextHiddenGraceExpiryMillis]): its server
     * may leave the set, so the process reconcile re-reads it without waiting for an unrelated change.
     */
    fun notifyHiddenGraceElapsed() = bumpRevision()

    /**
     * When the next hidden window with a claim stops counting (the first instant [desiredServerIds] drops it),
     * or null when no hidden window is still inside its grace at [nowMillis].
     */
    fun nextHiddenGraceExpiryMillis(nowMillis: Long): Long? =
        synchronized(lock) {
            shells.values
                .asSequence()
                .filter { shell -> shell.serverId != null && !shell.visible && !shell.hiddenTooLong(nowMillis) }
                .mapNotNull { shell -> shell.hiddenSinceMillis?.let { it + HiddenWindowSessionGraceMillis + 1L } }
                .minOrNull()
        }

    fun onVisibility(shellInstanceId: String, visible: Boolean, nowMillis: Long) {
        val changed =
            synchronized(lock) {
                val shell = shells.getOrPut(normalizeShell(shellInstanceId)) { ShellDemand() }
                val wasVisible = shell.visible
                if (visible) {
                    shell.visible = true
                    shell.hiddenSinceMillis = null
                    shell.lastVisibleOrder = ++visibleOrder
                } else if (shell.visible) {
                    shell.visible = false
                    shell.hiddenSinceMillis = nowMillis
                }
                // Only a window with a claim moves the set; a page that never claimed (thread, settings) does not.
                shell.serverId != null && wasVisible != visible
            }
        if (changed) bumpRevision()
    }

    internal fun clearForTests() {
        synchronized(lock) {
            shells.clear()
            visibleOrder = 0L
        }
        revisionState.value = 0L
    }

    private fun bumpRevision() {
        revisionState.update { it + 1 }
    }

    /** The servers to keep a realtime session for, highest priority first. */
    fun desiredServerIds(
        grantedServerIds: Set<String>,
        selectedServerId: String?,
        nowMillis: Long,
        enabled: Boolean,
    ): Set<String> {
        val legacy = realtimeCurrentServerScopedServerIds(selectedServerId, grantedServerIds)
        if (!enabled) return legacy
        val grants = grantedServerIds.asSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        val ranked =
            synchronized(lock) {
                shells.values
                    .filter { shell ->
                        val server = shell.serverId
                        server != null && server in grants && !shell.hiddenTooLong(nowMillis)
                    }
                    .sortedWith(
                        compareByDescending<ShellDemand> { it.visible }
                            .thenByDescending { it.lastVisibleOrder },
                    )
                    .mapNotNull { it.serverId }
            }
        val desired = LinkedHashSet<String>()
        for (server in ranked) {
            if (desired.size >= MaxRealtimeSessions) break
            desired += server
        }
        return desired.ifEmpty { legacy }
    }

    /** The server the front window shows: the first of [desiredServerIds]. */
    fun primaryServerId(
        grantedServerIds: Set<String>,
        selectedServerId: String?,
        nowMillis: Long,
        enabled: Boolean,
    ): String? = desiredServerIds(grantedServerIds, selectedServerId, nowMillis, enabled).firstOrNull()

    private fun ShellDemand.hiddenTooLong(nowMillis: Long): Boolean {
        val since = hiddenSinceMillis ?: return false
        return !visible && nowMillis - since > HiddenWindowSessionGraceMillis
    }

    companion object {
        /** A window hidden longer than this no longer keeps its server's session. */
        const val HiddenWindowSessionGraceMillis: Long = 5L * 60L * 1000L

        /** At most this many realtime sessions at once, whatever the number of open windows. */
        const val MaxRealtimeSessions: Int = RaftRealtimeMaxSessions
    }
}

/** At most this many realtime sessions at once (multi-session realtime); public for the Android diagnostics line. */
const val RaftRealtimeMaxSessions: Int = 3

private fun normalizeShell(shellInstanceId: String): String =
    shellInstanceId.trim().ifBlank { RaftShellStoreScope.Legacy.shellInstanceId }
