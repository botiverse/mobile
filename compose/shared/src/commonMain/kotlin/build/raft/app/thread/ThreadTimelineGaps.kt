package build.raft.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import build.raft.app.network.sync.CanonicalMessageV2TimelineGap
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.thread_offline_older_unavailable
import build.raft.app.resources.thread_retry_older
import build.raft.app.resources.thread_state_loading_older
import build.raft.app.resources.thread_state_loading_older_replies
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

internal const val ThreadFillTimelineGapAction: String = "fillTimelineGap"

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

/**
 * Q5 inline gap row (artin 2026-10-27): reuses the existing history loading/offline/retry chrome
 * with zero new copy or visual variants.
 * - Online (`!durableLastKnown && !mainLoadFailed`): auto-fetches on appearance and shows the
 *   existing loading spinner; shows Retry on failure.
 * - Offline (`durableLastKnown && mainLoadFailed`): shows the existing "older messages not
 *   available offline" hint (tappable to retry once connectivity returns).
 * - Stand-in whose initial main load is still in flight (`durableLastKnown && !mainLoadFailed`):
 *   shows the loading spinner, matching [threadOlderMessagesStatusSpec].
 */
@Composable
internal fun ThreadTimelineGapRow(
    gap: ThreadTimelineGap,
    durableLastKnown: Boolean,
    mainLoadFailed: Boolean,
    onFillGap: (ThreadTimelineGap) -> Unit,
    replyThread: Boolean = false,
) {
    val onlineAutoFill = !durableLastKnown && !mainLoadFailed
    if (onlineAutoFill && gap.status == "idle") {
        LaunchedEffect(gap.key) {
            onFillGap(gap)
        }
    }
    when {
        gap.status == "loading" || (onlineAutoFill && gap.status == "idle") || (durableLastKnown && !mainLoadFailed) ->
            ThreadHistoryStatusRow(
                ThreadHistoryStatusSpec(
                    label =
                        if (replyThread) {
                            Res.string.thread_state_loading_older_replies.desc()
                        } else {
                            Res.string.thread_state_loading_older.desc()
                        },
                    loading = true,
                )
            )
        gap.status == "error" && !durableLastKnown ->
            ThreadInlineActionRow(
                label = stringResource(Res.string.thread_retry_older),
                onClick = { onFillGap(gap) },
            )
        else ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onFillGap(gap) }
            ) {
                ThreadHistoryStatusRow(
                    ThreadHistoryStatusSpec(
                        label = Res.string.thread_offline_older_unavailable.desc(),
                    )
                )
            }
    }
}
