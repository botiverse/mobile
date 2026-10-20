package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.app.data.InboxItem
import build.raft.app.data.activityDoneRequestFrontier
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Rule L (raft-design #562) swipe shortcut for the Activity conversation-list rows: dragging a row toward
 * its start (left in LTR) reveals Done behind it; releasing past the threshold applies Done, then an undo
 * toast lets the person take it back. The swipe is never the only way: the row's Done button and the
 * press-and-hold menu stay.
 *
 * Gesture conflicts: the row claims a drag only once it is horizontal-dominant and toward the start.
 * Vertical drags are left to the list; a drag toward the end (the back direction) is never consumed and
 * the row registers no back-swipe region, so the host's full-page back swipe still takes it.
 *
 * Undo: Done is applied by the Activity store (SharedActivityIntent.MarkDone), which has no un-done path
 * for every row kind (only threads have `markThreadUndone`, with no caller). So a swiped row is hidden
 * locally and the MarkDone is committed when the toast times out, when another row is swiped, or when
 * the list owner (account, server, filter) goes away; Undo just shows the row again.
 */

/** How far toward the start a row must be dragged, as a share of its width, for release to apply Done. */
internal const val ActivitySwipeDoneThresholdFraction = 0.4f

/** A flick at least this fast applies Done (toward the start) or cancels it (back toward the end). */
internal val ActivitySwipeDoneFlingVelocity = 800.dp

/** How long the undo toast stays before the swiped Done is committed. */
internal const val ActivitySwipeDoneUndoWindowMillis = 5_000L

internal enum class ActivitySwipeDragDecision {
    /** Not past the touch slop yet. */
    Undecided,

    /** Horizontal and toward the row's start: the row takes the drag. */
    Claim,

    /** Vertical (the list scrolls) or toward the end (the back swipe): never consumed. */
    Release,
}

internal fun activitySwipeDragDecision(dxPx: Float, dyPx: Float, touchSlopPx: Float, isLtr: Boolean): ActivitySwipeDragDecision {
    val adx = abs(dxPx)
    val ady = abs(dyPx)
    if (adx < touchSlopPx && ady < touchSlopPx) return ActivitySwipeDragDecision.Undecided
    if (ady >= adx) return ActivitySwipeDragDecision.Release
    val towardStart = if (isLtr) dxPx < 0f else dxPx > 0f
    return if (towardStart) ActivitySwipeDragDecision.Claim else ActivitySwipeDragDecision.Release
}

/** The revealed width after a finger movement of [dxPx], kept within the row ([0, widthPx]). */
internal fun activitySwipeRevealPx(currentRevealPx: Float, dxPx: Float, widthPx: Float, isLtr: Boolean): Float =
    (currentRevealPx + if (isLtr) -dxPx else dxPx).coerceIn(0f, widthPx.coerceAtLeast(0f))

/**
 * Whether releasing at [revealPx] with [revealVelocityPx] (positive = toward the start) applies Done: past
 * the threshold unless flicked back, or a fast flick toward the start past half the threshold.
 */
internal fun activitySwipeReleaseAppliesDone(
    revealPx: Float,
    revealVelocityPx: Float,
    widthPx: Float,
    flingVelocityPx: Float,
): Boolean {
    if (widthPx <= 0f || revealPx <= 0f) return false
    if (revealVelocityPx <= -flingVelocityPx) return false
    val threshold = widthPx * ActivitySwipeDoneThresholdFraction
    if (revealPx >= threshold) return true
    return revealVelocityPx >= flingVelocityPx && revealPx >= threshold / 2f
}

/** A swiped row waiting out its undo window. */
@Immutable
internal data class ActivityPendingSwipeDone(val item: InboxItem, val token: Long)

/** One step of [ActivitySwipeDoneUndoState]: the next state, and the row whose Done to commit now, if any. */
internal data class ActivitySwipeDoneTransition(val state: ActivitySwipeDoneUndoState, val commit: InboxItem?)

/** At most one swiped Done waits for undo; a newer swipe commits the older one. Pure; unit-tested. */
@Immutable
internal data class ActivitySwipeDoneUndoState(
    val pending: ActivityPendingSwipeDone? = null,
    private val nextToken: Long = 1L,
) {
    /** The row hidden from the list while its Done waits. */
    val hiddenKey: String? get() = pending?.item?.key

    fun swipe(item: InboxItem): ActivitySwipeDoneTransition {
        if (pending?.item?.key == item.key) return ActivitySwipeDoneTransition(this, commit = null)
        return ActivitySwipeDoneTransition(
            ActivitySwipeDoneUndoState(ActivityPendingSwipeDone(item, nextToken), nextToken + 1),
            commit = pending?.item,
        )
    }

    /** Undo: the row comes back and nothing is written. A stale toast's token does nothing. */
    fun undo(token: Long): ActivitySwipeDoneUndoState = if (pending?.token == token) copy(pending = null) else this

    /** The undo window of [token] ran out: commit it. */
    fun expire(token: Long): ActivitySwipeDoneTransition {
        val expiring = pending?.takeIf { it.token == token } ?: return ActivitySwipeDoneTransition(this, commit = null)
        return ActivitySwipeDoneTransition(copy(pending = null), expiring.item)
    }

    /** The list owner goes away: commit now rather than drop the person's Done. */
    fun flush(): ActivitySwipeDoneTransition = ActivitySwipeDoneTransition(copy(pending = null), pending?.item)
}

/**
 * The row to send MarkDone for when a swiped Done commits. The person swiped what they saw; if the row has
 * moved on since (newer activity: another Done frontier or time), nothing is written and the row simply
 * comes back with its news. A row no longer presented is still sent: the store decides from what it holds.
 */
internal fun activitySwipeDoneCommitItem(swiped: InboxItem, current: InboxItem?): InboxItem? {
    if (current == null) return swiped
    val moved =
        current.activityDoneRequestFrontier() != swiped.activityDoneRequestFrontier() ||
            (current.latestActivityAt ?: current.lastMessageAt) != (swiped.latestActivityAt ?: swiped.lastMessageAt)
    return if (moved) null else current
}

/**
 * Hosts one conversation-list row with the swipe-to-Done shortcut. [onSwipeDone] null: no swipe (the row
 * renders exactly as before). [gestureKey] resets the drag when the row's slot changes owner.
 */
@Composable
internal fun ActivitySwipeToDoneBox(
    gestureKey: String,
    onSwipeDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onSwipeDone == null) {
        Box(modifier) { content() }
        return
    }
    val colors = RaftTheme.colors
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val flingVelocityPx = with(LocalDensity.current) { ActivitySwipeDoneFlingVelocity.toPx() }
    val reveal = remember(gestureKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnSwipeDone by rememberUpdatedState(onSwipeDone)
    var widthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .clipToBounds()
            // On the box that does not move, so finger positions are not shifted by the row's own offset.
            // Children (the row's tap / long press, the Done button) see each event first; consuming a
            // claimed drag here cancels their tap, and the list above never sees a claimed drag.
            .pointerInput(gestureKey, isLtr, flingVelocityPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var claimed = false
                    while (!claimed) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        // Consumed: the list is scrolling, or a long press opened the menu.
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        tracker.addPointerInputChange(change)
                        val total = change.position - down.position
                        when (activitySwipeDragDecision(total.x, total.y, slop, isLtr)) {
                            ActivitySwipeDragDecision.Undecided -> Unit
                            ActivitySwipeDragDecision.Release -> return@awaitEachGesture
                            ActivitySwipeDragDecision.Claim -> {
                                claimed = true
                                change.consume()
                            }
                        }
                    }
                    var target = reveal.value
                    var applied = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPointerInputChange(change)
                            if (!change.pressed) {
                                change.consume()
                                val velocityX = tracker.calculateVelocity().x
                                val revealVelocity = if (isLtr) -velocityX else velocityX
                                applied =
                                    activitySwipeReleaseAppliesDone(target, revealVelocity, widthPx.toFloat(), flingVelocityPx)
                                break
                            }
                            target = activitySwipeRevealPx(target, change.position.x - change.previousPosition.x, widthPx.toFloat(), isLtr)
                            change.consume()
                            scope.launch { reveal.snapTo(target) }
                        }
                    } finally {
                        // Also on cancellation (pointer input reset): never leave a row half open.
                        scope.launch {
                            if (applied) {
                                reveal.animateTo(widthPx.toFloat())
                                currentOnSwipeDone()
                                // The list drops the row in the same frame; if it could not (the slot
                                // changed owner), the row comes back rather than staying slid away.
                                reveal.snapTo(0f)
                            } else {
                                reveal.animateTo(0f)
                            }
                        }
                    }
                }
            }
    ) {
        // Behind the row: what releasing does. Decorative; screen readers use the row's Done button.
        Row(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(colors.successSoft)
                    .clearAndSetSemantics {}
                    .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftGlyphIcon(RaftGlyph.Check, size = 18.dp, color = colors.successStrong)
            RaftText(
                text = stringResource(MR.strings.common_done),
                style = RaftTheme.typography.callout,
                color = colors.successStrong,
                maxLines = 1,
            )
        }
        // The row itself, opaque on the panel's surface so the Done layer shows only where it slid away.
        Box(
            Modifier
                .offset { IntOffset((if (isLtr) -reveal.value else reveal.value).roundToInt(), 0) }
                .background(RaftColor.SurfaceWhite)
        ) {
            content()
        }
    }
}
