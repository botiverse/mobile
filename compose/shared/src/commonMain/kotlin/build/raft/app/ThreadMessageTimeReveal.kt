package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.designsystem.gesture.LocalRaftHorizontalDragKeepers
import build.raft.designsystem.gesture.RaftNestedHorizontalDrags
import build.raft.designsystem.gesture.raftKeepsHorizontalDrags
import build.raft.designsystem.motion.rememberRaftReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Drag the message list toward its start to see each message's time (iMessage; Jasper, #triage:#189).
 *
 * - The whole list answers one drag: every message row slides toward its start by the same amount, up to
 *   [ThreadTimeRevealGutterWidth] (64dp, then a rubber band), avatar included, and its time (the clock
 *   in the person's 12/24-hour setting, [formatThreadTimeRevealLabel]) slides in at the end edge. On
 *   release everything springs back; nothing stays open. The rows follow the finger from its down, not
 *   from the claim: a 40dp drag moves them 40dp, the whole gutter shows at 64dp (web parity). Date
 *   dividers, system rows and the thread's origin card stay put (the day comes from the dividers).
 * - On whether compact consecutive messages is on or off alike; the drag is the same gesture everywhere.
 * - Touch only ([raftIsTouchLike] at the down): a mouse or trackpad keeps the hover toolbar, Compose 版
 *   gets nothing new.
 * - Where: message lists not inside the Chat / Tasks / Files tab pager ([LocalThreadTimeRevealAvailable]):
 *   task v2 conversations (side cards), reply threads, task conversations, the assistant panel. A channel
 *   or DM on a server without task v2 keeps its pager, whose leftward drag pages to Tasks, and gets no
 *   reveal. Off while messages are being selected (multi-select, "Generate image" capture) or text is
 *   selected in place.
 *
 * Gesture arbitration (copied from the Activity row swipe, ActivityConversationSwipe.kt):
 *
 * - Claimed only on clear intent toward the start: past 2x the touch slop (16-24dp) AND |dx| > 2|dy|
 *   ([ActivitySwipeDragPolicy]). Vertical or diagonal movement past the slop, or a drag toward the end
 *   (iOS back swipe), releases the stream for good: the list scrolls as before.
 * - A drag that starts within the window's edge zones (24dp or the system-gesture inset, the larger,
 *   each side: Android gesture-nav back) is never claimed.
 * - A touch while the list still flings only stops it.
 * - A drag that starts on a code block, wide Markdown table or display formula is never claimed
 *   ([raftKeepsHorizontalDrags]): it stays theirs even at their scroll end (no hand-off). Any other
 *   child that takes the drag first (consumes it) wins too.
 * - Two fingers: never claimed, and after a claim the second finger's events are not consumed (the
 *   message list has no pinch gesture today; anything added later keeps working).
 * - Long press: holding still opens the menu as now; once the long-press timeout passed without a claim
 *   (the menu has opened) the stream is left alone. A claim consumes the stream, which cancels the row's pending long press
 *   and tap.
 * - After the claim every change is consumed at the Initial pass, so the list never starts a vertical
 *   scroll from it and the rows see none of it.
 * - Left drag is reserved for the time: there is no swipe-to-reply.
 *
 * Viewport ownership (docs/programmatic-scroll-ownership-contract.md): the reveal never scrolls the list
 * and owns no viewport intent; it only translates rows in the draw phase.
 *
 * Performance: each frame only changes graphicsLayer translation / alpha (draw phase). The labels are
 * composed while [ThreadTimeRevealState.active] is true, which flips twice per gesture. The LazyColumn
 * itself is never translated (its scroll clip would cut the gutter off).
 *
 * Reduced motion: rows don't move. The times fade in only on compact continuation rows, in their start
 * gutter where the pressed (desktop hover) time sits ([LocalThreadTimeRevealGutter]); an author row's
 * header already shows its time. Nothing overlaps a header or the body. They fade out on release.
 */

/** How far rows slide, and the width the time shows in. */
internal val ThreadTimeRevealGutterWidth = 64.dp

/** Test tag of a revealed time label (it has no other semantics). */
internal const val ThreadTimeRevealLabelTestTag = "thread-time-reveal-label"

/** The time's distance from the row's end edge. */
private val ThreadTimeRevealLabelEndPadding = 8.dp

/**
 * Past the gutter the rows follow the finger with resistance (rubber band): [overPx] of drag beyond it
 * moves them this much further, approaching [limitPx].
 */
internal fun threadTimeRevealRubberBandPx(overPx: Float, limitPx: Float): Float {
    if (overPx <= 0f || limitPx <= 0f) return 0f
    return limitPx * (1f - 1f / (overPx * ThreadTimeRevealRubberBandResistance / limitPx + 1f))
}

private const val ThreadTimeRevealRubberBandResistance = 0.55f

/**
 * The rows' slide for a finger [dragPx] toward the start since its down: 1:1 up to [gutterPx], then
 * rubber-banded (at most half the gutter more). Never toward the end.
 */
internal fun threadTimeRevealOffsetPx(dragPx: Float, gutterPx: Float): Float =
    when {
        dragPx <= 0f -> 0f
        dragPx <= gutterPx -> dragPx
        else -> gutterPx + threadTimeRevealRubberBandPx(dragPx - gutterPx, gutterPx / 2f)
    }

/**
 * The revealed time: the clock only (HH:mm, or h:mm a with the 12-hour setting); the day comes from
 * the date dividers. Empty without the page's time context (never the raw wire timestamp).
 */
internal fun formatThreadTimeRevealLabel(value: String, timeContext: RaftTimeContext?): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val context = timeContext ?: return ""
    val epochMillis = parseRaftTimestampEpochMillisOrNull(trimmed, context.localOffsetMinutes) ?: return trimmed
    return formatRaftTimestampEpochMillis(
        epochMillis = epochMillis,
        localOffsetMinutes = context.localOffsetMinutes,
        // The message's own day: clock only.
        todayEpochDay = threadLocalEpochDay(epochMillis, context.localOffsetMinutes),
        timeFormat = context.timeFormat,
    )
}

/**
 * Whether the message list composed under this may offer the reveal (the page provides true where the
 * list is not inside the tab pager). Default false.
 */
internal val LocalThreadTimeRevealAvailable = staticCompositionLocalOf { false }

/**
 * Under reduced motion, a continuation row's reveal: its avatar slot fades the compact time in while
 * [ThreadTimeRevealState.active] (ThreadMessageAvatarSlot). Null: no gutter time (moving rows, author rows).
 */
internal val LocalThreadTimeRevealGutter = staticCompositionLocalOf<ThreadTimeRevealState?> { null }

@Stable
internal class ThreadTimeRevealState {
    /** How far (px) the rows have slid toward the start, 0..[gutterPx]. Read in draw-phase lambdas only. */
    internal val offset = Animatable(0f)

    /** True from a claim until the rows are back at rest: the time labels are composed. */
    var active by mutableStateOf(false)
        private set

    internal var gutterPx = 0f

    /** Code blocks, wide tables and formulas in the rows ([raftKeepsHorizontalDrags]): a drag from one is theirs. */
    internal val keepers = RaftNestedHorizontalDrags()
    internal var reducedMotion = false
    private var generation = 0

    /** Share of the gutter revealed, 0..1 (1 also while rubber-banded beyond it). */
    val fraction: Float
        get() = if (gutterPx <= 0f) 0f else (offset.value / gutterPx).coerceIn(0f, 1f)

    internal fun begin() {
        generation += 1
        active = true
    }

    internal fun follow(scope: CoroutineScope, px: Float) {
        scope.launch { offset.snapTo(px) }
    }

    internal fun settle(scope: CoroutineScope) {
        val settling = ++generation
        scope.launch {
            val spec: AnimationSpec<Float> =
                if (reducedMotion) tween(ThreadTimeRevealFadeOutMillis) else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
            // A new drag that claims meanwhile cancels this (snapTo) and keeps the labels.
            offset.animateTo(0f, spec)
            if (generation == settling) active = false
        }
    }
}

private const val ThreadTimeRevealFadeOutMillis = 150

@Composable
internal fun rememberThreadTimeRevealState(
    key: Any?,
    reducedMotion: Boolean = rememberRaftReducedMotion(),
): ThreadTimeRevealState {
    val state = remember(key) { ThreadTimeRevealState() }
    state.gutterPx = with(LocalDensity.current) { ThreadTimeRevealGutterWidth.toPx() }
    state.reducedMotion = reducedMotion
    return state
}

/**
 * The list-level drag for [state] (see the header). Put it on the list's own modifier, so it is an
 * ancestor of the list's scroll and of every row. [isListScrolling] and [selectionShowing] are read at
 * each gesture's down.
 */
@Composable
internal fun Modifier.threadTimeRevealGesture(
    state: ThreadTimeRevealState,
    isListScrolling: () -> Boolean,
    selectionShowing: () -> Boolean,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val density = LocalDensity.current
    val systemGestures = WindowInsets.systemGestures
    val (startEdgePx, endEdgePx) =
        activitySwipeEdgeZonesPx(
            minInsetPx = with(density) { ActivitySwipeMinEdgeInset.toPx() },
            systemGestureStartPx =
                (if (isLtr) systemGestures.getLeft(density, layoutDirection) else systemGestures.getRight(density, layoutDirection)).toFloat(),
            systemGestureEndPx =
                (if (isLtr) systemGestures.getRight(density, layoutDirection) else systemGestures.getLeft(density, layoutDirection)).toFloat(),
            // The host's back swipe is rightward (toward the end): this drag never claims that way.
            hostBackSwipeEdgePx = 0f,
        )
    val scope = rememberCoroutineScope()
    val listScrolling by rememberUpdatedState(isListScrolling)
    val textSelected by rememberUpdatedState(selectionShowing)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onPlaced { coordinates[0] = it }
        .pointerInput(state, isLtr, startEdgePx, endEdgePx) {
            awaitEachGesture {
                // Initial pass: read before the row's own down handling (which may clear a selection).
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!down.type.raftIsTouchLike()) return@awaitEachGesture
                if (listScrolling() || textSelected()) return@awaitEachGesture
                val coords = coordinates[0]?.takeIf { it.isAttached }
                // A code block, wide table or formula keeps a drag that starts on it, even at its end.
                if (coords != null && state.keepers.claimDistanceAt(coords.localToRoot(down.position)) > 0f) {
                    return@awaitEachGesture
                }
                // Edge zones are the window's: on a tablet pane the list need not touch its edges.
                val root = coords?.findRootCoordinates()
                val downX = if (coords != null && root != null) root.localPositionOf(coords, down.position).x else down.position.x
                val widthPx = root?.size?.width?.toFloat() ?: size.width.toFloat()
                val slop = viewConfiguration.touchSlop
                val policy =
                    ActivitySwipeDragPolicy(
                        touchSlopPx = slop,
                        claimThresholdPx =
                            activitySwipeClaimThresholdPx(
                                slop,
                                ActivitySwipeMinClaimDistance.toPx(),
                                ActivitySwipeMaxClaimDistance.toPx(),
                            ),
                        isLtr = isLtr,
                        canOpen = true,
                        canClose = false,
                        startsInEdgeZone = activitySwipeStartsInEdgeZone(downX, widthPx, startEdgePx, endEdgePx, isLtr),
                    )
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                var claimX = down.position.x
                // Main pass until the claim: the rows and their scrollers (a wide table) decide first.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    // The down's own Main pass (awaited at Initial above): a row's tap detector consumes it.
                    if (change.changedToDownIgnoreConsumed()) continue
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    // Two fingers (a pinch) are never this drag.
                    if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    // Held still past the long press: the menu (or the text selection) owns it.
                    if (change.uptimeMillis - down.uptimeMillis >= longPressTimeout) return@awaitEachGesture
                    val total = change.position - down.position
                    when (policy.onMove(total.x, total.y)) {
                        ActivitySwipeDragDecision.Undecided -> Unit
                        ActivitySwipeDragDecision.Release -> return@awaitEachGesture
                        ActivitySwipeDragDecision.Claim -> {
                            claimX = change.position.x
                            change.consume()
                            break
                        }
                    }
                }
                state.begin()
                // The rows track the finger from its down, not from the claim (web parity: a 40dp drag
                // moves them 40dp): the claim distance already travelled shows at once.
                fun dragSinceDown(x: Float): Float = if (isLtr) down.position.x - x else x - down.position.x
                try {
                    state.follow(scope, threadTimeRevealOffsetPx(dragSinceDown(claimX), state.gutterPx))
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        // Only this finger: a second one is left to whatever else it starts.
                        change?.consume()
                        if (change == null || !change.pressed) break
                        state.follow(scope, threadTimeRevealOffsetPx(dragSinceDown(change.position.x), state.gutterPx))
                    }
                } finally {
                    // Also on cancellation (pointer input reset): never leave rows slid.
                    state.settle(scope)
                }
            }
        }
}

/**
 * One message row under the reveal: [content] slides toward the start by the list's offset, and
 * [timeLabel] slides in at the end edge, aligned with the row's first line ([continuation]: a compact
 * row without its author header). Under reduced motion nothing slides and only a [continuation] row
 * shows its time, in its own avatar gutter ([LocalThreadTimeRevealGutter]). Without a reveal ([state]
 * null), just [content].
 */
@Composable
internal fun ThreadTimeRevealRow(
    state: ThreadTimeRevealState?,
    timeLabel: String,
    continuation: Boolean,
    content: @Composable () -> Unit,
) {
    if (state == null) {
        content()
        return
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1f else 1f
    Box(
        // Clipped only while slid: the row's own overflow (a located glow) is untouched at rest.
        Modifier.graphicsLayer { clip = state.offset.value > 0f }
    ) {
        val reducedMotion = state.reducedMotion
        Box(
            Modifier.graphicsLayer {
                translationX = if (reducedMotion) 0f else direction * state.offset.value
            }
        ) {
            CompositionLocalProvider(
                LocalRaftHorizontalDragKeepers provides state.keepers,
                LocalThreadTimeRevealGutter provides state.takeIf { reducedMotion && continuation },
                content = content,
            )
        }
        if (!reducedMotion) ThreadTimeRevealLabelSlot(state, timeLabel, continuation, direction)
    }
}

@Composable
private fun BoxScope.ThreadTimeRevealLabelSlot(
    state: ThreadTimeRevealState,
    timeLabel: String,
    continuation: Boolean,
    direction: Float,
) {
    // Only this slot reads `active`: the row's content does not recompose when a drag starts.
    if (!state.active || timeLabel.isBlank()) return
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                // The row's first line: `timeline-row` (4) under its top, plus a turn's
                // `timeline-turn` (8) band above an author row (raft-ui timeline rhythm).
                .padding(
                    top =
                        if (continuation) {
                            ThreadMessageTimelineRowInset
                        } else {
                            ThreadMessageTimelineTurn + ThreadMessageTimelineRowInset
                        }
                )
                .widthIn(min = ThreadTimeRevealGutterWidth)
                // The first line: the 18dp compact time slot, or the author header's line.
                .height(if (continuation) 18.dp else 20.dp)
                .graphicsLayer {
                    alpha = state.fraction
                    // Rides with the row's end edge: at the end once the gutter is open, then with the rubber band.
                    translationX = -direction * (state.gutterPx - state.offset.value)
                }
                // Shown only mid-drag; the row's own semantics carry its time (the tag is for tests).
                .clearAndSetSemantics { testTag = ThreadTimeRevealLabelTestTag }
                .padding(end = ThreadTimeRevealLabelEndPadding),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = timeLabel,
            // raft-ui `continuationTime`: font-mono text-mono-meta, 12/16 regular (as the compact slot).
            style =
                RaftType.Caption.copy(
                    fontFamily = RaftMonoFamily,
                    fontWeight = FontWeight.Normal,
                    color = RaftColor.TextBlack.copy(alpha = 0.38f),
                ),
            color = RaftColor.TextBlack.copy(alpha = 0.38f),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
        )
    }
}
