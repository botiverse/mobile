package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private object RaftHostVisibilityRegistry {
    private val lock = SynchronizedObject()
    private val visibilityByShell = linkedMapOf<String, Boolean>()

    fun update(shellInstanceId: String, visible: Boolean) {
        synchronized(lock) {
            visibilityByShell[normalizeShellInstanceId(shellInstanceId)] = visible
        }
    }

    fun isVisible(shellInstanceId: String): Boolean =
        synchronized(lock) {
            visibilityByShell[normalizeShellInstanceId(shellInstanceId)] ?: true
        }

    fun clear() {
        synchronized(lock) { visibilityByShell.clear() }
    }
}

fun updateRaftHostVisibility(shellInstanceId: String, visible: Boolean) {
    RaftHostVisibilityRegistry.update(shellInstanceId, visible)
}

fun isRaftHostVisible(shellInstanceId: String): Boolean =
    RaftHostVisibilityRegistry.isVisible(shellInstanceId)

internal fun clearRaftHostVisibilityForTests() {
    RaftHostVisibilityRegistry.clear()
}

private fun normalizeShellInstanceId(shellInstanceId: String): String =
    shellInstanceId.trim().ifBlank { RaftShellStoreScope.Legacy.shellInstanceId }
