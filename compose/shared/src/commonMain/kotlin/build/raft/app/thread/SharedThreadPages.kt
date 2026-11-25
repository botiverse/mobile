package build.raft.app

import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY
import build.raft.app.data.RawMessagePage
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableReplyParent

/** Durable first-frame or anchored-island payload paired with the scope key to touch once adopted. */
internal data class SharedThreadDurableLastKnownResult(
    val payload: RaftThreadScreenPayload,
    val scope: SyncScopeKey? = null,
)

/** An older page served strictly from inside a proven contiguous durable V2 segment (or legacy window). */
internal data class SharedThreadDurableOlderPage(
    val messages: List<RaftThreadMessageItem>,
    val pageSize: Int,
    val canLoadMoreBefore: Boolean,
    /** The page reaches the conversation's first message: nothing older is stored and the window starts there. */
    val reachedOldest: Boolean,
    /** Whether more older rows remain stored inside the SAME proven contiguous segment. */
    val hasOlderStored: Boolean = false,
    /** Phase 2d: true when `beforeSeq - 1` lies inside a proven contiguous segment (safe for local paging). */
    val insideProvenSegment: Boolean = false,
    val scope: SyncScopeKey? = null,
)

/** A newer page served strictly from inside a proven contiguous durable V2 segment. */
internal data class SharedThreadDurableNewerPage(
    val messages: List<RaftThreadMessageItem>,
    val pageSize: Int,
    val canLoadMoreAfter: Boolean,
    val reachedHead: Boolean,
    /** Whether more newer rows remain stored inside the SAME proven contiguous segment. */
    val hasNewerStored: Boolean = false,
    val insideProvenSegment: Boolean = false,
    val scope: SyncScopeKey? = null,
)

internal data class SharedThreadPageFetchResult(
    val payload: RaftThreadScreenPayload,
    val requestedPageSize: Int,
    /**
     * Task #259 (AGENTS.md No silent drops): raw server row count in `messages` before row-level
     * decode drops, thread-ownership filtering, or dedup against the loaded timeline.
     */
    val rawServerRowCount: Int = payload.messages.size,
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
