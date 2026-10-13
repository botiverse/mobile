package build.raft.app.network.sync

import kotlinx.serialization.json.JsonArray

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

/** Which edge of the stored window a paging page would extend. */
internal enum class ThreadDurablePageDirection(val wireName: String) {
    /** `before=<anchorSeq>`: the page is the messages immediately older than the anchor. */
    Older("older"),

    /** `after=<anchorSeq>`: the page is the messages immediately newer than the anchor. */
    Newer("newer"),
}

/**
 * Second write entry of the offline cache (task #168): a channel / DM page the user fetched by
 * paging older (`before=anchorSeq`) or newer (`after=anchorSeq`) is persisted into the same V2
 * window [ThreadEnteredScopeSnapshotLoader] created — but only when the page joins the stored
 * window without a gap, so the one contiguous window per scope never gets a hole.
 *
 * Joining is decided by anchor membership (the paging anchor is a message that is on disk inside
 * the stored window; the page is the server's contiguous block right next to it), never by seq
 * arithmetic: seqs are server-global and not dense per channel. The extender never creates a
 * window (no stored window ⇒ nothing), never touches a scope the canary owns, and — like the
 * enter loader — re-reads `stillCurrent` after its network round trip and before the write.
 * Every non-positive case writes nothing and logs one line.
 */
internal fun interface ThreadDurablePageExtender {
    suspend fun extendWithAdjacentPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawMessages: JsonArray,
        requestedLimit: Int,
        stillCurrent: () -> Boolean,
    )
}
