package build.raft.app

import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY
import build.raft.app.data.RawMessagePage
import build.raft.app.network.sync.ThreadDurableReplyParent

/** An older page served from the durable V2 window while the stand-in is shown (task #168 step 2b). */
internal data class SharedThreadDurableOlderPage(
    val messages: List<RaftThreadMessageItem>,
    val pageSize: Int,
    val canLoadMoreBefore: Boolean,
    /** The page reaches the conversation's first message: nothing older is stored and the window starts there. */
    val reachedOldest: Boolean,
)

internal data class SharedThreadPageFetchResult(
    val payload: RaftThreadScreenPayload,
    val requestedPageSize: Int,
    /**
     * Task #168: the raw server page plus the identity that fetched it, for the durable window
     * extender. Present only for repository-backed older / newer pages; never read for rendering.
     */
    val durablePage: SharedThreadDurablePage? = null
)

/** A fetched paging page as the durable window extender needs it (task #168). */
internal data class SharedThreadDurablePage(
    val context: ReplyCacheProducerContext,
    val rawPage: RawMessagePage,
    /** Phase 3b: the durable partition's head epoch read before this page was requested (`null` = none). */
    val headEpochAtRequest: Long? = null,
    /** Phase 2c: the durable write revision read before this page was requested (`null` = none). */
    val revAtRequest: Long? = null,
)

/**
 * Task #14 part C: the `messages/context/{anchor}` page an anchored open fetched, as the durable writer needs
 * it: the identity that fetched it, the route it was fetched for (resolved by the data source, so a reply
 * route resolved by its parent pair names its thread channel), the anchor, the raw page and the server's own
 * edge facts (`hasOlder` / `hasNewer`). Never read for rendering.
 */
internal data class SharedThreadDurableContextPage(
    val context: ReplyCacheProducerContext,
    val routeIdentity: ThreadRouteIdentity,
    val replyParent: ThreadDurableReplyParent?,
    val anchorMessageId: String,
    val rawPage: RawMessagePage,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    /** Phase 3b: the durable partition's head epoch read before this page was requested (`null` = none). */
    val headEpochAtRequest: Long? = null,
    /** Phase 2c: the durable write revision read before this page was requested (`null` = none). */
    val revAtRequest: Long? = null,
)

internal object SharedThreadPaging {
    const val DefaultPageSize = 30
    const val MinimumPageSize = 10
    const val MaximumPageSize = 50

    fun resolve(candidate: Int?): Int =
        candidate?.takeIf { it in MinimumPageSize..MaximumPageSize } ?: DefaultPageSize
}

internal fun ServerFeatureFlagSnapshot.mobileThreadPageSize(): Int? =
    if (isEnabled(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY)) {
        variant(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY)?.trim()?.toIntOrNull()
    } else {
        null
    }
/**
 * A canonical fetch whose freshness generation moved while it ran (a realtime event landed) is
 * refetched at most this many extra times, with [SharedThreadStaleCanonicalRefetchBackoffMillis]
 * per round before it. The rounds used to be unbounded: a busy conversation's realtime traffic kept
 * a task conversation loading 4 full cycles (context -> channel -> parent channel) before its
 * composer could mount. After the cap the latest result is accepted as the page's content but is
 * not cached under a generation it does not match; the realtime events that moved the generation
 * are folded into the live timeline, and the next refresh re-baselines.
 */
internal const val SharedThreadStaleCanonicalRefetchMaxExtraRounds = 2
internal const val SharedThreadStaleCanonicalRefetchBackoffMillis = 150L
/** After a capped refetch the store refreshes once more after this pause, instead of committing it. */
internal const val SharedThreadCappedRefreshFollowUpDelayMillis = 1_000L
