package build.raft.app.data

import build.raft.app.data.realtime.realtimeCurrentServerScopedServerIds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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

    /** [shellInstanceId] now shows [serverId]; replaces the server it claimed before. A blank server releases. */
    fun claim(shellInstanceId: String, serverId: String) {
        val server = serverId.trim()
        if (server.isEmpty()) {
            release(shellInstanceId)
            return
        }
        synchronized(lock) {
            val shell = shells.getOrPut(normalizeShell(shellInstanceId)) { ShellDemand() }
            shell.serverId = server
            if (shell.lastVisibleOrder == 0L) shell.lastVisibleOrder = ++visibleOrder
        }
    }

    /** The window closed: drop its claim and visibility. Other windows on the same server keep it wanted. */
    fun release(shellInstanceId: String) {
        synchronized(lock) { shells.remove(normalizeShell(shellInstanceId)) }
    }

    fun onVisibility(shellInstanceId: String, visible: Boolean, nowMillis: Long) {
        synchronized(lock) {
            val shell = shells.getOrPut(normalizeShell(shellInstanceId)) { ShellDemand() }
            if (visible) {
                shell.visible = true
                shell.hiddenSinceMillis = null
                shell.lastVisibleOrder = ++visibleOrder
            } else if (shell.visible) {
                shell.visible = false
                shell.hiddenSinceMillis = nowMillis
            }
        }
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
        const val MaxRealtimeSessions: Int = 3
    }
}

private fun normalizeShell(shellInstanceId: String): String =
    shellInstanceId.trim().ifBlank { RaftShellStoreScope.Legacy.shellInstanceId }
