package build.raft.app.network.sync

import build.raft.app.data.ChannelMessageWindow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonArray

/**
 * Write entry of the offline cache (task #1124; message storage Phase 2b): the page the thread screen itself
 * fetched for the channel / DM / reply thread it shows is written through to the caller's durable partition, so the
 * next entry can render it offline through [ThreadDurableLastKnownReader]. Before Phase 2b the loader fetched its own
 * copy of the page (`channels/{id}`, `messages/channel?limit=200`, `channels/{id}/threads`).
 *
 * The thread store calls [beginEnter] BEFORE its page request (the realtime baseline, the partition generation, the
 * write revision and the head epoch are taken then), then [persistEnteredPage] with the page it got, or [abandonEnter]
 * when it got none. `stillCurrent` is re-read before the write, so an identity change while the request was in flight
 * cannot land identity A's page under identity B's key. Implementations answer every non-positive case by writing
 * nothing and logging.
 */
internal interface ThreadEnteredScopeSnapshotLoader {
    /**
     * Phase 2b: [channelId] (a top-level channel / DM, or with [replyParent] the reply thread under it) is about to
     * be fetched. Registers the realtime baseline (unless [registerBaseline] is `false`: the page was fetched
     * already) and reads the partition generation, write revision and head epoch. `null` = this loader writes nothing
     * for it.
     */
    suspend fun beginEnter(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent?,
        registerBaseline: Boolean = true,
        /** The generation and revision read before the request, when the ticket is taken after it. */
        stamp: ThreadDurableRequestStamp? = null,
    ): ThreadDurableEnterTicket? = null

    /** Phase 2b: [partition]'s generation and write revision, read before a request that cannot name its scope yet. */
    suspend fun requestStamp(partition: DurablePartitionKey): ThreadDurableRequestStamp? = null

    /** Phase 2b: write [page] (the page the thread store fetched after [beginEnter]) under [ticket]. */
    suspend fun persistEnteredPage(
        ticket: ThreadDurableEnterTicket,
        page: ThreadDurableEnteredPage,
        stillCurrent: () -> Boolean,
    ) = Unit

    /** Phase 2b: [ticket]'s fetch produced no page to write (failed, reused, focused open). Idempotent. */
    suspend fun abandonEnter(ticket: ThreadDurableEnterTicket) = Unit

    /**
     * Task #14b: the Activity reply-thread prewarm already fetched the thread's latest reply page
     * ([rawMessages], sent with `limit=`[requestedLimit]); persist THAT page as the `thread` scope's
     * window instead of discarding it with the in-memory prewarm. Same admission as
     * [beginEnter] + [persistEnteredPage] (flag, partition generation, server-confirmed parent, `stillCurrent`
     * re-read under the scope writer) but page-only: no realtime baseline is registered, a stored
     * window at least as new as the page is never replaced, and any write of the scope after
     * [expectedCommitGeneration] was read (e.g. an enter that saw a deletion) makes the page stale. Implementations that do not persist reply
     * threads write nothing.
     */
    /**
     * Task #14b: the thread scope's commit generation, read by the prewarm BEFORE its page fetch and handed
     * back as [persistReplyThreadPrewarmPage]'s `expectedCommitGeneration`. `null` = this loader does not
     * persist prewarm pages.
     */
    fun replyThreadCommitGeneration(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
    ): Long? = null

    suspend fun persistReplyThreadPrewarmPage(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        rawMessages: JsonArray,
        requestedLimit: Int,
        /** [replyThreadCommitGeneration] read before the page was fetched; any later write of the scope wins. */
        expectedCommitGeneration: Long,
        /** The page's server `messageWindow` and `historyLimited` (what range it proves; null = legacy claim). */
        messageWindow: ChannelMessageWindow? = null,
        historyLimited: Boolean = false,
        /**
         * Phase 3b: the partition's head epoch ([headEpoch]) read BEFORE the page's HTTP request (with the partition
         * generation). A horizonReset between that read and this write voids the page's head claim, never its facts.
         * `null` = not captured: the writer reads it now.
         */
        requestHeadEpoch: Long? = null,
        /** Phase 2b: [currentLocalRev] read before the page's fetch (`null` = read at write time). */
        revAtRequest: Long? = null,
        stillCurrent: () -> Boolean,
    ) = Unit

    /** Phase 3b: the partition's head epoch, for a caller to read before its page request (`null` = not tracked). */
    fun headEpoch(partition: DurablePartitionKey): Long? = null

    /** Message storage Phase 2b: the partition's fact write revision, read before a page fetch (`revAtRequest`). */
    suspend fun currentLocalRev(partition: DurablePartitionKey): Long? = null
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
 * paging older (`before=anchorSeq`) or newer (`after=anchorSeq`) is stored with the seq range its server window proves,
 * unioned into the scope's segments (Phase 2c). A page that reaches the head's coverage grows it; any other is an island,
 * stored but not shown until Phase 2d. The extender re-reads `stillCurrent` after its network round trip and before the
 * write. Every non-positive case writes nothing and logs one line.
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
        /**
         * Phase 3b: the partition's head epoch ([headEpoch]) read BEFORE the page's HTTP request (with the partition
         * generation). A horizonReset between that read and this write voids the page's head claim, never its facts.
         * `null` = not captured: the writer reads it now.
         */
        requestHeadEpoch: Long?,
        /** Phase 2c: the page's server window (scope key and range; `null` = nothing is written) and history flag. */
        messageWindow: ChannelMessageWindow? = null,
        historyLimited: Boolean = false,
        /** Phase 2c: [currentLocalRev] read before the page's fetch (`null` = read at write time). */
        revAtRequest: Long? = null,
        /** Phase 2c: the counts the response embeds; a thread parent without one is left out. Never fetched. */
        replyCountByParentId: Map<String, Int>? = null,
        stillCurrent: () -> Boolean,
    )

    /** Phase 3b: the partition's head epoch, for a caller to read before its page request (`null` = not tracked). */
    fun headEpoch(partition: DurablePartitionKey): Long? = null

    /**
     * Gap catch-up entry (task #168 step 1, product decision "option A"): [rawMessages] is the
     * channel's LATEST page (`messages/channel/{id}?limit=[requestedLimit]`, no cursor). Message storage
     * Phase 2a: the page is stored with the seq range it proves ([messageWindow], else the legacy latest-page
     * claim) unioned into the stored ranges — `window_extended` when it reaches the stored head, otherwise
     * the old head stays as an older island (`island_kept`); nothing stored is dropped.
     * Same admission as [extendWithAdjacentPage]; never creates a window (no stored window ⇒
     * nothing). Implementations that do not persist pages write nothing.
     */
    suspend fun applyLatestPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        /** The page's server `messageWindow` and `historyLimited` (what range it proves; null = legacy claim). */
        messageWindow: ChannelMessageWindow? = null,
        historyLimited: Boolean = false,
        /**
         * Phase 3b: the partition's head epoch ([headEpoch]) read BEFORE the page's HTTP request (with the partition
         * generation). A horizonReset between that read and this write voids the page's head claim, never its facts.
         * `null` = not captured: the writer reads it now.
         */
        requestHeadEpoch: Long? = null,
        /** Phase 2b: [currentLocalRev] read before the page's fetch (`null` = read at write time). */
        revAtRequest: Long? = null,
        stillCurrent: () -> Boolean,
    ) = Unit

    /**
     * Message storage Phase 2b: the partition's fact write revision, read by a caller BEFORE it fetches a page and
     * handed back as that page's `revAtRequest` (a fact written after it is newer than the page and kept). `null` =
     * this writer does not persist pages.
     */
    suspend fun currentLocalRev(partition: DurablePartitionKey): Long? = null

    /**
     * Task #168 step 3: called BEFORE the gap catch-up fetches its latest page, for a top-level channel /
     * DM, or (task #14c, [replyParent] non-null) a reply thread. Returns the request-time realtime baseline to
     * hand to [applyLatestPageAfterBaseline], or `null` when none can be registered (the catch-up then commits
     * as before and the window is display only for realtime frames until the next enter).
     */
    suspend fun beginLatestPageBaseline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent?,
    ): CanonicalMessageV2EnterBaseline? = null

    /**
     * Task #14c: a late `channel:joined` of a reply thread whose enter committed discontinuous only for want of an
     * earlier ack; the open page of that thread runs one gap catch-up. Hot, never replayed. Empty by default.
     */
    fun threadJoinConfirmations(): Flow<CanonicalMessageV2ThreadJoinConfirmed> = emptyFlow()

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
        messageWindow: ChannelMessageWindow? = null,
        historyLimited: Boolean = false,
        /**
         * Phase 3b: the partition's head epoch ([headEpoch]) read BEFORE the page's HTTP request (with the partition
         * generation). A horizonReset between that read and this write voids the page's head claim, never its facts.
         * `null` = not captured: the writer reads it now.
         */
        requestHeadEpoch: Long? = null,
        revAtRequest: Long? = null,
        stillCurrent: () -> Boolean,
    ) = applyLatestPage(
        partition, channelId, rawMessages, requestedLimit, replyParent, messageWindow, historyLimited, requestHeadEpoch, revAtRequest,
        stillCurrent,
    )

    /**
     * Phase 2c: [rawMessages] is a `messages/context/{anchor}` page, the server's contiguous block around
     * [anchorMessageId], with its own edge fact [hasOlder]. [channelId] is the RESPONSE's top-level `channelId`, which may
     * be a thread and need not be the route's: the page is written under that scope, never mixed into a parent's.
     * The page merges with a stored range only when they share a message id; otherwise it is an island, which is
     * stored but is neither the head nor read until Phase 2d. [hasNewer] proves nothing: only a `direction: latest` fetch
     * proves the head. Implementations that do not persist pages write nothing.
     */
    suspend fun persistContextPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        anchorMessageId: String,
        rawMessages: JsonArray,
        hasOlder: Boolean,
        hasNewer: Boolean,
        replyParent: ThreadDurableReplyParent?,
        /**
         * Phase 3b: the partition's head epoch ([headEpoch]) read BEFORE the page's HTTP request (with the partition
         * generation). A horizonReset between that read and this write voids the page's head claim, never its facts.
         * `null` = not captured: the writer reads it now.
         */
        requestHeadEpoch: Long? = null,
        /** `!hasOlder && historyLimited` is the plan's cutoff, not the channel's start. */
        historyLimited: Boolean = false,
        /** [currentLocalRev] read before the page's fetch (`null` = read at write time). */
        revAtRequest: Long? = null,
        /** The counts the response embeds; a thread parent without one is left out. Never fetched. */
        replyCountByParentId: Map<String, Int>? = null,
        /** `false` for a link or overview write: it stores facts but is no use of the scope for retention. */
        userDriven: Boolean = true,
        /** The scope's channel type if the caller already read it (a never-stored scope needs one). */
        channelType: String? = null,
        stillCurrent: () -> Boolean,
    ) = Unit
}

/**
 * Message storage Phase 2b: what [ThreadEnteredScopeSnapshotLoader.beginEnter] took before the thread store's page
 * request. Opaque to the thread store.
 */
internal class ThreadDurableEnterTicket(
    val partition: DurablePartitionKey,
    val channelId: SyncScopeId,
    val replyParent: ThreadDurableReplyParent?,
    val expectedPartitionGeneration: Long,
    val revAtRequest: Long,
    val baseline: CanonicalMessageV2EnterBaseline?,
    /** Phase 3b: the partition's head epoch read before the request (`null` = not tracked). */
    val expectedHeadEpoch: Long? = null,
) {
    val stamp: ThreadDurableRequestStamp
        get() = ThreadDurableRequestStamp(partition, expectedPartitionGeneration, revAtRequest, expectedHeadEpoch)
}

/**
 * Message storage Phase 2b: a partition's generation and write revision as read before a request; Phase 3b: with its
 * head epoch (a horizonReset after that read voids the page's head claim, never its facts).
 */
internal data class ThreadDurableRequestStamp(
    val partition: DurablePartitionKey,
    val expectedPartitionGeneration: Long,
    val revAtRequest: Long,
    val expectedHeadEpoch: Long? = null,
)

/**
 * Message storage Phase 2b: the page the thread store fetched for [channelId] (`messages/channel/{id}?limit=`), its raw
 * rows and server window, plus what the same load already knew: [channelType] / [taskParentChannelId] from the
 * channel detail (top-level only; `null` = unknown) and [replyCountByParentId] from the page's embedded summaries
 * (`null` = the page carried none).
 */
internal data class ThreadDurableEnteredPage(
    val channelId: SyncScopeId,
    val rawMessages: JsonArray,
    val requestedLimit: Int,
    val messageWindow: ChannelMessageWindow?,
    val historyLimited: Boolean,
    val channelType: String? = null,
    val taskParentChannelId: SyncScopeId? = null,
    val replyCountByParentId: Map<String, Int>? = null,
)
