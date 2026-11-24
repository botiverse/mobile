package build.raft.app

import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftHoverTier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import build.raft.app.network.sync.CanonicalMessageV2TimelineGap
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
import build.raft.app.resources.localized
import build.raft.app.resources.thread_offline_gap_unavailable
import build.raft.app.resources.thread_offline_gap_unavailable_replies
import build.raft.app.resources.thread_retry_gap
import build.raft.app.resources.thread_retry_gap_replies
import build.raft.app.resources.thread_state_loading_gap
import build.raft.app.resources.thread_state_loading_gap_replies
import kotlinx.serialization.Serializable

internal const val ThreadFillTimelineGapAction: String = "fillTimelineGap"

/**
 * #proj-mobile:#358: grace window before showing an inline gap loading row while an initial
 * stand-in refresh (`durableLastKnown && !mainLoadFailed`) is in flight. Unlike top-of-list
 * history status rows (600 ms), inline gaps sit between two visible messages (often when loose
 * realtime/resume rows land above a cached head segment), so ordinary slow opens and brief
 * reconnects settle into the contiguous server timeline without flashing a mid-list spinner or
 * reserving blank whitespace between messages.
 */
internal const val ThreadStandInInlineGapRevealDelayMs: Long = 2_000L

/**
 * Message storage design 2026-10-27 (Phase 2d): derived inline gap between two stored messages
 * `(afterSeq, beforeSeq)` where missing server messages may exist. Never persisted; always
 * reconstructed from SQLite segments on bounded reads and reconciled on gap fills / refreshes.
 */
@Serializable
internal data class ThreadTimelineGap(
    val afterSeq: Long,
    val beforeSeq: Long,
    /** `"idle"` | `"loading"` | `"error"` */
    val status: String = "idle",
) {
    init {
        require(afterSeq >= 0L && beforeSeq > afterSeq && beforeSeq - 1L > afterSeq) {
            "timeline gap ($afterSeq, $beforeSeq) is empty"
        }
    }

    val key: String get() = "$afterSeq:$beforeSeq"
}

internal fun CanonicalMessageV2TimelineGap.toThreadTimelineGap(status: String = "idle"): ThreadTimelineGap =
    ThreadTimelineGap(afterSeq = afterSeq, beforeSeq = beforeSeq, status = status)

internal fun parseThreadTimelineGapKey(raw: String?): Pair<Long, Long>? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val sep = text.indexOf(':')
    if (sep <= 0 || sep >= text.lastIndex) return null
    val afterSeq = text.substring(0, sep).toLongOrNull() ?: return null
    val beforeSeq = text.substring(sep + 1).toLongOrNull() ?: return null
    if (afterSeq < 0L || beforeSeq <= afterSeq + 1L) return null
    return afterSeq to beforeSeq
}

/**
 * Reconciles [existingGaps] + [addedGaps] against the sorted [messages] list so every surviving gap:
 * - has strictly positive missing-seq room (`beforeSeq > afterSeq + 1L`),
 * - still lies strictly between two present server-sequenced messages in [messages], and
 * - has no overlapping duplicates (sorted ascending by `afterSeq`, preserving loading/error state).
 */
internal fun reconcileThreadTimelineGaps(
    existingGaps: List<ThreadTimelineGap>,
    addedGaps: List<ThreadTimelineGap>,
    messages: List<RaftThreadMessageItem>,
): List<ThreadTimelineGap> {
    if (existingGaps.isEmpty() && addedGaps.isEmpty()) return emptyList()
    val seqs = messages.mapNotNull { it.seq }.distinct().sorted()
    if (seqs.size < 2) return emptyList()
    val statusByKey = HashMap<String, String>(existingGaps.size)
    existingGaps.forEach { gap -> statusByKey[gap.key] = gap.status }
    val candidates =
        (existingGaps + addedGaps)
            .filter { gap -> gap.beforeSeq > gap.afterSeq + 1L }
            .distinctBy { it.key }
            .sortedWith(compareBy<ThreadTimelineGap>({ it.afterSeq }, { it.beforeSeq }))
    val result = mutableListOf<ThreadTimelineGap>()
    for (candidate in candidates) {
        // Snap the gap onto the tightest adjacent server-message pair inside `seqs` that encloses it
        // (or drop it if a message already landed strictly inside `(afterSeq, beforeSeq)` and bridged it).
        val lowerIdx = seqs.indexOfLast { it <= candidate.afterSeq }
        val upperIdx = seqs.indexOfFirst { it >= candidate.beforeSeq }
        if (lowerIdx < 0 || upperIdx < 0 || upperIdx <= lowerIdx) continue
        // If another message exists strictly inside (lower, upper), check whether this candidate
        // was already narrowed; only keep gaps between strictly adjacent loaded server seqs.
        if (upperIdx != lowerIdx + 1) continue
        val low = seqs[lowerIdx]
        val high = seqs[upperIdx]
        if (high <= low + 1L) continue
        val snapped =
            ThreadTimelineGap(
                afterSeq = low,
                beforeSeq = high,
                status = statusByKey[candidate.key] ?: candidate.status,
            )
        if (result.lastOrNull()?.let { it.afterSeq == snapped.afterSeq && it.beforeSeq == snapped.beforeSeq } != true) {
            result += snapped
        }
    }
    return result
}

/**
 * Reconciles [existingGaps] after fetching an older page `beforeSeq = filledBeforeSeq` for the gap
 * `(filledAfterSeq, filledBeforeSeq)`:
 * - The server endpoint `GET /messages/channel/:id?before=filledBeforeSeq&limit=N` returns the
 *   newest `<= N` messages strictly below `filledBeforeSeq` with no lower bound, so:
 *   - A short or empty page proves `(0, filledBeforeSeq)` complete and clears every gap with
 *     `beforeSeq <= filledBeforeSeq`.
 *   - A full page with minimum seq `minFetchedSeq` proves `[minFetchedSeq, filledBeforeSeq)`
 *     complete across **all** gaps below `filledBeforeSeq`: gaps inside that range are bridged and
 *     removed, while any lower gap that straddles `minFetchedSeq` (`afterSeq < minFetchedSeq - 1L
 *     && beforeSeq > minFetchedSeq`) shrinks to `(afterSeq, minFetchedSeq)` instead of being
 *     silently dropped.
 */
internal fun reconcileThreadTimelineGapsAfterFill(
    existingGaps: List<ThreadTimelineGap>,
    filledAfterSeq: Long,
    filledBeforeSeq: Long,
    fetchedPageMessages: List<RaftThreadMessageItem>,
    requestedPageSize: Int,
    mergedMessages: List<RaftThreadMessageItem>,
    rawServerRowCount: Int = fetchedPageMessages.size,
): List<ThreadTimelineGap> {
    val resolvedPageSize = SharedThreadPaging.resolve(requestedPageSize)
    val fetchedSeqs = fetchedPageMessages.mapNotNull { it.seq }
    val minFetchedSeq = fetchedSeqs.minOrNull()
    val provedToStart = fetchedSeqs.isEmpty() || rawServerRowCount < resolvedPageSize
    val updatedGaps =
        if (provedToStart) {
            existingGaps.filter { gap -> gap.afterSeq >= filledBeforeSeq }
        } else {
            val lowerBound = minFetchedSeq!!
            buildList(existingGaps.size) {
                for (gap in existingGaps) {
                    val isFilledTarget = gap.afterSeq == filledAfterSeq && gap.beforeSeq == filledBeforeSeq
                    when {
                        gap.afterSeq >= filledBeforeSeq -> add(gap)
                        gap.beforeSeq <= lowerBound -> add(if (isFilledTarget) gap.copy(status = "idle") else gap)
                        gap.afterSeq >= lowerBound - 1L && gap.beforeSeq <= filledBeforeSeq -> Unit
                        gap.afterSeq < lowerBound - 1L && gap.beforeSeq > lowerBound ->
                            add(
                                ThreadTimelineGap(
                                    afterSeq = gap.afterSeq,
                                    beforeSeq = lowerBound,
                                    status = "idle",
                                )
                            )
                        else -> add(if (isFilledTarget) gap.copy(status = "idle") else gap)
                    }
                }
            }
        }
    return reconcileThreadTimelineGaps(
        existingGaps = updatedGaps,
        addedGaps = emptyList(),
        messages = mergedMessages,
    )
}

/**
 * Finds the timeline gap (if any) that sits directly before [messages]`[index]`.
 *
 * A gap `(afterSeq, beforeSeq)` attaches to the first server-sequenced message with `seq >= beforeSeq`
 * whose nearest preceding server-sequenced message has `seq <= afterSeq`, so local pending sends
 * (`seq == null`) never hide or duplicate a gap row.
 */
internal fun threadTimelineGapAboveMessage(
    messages: List<RaftThreadMessageItem>,
    index: Int,
    timelineGaps: List<ThreadTimelineGap>,
): ThreadTimelineGap? {
    if (timelineGaps.isEmpty() || index <= 0 || index >= messages.size) return null
    val currentSeq = messages[index].seq ?: return null
    var prevIndex = index - 1
    var prevSeq: Long? = null
    while (prevIndex >= 0) {
        val candidateSeq = messages[prevIndex].seq
        if (candidateSeq != null) {
            prevSeq = candidateSeq
            break
        }
        prevIndex -= 1
    }
    if (prevSeq == null) return null
    return timelineGaps.firstOrNull { gap ->
        gap.afterSeq >= prevSeq && gap.beforeSeq <= currentSeq
    }
}

internal sealed interface ThreadTimelineGapRowSpec {
    val label: StringDesc

    data class Loading(
        override val label: StringDesc,
        val revealDelayMs: Long,
        val reserveSpaceBeforeReveal: Boolean,
    ) : ThreadTimelineGapRowSpec

    data class Retry(
        override val label: StringDesc,
    ) : ThreadTimelineGapRowSpec

    data class Offline(
        override val label: StringDesc,
    ) : ThreadTimelineGapRowSpec
}

internal fun threadTimelineGapRowSpec(
    gap: ThreadTimelineGap,
    durableLastKnown: Boolean,
    mainLoadFailed: Boolean,
    replyThread: Boolean = false,
    userInitiatedFill: Boolean = false,
): ThreadTimelineGapRowSpec {
    val loadingLabel =
        if (replyThread) {
            Res.string.thread_state_loading_gap_replies.desc()
        } else {
            Res.string.thread_state_loading_gap.desc()
        }
    val retryLabel =
        if (replyThread) {
            Res.string.thread_retry_gap_replies.desc()
        } else {
            Res.string.thread_retry_gap.desc()
        }
    val offlineLabel =
        if (replyThread) {
            Res.string.thread_offline_gap_unavailable_replies.desc()
        } else {
            Res.string.thread_offline_gap_unavailable.desc()
        }
    val onlineAutoFill = !durableLastKnown && !mainLoadFailed
    return when {
        gap.status == "loading" ->
            ThreadTimelineGapRowSpec.Loading(
                label = loadingLabel,
                revealDelayMs =
                    if (onlineAutoFill && !userInitiatedFill) {
                        ThreadStandInLoadingRevealDelayMs
                    } else {
                        0L
                    },
                reserveSpaceBeforeReveal = false,
            )
        onlineAutoFill && gap.status == "idle" ->
            ThreadTimelineGapRowSpec.Loading(
                label = loadingLabel,
                revealDelayMs = ThreadStandInLoadingRevealDelayMs,
                reserveSpaceBeforeReveal = false,
            )
        durableLastKnown && !mainLoadFailed && gap.status == "idle" ->
            ThreadTimelineGapRowSpec.Loading(
                label = loadingLabel,
                revealDelayMs = ThreadStandInInlineGapRevealDelayMs,
                reserveSpaceBeforeReveal = false,
            )
        gap.status == "error" && (!durableLastKnown || !mainLoadFailed) ->
            ThreadTimelineGapRowSpec.Retry(label = retryLabel)
        else ->
            ThreadTimelineGapRowSpec.Offline(label = offlineLabel)
    }
}

/**
 * Q5 inline gap row (#proj-mobile:#240, #proj-mobile:#358): renders between two stored messages
 * whose continuity is not yet proven, using dedicated missing-message / missing-reply copy rather
 * than top-of-list "older messages" wording.
 * - Online (`!durableLastKnown && !mainLoadFailed`): auto-fetches on appearance, stays collapsed
 *   for [ThreadStandInLoadingRevealDelayMs] so fast fills do not flash, and shows Retry on failure.
 * - Offline (`durableLastKnown && mainLoadFailed`): shows the offline missing-messages hint
 *   (tappable to retry once connectivity returns, revealing the loading spinner immediately).
 * - Stand-in whose initial main load is still in flight (`durableLastKnown && !mainLoadFailed`):
 *   stays collapsed (0 dp) for [ThreadStandInInlineGapRevealDelayMs] so ordinary slow opens and
 *   brief reconnects settle into the contiguous server page without inserting blank space or a
 *   mid-timeline spinner, then reveals the inline loading row if the refresh remains stalled.
 */
@Composable
internal fun ThreadTimelineGapRow(
    gap: ThreadTimelineGap,
    durableLastKnown: Boolean,
    mainLoadFailed: Boolean,
    onFillGap: (ThreadTimelineGap) -> Unit,
    replyThread: Boolean = false,
) {
    key(gap.key) {
        val onlineAutoFill = !durableLastKnown && !mainLoadFailed
        var userInitiatedFill by remember { mutableStateOf(false) }
        LaunchedEffect(gap.status, onlineAutoFill) {
            if (gap.status != "loading") {
                userInitiatedFill = false
            }
        }
        if (onlineAutoFill && gap.status == "idle") {
            LaunchedEffect(gap.key) {
                onFillGap(gap)
            }
        }
        val spec =
            threadTimelineGapRowSpec(
                gap = gap,
                durableLastKnown = durableLastKnown,
                mainLoadFailed = mainLoadFailed,
                replyThread = replyThread,
                userInitiatedFill = userInitiatedFill,
            )
        when (spec) {
            is ThreadTimelineGapRowSpec.Loading ->
                ThreadHistoryStatusRow(
                    spec = ThreadHistoryStatusSpec(label = spec.label, loading = true),
                    loadingRevealDelayMs = spec.revealDelayMs,
                    reserveSpaceBeforeReveal = spec.reserveSpaceBeforeReveal,
                )
            is ThreadTimelineGapRowSpec.Retry ->
                ThreadInlineActionRow(
                    label = spec.label.localized(),
                    onClick = {
                        userInitiatedFill = true
                        onFillGap(gap)
                    },
                )
            is ThreadTimelineGapRowSpec.Offline ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .raftClickable(
                                onClick = {
                                    userInitiatedFill = true
                                    onFillGap(gap)
                                },
                                role = null,
                                tier = RaftHoverTier.Subtle,
                            )
                ) {
                    ThreadHistoryStatusRow(ThreadHistoryStatusSpec(label = spec.label))
                }
        }
    }
}
