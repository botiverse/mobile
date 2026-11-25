package build.raft.app

import build.raft.app.resources.*
import build.raft.app.RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY
import build.raft.app.data.map
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.model.delegatedGroupKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import kotlin.math.ceil

private const val ThreadMessagesMinBeyondBoundsItemCount = 4
private const val ThreadMessagesMaxBeyondBoundsItemCount = 8
private const val ThreadMessagesRepresentativeRowStepDp = 64f
private const val ThreadMessagesMinMeasuredBeyondBoundsItemCount = 1
private const val ThreadMessagesRowHeightSmoothing = 0.5f
internal const val ThreadOlderMessagesListItemKey = "thread-page:older-messages"
internal const val ThreadOriginListItemKey = "thread-page:origin"
internal data class ThreadInlineActionSpec(
    val label: StringDesc,
    val event: String? = null,
    val leadingIcon: RaftLucideIconName? = null
)

internal data class ThreadHistoryStatusSpec(
    val label: StringDesc,
    val leadingIcon: RaftLucideIconName? = null,
    val secondaryLabel: String? = null,
    /** When set, the secondary line is this reply count, localized at render time. */
    val secondaryReplyCount: Int? = null,
    /**
     * Task #80: the history above is still loading (web `HistoryTopState` `loadingOlder`, a
     * `ContentLoading` spinner beside the line). Revealed after [ThreadStandInLoadingRevealDelayMs].
     */
    val loading: Boolean = false
)

/**
 * The top of the list is where the shown history starts (the "Beginning of conversation" case of
 * [threadOlderMessagesStatusSpec]): no older page to load, and not an offline stand-in cut short.
 */
internal fun ThreadTimelineSlice.threadHistoryReachesStart(): Boolean =
    !canLoadMoreBefore && !(durableLastKnown && !durableReachedOldest)

internal fun threadOlderMessagesStatusSpec(payload: RaftThreadScreenPayload): ThreadHistoryStatusSpec? =
    threadOlderMessagesStatusSpec(payload.toThreadTimelineSlice())

internal fun threadOlderMessagesStatusSpec(timelineSlice: ThreadTimelineSlice): ThreadHistoryStatusSpec? =
    threadOlderMessagesStatusSpec(
        canLoadMoreBefore = timelineSlice.canLoadMoreBefore,
        olderMessagesStatus = timelineSlice.olderMessagesStatus,
        replyThread = timelineSlice.replyThreadSurface || timelineSlice.taskConversationSurface,
        secondaryLabel = timelineSlice.beginningReplyCountLabel,
        secondaryReplyCount = timelineSlice.beginningReplyCount,
        durableLastKnown = timelineSlice.durableLastKnown,
        durableReachedOldest = timelineSlice.durableReachedOldest,
        mainLoadFailed = timelineSlice.mainLoadFailed
    )

internal fun threadOlderMessagesStatusSpec(
    canLoadMoreBefore: Boolean,
    olderMessagesStatus: String = "idle",
    replyThread: Boolean = false,
    secondaryLabel: String? = null,
    secondaryReplyCount: Int? = null,
    durableLastKnown: Boolean = false,
    durableReachedOldest: Boolean = false,
    mainLoadFailed: Boolean = false
): ThreadHistoryStatusSpec? {
    val loadingOlderLabel =
        if (replyThread) {
            Res.string.thread_state_loading_older_replies.desc()
        } else {
            Res.string.thread_state_loading_older.desc()
        }
    return when {
        // Task #258 (web HistoryTopState): while older pages remain, show "Loading older…" only
        // while a page fetch is actively running, never while idle.
        canLoadMoreBefore ->
            if (olderMessagesStatus == "loading") {
                ThreadHistoryStatusSpec(label = loadingOlderLabel, loading = true)
            } else {
                null
            }
        // Task #80 (artin): a stand-in (the Activity seed, a stored frame) while its first load is still
        // running has the rest of the conversation on the way: say so, with the spinner, as the web's
        // list top does while it loads. "Not available offline" is only true once that load failed,
        // the same gate as the offline tip (Hands e0a5248d).
        durableLastKnown && !durableReachedOldest && !mainLoadFailed ->
            ThreadHistoryStatusSpec(label = loadingOlderLabel, loading = true)
        // Task #1125: an offline stand-in frame with paging closed is NOT the beginning of the
        // conversation. Say what is true instead of claiming a boundary the frame cannot prove —
        // unless the stored window itself reaches the start (task #168): then it is the beginning.
        durableLastKnown && !durableReachedOldest ->
            ThreadHistoryStatusSpec(label = Res.string.thread_offline_older_unavailable.desc())
        else ->
            ThreadHistoryStatusSpec(
                label =
                    if (replyThread) {
                        Res.string.thread_state_beginning_replies.desc()
                    } else {
                        Res.string.thread_state_beginning_conversation.desc()
                    },
                secondaryLabel = secondaryLabel,
                secondaryReplyCount = secondaryReplyCount
            )
    }
}

/**
 * Which outer boundary of [ThreadMessagesList] currently owns the single visible loading
 * indicator (#proj-mobile:#390).
 */
internal enum class ThreadBoundaryLoadingSlot {
    Older,
    Newer,
}

/**
 * Enforces at most one visible boundary loading indicator across the timeline while keeping older
 * and newer paging fetches running in parallel in [SharedThreadStore] (#proj-mobile:#390).
 *
 * - Neither boundary loading -> `null`.
 * - Only one boundary loading -> that boundary.
 * - Both boundaries loading simultaneously -> preserve [activeSlot] if one is already showing so
 *   staggered starts never flicker between edges; otherwise pick [ThreadBoundaryLoadingSlot.Older]
 *   deterministically (the historical/top edge).
 */
internal fun resolveVisibleThreadBoundaryLoadingSlot(
    olderLoading: Boolean,
    newerLoading: Boolean,
    activeSlot: ThreadBoundaryLoadingSlot?,
): ThreadBoundaryLoadingSlot? =
    when {
        olderLoading && newerLoading -> activeSlot ?: ThreadBoundaryLoadingSlot.Older
        olderLoading -> ThreadBoundaryLoadingSlot.Older
        newerLoading -> ThreadBoundaryLoadingSlot.Newer
        else -> null
    }

/**
 * Filters an older-boundary [ThreadHistoryStatusSpec] through the active [visibleLoadingSlot].
 * Non-loading labels ("Beginning of conversation", "Older messages are not available offline")
 * are never suppressed; loading specs render only while [ThreadBoundaryLoadingSlot.Older] owns
 * the single visible boundary slot.
 */
internal fun ThreadHistoryStatusSpec?.visibleForBoundaryLoadingSlot(
    visibleLoadingSlot: ThreadBoundaryLoadingSlot?,
): ThreadHistoryStatusSpec? =
    this?.takeUnless { it.loading && visibleLoadingSlot != ThreadBoundaryLoadingSlot.Older }

/**
 * Whether [ThreadNewerMessagesBoundaryRow] should suppress its loading indicator for the given
 * [visibleLoadingSlot] (or while an empty-reply loading skeleton already owns the list). Error
 * retry rows are handled before suppression inside [ThreadNewerMessagesBoundaryRow].
 */
internal fun shouldSuppressThreadNewerBoundaryLoading(
    visibleLoadingSlot: ThreadBoundaryLoadingSlot?,
    replyLoadingSkeletonVisible: Boolean = false,
): Boolean = replyLoadingSkeletonVisible || visibleLoadingSlot != ThreadBoundaryLoadingSlot.Newer

private class ThreadBoundaryLoadingSlotHolder {
    var activeSlot: ThreadBoundaryLoadingSlot? = null
}

/**
 * Remembers the active boundary loading slot per [threadInstanceKey] so switching conversations
 * resets arbitration state, and commits updates synchronously via [SideEffect] so staggered loads
 * never flip between edges on the next recomposition.
 */
@Composable
internal fun rememberVisibleThreadBoundaryLoadingSlot(
    threadInstanceKey: String,
    olderLoading: Boolean,
    newerLoading: Boolean,
): ThreadBoundaryLoadingSlot? {
    val holder = remember(threadInstanceKey) { ThreadBoundaryLoadingSlotHolder() }
    val visibleSlot =
        resolveVisibleThreadBoundaryLoadingSlot(
            olderLoading = olderLoading,
            newerLoading = newerLoading,
            activeSlot = holder.activeSlot,
        )
    SideEffect {
        holder.activeSlot = visibleSlot
    }
    return visibleSlot
}

/**
 * The realtime transport for this page's server (re)connected — that server is reachable again. An
 * event for another server, or with no server, is not this page's recovery (LiBai #2782).
 */
internal fun isRealtimeTransportConnectedEvent(
    eventData: kotlinx.serialization.json.JsonObject,
    currentServerId: String?,
): Boolean {
    if (eventData.optString(RaftRealtimeNotifyContract.KeyAction) != RaftRealtimeNotifyContract.ActionPresenceTransportConnected) {
        return false
    }
    val serverId = currentServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return eventData.optString(RaftRealtimeNotifyContract.KeyServerId).trim() == serverId
}

/** Retry spacing for a visible page stuck on a failed load: 10 s, 20 s, 40 s, then every 60 s. */
internal fun offlineRecoveryRetryDelayMs(attempt: Int): Long =
    (10_000L shl attempt.coerceIn(0, 3)).coerceAtMost(60_000L)

internal enum class OfflineStandInRecoveryAction { Refresh, Defer, None }

/**
 * What a recovered network does to the thread on screen. Anything the last load left behind as a failure
 * is re-asked: the offline stand-in, a memory copy kept after a failed refresh (HuaTuo alpha 78: warm
 * re-entry shows that, not the stand-in), or the error page. A load still running is remembered, since it
 * may yet fail; a permission answer is final and is not retried.
 */
internal fun offlineStandInRecoveryAction(payload: RaftThreadScreenPayload): OfflineStandInRecoveryAction {
    if (payload.status == "no_permission") return OfflineStandInRecoveryAction.None
    if (payload.backgroundRefreshing || payload.status == "loading") return OfflineStandInRecoveryAction.Defer
    val failed = payload.durableLastKnown || payload.mainLoadFailed
    return if (failed) OfflineStandInRecoveryAction.Refresh else OfflineStandInRecoveryAction.None
}

/**
 * Per-page memory of a network recovery (LiBai #2782): the host de-duplicates repeated online signals,
 * so one that lands while a refresh is running must be kept until that refresh settles. Each call
 * answers whether a refresh should be dispatched now.
 */
internal class OfflineStandInRecoveryGate {
    private var pending = false

    fun onNetworkRecovered(payload: RaftThreadScreenPayload): Boolean = decide(payload)

    /** Called whenever the stand-in / refresh state changes; acts only on a remembered recovery. */
    fun onPayloadSettled(payload: RaftThreadScreenPayload): Boolean = pending && decide(payload)

    private fun decide(payload: RaftThreadScreenPayload): Boolean =
        when (offlineStandInRecoveryAction(payload)) {
            OfflineStandInRecoveryAction.Refresh -> { pending = false; true }
            OfflineStandInRecoveryAction.Defer -> { pending = true; false }
            OfflineStandInRecoveryAction.None -> { pending = false; false }
        }
}

/**
 * Task #1125 / 1.30.0 open-path jitter P0-2: chrome around a durable last-known (offline stand-in)
 * frame.
 *
 * - [tipVisible]: the tip itself shows only once the background refresh has ended without
 *   replacing the frame (transient failure).
 * - [insetActive]: mirrors [tipVisible]. Online cached opens also start on a durable stand-in
 *   before the refresh completes; reserving a phantom 40dp top inset while online causes an
 *   `inset:padding` collapse on `network_applied` and triggers 40dp drift reanchors on focused
 *   opens. Reserve the top inset only while the offline tip is actually visible.
 */
internal data class ThreadOfflineLastKnownChrome(
    val insetActive: Boolean,
    val tipVisible: Boolean
)

internal fun threadOfflineLastKnownChrome(
    durableLastKnown: Boolean,
    status: String,
    backgroundRefreshing: Boolean,
    mainLoadFailed: Boolean
): ThreadOfflineLastKnownChrome {
    // Task #1137: a channel that was empty when last seen is a stand-in too.
    val standIn = durableLastKnown && (status == "content" || status == "empty")
    // Hands e0a5248d / 1.30.0 P0-2: the stand-in also opens online, before its refresh has even
    // started; show the offline tip — and reserve its 40dp top inset — only once the network load
    // actually failed and nothing is retrying, so online cached opens do not reserve phantom
    // padding that collapses on `network_applied`.
    val tipVisible = standIn && mainLoadFailed && !backgroundRefreshing
    return ThreadOfflineLastKnownChrome(
        insetActive = tipVisible,
        tipVisible = tipVisible
    )
}

internal fun threadOfflineLastKnownChrome(payload: RaftThreadScreenPayload): ThreadOfflineLastKnownChrome =
    threadOfflineLastKnownChrome(
        durableLastKnown = payload.durableLastKnown,
        status = payload.status,
        backgroundRefreshing = payload.backgroundRefreshing,
        mainLoadFailed = payload.mainLoadFailed
    )

/** Fixed so the list's top inset equals the overlay's height exactly (two caption lines + padding). */
internal val ThreadOfflineLastKnownTipHeight = 40.dp
internal const val ThreadOfflineLastKnownTipZIndex = 3f

@Composable
internal fun ThreadOfflineLastKnownTip(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ThreadOfflineLastKnownTipHeight)
                .background(RaftColor.FillMuted)
                .padding(horizontal = RaftAppSpacing.Md, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Res.string.thread_offline_last_known_tip),
            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun threadNewerMessagesActionSpec(newerMessagesStatus: String): ThreadInlineActionSpec =
    when (newerMessagesStatus) {
        "loading" -> ThreadInlineActionSpec(label = Res.string.thread_state_loading_new.desc())
        "error" -> ThreadInlineActionSpec(label = Res.string.thread_retry_new.desc(), event = "retryLoadNewer")
        else -> ThreadInlineActionSpec(label = Res.string.thread_load_new.desc())
    }

internal fun threadMessagesLazyItemCount(
    originStatus: String,
    showHistoryBoundary: Boolean,
    messagesCount: Int,
    canLoadMoreAfter: Boolean,
    showReplyEmptyState: Boolean = false,
    showBottomScrollPadding: Boolean = false
): Int =
    (if (originStatus != "none") 1 else 0) +
        (if (showHistoryBoundary) 1 else 0) +
        messagesCount.coerceAtLeast(0) +
        (if (canLoadMoreAfter) 1 else 0) +
        (if (showReplyEmptyState) 1 else 0) +
        (if (showBottomScrollPadding) 1 else 0)

internal fun threadMessagesBeyondBoundsItemCount(
    itemCount: Int,
    viewportHeightDp: Float = 0f,
    representativeRowStepDp: Float = ThreadMessagesRepresentativeRowStepDp
): Int =
    raftViewportBeyondBoundsItemCount(
        itemCount = itemCount,
        viewportHeightDp = viewportHeightDp,
        representativeRowStepDp = representativeRowStepDp,
        minimumItems = ThreadMessagesMinBeyondBoundsItemCount,
        maximumItems = ThreadMessagesMaxBeyondBoundsItemCount
    )

internal data class ThreadLazyWindowConsumerCounts(
    val listBeyondBoundsItems: Int,
    val markdownPreparseOverscanItems: Int,
    val diagnosticsBeyondBoundsItems: Int
)

/**
 * [measuredListBeyondBoundsItems] (from [threadMeasuredBeyondBoundsItemCount], 0 = rows not
 * measured yet) sizes the composed beyond-bounds window by real row heights: a fixed item count
 * from a 64dp representative row kept 7 rows composed even when each was 1500-2260px tall, i.e.
 * several extra screens that all recomposed on any context change. The markdown preparse keeps
 * the representative-row count: that work runs off the main thread and only fills caches.
 */
internal fun threadLazyWindowConsumerCounts(
    itemCount: Int,
    viewportHeightDp: Float,
    measuredListBeyondBoundsItems: Int = 0,
    representativeRowStepDp: Float = ThreadMessagesRepresentativeRowStepDp
): ThreadLazyWindowConsumerCounts {
    val representativeCount =
        threadMessagesBeyondBoundsItemCount(
            itemCount = itemCount,
            viewportHeightDp = viewportHeightDp,
            representativeRowStepDp = representativeRowStepDp
        )
    val listCount =
        if (measuredListBeyondBoundsItems > 0 && threadViewportMeasurementReady(viewportHeightDp)) {
            measuredListBeyondBoundsItems.coerceAtMost(itemCount.coerceAtLeast(0))
        } else {
            representativeCount
        }
    return ThreadLazyWindowConsumerCounts(
        listBeyondBoundsItems = listCount,
        markdownPreparseOverscanItems = representativeCount,
        diagnosticsBeyondBoundsItems = listCount
    )
}

/**
 * Rows to keep composed beyond the viewport: about one viewport of content at the measured
 * average row height, clamped to [ThreadMessagesMinMeasuredBeyondBoundsItemCount]..
 * [ThreadMessagesMaxBeyondBoundsItemCount]. Tall rows give 1-2, short rows up to the cap.
 * 0 when either measurement is missing.
 */
internal fun threadMeasuredBeyondBoundsItemCount(
    viewportHeightDp: Float,
    averageRowHeightDp: Float
): Int {
    if (!threadViewportMeasurementReady(viewportHeightDp)) return 0
    if (!averageRowHeightDp.isFinite() || averageRowHeightDp <= 0f) return 0
    return ceil(viewportHeightDp / averageRowHeightDp).toInt()
        .coerceIn(ThreadMessagesMinMeasuredBeyondBoundsItemCount, ThreadMessagesMaxBeyondBoundsItemCount)
}

internal fun threadAverageVisibleRowHeightPx(visibleItems: List<LazyListItemInfo>): Float {
    if (visibleItems.isEmpty()) return 0f
    var total = 0L
    visibleItems.forEach { item -> total += item.size.coerceAtLeast(0) }
    return total.toFloat() / visibleItems.size
}

/** Exponential smoothing so one odd row entering or leaving does not flip the window size. */
internal fun threadSmoothedRowHeightPx(previousPx: Float, samplePx: Float): Float =
    if (previousPx <= 0f) samplePx else previousPx + (samplePx - previousPx) * ThreadMessagesRowHeightSmoothing

internal fun threadViewportMeasurementReady(viewportHeightDp: Float): Boolean =
    viewportHeightDp.isFinite() && viewportHeightDp > 0f

internal data class ThreadLazyWindowStats(
    val totalItems: Int,
    val totalMessages: Int,
    val beyondBoundsItems: Int,
    val visibleItems: Int,
    val firstVisibleItem: Int,
    val lastVisibleItem: Int,
    val firstVisibleMessage: Int,
    val lastVisibleMessage: Int,
    val visibleMessages: Int
)

internal fun threadMessagePerformanceKey(messageId: String, index: Int): String =
    "message:$index:${messageId.take(8)}"

internal fun ThreadListViewport.threadLazyWindowStats(
    timelineSlice: ThreadTimelineSlice,
    lazyItemCount: Int,
    beyondBoundsItems: Int
): ThreadLazyWindowStats {
    val visibleItems = layoutInfo.visibleItemsInfo
    val firstVisibleItem = visibleItems.firstOrNull()?.index ?: -1
    val lastVisibleItem = visibleItems.lastOrNull()?.index ?: -1
    val firstMessageListItemIndex = timelineSlice.firstMessageListItemIndex()
    val visibleMessageIndexes =
        visibleItems.mapNotNull { item ->
            (item.index - firstMessageListItemIndex)
                .takeIf { it in timelineSlice.messages.indices }
        }
    return ThreadLazyWindowStats(
        totalItems = lazyItemCount,
        totalMessages = timelineSlice.messages.size,
        beyondBoundsItems = beyondBoundsItems,
        visibleItems = visibleItems.size,
        firstVisibleItem = firstVisibleItem,
        lastVisibleItem = lastVisibleItem,
        firstVisibleMessage = visibleMessageIndexes.firstOrNull() ?: -1,
        lastVisibleMessage = visibleMessageIndexes.lastOrNull() ?: -1,
        visibleMessages = visibleMessageIndexes.size
    )
}

internal fun ThreadLazyWindowStats.toDiagnosticsString(result: String): String =
    "thread_lazy_window result=$result totalItems=$totalItems totalMessages=$totalMessages " +
        "beyondBounds=$beyondBoundsItems visibleItems=$visibleItems " +
        "visibleItemRange=$firstVisibleItem..$lastVisibleItem " +
        "visibleMessageRange=$firstVisibleMessage..$lastVisibleMessage " +
        "visibleMessages=$visibleMessages"

/**
 * task #185 (PR #3147): whether the message long-press menu must be hosted by the shell instead of
 * drawn in-page.
 *
 * A page at the window's left edge is full-width, and its in-page modal can draw the whole menu. A
 * page inside a narrower pane cannot -- the modal surface clips it, which is the defect this
 * predicate removes.
 *
 * `originMeasured` is not redundant. The origin defaults to zero and is filled in by the first
 * layout pass, so "not measured yet" and "genuinely at x = 0" are indistinguishable. Before the flag
 * existed, an unmeasured adaptive page took the in-page branch and could clip the menu once: the
 * original bug, reduced to a single frame and therefore intermittent -- and intermittent is harder
 * to diagnose than stable. Unmeasured therefore routes to the shell host, where the cost of being
 * wrong is one unnecessary host instead of one clipped menu.
 *
 * Deleting `!originMeasured ||` collapses this back to the intermittent form; see
 * `ThreadMenuShellHostPredicateTest`.
 */
/**
 * Back in a conversation header. A channel / DM that is the detail pane of a split has the list on
 * its left and nothing to go back to (web desktop has no Back there); a reply thread keeps it (it
 * returns to its channel), and single-pane pages always keep it.
 */
internal fun threadHeaderShowsBack(showBack: Boolean, surfaceKind: String): Boolean =
    showBack || surfaceKind == RaftThreadSurfaceKind.ReplyThread

/**
 * True when this conversation is the detail pane of a split: an adaptive detail page laid out right
 * of the window's left edge. A single-pane page (phones open threads through the same adaptive
 * workspace, full width) and a page not measured yet count as not split, so Back never flickers off.
 */
internal fun threadSplitDetailPane(adaptiveDetailPage: Boolean, originMeasured: Boolean, windowOriginX: Float): Boolean =
    adaptiveDetailPage && originMeasured && windowOriginX > 0f

@Composable
internal fun threadInSplitDetailPane(): Boolean {
    val params = LocalActivity.current.pageData.params
    if (params.optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY).isBlank()) return false
    val origin = RaftChildPageOriginRegistry.stateFor(raftChildPageOriginKey(THREAD_PAGE, params))
    return threadSplitDetailPane(adaptiveDetailPage = true, originMeasured = origin.measured, windowOriginX = origin.windowOrigin.x)
}

internal fun threadMenuUsesShellHost(
    adaptiveDetailPage: Boolean,
    originMeasured: Boolean,
    windowOriginX: Float,
): Boolean = adaptiveDetailPage && (!originMeasured || windowOriginX > 0f)

internal fun threadInitialFirstVisibleItemIndex(latestItemIndex: Int): Int =
    latestItemIndex.coerceAtLeast(0)

/**
 * Initial physical `LazyListState` index for a thread message list (#proj-mobile:#258).
 *
 * When the entry payload already carries its focused target message in cache, start at that row
 * instead of the latest/bottom row so Frame 1 never renders the bottom of the conversation before
 * jumping to the target. Reversed lists pair this with [ThreadInitialFocusedPlacementOwner] to
 * align the measured row before the first frame draws.
 */
internal fun threadInitialFirstVisibleItemIndex(
    reverseLayout: Boolean,
    latestItemIndex: Int,
    lazyItemCount: Int,
    focusedItemIndex: Int,
): Int {
    if (focusedItemIndex in 0 until lazyItemCount) {
        return if (reverseLayout) {
            reversedThreadListIndex(focusedItemIndex, lazyItemCount)
        } else {
            focusedItemIndex
        }
    }
    return if (reverseLayout) 0 else threadInitialFirstVisibleItemIndex(latestItemIndex = latestItemIndex)
}

internal fun threadInitialBottomAnchorTargetItemIndex(
    latestMessageItemIndex: Int,
    bottomPaddingItemIndex: Int
): Int =
    latestMessageItemIndex.takeIf { it >= 0 } ?: bottomPaddingItemIndex

internal enum class ThreadInitialBottomAnchorStage {
    UserOwned,
    AdvanceThenAnchor,
    Anchor,
    Settled
}

internal fun threadInitialBottomAnchorStage(
    initialBottomAnchorReady: Boolean,
    userScrollInProgress: Boolean,
    latestItemVisible: Boolean
): ThreadInitialBottomAnchorStage =
    when {
        initialBottomAnchorReady -> ThreadInitialBottomAnchorStage.Settled
        userScrollInProgress -> ThreadInitialBottomAnchorStage.UserOwned
        !latestItemVisible -> ThreadInitialBottomAnchorStage.AdvanceThenAnchor
        else -> ThreadInitialBottomAnchorStage.Anchor
    }

internal fun RaftThreadMessageItem.threadMessageRenderIdentity(): String =
    randomId?.trim()?.takeIf { it.isNotBlank() }?.let { "random:$it" }
        ?: id

internal fun List<RaftThreadMessageItem>.threadDuplicateRandomIds(): Set<String> =
    mapNotNull { message -> message.randomId?.trim()?.takeIf { it.isNotBlank() } }
        .groupingBy { it }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys

internal fun RaftThreadMessageItem.messageListItemKey(
    duplicateRandomIds: Set<String> = emptySet()
): String {
    val cleanRandomId = randomId?.trim()?.takeIf { it.isNotBlank() }
    val identity =
        if (cleanRandomId != null && cleanRandomId !in duplicateRandomIds) {
            "random:$cleanRandomId"
        } else {
            id
        }
    return "thread-page:$identity"
}

internal data class ThreadMessageListKeyDedupe(
    val messages: List<RaftThreadMessageItem>,
    val droppedKeys: List<String>,
)

/**
 * Safety net for the message LazyColumn: a repeated item key throws during measure and crashes
 * the app, so rows whose [messageListItemKey] repeats are dropped, keeping the last occurrence
 * (the newest in timeline order). Normal data never repeats a key; [ThreadMessageListKeyDedupe.droppedKeys]
 * is non-empty only when an upstream merge produced duplicate rows, and the caller logs it.
 */
internal fun List<RaftThreadMessageItem>.withUniqueMessageListKeys(): ThreadMessageListKeyDedupe {
    var current = this
    val dropped = mutableListOf<String>()
    while (true) {
        val duplicateRandomIds = current.threadDuplicateRandomIds()
        val keys = current.map { message -> message.messageListItemKey(duplicateRandomIds) }
        val lastIndexByKey = HashMap<String, Int>(keys.size)
        keys.forEachIndexed { index, key -> lastIndexByKey[key] = index }
        if (lastIndexByKey.size == keys.size) return ThreadMessageListKeyDedupe(current, dropped)
        // Dropping a row can make a repeated randomId unique again, which changes the surviving
        // rows' keys; loop until the keys are stable. Every pass removes at least one row.
        val next = ArrayList<RaftThreadMessageItem>(lastIndexByKey.size)
        keys.forEachIndexed { index, key ->
            if (lastIndexByKey[key] == index) next += current[index] else dropped += key
        }
        current = next
    }
}

internal data class ThreadMessageMarkdownRenderSignature(
    val renderIdentity: String,
    val bodyLength: Int,
    val bodyHash: Int
) {
    val inlineContentScope: String =
        "$renderIdentity:$bodyLength:${bodyHash.toString(16)}"
}

internal fun RaftThreadMessageItem.threadMarkdownRenderSignature(): ThreadMessageMarkdownRenderSignature =
    ThreadMessageMarkdownRenderSignature(
        renderIdentity = threadMessageRenderIdentity(),
        bodyLength = body.length,
        bodyHash = body.hashCode()
    )

internal data class ThreadMessageActionCardSignature(
    val messageId: String,
    val metadata: Map<String, String>
)

internal fun RaftThreadMessageItem.threadActionCardSignature(): ThreadMessageActionCardSignature =
    ThreadMessageActionCardSignature(
        messageId = id,
        metadata = metadata
    )

internal fun RaftThreadMessageItem.threadActionCard(): ThreadActionCard? =
    actionCardFromMessageMetadata(metadata)

internal data class ThreadCompactAuthorSignature(
    val isSystemMessage: Boolean,
    val authorIdentityKey: String,
    val timestampLabel: String,
    val delegatedGroupKey: String = ""
)

internal fun RaftThreadMessageItem.threadCompactAuthorSignature(): ThreadCompactAuthorSignature =
    ThreadCompactAuthorSignature(
        isSystemMessage = isSystemMessage(),
        authorIdentityKey = authorIdentityKey(),
        timestampLabel = timestampLabel,
        delegatedGroupKey = delegatedBy.delegatedGroupKey()
    )

internal fun RaftThreadScreenPayload.threadActionMessage(
    messageId: String,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection()
): RaftThreadMessageItem? =
    toThreadTimelineSlice().threadActionMessage(messageId, linkedTasksProjection)

internal fun formatThreadMessageQuote(text: String): String =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .trimEnd()
        .takeIf { it.trim().isNotBlank() }
        ?.let { normalized ->
            normalized
                .split('\n')
                .joinToString("\n") { line ->
                    if (line.trim().isNotEmpty()) {
                        "> $line"
                    } else {
                        ">"
                    }
                }
            }
        .orEmpty()

internal fun appendThreadQuoteToComposer(existing: String, quote: String): String {
    val cleanQuote = quote.takeIf { it.isNotBlank() } ?: return existing
    val base = existing.trimEnd()
    return if (base.isBlank()) {
        "$cleanQuote\n\n"
    } else {
        "$base\n\n$cleanQuote\n\n"
    }
}

internal fun RaftThreadScreenPayload.hasThreadHistoryBoundaryItem(): Boolean =
    messages.isNotEmpty()

private fun RaftThreadScreenPayload.hasThreadOriginItem(): Boolean =
    originStatus != "none"

private fun RaftThreadScreenPayload.threadHistoryBoundaryListItemIndex(): Int =
    if (hasThreadOriginItem()) 1 else 0

internal fun RaftThreadScreenPayload.firstMessageListItemIndex(): Int {
    var index = 0
    if (hasThreadOriginItem()) index += 1
    if (hasThreadHistoryBoundaryItem()) index += 1
    return index
}

private fun RaftThreadScreenPayload.threadMessageListItemIndex(messageId: String): Int {
    val messageIndex = messages.indexOfFirst { it.id == messageId }
    if (messageIndex < 0) return -1
    return firstMessageListItemIndex() + messageIndex
}

internal fun ThreadTimelineSlice.threadViewportListItemIndex(
    target: LazyViewportAnchorTarget
): Int =
    when (target) {
        is LazyViewportAnchorTarget.Message -> threadMessageListItemIndex(target.messageId)
        is LazyViewportAnchorTarget.StableItem ->
            threadStableViewportListItemIndex(
                key = target.key,
                originVisible = hasThreadOriginItem(),
                historyBoundaryVisible = hasThreadHistoryBoundaryItem()
            )
    }
