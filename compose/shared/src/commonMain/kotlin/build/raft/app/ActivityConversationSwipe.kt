package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
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
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.activityDoneRequestFrontier
import build.raft.app.data.isFollowedActivityThread
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.gesture.LocalRaftBackSwipeRegions
import build.raft.designsystem.gesture.RaftBackSwipeEdgeWidth
import build.raft.designsystem.gesture.raftBackSwipeRegion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Rule L (raft-design #562, touch.md#swipe) swipe shortcuts for the Activity conversation-list rows, one
 * action each way as in mail apps:
 *
 * - Toward the start (left in LTR): Done. Releasing past the threshold applies Done, then an undo toast
 *   lets the person take it back.
 * - Toward the end (right in LTR): stop following the row. Unfollow on a followed thread row, Mute
 *   Activity on a joined channel row ([activitySwipeEndAction], the same rows web's InboxContextMenu
 *   offers Unfollow / Mute Activity to). DMs (no Activity mute), mention rows (a mute does not hold back
 *   a direct mention), unknown or unjoined channels, an unfollowed thread and an already muted channel
 *   don't move to the end: a swipe never reveals an action that does nothing.
 *
 * The row has no Done button (artin 2026-10-23): Done is reached by the swipe above and the press-and-hold
 * menu entry. The swipes are never the only way: the menu (Done, Unfollow, Mute Activity) stays, so screen
 * readers and anyone who can't swipe reach the same actions.
 *
 * Gesture conflicts. The end direction is also the back direction, so the row arbitrates it explicitly:
 *
 * - Edge zones: a drag that starts within the row's edge zone ([activitySwipeEdgeZonesPx]) is never
 *   claimed, in either direction. At the leading edge that is the largest of [ActivitySwipeMinEdgeInset]
 *   (24dp), the platform's system-gesture inset (Android gesture navigation's predictive back) and, on a
 *   host with a full-page back swipe (iOS interactive pop), [RaftBackSwipeEdgeWidth] (the host's 40pt
 *   edge); at the trailing edge, the larger of 24dp and the system-gesture inset (Android's back from
 *   the right edge). Android's OS edge gestures win over the app anyway; the zone keeps the row from
 *   even starting to slide under them.
 * - iOS full-page pop: a row with an end action registers a back-swipe region without its leading
 *   [RaftBackSwipeEdgeWidth] strip (`raftBackSwipeRegion(edgeWidth)`, the gesture arbiter the pager and
 *   image gallery use), so the host's pop starts from the leading edge and the row body keeps the drag.
 *   A row with no end action registers nothing and a drag toward the end is released, as before.
 * - Inside the row, the row claims a drag only on clear horizontal intent ([activitySwipeDragDecision]):
 *   past [activitySwipeClaimThresholdPx] (twice the touch slop, 16-24dp) AND |dx| > [ActivitySwipeDominanceRatio]
 *   (2) x |dy|, in a direction that has an action, as with iOS table swipe actions. Any movement past the
 *   touch slop that is not on track for that (vertical-dominant or diagonal) hands the gesture to the list
 *   and locks the swipe out for the rest of that pointer stream ([ActivitySwipeDragPolicy]): a later
 *   horizontal wobble in the same stroke never claims. Once claimed, the drag stays horizontal.
 *
 * No Back handler is registered (AGENTS.md Kuikly Back Handling Rule): an open row is not a cancelable
 * transient state. Every release either applies the action (and the row snaps back) or springs closed.
 *
 * Undo, Done: Done is applied by the Activity store (SharedActivityIntent.MarkDone), which has no un-done
 * path for every row kind (only threads have `markThreadUndone`, with no caller). So a swiped row is
 * hidden locally and the MarkDone is committed when the toast times out, when another row is swiped, or
 * when the list owner (account, server, filter) goes away; Undo just shows the row again.
 *
 * Undo, end: Unfollow and Mute have exact reverses (Follow, Unmute), so they are applied on release
 * through the same intents the row menu uses ([activitySwipeEndApplyCommand]); the row then behaves as
 * after the menu item (an unfollowed thread leaves the attention filters and stays, marked, in All and
 * Pinned; a muted channel stays, quiet). Undo sends the reverse ([activitySwipeEndUndoCommand]); the
 * store brings an unfollowed thread back even when the filter had dropped it. One toast at a time: a
 * newer swipe either way replaces the older toast (a waiting Done is committed first).
 */

/** How far a row must be dragged either way, as a share of its width, for release to apply that side's action. */
internal const val ActivitySwipeThresholdFraction = 0.4f

/** A flick at least this fast applies the revealed action, or (back toward closed) cancels it. */
internal val ActivitySwipeFlingVelocity = 800.dp

/** How long the undo toast stays (and, for Done, before the swiped Done is committed). */
internal const val ActivitySwipeDoneUndoWindowMillis = 5_000L

/** The least edge zone at either side of a row where no drag is claimed (see the header). */
internal val ActivitySwipeMinEdgeInset = 24.dp

/** A claim needs |dx| greater than this many times |dy|; with less, the drag is vertical-leaning and scrolls. */
internal const val ActivitySwipeDominanceRatio = 2f

/** Bounds of the claim distance: twice the touch slop, kept between these (Android 8dp slop -> 16dp). */
internal val ActivitySwipeMinClaimDistance = 16.dp
internal val ActivitySwipeMaxClaimDistance = 24.dp

/**
 * How far a drag must go horizontally before the row may claim it: [2 x the
 * platform touch slop, clamped to [minPx]..[maxPx] so a large slop (iOS) does not make the swipe stiff.
 */
internal fun activitySwipeClaimThresholdPx(touchSlopPx: Float, minPx: Float, maxPx: Float): Float =
    (touchSlopPx * 2f).coerceIn(minPx, maxPx)

/** Which side of the row a claimed drag reveals. */
internal enum class ActivitySwipeSide {
    /** Dragged toward the start: Done. */
    Done,

    /** Dragged toward the end: [ActivitySwipeEndAction]. */
    End,
}

/** What a swipe toward the end does to a row (web InboxContextMenu Unfollow / Mute Activity). */
internal enum class ActivitySwipeEndAction {
    UnfollowThread,
    MuteActivity,
}

/**
 * The end-swipe action of [this] row, or null for no end swipe. Same eligibility as web
 * InboxContextMenu (slock packages/web/src/components/thread/InboxContextMenu.tsx):
 *
 * - a followed thread row (`kind == "thread" && isFollowing !== false`): Unfollow. Task conversations are
 *   thread rows there too (`unfollowTaskConversation`, the same `/channels/threads/unfollow` route); the
 *   mobile inbox parser does not list task-conversation rows yet.
 * - a channel row whose channel is joined and has a user-reachable Activity mute that is off
 *   (`canToggleActivityMute`): Mute Activity. [mutedByChannelId] holds exactly those channels
 *   ([activityMuteByChannelId]); an unknown or unjoined channel is absent and gets no swipe, not a guess.
 * - DMs (no Activity mute), mention rows (a mute does not hold back a direct mention), system rows, an
 *   unfollowed thread and a muted channel: none.
 */
internal fun InboxItem.activitySwipeEndAction(mutedByChannelId: Map<String, Boolean>): ActivitySwipeEndAction? =
    when {
        isFollowedActivityThread -> ActivitySwipeEndAction.UnfollowThread
        kind == InboxItemKind.Channel && mutedByChannelId[channelId.value] == false -> ActivitySwipeEndAction.MuteActivity
        else -> null
    }

/** The menu item that does the same as [this] swipe (`activityContextMenuActionIds`). */
internal val ActivitySwipeEndAction.menuActionId: String
    get() =
        when (this) {
            ActivitySwipeEndAction.UnfollowThread -> "unfollow-thread"
            ActivitySwipeEndAction.MuteActivity -> "mute-activity"
        }

/** One write an end swipe (or its Undo) makes: an Activity store intent, or the Home channel mute. */
internal sealed interface ActivitySwipeEndCommand {
    data class Activity(val intent: SharedActivityIntent) : ActivitySwipeEndCommand

    data class ChannelMute(val channelId: String, val muted: Boolean) : ActivitySwipeEndCommand
}

/** Release past the threshold: the same write as the row menu's item. */
internal fun activitySwipeEndApplyCommand(item: InboxItem, action: ActivitySwipeEndAction): ActivitySwipeEndCommand =
    when (action) {
        ActivitySwipeEndAction.UnfollowThread -> ActivitySwipeEndCommand.Activity(SharedActivityIntent.UnfollowThread(item))
        ActivitySwipeEndAction.MuteActivity -> ActivitySwipeEndCommand.ChannelMute(item.channelId.value, muted = true)
    }

/**
 * Undo: the exact reverse (web refollow / unmute). The swiped row is passed as unfollowed, the state
 * the Unfollow left it in, so the store follows it again whether it still lists the row or not.
 */
internal fun activitySwipeEndUndoCommand(item: InboxItem, action: ActivitySwipeEndAction): ActivitySwipeEndCommand =
    when (action) {
        ActivitySwipeEndAction.UnfollowThread ->
            ActivitySwipeEndCommand.Activity(SharedActivityIntent.FollowThread(item.copy(isFollowing = false)))
        ActivitySwipeEndAction.MuteActivity -> ActivitySwipeEndCommand.ChannelMute(item.channelId.value, muted = false)
    }

/** The conversation the end-swipe toast names: a channel row's channel, a thread's parent (`#name` / `@peer`). */
internal fun InboxItem.activitySwipeConversationLabel(): String {
    val (name, kind) =
        if (kind == InboxItemKind.Thread) {
            (parentChannelName ?: channelName) to parentChannelKind
        } else {
            channelName to channelKind
        }
    val bare = name.trim().removePrefix("#").removePrefix("@")
    return if (kind == ConversationKind.DirectMessage) "@$bare" else "#$bare"
}

/**
 * The edge zones (start, end) of a row in pixels, where no drag is claimed: the larger of the minimum
 * inset and the system-gesture inset on each side, and at the start also the host's back-swipe edge
 * ([hostBackSwipeEdgePx], 0 when the host has no full-page back swipe).
 */
internal fun activitySwipeEdgeZonesPx(
    minInsetPx: Float,
    systemGestureStartPx: Float,
    systemGestureEndPx: Float,
    hostBackSwipeEdgePx: Float,
): Pair<Float, Float> =
    maxOf(minInsetPx, systemGestureStartPx, hostBackSwipeEdgePx) to maxOf(minInsetPx, systemGestureEndPx)

/** Whether a drag that went down at [downXPx] (row-local) starts in one of the row's edge zones. */
internal fun activitySwipeStartsInEdgeZone(
    downXPx: Float,
    widthPx: Float,
    startEdgePx: Float,
    endEdgePx: Float,
    isLtr: Boolean,
): Boolean {
    val fromStart = if (isLtr) downXPx else widthPx - downXPx
    val fromEnd = widthPx - fromStart
    return fromStart < startEdgePx || fromEnd < endEdgePx
}

internal enum class ActivitySwipeDragDecision {
    /** Not past the touch slop yet. */
    Undecided,

    /** Horizontal and toward the row's start, and the row has Done: the row takes the drag. */
    ClaimDone,

    /** Horizontal and toward the row's end, and the row has an end action: the row takes the drag. */
    ClaimEnd,

    /**
     * Vertical (the list scrolls), from an edge zone (system / host back), or toward a side with no
     * action (the host's back swipe): never consumed.
     */
    Release,
}

/**
 * The decision for a pointer stream that has moved ([dxPx], [dyPx]) since it went down:
 *
 * - Past the touch slop vertically while [dxPx] is not more than [ActivitySwipeDominanceRatio] x [dyPx]
 *   (vertical-dominant or diagonal), or past the slop at all from an edge zone: [ActivitySwipeDragDecision.Release].
 * - Horizontal past [claimThresholdPx] with |dx| > ratio x |dy|: claim, if that direction has an action.
 * - Otherwise [ActivitySwipeDragDecision.Undecided].
 *
 * Stateless; [ActivitySwipeDragPolicy] adds the lock for the rest of the stream.
 */
internal fun activitySwipeDragDecision(
    dxPx: Float,
    dyPx: Float,
    touchSlopPx: Float,
    isLtr: Boolean,
    doneEnabled: Boolean = true,
    endEnabled: Boolean = false,
    startsInEdgeZone: Boolean = false,
    claimThresholdPx: Float = touchSlopPx * 2f,
): ActivitySwipeDragDecision {
    val adx = abs(dxPx)
    val ady = abs(dyPx)
    if (startsInEdgeZone) {
        return if (adx >= touchSlopPx || ady >= touchSlopPx) ActivitySwipeDragDecision.Release else ActivitySwipeDragDecision.Undecided
    }
    if (ady >= touchSlopPx && adx <= ActivitySwipeDominanceRatio * ady) return ActivitySwipeDragDecision.Release
    if (adx < claimThresholdPx || adx <= ActivitySwipeDominanceRatio * ady) return ActivitySwipeDragDecision.Undecided
    val towardStart = if (isLtr) dxPx < 0f else dxPx > 0f
    return when {
        towardStart && doneEnabled -> ActivitySwipeDragDecision.ClaimDone
        !towardStart && endEnabled -> ActivitySwipeDragDecision.ClaimEnd
        else -> ActivitySwipeDragDecision.Release
    }
}

/**
 * One pointer stream's arbitration before a claim. The first [ActivitySwipeDragDecision.Release] locks the
 * stream out: the list keeps the gesture and later horizontal movement never claims. (A claim is final too;
 * the row then only moves horizontally.)
 */
internal class ActivitySwipeDragPolicy(
    private val touchSlopPx: Float,
    private val claimThresholdPx: Float,
    private val isLtr: Boolean,
    private val doneEnabled: Boolean,
    private val endEnabled: Boolean,
    private val startsInEdgeZone: Boolean,
) {
    var decision: ActivitySwipeDragDecision = ActivitySwipeDragDecision.Undecided
        private set

    /** The decision after the pointer moved to a total offset of ([dxPx], [dyPx]) from where it went down. */
    fun onMove(dxPx: Float, dyPx: Float): ActivitySwipeDragDecision {
        if (decision != ActivitySwipeDragDecision.Undecided) return decision
        decision =
            activitySwipeDragDecision(
                dxPx, dyPx, touchSlopPx, isLtr, doneEnabled, endEnabled, startsInEdgeZone, claimThresholdPx,
            )
        return decision
    }
}

/**
 * The signed reveal after a finger movement of [dxPx]: positive toward the start (Done), negative toward
 * the end. It stays on the claimed [side] and within the row ([0, widthPx] or [-widthPx, 0]).
 */
internal fun activitySwipeRevealPx(
    currentRevealPx: Float,
    dxPx: Float,
    widthPx: Float,
    isLtr: Boolean,
    side: ActivitySwipeSide = ActivitySwipeSide.Done,
): Float {
    val next = currentRevealPx + if (isLtr) -dxPx else dxPx
    val width = widthPx.coerceAtLeast(0f)
    return when (side) {
        ActivitySwipeSide.Done -> next.coerceIn(0f, width)
        ActivitySwipeSide.End -> next.coerceIn(-width, 0f)
    }
}

/**
 * Whether releasing at [revealPx] with [revealVelocityPx] (both measured along the opened side, positive
 * = further open) applies the action: past the threshold unless flicked back, or a fast flick further
 * open past half the threshold.
 */
internal fun activitySwipeReleaseAppliesDone(
    revealPx: Float,
    revealVelocityPx: Float,
    widthPx: Float,
    flingVelocityPx: Float,
): Boolean {
    if (widthPx <= 0f || revealPx <= 0f) return false
    if (revealVelocityPx <= -flingVelocityPx) return false
    val threshold = widthPx * ActivitySwipeThresholdFraction
    if (revealPx >= threshold) return true
    return revealVelocityPx >= flingVelocityPx && revealPx >= threshold / 2f
}

/**
 * [activitySwipeReleaseAppliesDone] for either side, from the signed reveal ([activitySwipeRevealPx]) and
 * the finger's horizontal velocity on screen.
 */
internal fun activitySwipeReleaseApplies(
    side: ActivitySwipeSide,
    signedRevealPx: Float,
    fingerVelocityX: Float,
    widthPx: Float,
    flingVelocityPx: Float,
    isLtr: Boolean,
): Boolean {
    // Positive toward the start, like the signed reveal.
    val towardStartVelocity = if (isLtr) -fingerVelocityX else fingerVelocityX
    val sign = if (side == ActivitySwipeSide.Done) 1f else -1f
    return activitySwipeReleaseAppliesDone(sign * signedRevealPx, sign * towardStartVelocity, widthPx, flingVelocityPx)
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

/** An end swipe (already applied) whose toast offers Undo. */
@Immutable
internal data class ActivityPendingSwipeEnd(val item: InboxItem, val action: ActivitySwipeEndAction, val token: Long)

/** One step of [ActivitySwipeEndUndoState]: the next state, and the swipe to reverse now, if any. */
internal data class ActivitySwipeEndTransition(val state: ActivitySwipeEndUndoState, val reverse: ActivityPendingSwipeEnd?)

/**
 * The toast of the latest end swipe. The action is written on release, so a newer swipe, the window
 * running out or the list owner going away only drops the toast; Undo returns the swipe to reverse.
 * Pure; unit-tested.
 */
@Immutable
internal data class ActivitySwipeEndUndoState(
    val pending: ActivityPendingSwipeEnd? = null,
    private val nextToken: Long = 1L,
) {
    fun swipe(item: InboxItem, action: ActivitySwipeEndAction): ActivitySwipeEndUndoState =
        ActivitySwipeEndUndoState(ActivityPendingSwipeEnd(item, action, nextToken), nextToken + 1)

    /** Undo of [token]'s toast: reverse it. A stale toast's token does nothing. */
    fun undo(token: Long): ActivitySwipeEndTransition {
        val undone = pending?.takeIf { it.token == token } ?: return ActivitySwipeEndTransition(this, reverse = null)
        return ActivitySwipeEndTransition(copy(pending = null), undone)
    }

    /** The undo window of [token] ran out: the swipe stands. */
    fun expire(token: Long): ActivitySwipeEndUndoState = if (pending?.token == token) copy(pending = null) else this

    /** Another swipe's toast takes over, or the list owner goes away: the swipe stands. */
    fun dismiss(): ActivitySwipeEndUndoState = if (pending == null) this else copy(pending = null)
}

/**
 * Hosts one conversation-list row with the swipe shortcuts. [onSwipeDone] null: no Done swipe;
 * [endAction] / [onSwipeEnd] null: no end swipe; neither: the row renders exactly as before.
 * [gestureKey] resets the drag when the row's slot changes owner. [previewRevealFraction] opens the row
 * by that share of its width (positive toward the start), for screenshots only.
 */
@Composable
internal fun ActivitySwipeActionsBox(
    gestureKey: String,
    onSwipeDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    endAction: ActivitySwipeEndAction? = null,
    onSwipeEnd: (() -> Unit)? = null,
    previewRevealFraction: Float = 0f,
    content: @Composable () -> Unit,
) {
    val endEnabled = endAction != null && onSwipeEnd != null
    val doneEnabled = onSwipeDone != null
    if (!doneEnabled && !endEnabled) {
        Box(modifier) { content() }
        return
    }
    val colors = RaftTheme.colors
    val layoutDirection = LocalLayoutDirection.current
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val density = LocalDensity.current
    val flingVelocityPx = with(density) { ActivitySwipeFlingVelocity.toPx() }
    val hostHasBackSwipe = LocalRaftBackSwipeRegions.current != null
    val systemGestures = WindowInsets.systemGestures
    val (startEdgePx, endEdgePx) =
        activitySwipeEdgeZonesPx(
            minInsetPx = with(density) { ActivitySwipeMinEdgeInset.toPx() },
            systemGestureStartPx =
                (if (isLtr) systemGestures.getLeft(density, layoutDirection) else systemGestures.getRight(density, layoutDirection)).toFloat(),
            systemGestureEndPx =
                (if (isLtr) systemGestures.getRight(density, layoutDirection) else systemGestures.getLeft(density, layoutDirection)).toFloat(),
            hostBackSwipeEdgePx = if (hostHasBackSwipe) with(density) { RaftBackSwipeEdgeWidth.toPx() } else 0f,
        )
    val reveal = remember(gestureKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnSwipeDone by rememberUpdatedState(onSwipeDone)
    val currentOnSwipeEnd by rememberUpdatedState(onSwipeEnd)
    val currentEndEnabled by rememberUpdatedState(endEnabled)
    var widthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            // iOS full-page pop: the row body keeps back-direction drags while it has an end action;
            // the leading edge strip still pops the page. Inert on hosts without a content back swipe.
            .then(if (endEnabled) Modifier.raftBackSwipeRegion(RaftBackSwipeEdgeWidth) { currentEndEnabled } else Modifier)
            .clipToBounds()
            // On the box that does not move, so finger positions are not shifted by the row's own offset.
            // Children (the row's tap / long press, the Done button) see each event first; consuming a
            // claimed drag here cancels their tap, and the list above never sees a claimed drag.
            .pointerInput(gestureKey, isLtr, flingVelocityPx, doneEnabled, endEnabled, startEdgePx, endEdgePx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val slop = viewConfiguration.touchSlop
                    val startsInEdgeZone =
                        activitySwipeStartsInEdgeZone(down.position.x, size.width.toFloat(), startEdgePx, endEdgePx, isLtr)
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
                            doneEnabled = doneEnabled,
                            endEnabled = endEnabled,
                            startsInEdgeZone = startsInEdgeZone,
                        )
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var side: ActivitySwipeSide? = null
                    while (side == null) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        // Consumed: the list is scrolling, or a long press opened the menu.
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        tracker.addPointerInputChange(change)
                        val total = change.position - down.position
                        when (policy.onMove(total.x, total.y)) {
                            ActivitySwipeDragDecision.Undecided -> Unit
                            ActivitySwipeDragDecision.Release -> return@awaitEachGesture
                            ActivitySwipeDragDecision.ClaimDone -> side = ActivitySwipeSide.Done
                            ActivitySwipeDragDecision.ClaimEnd -> side = ActivitySwipeSide.End
                        }
                        if (side != null) change.consume()
                    }
                    val claimedSide = side ?: return@awaitEachGesture
                    var target = reveal.value
                    var applied = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPointerInputChange(change)
                            if (!change.pressed) {
                                change.consume()
                                applied =
                                    activitySwipeReleaseApplies(
                                        side = claimedSide,
                                        signedRevealPx = target,
                                        fingerVelocityX = tracker.calculateVelocity().x,
                                        widthPx = widthPx.toFloat(),
                                        flingVelocityPx = flingVelocityPx,
                                        isLtr = isLtr,
                                    )
                                break
                            }
                            target =
                                activitySwipeRevealPx(
                                    target,
                                    change.position.x - change.previousPosition.x,
                                    widthPx.toFloat(),
                                    isLtr,
                                    claimedSide,
                                )
                            change.consume()
                            scope.launch { reveal.snapTo(target) }
                        }
                    } finally {
                        // Also on cancellation (pointer input reset): never leave a row half open.
                        scope.launch {
                            if (applied) {
                                val open = if (claimedSide == ActivitySwipeSide.Done) widthPx.toFloat() else -widthPx.toFloat()
                                reveal.animateTo(open)
                                when (claimedSide) {
                                    ActivitySwipeSide.Done -> currentOnSwipeDone?.invoke()
                                    ActivitySwipeSide.End -> currentOnSwipeEnd?.invoke()
                                }
                                // Done: the list drops the row in the same frame; End: the row stays (or
                                // leaves with its filter). Either way the row comes back closed rather
                                // than staying slid away.
                                reveal.snapTo(0f)
                            } else {
                                reveal.animateTo(0f)
                            }
                        }
                    }
                }
            }
    ) {
        val shownReveal = reveal.value + previewRevealFraction * widthPx
        // Behind the row: what releasing does, on the side the row slid away from. Decorative; screen
        // readers use the row's Done button and its press-and-hold menu.
        if (shownReveal > 0f && doneEnabled) {
            ActivitySwipeActionLayer(
                glyph = { RaftGlyphIcon(RaftGlyph.Check, size = 18.dp, color = colors.successStrong) },
                label = stringResource(MR.strings.common_done),
                background = colors.successSoft,
                foreground = colors.successStrong,
                alignEnd = true,
                modifier = Modifier.matchParentSize(),
            )
        } else if (shownReveal < 0f && endAction != null) {
            ActivitySwipeActionLayer(
                glyph = {
                    RaftLucideIcon(RaftLucideIconName.BellOff, Modifier.size(18.dp), color = raftArgbHex(colors.warningStrong))
                },
                label =
                    when (endAction) {
                        ActivitySwipeEndAction.UnfollowThread -> stringResource(MR.strings.activity_swipe_unfollow)
                        ActivitySwipeEndAction.MuteActivity -> stringResource(MR.strings.activity_action_mute_activity)
                    },
                background = colors.warningSoft,
                foreground = colors.warningStrong,
                alignEnd = false,
                modifier = Modifier.matchParentSize(),
            )
        }
        // The row itself, opaque on the panel's surface so the action layer shows only where it slid away.
        Box(
            Modifier
                .offset {
                    val value = reveal.value + previewRevealFraction * widthPx
                    IntOffset((if (isLtr) -value else value).roundToInt(), 0)
                }
                .background(RaftColor.SurfaceWhite)
        ) {
            content()
        }
    }
}

/** The action revealed behind a sliding row: its icon and label, at the side the row uncovered. */
@Composable
private fun ActivitySwipeActionLayer(
    glyph: @Composable () -> Unit,
    label: String,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    alignEnd: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .background(background)
                .clearAndSetSemantics {}
                .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (alignEnd) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph()
        RaftText(
            text = label,
            style = RaftTheme.typography.callout,
            color = foreground,
            maxLines = 1,
        )
    }
}
