package build.raft.app.network.sync

import kotlinx.serialization.json.JsonArray

/**
 * Write entry of the offline cache (task #1124, Phase 2-2): when the thread screen enters one
 * channel / DM, fetch that one channel's current REST page and commit it as the V2 baseline of the
 * caller's durable partition, so the next entry can render it offline through
 * [ThreadDurableLastKnownReader].
 *
 * Scope is deliberately narrow: only the channel being entered, whole-page replacement, no
 * difference stitching. Reply threads have their own entry ([loadReplyThreadOnEnter], task #168).
 * The loader never selects or widens the production canary; a scope the canary currently owns is
 * left to the canary.
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

    /**
     * Task #168 step 3: the reply-thread twin of [loadOnEnter]. Same admission (flag, one in flight
     * per scope, partition generation captured before the fetch, `stillCurrent` re-read under the
     * scope writer), but the scope is the `thread` scope [threadChannelId] under [replyParent], and
     * the server must confirm that parent before anything is written. Implementations that do not
     * persist reply threads write nothing.
     */
    suspend fun loadReplyThreadOnEnter(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        stillCurrent: () -> Boolean,
    ) = Unit
}

/**
 * The typed parent of a reply thread as the route knows it (task #168 step 3): the top-level channel
 * the root message lives in and the root message itself. A `thread` scope is read or written only
 * when its stored cursor names exactly this parent.
 */
internal data class ThreadDurableReplyParent(
    val parentChannelId: SyncScopeId,
    val rootMessageId: String,
)

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
    /**
     * [replyParent] selects the scope family explicitly: `null` = a top-level channel / DM window;
     * non-null = the reply thread [channelId] whose stored cursor must name exactly that parent
     * (task #168 step 3). Never both.
     */
    suspend fun extendWithAdjacentPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        stillCurrent: () -> Boolean,
    )

    /**
     * Gap catch-up entry (task #168 step 1, product decision "option A"): [rawMessages] is the
     * channel's LATEST page (`messages/channel/{id}?limit=[requestedLimit]`, no cursor). Whether it
     * joins the stored window is the explicit fact "a fact stored inside the window is on the page"
     * (both blocks are contiguous, so sharing one message means page ∪ window is contiguous):
     * - joined → the page is merged and the newest edge moves to the page (`extended`);
     * - not joined → the stored window is REPLACED by the page as a snapshot (`window_replaced`,
     *   logged with both windows' bounds); the dropped history comes back through older paging.
     * Same admission as [extendWithAdjacentPage]; never creates a window (no stored window ⇒
     * nothing). Implementations that do not persist pages write nothing.
     */
    suspend fun applyLatestPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        stillCurrent: () -> Boolean,
    ) = Unit

    /**
     * Task #168 step 3: called BEFORE the gap catch-up fetches its latest page, for a top-level channel /
     * DM. Returns the request-time realtime baseline to hand to [applyLatestPageAfterBaseline], or `null`
     * when none can be registered (the catch-up then commits as before and the window is display only for
     * realtime frames until the next enter).
     */
    suspend fun beginLatestPageBaseline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
    ): CanonicalMessageV2EnterBaseline? = null

    /** Task #168 step 3: the catch-up of [baseline] will not commit (failed, stale, cancelled). Idempotent. */
    suspend fun abandonLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) = Unit

    /** [applyLatestPage] with the baseline [beginLatestPageBaseline] registered before the fetch. */
    suspend fun applyLatestPageAfterBaseline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        baseline: CanonicalMessageV2EnterBaseline?,
        stillCurrent: () -> Boolean,
    ) = applyLatestPage(partition, channelId, rawMessages, requestedLimit, replyParent, stillCurrent)
}
