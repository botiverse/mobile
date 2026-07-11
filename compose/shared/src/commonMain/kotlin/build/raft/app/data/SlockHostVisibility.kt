package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private object SlockHostVisibilityRegistry {
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

fun updateSlockHostVisibility(shellInstanceId: String, visible: Boolean) {
    SlockHostVisibilityRegistry.update(shellInstanceId, visible)
}

fun isSlockHostVisible(shellInstanceId: String): Boolean =
    SlockHostVisibilityRegistry.isVisible(shellInstanceId)

internal fun clearSlockHostVisibilityForTests() {
    SlockHostVisibilityRegistry.clear()
}

private fun normalizeShellInstanceId(shellInstanceId: String): String =
    shellInstanceId.trim().ifBlank { SlockShellStoreScope.Legacy.shellInstanceId }
