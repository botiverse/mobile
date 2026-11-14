package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** One incremental-sync pull requested before the account binding was known. */
internal data class RaftDeferredSyncPull(
    val serverId: String,
    val trigger: String,
)

/**
 * Incremental-sync pulls requested while the account binding is still [RaftAccountBinding.Unknown]
 * (cold start, before the stored session was read). Such a pull has no sync scope yet; instead of
 * being dropped (architecture review B, site 4: the `server_selected` pull was skipped and nothing
 * retried it) it waits here and runs once the account is bound. A known binding without an account
 * (signed out, corrupt session) drops what waited: there is nothing to pull for.
 */
internal class RaftSyncPullBindingGate {
    private val lock = SynchronizedObject()
    private val deferred = mutableMapOf<String, String>()

    /** True when the pull was kept to run once the account is bound. */
    fun deferUntilBound(serverId: String, trigger: String, binding: RaftAccountBinding<*>): Boolean {
        if (binding !is RaftAccountBinding.Unknown) return false
        val id = serverId.trim().takeIf { it.isNotBlank() } ?: return false
        synchronized(lock) { deferred[id] = trigger }
        return true
    }

    /** The binding changed; returns the pulls to run now (each once). */
    fun onBinding(binding: RaftAccountBinding<*>): List<RaftDeferredSyncPull> {
        if (binding is RaftAccountBinding.Unknown) return emptyList()
        val waiting =
            synchronized(lock) {
                deferred.map { (serverId, trigger) -> RaftDeferredSyncPull(serverId, trigger) }
                    .also { deferred.clear() }
            }
        return if (binding is RaftAccountBinding.SignedIn) waiting else emptyList()
    }
}
