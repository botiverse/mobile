package build.raft.app.thread

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Expand reveal (artin dm:@artin:#151): "when I expand something I should directly see what was
 * expanded, not have to scroll down manually."
 *
 * A user expand happens in two steps. First the tapped control keeps its place in the frame the
 * content opens: the tapped row becomes the reading anchor (`keepReadingAnchorFor`,
 * ThreadReadingAnchorKeeper.kt, #5067), which owns that part. Then
 * this owner, [ThreadExpandRevealOwner], brings the opened content into view when it ends below
 * the viewport: it scrolls smoothly by as much as is hidden, but never so far that the tapped
 * control (the content's start) leaves the top of the viewport. Content that already fits moves
 * nothing.
 *
 * Programmatic scroll ownership (docs/programmatic-scroll-ownership-contract.md):
 * - intent owner: one direct user expand, one [ThreadExpandRevealRequest] per tap (a newer tap
 *   replaces it);
 * - fences: the user-scroll generation captured at the tap (any drag since retires it), live
 *   `isScrollInProgress` and owner liveness (job, list, page) after every suspension, the opened
 *   content laid out; the animation runs under the list's scroll mutex, so a drag cancels it;
 * - with the reading anchor keeper: no fight. While this animates a scroll is in progress (the
 *   keeper does not own), and afterwards the list's scroll position has changed, so the keeper
 *   re-reads its anchor instead of correcting; the list then re-anchors keeper and controller on
 *   the row at its new top (`keepReadingAnchorFor`), or follows the latest row at the tail;
 * - bounded: waits at most [ThreadExpandRevealSettleTimeoutMs] for the expansion and the viewport
 *   controller's pending command, then gives up.
 * Diagnostics tag (open-trace owner tags): `thread_expand_reveal`.
 */

/** One user expand to bring into view: forward rows [firstMessageId]..[lastMessageId]. */
internal data class ThreadExpandRevealRequest(
    val id: Long,
    val firstMessageId: String,
    val lastMessageId: String,
    /** Where the tapped control sits in the first row, from its top (px). */
    val controlTopInRow: Int,
    /** The first row's size when the user tapped: the reveal waits for it to change. */
    val rowSizeBefore: Int?,
    val userScrollGeneration: Int,
)

internal class ThreadExpandRevealOwner {
    private var nextId = 0L

    var request: ThreadExpandRevealRequest? by mutableStateOf(null)
        private set

    fun request(
        firstMessageId: String,
        lastMessageId: String = firstMessageId,
        controlTopInRow: Int = 0,
        rowSizeBefore: Int?,
        userScrollGeneration: Int,
    ) {
        nextId += 1
        request =
            ThreadExpandRevealRequest(
                id = nextId,
                firstMessageId = firstMessageId,
                lastMessageId = lastMessageId,
                controlTopInRow = controlTopInRow,
                rowSizeBefore = rowSizeBefore,
                userScrollGeneration = userScrollGeneration,
            )
    }

    /** A collapse or another viewport-replacing action retires a pending reveal. */
    fun cancel() {
        request = null
    }

    fun finish(id: Long) {
        if (request?.id == id) request = null
    }
}

/**
 * The forward scroll (positive: content moves up) that brings opened content into view.
 * Offsets are forward item offsets (from the content start under the top padding); the visible
 * band is `0..visibleBottom`. [contentBottom] null: the content's last row is not laid out, i.e.
 * it ends below the viewport. Never scrolls the control above `topMargin`, never scrolls content
 * that already fits, never scrolls backward.
 */
internal fun threadExpandRevealDelta(
    controlTop: Int,
    contentBottom: Int?,
    visibleBottom: Int,
    topMargin: Int,
): Int {
    val hidden = if (contentBottom == null) Int.MAX_VALUE else contentBottom - visibleBottom
    if (hidden <= 0) return 0
    val room = controlTop - topMargin
    return min(hidden, room).coerceAtLeast(0)
}

/** The bottom of the visible band in forward item offsets (between the content paddings). */
internal fun LazyListLayoutInfo.threadVisibleContentBottom(): Int = viewportEndOffset - afterContentPadding

internal sealed interface ThreadExpandRevealOutcome {
    /** Scrolled by [delta] (0: everything fit); the first row's top is now [rowTop]. */
    data class Revealed(val delta: Int, val rowTop: Int, val atBottom: Boolean) : ThreadExpandRevealOutcome

    /** Not applied: [reason] (user scroll, row gone, timeout, owner gone). */
    data class Skipped(val reason: String) : ThreadExpandRevealOutcome
}

/**
 * Runs [owner]'s current request against [listState]. [listItemIndexOf] maps a message id to its
 * forward list index; [controllerSettled] is true while the viewport controller has no pending
 * command (its anchor replay must land before the reveal measures); [onOutcome] reports to the
 * list (controller re-anchor, diagnostics).
 */
@Composable
internal fun ThreadExpandRevealEffect(
    owner: ThreadExpandRevealOwner,
    listState: ThreadListViewport,
    instanceKey: Any?,
    listItemIndexOf: (String) -> Int,
    userScrollGeneration: () -> Int,
    controllerSettled: () -> Boolean,
    topMarginPx: Int,
    onOutcome: (ThreadExpandRevealRequest, ThreadExpandRevealOutcome) -> Unit,
) {
    val request = owner.request
    val indexOf: State<(String) -> Int> = rememberUpdatedState(listItemIndexOf)
    val generation = rememberUpdatedState(userScrollGeneration)
    val settled = rememberUpdatedState(controllerSettled)
    val report = rememberUpdatedState(onOutcome)
    ThreadListScrollOwnerEffect(listState, instanceKey, request?.id) {
        val current = request ?: return@ThreadListScrollOwnerEffect
        fun live(): Boolean =
            ownerLive() &&
                owner.request?.id == current.id &&
                generation.value() == current.userScrollGeneration
        fun skip(reason: String) {
            owner.finish(current.id)
            report.value(current, ThreadExpandRevealOutcome.Skipped(reason))
        }
        // The expansion lays out (the row's size changes), then the controller's anchor replay of
        // the HeightChange lands; only then is the geometry the one to reveal from.
        val laidOut =
            withTimeoutOrNull(ThreadExpandRevealSettleTimeoutMs) {
                snapshotFlow {
                    val index = indexOf.value(current.firstMessageId)
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    item != null && (current.rowSizeBefore == null || item.size != current.rowSizeBefore)
                }.first { it }
                withFrameNanos { }
                snapshotFlow { settled.value() }.first { it }
                withFrameNanos { }
                true
            }
        if (!live()) {
            if (owner.request?.id == current.id) skip("superseded")
            return@ThreadListScrollOwnerEffect
        }
        if (laidOut == null) return@ThreadListScrollOwnerEffect skip("timeout")
        if (listState.isScrollInProgress) return@ThreadListScrollOwnerEffect skip("scrolling")
        val info = listState.layoutInfo
        val firstIndex = indexOf.value(current.firstMessageId)
        val lastIndex = indexOf.value(current.lastMessageId)
        val first = info.visibleItemsInfo.firstOrNull { it.index == firstIndex }
            ?: return@ThreadListScrollOwnerEffect skip("row_not_laid_out")
        val last = info.visibleItemsInfo.firstOrNull { it.index == lastIndex }
        val delta =
            threadExpandRevealDelta(
                controlTop = first.offset + current.controlTopInRow,
                contentBottom = last?.let { it.offset + it.size },
                visibleBottom = info.threadVisibleContentBottom(),
                topMargin = topMarginPx,
            )
        var moved = 0
        if (delta > 0) {
            try {
                // Consumed: a reveal whose last row was not laid out stops at the list's end.
                moved = listState.animateScrollBy(delta.toFloat()).roundToInt()
            } catch (interrupted: CancellationException) {
                // A drag (or another scroll) took the list's scroll mutex: the user's movement wins.
                currentCoroutineContext().ensureActive()
                if (owner.request?.id == current.id) skip("interrupted")
                return@ThreadListScrollOwnerEffect
            }
            if (!live()) {
                if (owner.request?.id == current.id) skip("interrupted")
                return@ThreadListScrollOwnerEffect
            }
        }
        owner.finish(current.id)
        val rowTop =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstIndex }?.offset
                ?: (first.offset - moved)
        report.value(
            current,
            ThreadExpandRevealOutcome.Revealed(delta = moved, rowTop = rowTop, atBottom = !listState.canScrollForward)
        )
    }
}

internal const val ThreadExpandRevealSettleTimeoutMs = 1_000L

/** Room kept above the tapped control when the reveal scrolls. */
internal val ThreadExpandRevealTopMargin = 8.dp
