package build.raft.app.data

/** One incremental-sync pull requested before the account binding was known. */
internal data class RaftDeferredSyncPull(
    val serverId: String,
    val trigger: String,
)

/**
 * Incremental-sync pulls requested while the account binding is still [RaftAccountBinding.Unknown]
 * (cold start, before the stored session was read). Today such a pull is dropped.
 */
internal class RaftSyncPullBindingGate {
    /** True when the pull was kept to run once the account is bound. */
    fun deferUntilBound(serverId: String, trigger: String, binding: RaftAccountBinding<*>): Boolean = false

    /** The binding changed; returns the pulls to run now. */
    fun onBinding(binding: RaftAccountBinding<*>): List<RaftDeferredSyncPull> = emptyList()
}
