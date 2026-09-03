package build.raft.app.network.sync

/**
 * Write entry of the offline cache (task #1124, Phase 2-2): when the thread screen enters one
 * channel / DM, fetch that one channel's current REST page and commit it as the V2 baseline of the
 * caller's durable partition, so the next entry can render it offline through
 * [ThreadDurableLastKnownReader].
 *
 * Scope is deliberately narrow: only the channel being entered, whole-page replacement, no
 * difference stitching, no reply threads (no V2 writer for them yet). The loader never selects or
 * widens the production canary; a scope the canary currently owns is left to the canary.
 *
 * `stillCurrent` is re-read after the network round trip and before the write, so an identity
 * change while the request was in flight cannot land identity A's page under identity B's key.
 * Implementations answer every non-positive case by writing nothing and logging.
 */
internal fun interface ThreadEnteredScopeSnapshotLoader {
    suspend fun loadOnEnter(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        stillCurrent: () -> Boolean,
    )
}
