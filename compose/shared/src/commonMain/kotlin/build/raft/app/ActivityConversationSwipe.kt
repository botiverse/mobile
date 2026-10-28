package build.raft.app

import build.raft.app.resources.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.haptics.rememberRaftHaptics
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftActivityMarkAction
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.activityDoneRequestFrontier
import build.raft.app.data.isFollowedActivityThread
import build.raft.app.data.isUnfollowedActivityThread
import build.raft.app.data.showsUnread
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.gesture.LocalRaftBackSwipeRegions
import build.raft.designsystem.gesture.RaftBackSwipeEdgeWidth
import build.raft.designsystem.gesture.raftBackSwipeRegion
import build.raft.designsystem.gesture.LocalRaftScrollInProgress
import build.raft.designsystem.gesture.raftClaimsHorizontalDragFirst
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Swipe actions on the Activity conversation-list rows (raft-design #785, responsive.md › Touch screens),
 * as in iOS Mail:
 *
 * - Only toward the start (left in LTR). The row slides away and uncovers a row of buttons at its end:
 *   Done at the outer edge, then the row's other actions inward ([activitySwipeButtons]): Unfollow on a
 *   followed thread, Mute on a joined channel whose Activity isn't muted (the same rows web's
 *   InboxContextMenu offers them to). Each button is [ActivitySwipeButtonWidth] wide and the row's height,
 *   icon over word. Done is solid `success` with ink; the others `fill-muted` with `foreground`.
 * - Released past half the buttons' width the row stays open on them; short of that it springs back
 *   ([activitySwipeReleaseTarget]). Pulled past [ActivitySwipeFullDoneFraction] of the row's width, Done
 *   grows over the whole uncovered area, Android ticks once, and the release applies Done at once.
 * - Open: tapping a button runs its action (Done and Unfollow hide the row behind an 8 s Undo and write
 *   only when it ends; Mute writes at once with an Undo that reverses it). One row is open at a time
 *   ([ActivitySwipeOpenRow], held by the list): a tap anywhere else, a scroll, a swipe back, opening
 *   another row or system Back closes it. A tap on the open row itself closes it rather than opening the
 *   conversation.
 * - Toward the end (right in LTR; raft-design #793): one button at the row's start for the action that
 *   only changes how the row reads ([activitySwipeStartButton]): Read on an unread row, Unread on a read
 *   one, with the menu item's icon (`message-square-check` / `message-square-dot`), `fill-muted` with
 *   `foreground`, the same cell as the end buttons. Same distances: released past half its width it stays
 *   open (tap to act), pulled past [ActivitySwipeFullDoneFraction] the release applies it at once, with the
 *   same tick ([activitySwipeStartReleaseTarget]). The row stays where it is: no toast, no Undo; swiping
 *   again switches it back. The write is the menu's Mark as read / unread ([activityReadToggleIntent]).
 *   A row whose menu has no read toggle (an unfollowed thread) has no start button.
 *
 * The row has no Done button at rest (artin 2026-10-23). The swipe is never the only way: the press-and-
 * hold menu (Mark as read / unread, Done, Unfollow, Mute Activity) stays. A screen reader can neither
 * swipe nor press and hold, so the row's own accessibility node carries the menu's items as custom
 * actions (raft-design #776 / #777; TalkBack's Actions menu, VoiceOver's Actions rotor), named as in the
 * menu and making the same writes (`activityRowActionWrite`). The uncovered buttons themselves are hidden
 * from accessibility (`clearAndSetSemantics`) so a screen reader doesn't hear them twice.
 *
 * Gesture conflicts:
 *
 * - Edge zones: a drag that starts within the row's edge zone ([activitySwipeEdgeZonesPx]) is never
 *   claimed. At the leading edge that is the largest of [ActivitySwipeMinEdgeInset] (24dp), the platform's
 *   system-gesture inset and, on a host with a full-page back swipe (iOS interactive pop),
 *   [RaftBackSwipeEdgeWidth]; at the trailing edge, the larger of 24dp and the system-gesture inset.
 * - Inside the row, the row claims a drag only on clear horizontal intent ([activitySwipeDragDecision]):
 *   past [activitySwipeClaimThresholdPx] (twice the touch slop, 16-24dp) AND |dx| > [ActivitySwipeDominanceRatio]
 *   (2) x |dy|, toward the start when the row has end buttons or its start button is open, toward the end
 *   when it has a start button or its end buttons are open. Any movement past the touch slop that is not on track for that hands the gesture to the list and
 *   locks the swipe out for the rest of that pointer stream ([ActivitySwipeDragPolicy]).
 * - A touch while the list still flings (LocalRaftScrollInProgress at the down) only stops the list:
 *   the row claims nothing from that gesture, and the list holds the filter pager off it too
 *   (raftHoldsHorizontalDragsWhileScrolling). iOS and RecyclerView do the same (artin, #proj-mobile:34aed863).
 * - Filter pager: the rows sit in the Activity filter TabPager, whose drag would otherwise claim at 1x the
 *   touch slop, before the row's gate (iOS 1.15.5: the list slid toward the next filter, the row never
 *   opened). The row registers [raftClaimsHorizontalDragFirst] with its claim distance (0 in its edge
 *   zones), so over a row the pager waits until the row has claimed or released.
 * - iOS full-page pop: an open row, and every row with a start button, registers a back-swipe region
 *   (without its leading [RaftBackSwipeEdgeWidth] strip), so its own drag toward the end is not taken for a
 *   page pop. A closed row without a start button leaves every drag toward the end to the host.
 *
 * Undo, Done and Unfollow ([ActivitySwipeDeferredAction]): Done is applied by the Activity store
 * (SharedActivityIntent.MarkDone), which has no un-done path for every row kind; Unfollow also marks the
 * thread read on the server, so following it again can't restore it (raft-design #790, Jasper and Remy).
 * So the row is hidden locally and the write is committed when the toast times out, when another row's
 * action is applied, or when the list owner (account, server, filter) goes away; Undo just shows the row
 * again, as it was, and nothing is written. A Done is dropped if the row moved on meanwhile; an Unfollow
 * still applies. This holds for the swipe's Unfollow button and the row menu's Unfollow alike.
 *
 * Undo, Mute: it has an exact reverse (Unmute), so it is written on tap through the same command the row
 * menu uses ([activitySwipeEndApplyCommand]); Undo sends the reverse ([activitySwipeEndUndoCommand]). One
 * toast at a time: a newer action replaces the older toast (a waiting Done or Unfollow is committed first).
 */

/**
 * Tests only: button words without the app's string resources (the iOS simulator test binary does not
 * bundle them). Null in the app.
 */
internal val LocalActivitySwipeButtonTestLabels = staticCompositionLocalOf<((ActivitySwipeButton) -> String)?> { null }

/** Width of one uncovered button (raft-design #785: 72px, the row's height, icon over word). */
internal val ActivitySwipeButtonWidth = 72.dp

/** Pulled past this share of the row's width, Done covers the uncovered area and the release applies it. */
internal const val ActivitySwipeFullDoneFraction = 0.6f

/** How long the undo toast stays (and, for Done, before the Done is committed): 8 s (raft-design #775). */
internal const val ActivitySwipeDoneUndoWindowMillis = 8_000L

/** The least edge zone at either side of a row where no drag is claimed (see the header). */
internal val ActivitySwipeMinEdgeInset = 24.dp

/** A claim needs |dx| greater than this many times |dy|; with less, the drag is vertical-leaning and scrolls. */
internal const val ActivitySwipeDominanceRatio = 2f

/** Bounds of the claim distance: twice the touch slop, kept between these (Android 8dp slop -> 16dp). */
internal val ActivitySwipeMinClaimDistance = 16.dp
internal val ActivitySwipeMaxClaimDistance = 24.dp

/**
 * How far a drag must go horizontally before the row may claim it: 2 x the platform touch slop, clamped
 * to [minPx]..[maxPx] so a large slop (iOS) does not make the swipe stiff.
 */
internal fun activitySwipeClaimThresholdPx(touchSlopPx: Float, minPx: Float, maxPx: Float): Float =
    (touchSlopPx * 2f).coerceIn(minPx, maxPx)

/** A row's swipe action besides Done: its Unfollow or Mute button (web InboxContextMenu Unfollow / Mute Activity). */
internal enum class ActivitySwipeEndAction {
    UnfollowThread,
    MuteActivity,
}

/**
 * The Unfollow / Mute button of [this] row, or null for Done only. Same eligibility as web
 * InboxContextMenu (slock packages/web/src/components/thread/InboxContextMenu.tsx):
 *
 * - a followed thread row (`kind == "thread" && isFollowing !== false`): Unfollow. Task conversations are
 *   thread rows there too (`unfollowTaskConversation`, the same `/channels/threads/unfollow` route, and
 *   its Undo follows by the conversation's own id).
 * - a channel row whose channel is joined and has a user-reachable Activity mute that is off
 *   (`canToggleActivityMute`): Mute Activity. [mutedByChannelId] holds exactly those channels
 *   ([activityMuteByChannelId]); an unknown or unjoined channel is absent and gets no Mute button, not a guess.
 * - DMs (no Activity mute), mention rows (a mute does not hold back a direct mention), system rows, an
 *   unfollowed thread and a muted channel: none.
 */
internal fun InboxItem.activitySwipeEndAction(mutedByChannelId: Map<String, Boolean>): ActivitySwipeEndAction? =
    when {
        isFollowedActivityThread -> ActivitySwipeEndAction.UnfollowThread
        kind == InboxItemKind.Channel && mutedByChannelId[channelId.value] == false -> ActivitySwipeEndAction.MuteActivity
        else -> null
    }

/** The menu item that does the same as [this] button (`activityContextMenuActionIds`). */
internal val ActivitySwipeEndAction.menuActionId: String
    get() =
        when (this) {
            ActivitySwipeEndAction.UnfollowThread -> "unfollow-thread"
            ActivitySwipeEndAction.MuteActivity -> "mute-activity"
        }

/** One write an Unfollow / Mute button (or its Undo) makes: an Activity store intent, or the Home channel mute. */
internal sealed interface ActivitySwipeEndCommand {
    data class Activity(val intent: SharedActivityIntent) : ActivitySwipeEndCommand

    data class ChannelMute(val channelId: String, val muted: Boolean) : ActivitySwipeEndCommand
}

/** The button's tap: the same write as the row menu's item. */
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

/** The conversation the Unfollow / Mute toast names: a channel row's channel, a thread's parent (`#name` / `@peer`). */
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

/** One button the swipe uncovers: Done, Unfollow, Mute at the row's end; Read or Unread at its start. */
internal enum class ActivitySwipeButton {
    Done,
    Unfollow,
    Mute,
    Read,
    Unread,
}

/** Which side of the row is open: its start button, or its end buttons. */
internal enum class ActivitySwipeSide {
    Start,
    End,
}

/**
 * The button a swipe toward the end uncovers at [this] row's start (raft-design #793): Read on an unread
 * row, Unread on a read one. Null where the row menu has no read toggle (`activityContextMenuActionIds`:
 * an unfollowed thread, whose attention the server zeroes).
 */
internal fun InboxItem.activitySwipeStartButton(): ActivitySwipeButton? =
    when {
        isUnfollowedActivityThread -> null
        showsUnread -> ActivitySwipeButton.Read
        else -> ActivitySwipeButton.Unread
    }

/** The menu's Mark as read / unread write for [item] (`toggle-read`), which the start button shares. */
internal fun activityReadToggleIntent(item: InboxItem): SharedActivityIntent =
    if (item.showsUnread) SharedActivityIntent.MarkRead(item) else SharedActivityIntent.MarkUnread(item)

/** The `activity_mark` analytics value of [activityReadToggleIntent] (#3615): `read` or `unread`. */
internal fun activityReadToggleMarkAction(item: InboxItem): RaftActivityMarkAction =
    if (item.showsUnread) RaftActivityMarkAction.Read else RaftActivityMarkAction.Unread

/** The action a non-Done button applies. */
internal val ActivitySwipeButton.endAction: ActivitySwipeEndAction?
    get() =
        when (this) {
            ActivitySwipeButton.Done, ActivitySwipeButton.Read, ActivitySwipeButton.Unread -> null
            ActivitySwipeButton.Unfollow -> ActivitySwipeEndAction.UnfollowThread
            ActivitySwipeButton.Mute -> ActivitySwipeEndAction.MuteActivity
        }

/**
 * The buttons a row's swipe uncovers, from the outer edge inward: Done, then Unfollow and Mute where the
 * row can do them. Empty when the row has no Done and no other action (no swipe at all).
 */
internal fun activitySwipeButtons(doneEnabled: Boolean, endActions: List<ActivitySwipeEndAction>): List<ActivitySwipeButton> =
    buildList {
        if (doneEnabled) add(ActivitySwipeButton.Done)
        if (ActivitySwipeEndAction.UnfollowThread in endActions) add(ActivitySwipeButton.Unfollow)
        if (ActivitySwipeEndAction.MuteActivity in endActions) add(ActivitySwipeButton.Mute)
    }

internal enum class ActivitySwipeDragDecision {
    /** Not past the claim distance yet. */
    Undecided,

    /** Horizontal in a direction the row can slide ([activitySwipeDragDecision]): the row takes it. */
    Claim,

    /**
     * Vertical (the list scrolls), from an edge zone (system / host back), or toward a side the row
     * cannot slide (toward the end on a closed row without a start button: the host's back swipe):
     * never consumed.
     */
    Release,
}

/**
 * The decision for a pointer stream that has moved ([dxPx], [dyPx]) since it went down:
 *
 * - Past the touch slop vertically while |dx| is not more than [ActivitySwipeDominanceRatio] x |dy|
 *   (vertical-dominant or diagonal), or past the slop at all from an edge zone: [ActivitySwipeDragDecision.Release].
 * - Horizontal past [claimThresholdPx] with |dx| > ratio x |dy|: claim toward the start when [canOpen]
 *   (the row has end buttons, or its start button is open), toward the end when [canClose] (its end
 *   buttons are open, or it has a start button); otherwise release.
 * - Otherwise [ActivitySwipeDragDecision.Undecided].
 *
 * Stateless; [ActivitySwipeDragPolicy] adds the lock for the rest of the stream.
 */
internal fun activitySwipeDragDecision(
    dxPx: Float,
    dyPx: Float,
    touchSlopPx: Float,
    isLtr: Boolean,
    canOpen: Boolean = true,
    canClose: Boolean = false,
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
        towardStart && canOpen -> ActivitySwipeDragDecision.Claim
        !towardStart && canClose -> ActivitySwipeDragDecision.Claim
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
    private val canOpen: Boolean,
    private val canClose: Boolean,
    private val startsInEdgeZone: Boolean,
) {
    var decision: ActivitySwipeDragDecision = ActivitySwipeDragDecision.Undecided
        private set

    /** The decision after the pointer moved to a total offset of ([dxPx], [dyPx]) from where it went down. */
    fun onMove(dxPx: Float, dyPx: Float): ActivitySwipeDragDecision {
        if (decision != ActivitySwipeDragDecision.Undecided) return decision
        decision =
            activitySwipeDragDecision(
                dxPx, dyPx, touchSlopPx, isLtr, canOpen, canClose, startsInEdgeZone, claimThresholdPx,
            )
        return decision
    }
}

/**
 * The reveal (how far the row has slid toward its start, in px) after a finger movement of [dxPx]. Toward
 * the start uncovers the end buttons, toward the end covers them and then, with a start button
 * ([hasStart]), uncovers it as a negative reveal. It stays within [-widthPx, widthPx], at 0 on a side
 * with no button ([hasEnd], [hasStart]).
 */
internal fun activitySwipeRevealPx(
    currentRevealPx: Float,
    dxPx: Float,
    widthPx: Float,
    isLtr: Boolean,
    hasStart: Boolean = false,
    hasEnd: Boolean = true,
): Float {
    val width = widthPx.coerceAtLeast(0f)
    return (currentRevealPx + if (isLtr) -dxPx else dxPx).coerceIn(if (hasStart) -width else 0f, if (hasEnd) width else 0f)
}

/** Whether the pull is past [ActivitySwipeFullDoneFraction] of the row: Done covers the area and release applies it. */
internal fun activitySwipeFullDone(revealPx: Float, widthPx: Float): Boolean =
    // Half a pixel of slack: 0.6f is not exact in binary, and a sub-pixel never matters to a finger.
    widthPx > 0f && revealPx + 0.5f >= widthPx * ActivitySwipeFullDoneFraction

/** A release at least this fast (per second) toward or away from the buttons opens or closes the row. */
internal val ActivitySwipeFlickVelocity = 400.dp

/** Where a released row goes. */
internal enum class ActivitySwipeRelease {
    /** Springs back closed. */
    Close,

    /** Stays open on its buttons. */
    Open,

    /** Applies Done at once (the pull was past [ActivitySwipeFullDoneFraction]). */
    Done,
}

/**
 * The release at [revealPx] (raft-design #785): past [ActivitySwipeFullDoneFraction] of the row's width,
 * Done (when the row has it); past half the buttons' width ([buttonsWidthPx]), open; otherwise closed.
 */
internal fun activitySwipeReleaseTarget(
    revealPx: Float,
    buttonsWidthPx: Float,
    widthPx: Float,
    doneEnabled: Boolean = true,
    revealVelocityPxPerSec: Float = 0f,
    flickVelocityPxPerSec: Float = Float.POSITIVE_INFINITY,
): ActivitySwipeRelease =
    when {
        widthPx <= 0f || buttonsWidthPx <= 0f || revealPx <= 0f -> ActivitySwipeRelease.Close
        // Only distance applies Done, never a flick, so a fast swipe cannot finish a row by accident.
        doneEnabled && activitySwipeFullDone(revealPx, widthPx) -> ActivitySwipeRelease.Done
        // A quick flick decides by direction: toward the buttons opens, back closes (#785).
        revealVelocityPxPerSec >= flickVelocityPxPerSec -> ActivitySwipeRelease.Open
        revealVelocityPxPerSec <= -flickVelocityPxPerSec -> ActivitySwipeRelease.Close
        revealPx > buttonsWidthPx / 2f -> ActivitySwipeRelease.Open
        else -> ActivitySwipeRelease.Close
    }

/** Where a row released with its start button uncovered goes. */
internal enum class ActivitySwipeStartRelease {
    /** Springs back closed. */
    Close,

    /** Stays open on the button. */
    Open,

    /** Marks the row read or unread at once and springs back (the pull was past [ActivitySwipeFullDoneFraction]). */
    Toggle,
}

/**
 * The release with the start button uncovered by [revealPx] (> 0, raft-design #793), the left swipe's
 * distances: past [ActivitySwipeFullDoneFraction] of the row's width, toggle; past half the button's
 * width ([buttonWidthPx]), open; otherwise closed. A flick opens or closes by direction
 * ([revealVelocityPxPerSec], positive while uncovering the button) but never toggles.
 */
internal fun activitySwipeStartReleaseTarget(
    revealPx: Float,
    buttonWidthPx: Float,
    widthPx: Float,
    revealVelocityPxPerSec: Float = 0f,
    flickVelocityPxPerSec: Float = Float.POSITIVE_INFINITY,
): ActivitySwipeStartRelease =
    when {
        widthPx <= 0f || buttonWidthPx <= 0f || revealPx <= 0f -> ActivitySwipeStartRelease.Close
        activitySwipeFullDone(revealPx, widthPx) -> ActivitySwipeStartRelease.Toggle
        revealVelocityPxPerSec >= flickVelocityPxPerSec -> ActivitySwipeStartRelease.Open
        revealVelocityPxPerSec <= -flickVelocityPxPerSec -> ActivitySwipeStartRelease.Close
        revealPx > buttonWidthPx / 2f -> ActivitySwipeStartRelease.Open
        else -> ActivitySwipeStartRelease.Close
    }

/**
 * The side a claimed drag works on, from the reveal when it was claimed and its direction: toward the
 * start it covers an open start button or uncovers the end buttons, toward the end it covers open end
 * buttons or uncovers the start button.
 */
internal fun activitySwipeDragSide(revealPx: Float, towardStart: Boolean): ActivitySwipeSide =
    if (towardStart) {
        if (revealPx < 0f) ActivitySwipeSide.Start else ActivitySwipeSide.End
    } else {
        if (revealPx > 0f) ActivitySwipeSide.End else ActivitySwipeSide.Start
    }

/**
 * Whether a drag at [revealPx] is past the release point that applies at once: full Done toward the
 * start (with Done), the read toggle toward the end (with a start button). Feeds the tick.
 */
internal fun activitySwipeArmed(revealPx: Float, widthPx: Float, doneEnabled: Boolean, hasStart: Boolean): Boolean =
    when {
        revealPx > 0f -> doneEnabled && activitySwipeFullDone(revealPx, widthPx)
        revealPx < 0f -> hasStart && activitySwipeFullDone(-revealPx, widthPx)
        else -> false
    }

/**
 * Fires the full-Done tick: true exactly once each time the pull crosses into full Done (outward). Going
 * back under re-arms it silently; the next outward crossing ticks again.
 */
internal class ActivitySwipeFullDoneTickGate {
    private var wasFull = false

    /** Feeds the drag's new full-Done state; true when this update is an outward crossing. */
    fun update(fullDone: Boolean): Boolean {
        val crossed = fullDone && !wasFull
        wasFull = fullDone
        return crossed
    }
}

/**
 * The widths of the uncovered buttons, outer edge first, at [revealPx] with [count] buttons: each takes an
 * equal share of the uncovered width (exactly [ActivitySwipeButtonWidth] when open at rest, narrower while
 * sliding in, wider when pulled further). [fullDoneProgress] (0..1) grows the outer button (Done) over the
 * whole uncovered width; the inner ones keep their share and lie under it.
 */
internal fun activitySwipeButtonWidthsPx(revealPx: Float, count: Int, fullDoneProgress: Float): List<Float> {
    if (count <= 0) return emptyList()
    val reveal = revealPx.coerceAtLeast(0f)
    val share = reveal / count
    val progress = fullDoneProgress.coerceIn(0f, 1f)
    return List(count) { index -> if (index == 0) share + (reveal - share) * progress else share }
}

/**
 * Which row's buttons are open, list-wide (one at a time). Pure; unit-tested. A row that claims a drag
 * becomes the open one at once, so the previously open row closes as the new one starts to slide.
 */
@Immutable
internal data class ActivitySwipeOpenRow(val key: String? = null, val side: ActivitySwipeSide = ActivitySwipeSide.End) {
    fun isOpen(rowKey: String): Boolean = key == rowKey

    /** The side row [rowKey] is open on, or null when it is closed. */
    fun openSide(rowKey: String): ActivitySwipeSide? = side.takeIf { key == rowKey }

    /** Row [rowKey] opened on [side] (or started a swipe), or settled closed. */
    fun change(rowKey: String, open: Boolean, side: ActivitySwipeSide = ActivitySwipeSide.End): ActivitySwipeOpenRow =
        when {
            open -> if (key == rowKey && this.side == side) this else ActivitySwipeOpenRow(rowKey, side)
            key == rowKey -> ActivitySwipeOpenRow(null)
            else -> this
        }

    /** [change] from a side, or null for closed. */
    fun change(rowKey: String, side: ActivitySwipeSide?): ActivitySwipeOpenRow =
        if (side == null) change(rowKey, open = false) else change(rowKey, open = true, side = side)

    /** A tap elsewhere, a scroll, Back, the long-press menu or the list owner going away. */
    fun close(): ActivitySwipeOpenRow = if (key == null) this else ActivitySwipeOpenRow(null)

    /** A pointer went down at a point inside ([insideOpenRow]) or outside the open row: outside closes it. */
    fun onPointerDown(insideOpenRow: Boolean): ActivitySwipeOpenRow = if (insideOpenRow) this else close()

    /** The open row left the list (Done, a filter, a refresh): nothing stays open (nor holds Back). */
    fun retain(listedKeys: Collection<String>): ActivitySwipeOpenRow = if (key != null && key !in listedKeys) close() else this
}

/** What a hidden row waits to write when its toast ends: a Done or a thread's Unfollow (raft-design #790). */
internal enum class ActivitySwipeDeferredAction { Done, Unfollow }

/**
 * A swiped row waiting out its undo window. [listOwnerUserId] / [listOwnerServerId] are the account and
 * server whose list showed it, so an Unfollow written after the filter changed still lands, and only there.
 */
@Immutable
internal data class ActivityPendingSwipeDone(
    val item: InboxItem,
    val token: Long,
    val action: ActivitySwipeDeferredAction = ActivitySwipeDeferredAction.Done,
    val listOwnerUserId: String? = null,
    val listOwnerServerId: String? = null,
)

/** One step of [ActivitySwipeDoneUndoState]: the next state, and the waiting row to commit now, if any. */
internal data class ActivitySwipeDoneTransition(val state: ActivitySwipeDoneUndoState, val commit: ActivityPendingSwipeDone?)

/**
 * At most one swiped Done or Unfollow waits for undo; a newer one commits the older one. Pure; unit-tested.
 */
@Immutable
internal data class ActivitySwipeDoneUndoState(
    val pending: ActivityPendingSwipeDone? = null,
    private val nextToken: Long = 1L,
) {
    /** The row hidden from the list while its Done or Unfollow waits. */
    val hiddenKey: String? get() = pending?.item?.key

    fun swipe(
        item: InboxItem,
        action: ActivitySwipeDeferredAction = ActivitySwipeDeferredAction.Done,
        listOwnerUserId: String? = null,
        listOwnerServerId: String? = null,
    ): ActivitySwipeDoneTransition {
        if (pending?.item?.key == item.key) return ActivitySwipeDoneTransition(this, commit = null)
        return ActivitySwipeDoneTransition(
            ActivitySwipeDoneUndoState(
                ActivityPendingSwipeDone(item, nextToken, action, listOwnerUserId, listOwnerServerId),
                nextToken + 1,
            ),
            commit = pending,
        )
    }

    /** Undo: the row comes back and nothing is written. A stale toast's token does nothing. */
    fun undo(token: Long): ActivitySwipeDoneUndoState = if (pending?.token == token) copy(pending = null) else this

    /** The undo window of [token] ran out: commit it. */
    fun expire(token: Long): ActivitySwipeDoneTransition {
        val expiring = pending?.takeIf { it.token == token } ?: return ActivitySwipeDoneTransition(this, commit = null)
        return ActivitySwipeDoneTransition(copy(pending = null), expiring)
    }

    /** Another row's action or the list owner goes away: commit now rather than drop the person's action. */
    fun flush(): ActivitySwipeDoneTransition = ActivitySwipeDoneTransition(copy(pending = null), pending)
}

/**
 * The rows the list shows: without the one hidden behind its toast ([hiddenKey]), and without a thread
 * unfollowed here ([committedUnfollowKeys]) while it stays unfollowed (a failed or reversed Unfollow
 * shows it again).
 */
internal fun activitySwipeListedItems(
    items: List<InboxItem>,
    hiddenKey: String?,
    committedUnfollowKeys: Set<String>,
): List<InboxItem> {
    if (hiddenKey == null && committedUnfollowKeys.isEmpty()) return items
    return items.filterNot { item ->
        item.key == hiddenKey || (item.key in committedUnfollowKeys && !item.isFollowedActivityThread)
    }
}

/**
 * The intent to send when [pending] commits, or null to write nothing. [current] is the row as the list
 * presents it now (null: no longer presented). A Done follows [activitySwipeDoneCommitItem]. An Unfollow
 * always goes out: the person stops following whatever the thread says next (raft-design #790); it names
 * its list owner so the store writes it even if that list has since moved to another filter.
 */
internal fun activitySwipeDeferredCommitIntent(pending: ActivityPendingSwipeDone, current: InboxItem?): SharedActivityIntent? =
    when (pending.action) {
        ActivitySwipeDeferredAction.Done -> activitySwipeDoneCommitItem(pending.item, current)?.let(SharedActivityIntent::MarkDone)
        ActivitySwipeDeferredAction.Unfollow ->
            SharedActivityIntent.UnfollowThread(
                item = current?.takeIf { it.isFollowedActivityThread } ?: pending.item,
                listOwnerUserId = pending.listOwnerUserId,
                listOwnerServerId = pending.listOwnerServerId,
            )
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

/** An Unfollow / Mute (already applied) whose toast offers Undo. */
@Immutable
internal data class ActivityPendingSwipeEnd(val item: InboxItem, val action: ActivitySwipeEndAction, val token: Long)

/** One step of [ActivitySwipeEndUndoState]: the next state, and the swipe to reverse now, if any. */
internal data class ActivitySwipeEndTransition(val state: ActivitySwipeEndUndoState, val reverse: ActivityPendingSwipeEnd?)

/**
 * The toast of the latest Mute (an Unfollow waits for its toast instead: [ActivitySwipeDoneUndoState]). The
 * action is written on tap, so a newer action, the window running out or the list owner going away only
 * drops the toast; Undo returns the swipe to reverse.
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
 * After a DOWN ([downId] at [downPosition]): if the stream ends as a tap (lifted within the touch slop),
 * consume its UP at the [PointerEventPass.Initial] pass, so the row or control under it does not click,
 * and return true. A stream that moves past the slop (a scroll, a swipe) is left alone.
 */
internal suspend fun AwaitPointerEventScope.activitySwipeSwallowTap(downId: PointerId, downPosition: Offset): Boolean {
    val slop = viewConfiguration.touchSlop
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == downId } ?: return false
        if (!change.pressed) {
            change.consume()
            return true
        }
        if ((change.position - downPosition).getDistance() > slop) return false
    }
}

/**
 * Closes the list's open row on a pointer going down outside it ([ActivitySwipeOpenRow.onPointerDown]); a
 * tap that closes it is swallowed, so it does not also open a conversation. Put it on the box around the
 * list, after [ActivitySwipeOpenRowGeometry.trackPanel].
 */
internal fun Modifier.activitySwipeCloseOnOutsideDown(
    geometry: ActivitySwipeOpenRowGeometry,
    openRow: () -> ActivitySwipeOpenRow,
    onChange: (ActivitySwipeOpenRow) -> Unit,
): Modifier =
    pointerInput(geometry) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val current = openRow()
            if (current.key == null) return@awaitEachGesture
            val next = current.onPointerDown(insideOpenRow = geometry.containsInOpenRow(down.position))
            if (next == current) return@awaitEachGesture
            onChange(next)
            activitySwipeSwallowTap(down.id, down.position)
        }
    }

/**
 * Where the open row is, for [activitySwipeCloseOnOutsideDown]: the list's box and the open row's layout
 * coordinates, read at DOWN time only (not snapshot state).
 */
internal class ActivitySwipeOpenRowGeometry {
    private var panel: LayoutCoordinates? = null
    private var openRow: LayoutCoordinates? = null

    fun trackPanel(modifier: Modifier): Modifier = modifier.onPlaced { panel = it }

    fun onOpenRowPlaced(coordinates: LayoutCoordinates) {
        openRow = coordinates
    }

    fun containsInOpenRow(position: Offset): Boolean {
        val panelCoordinates = panel?.takeIf { it.isAttached } ?: return false
        val rowCoordinates = openRow?.takeIf { it.isAttached } ?: return false
        return panelCoordinates.localBoundingBoxOf(rowCoordinates, clipBounds = false).contains(position)
    }
}

/**
 * Hosts one conversation-list row with its swipe buttons: [buttons] at its end (a swipe toward the start)
 * and [startButton] at its start (a swipe toward the end). Neither: no swipe, the row renders as is.
 * [openSide] is the side the list has this row open on ([ActivitySwipeOpenRow]), null when closed; the
 * row reports a claimed swipe, and how it settles, through [onOpenChange]. [onDone] applies Done (8 s
 * Undo); [onAction] Unfollow or Mute; [onToggleRead] the start button's Mark as read / unread.
 * [gestureKey] resets the drag when the row's slot changes owner. [previewRevealFraction] slides the row
 * by that share of its width on top of [openSide] (negative: toward the end), for screenshots only.
 */
@Composable
internal fun ActivitySwipeActionsBox(
    gestureKey: String,
    buttons: List<ActivitySwipeButton>,
    openSide: ActivitySwipeSide?,
    onOpenChange: (ActivitySwipeSide?) -> Unit,
    onDone: () -> Unit,
    onAction: (ActivitySwipeEndAction) -> Unit,
    modifier: Modifier = Modifier,
    startButton: ActivitySwipeButton? = null,
    onToggleRead: () -> Unit = {},
    onOpenRowPlaced: ((LayoutCoordinates) -> Unit)? = null,
    previewRevealFraction: Float = 0f,
    content: @Composable () -> Unit,
) {
    if (buttons.isEmpty() && startButton == null) {
        Box(modifier) { content() }
        return
    }
    val layoutDirection = LocalLayoutDirection.current
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val density = LocalDensity.current
    val buttonsWidthPx = with(density) { ActivitySwipeButtonWidth.toPx() } * buttons.size
    val startButtonWidthPx = if (startButton != null) with(density) { ActivitySwipeButtonWidth.toPx() } else 0f
    val hasEnd = buttons.isNotEmpty()
    val hasStart = startButton != null
    val doneEnabled = ActivitySwipeButton.Done in buttons
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
    // The row decides at 2x the touch slop, later than the tab pager it sits in would take the drag (1x);
    // the pager waits for this distance over the row (outside its edge zones), in either direction, then
    // the row has claimed or released. Without it the whole list slid toward the next filter page on iOS
    // (1.15.5), and a swipe toward the end would turn the filter page instead of uncovering Read / Unread.
    val claimThresholdPx =
        activitySwipeClaimThresholdPx(
            LocalViewConfiguration.current.touchSlop,
            with(density) { ActivitySwipeMinClaimDistance.toPx() },
            with(density) { ActivitySwipeMaxClaimDistance.toPx() },
        )
    fun restReveal(side: ActivitySwipeSide?): Float =
        when (side) {
            ActivitySwipeSide.End -> if (hasEnd) buttonsWidthPx else 0f
            ActivitySwipeSide.Start -> if (hasStart) -startButtonWidthPx else 0f
            null -> 0f
        }
    // Starts where the list says the row is, so an open row recomposed (or captured) shows open at once.
    // Signed: positive uncovers the end buttons, negative the start button.
    val reveal = remember(gestureKey) { Animatable(restReveal(openSide)) }
    // True while a finger drives the row or a Done is completing: the row then owns its reveal.
    var busy by remember(gestureKey) { mutableStateOf(false) }
    // Crossing into full Done is a gesture threshold (RaftHaptics): both platforms buzz now, iOS too.
    val haptics = rememberRaftHaptics()
    val currentHaptics by rememberUpdatedState(haptics)
    val scope = rememberCoroutineScope()
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val currentOnDone by rememberUpdatedState(onDone)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnToggleRead by rememberUpdatedState(onToggleRead)
    val currentOpenSide by rememberUpdatedState(openSide)
    val currentHasStart by rememberUpdatedState(hasStart)
    // Whether the list around the row is still moving (a fling), read at each gesture's down.
    val listScrolling by rememberUpdatedState(LocalRaftScrollInProgress.current)
    var widthPx by remember { mutableIntStateOf(0) }
    // At rest the row follows the list: open on a side, or closed (another row opened, a tap elsewhere,
    // a scroll, Back).
    LaunchedEffect(reveal, openSide, busy, buttonsWidthPx, startButtonWidthPx) {
        if (busy) return@LaunchedEffect
        val rest = restReveal(openSide)
        if (reveal.value != rest) reveal.animateTo(rest)
    }
    fun completeDone() {
        scope.launch {
            busy = true
            currentOnOpenChange(null)
            reveal.animateTo(widthPx.toFloat())
            currentOnDone()
            // The list drops the row in the same frame; if it stays, it comes back closed.
            reveal.snapTo(0f)
            busy = false
        }
    }
    fun completeToggle() {
        scope.launch {
            busy = true
            currentOnOpenChange(null)
            // The row stays in the list (#793): it switches read state and springs back in place.
            currentOnToggleRead()
            reveal.animateTo(0f)
            busy = false
        }
    }
    val swipeOpen = openSide != null
    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .then(if (swipeOpen && onOpenRowPlaced != null) Modifier.onPlaced(onOpenRowPlaced) else Modifier)
            // iOS full-page pop: an open row, and a row whose swipe toward the end uncovers its start
            // button, keep that drag; the leading edge strip still pops the page. Inert on hosts without a
            // content back swipe.
            .then(
                if (swipeOpen || hasStart) {
                    Modifier.raftBackSwipeRegion(RaftBackSwipeEdgeWidth) { currentOpenSide != null || currentHasStart }
                } else {
                    Modifier
                }
            )
            .clipToBounds()
            .raftClaimsHorizontalDragFirst { down, size ->
                val inEdgeZone = activitySwipeStartsInEdgeZone(down.x, size.width.toFloat(), startEdgePx, endEdgePx, isLtr)
                if (inEdgeZone) 0f else claimThresholdPx
            }
            // On the box that does not move, so finger positions are not shifted by the row's own offset.
            // Children (the row's tap / long press, the buttons) see each event first; consuming a claimed
            // drag here cancels their tap, and the list above never sees a claimed drag.
            .pointerInput(gestureKey, isLtr, buttonsWidthPx, startButtonWidthPx, doneEnabled, startEdgePx, endEdgePx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    // A touch on a list that still flings only stops it (iOS, RecyclerView): this
                    // gesture is never a swipe. The row sees the down before the list stops.
                    if (listScrolling()) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val atDown = reveal.value
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
                            // Toward the start: uncovers the end buttons, or covers an open start button.
                            canOpen = hasEnd || atDown < 0f,
                            // Toward the end: covers open end buttons, or uncovers the start button.
                            canClose = atDown > 0f || hasStart,
                            startsInEdgeZone =
                                activitySwipeStartsInEdgeZone(down.position.x, size.width.toFloat(), startEdgePx, endEdgePx, isLtr),
                        )
                    var claimed = false
                    var claimedTowardStart = true
                    while (!claimed) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        // Consumed: the list is scrolling, or a long press opened the menu.
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        val total = change.position - down.position
                        when (policy.onMove(total.x, total.y)) {
                            ActivitySwipeDragDecision.Undecided -> Unit
                            ActivitySwipeDragDecision.Release -> return@awaitEachGesture
                            ActivitySwipeDragDecision.Claim -> {
                                claimed = true
                                claimedTowardStart = if (isLtr) total.x < 0f else total.x > 0f
                                change.consume()
                            }
                        }
                    }
                    busy = true
                    var target = reveal.value
                    // This row is the open one now: any other open row closes as this one slides.
                    currentOnOpenChange(activitySwipeDragSide(target, claimedTowardStart))
                    // Where the row goes on release (a value, not a lambda: Kotlin/Native miscasts a captured
                    // function-typed var here).
                    var settleSide: ActivitySwipeSide? = null
                    var applyOnRelease = false
                    val tickGate = ActivitySwipeFullDoneTickGate()
                    val velocity = VelocityTracker()
                    tickGate.update(activitySwipeArmed(target, widthPx.toFloat(), doneEnabled, hasStart))
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                velocity.addPointerInputChange(change)
                                // Positive while uncovering the end buttons: leftward in LTR, rightward in RTL.
                                val dragVelocityX = velocity.calculateVelocity().x
                                val revealVelocity = if (isLtr) -dragVelocityX else dragVelocityX
                                val flick = ActivitySwipeFlickVelocity.toPx()
                                if (target < 0f) {
                                    when (activitySwipeStartReleaseTarget(-target, startButtonWidthPx, widthPx.toFloat(), -revealVelocity, flick)) {
                                        ActivitySwipeStartRelease.Toggle -> {
                                            settleSide = ActivitySwipeSide.Start
                                            applyOnRelease = true
                                        }
                                        ActivitySwipeStartRelease.Open -> settleSide = ActivitySwipeSide.Start
                                        ActivitySwipeStartRelease.Close -> Unit
                                    }
                                } else {
                                    when (
                                        activitySwipeReleaseTarget(
                                            target, buttonsWidthPx, widthPx.toFloat(), doneEnabled, revealVelocity, flick,
                                        )
                                    ) {
                                        ActivitySwipeRelease.Done -> {
                                            settleSide = ActivitySwipeSide.End
                                            applyOnRelease = true
                                        }
                                        ActivitySwipeRelease.Open -> settleSide = ActivitySwipeSide.End
                                        ActivitySwipeRelease.Close -> Unit
                                    }
                                }
                                break
                            }
                            velocity.addPointerInputChange(change)
                            target =
                                activitySwipeRevealPx(
                                    target, change.position.x - change.previousPosition.x, widthPx.toFloat(), isLtr, hasStart, hasEnd,
                                )
                            change.consume()
                            if (tickGate.update(activitySwipeArmed(target, widthPx.toFloat(), doneEnabled, hasStart))) {
                                currentHaptics.threshold()
                            }
                            scope.launch { reveal.snapTo(target) }
                        }
                    } finally {
                        // Also on cancellation (pointer input reset): never leave a row half open.
                        when {
                            applyOnRelease && settleSide == ActivitySwipeSide.End -> completeDone()
                            applyOnRelease -> completeToggle()
                            else -> {
                                currentOnOpenChange(settleSide)
                                busy = false
                            }
                        }
                    }
                }
            }
    ) {
        val maxReveal = widthPx.toFloat().coerceAtLeast(0f)
        val shownReveal = (reveal.value + previewRevealFraction * widthPx).coerceIn(-maxReveal, maxReveal)
        if (shownReveal > 0f && hasEnd) {
            ActivitySwipeButtonsLayer(
                buttons = buttons,
                revealPx = shownReveal,
                fullDone = doneEnabled && activitySwipeFullDone(shownReveal, widthPx.toFloat()),
                onTap = { button ->
                    when (val action = button.endAction) {
                        null -> completeDone()
                        else -> {
                            currentOnOpenChange(null)
                            currentOnAction(action)
                        }
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        if (shownReveal < 0f && startButton != null) {
            ActivitySwipeStartButtonLayer(
                button = startButton,
                revealPx = -shownReveal,
                onTap = {
                    currentOnOpenChange(null)
                    currentOnToggleRead()
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        // The row itself, opaque on the panel's surface so the buttons show only where it slid away.
        Box(
            Modifier
                .offset {
                    val value = (reveal.value + previewRevealFraction * widthPx).coerceIn(-maxReveal, maxReveal)
                    IntOffset((if (isLtr) -value else value).roundToInt(), 0)
                }
                .background(RaftColor.SurfaceWhite)
                .then(
                    if (swipeOpen) {
                        // A tap on the open row closes it instead of opening the conversation; a drag
                        // still reaches the swipe (and a vertical one the list).
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (activitySwipeSwallowTap(down.id, down.position)) currentOnOpenChange(null)
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }
    }
}

/**
 * The buttons behind a sliding row, at its end: Done at the outer edge, the others inward, each an equal
 * share of [revealPx] ([activitySwipeButtonWidthsPx]); past full Done, Done grows over the others.
 * Decorative to accessibility (the row's press-and-hold menu has the same actions), but tappable.
 */
@Composable
private fun ActivitySwipeButtonsLayer(
    buttons: List<ActivitySwipeButton>,
    revealPx: Float,
    fullDone: Boolean,
    onTap: (ActivitySwipeButton) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val progress by animateFloatAsState(if (fullDone) 1f else 0f, label = "activitySwipeFullDone")
    val widths = activitySwipeButtonWidthsPx(revealPx, buttons.size, progress)
    Box(modifier.clearAndSetSemantics {}) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(with(density) { revealPx.toDp() })
                .fillMaxHeight()
        ) {
            // Inner buttons first (innermost at the start of the uncovered area), Done over them at the end.
            Row(Modifier.fillMaxHeight()) {
                buttons.indices.drop(1).reversed().forEach { index ->
                    ActivitySwipeButtonCell(button = buttons[index], widthPx = widths[index], onTap = onTap)
                }
            }
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                // Grown over the area, Done keeps its icon and word at the inner edge, beside the row.
                ActivitySwipeButtonCell(button = buttons[0], widthPx = widths[0], onTap = onTap)
            }
        }
    }
}

/**
 * The Read / Unread button behind a row slid toward its end, at its start, as wide as the uncovered
 * width; its icon and word sit at its inner edge (beside the row) once it is wider than
 * [ActivitySwipeButtonWidth]. Decorative to accessibility (the menu has Mark as read / unread).
 */
@Composable
private fun ActivitySwipeStartButtonLayer(
    button: ActivitySwipeButton,
    revealPx: Float,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.clearAndSetSemantics {}) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight()) {
            ActivitySwipeButtonCell(button = button, widthPx = revealPx, onTap = { onTap() }, innerEdgeAtEnd = true)
        }
    }
}

@Composable
private fun ActivitySwipeButtonCell(
    button: ActivitySwipeButton,
    widthPx: Float,
    onTap: (ActivitySwipeButton) -> Unit,
    /** The edge beside the row: the cell's start for the end buttons, its end for the start button. */
    innerEdgeAtEnd: Boolean = false,
) {
    val colors = RaftTheme.colors
    val density = LocalDensity.current
    val solid = button == ActivitySwipeButton.Done
    val narrow = widthPx < with(density) { ActivitySwipeButtonWidth.toPx() }
    val background = if (solid) RaftUiPalette.success else colors.fillMuted
    // Done keeps ink on its green in both themes (mock v1: dark mode is still solid green with ink).
    val foreground = if (solid) RaftColor.StickerInk else colors.foreground
    val testLabels = LocalActivitySwipeButtonTestLabels.current
    val label =
        when {
            testLabels != null -> testLabels(button)
            else ->
                when (button) {
                    ActivitySwipeButton.Done -> stringResource(Res.string.activity_swipe_layer_done)
                    ActivitySwipeButton.Unfollow -> stringResource(Res.string.activity_swipe_layer_unfollow)
                    ActivitySwipeButton.Mute -> stringResource(Res.string.activity_swipe_layer_mute)
                    ActivitySwipeButton.Read -> stringResource(Res.string.activity_swipe_layer_read)
                    ActivitySwipeButton.Unread -> stringResource(Res.string.activity_swipe_layer_unread)
                }
        }
    val innerAlignment = if (innerEdgeAtEnd) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .background(background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap(button) },
        contentAlignment = if (narrow) Alignment.Center else innerAlignment,
    ) {
        // A fixed 72dp column, so the icon and word never squeeze: centred while the button slides in
        // narrower than that, and at the inner edge (beside the row) once it is wider, as Done is past
        // full Done (mock v1, #785).
        Column(
            Modifier
                .wrapContentWidth(
                    when {
                        narrow -> Alignment.CenterHorizontally
                        innerEdgeAtEnd -> Alignment.End
                        else -> Alignment.Start
                    },
                    unbounded = true,
                )
                .width(ActivitySwipeButtonWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            when (button) {
                ActivitySwipeButton.Done -> RaftGlyphIcon(RaftGlyph.Check, size = 18.dp, color = foreground)
                // Each button uses its action's menu icon, as on web: Unfollow thread is message-circle-off.
                ActivitySwipeButton.Unfollow ->
                    RaftLucideIcon(RaftLucideIconName.MessageCircleOff, Modifier.size(18.dp), color = raftArgbHex(foreground))
                ActivitySwipeButton.Mute ->
                    RaftLucideIcon(RaftLucideIconName.BellOff, Modifier.size(18.dp), color = raftArgbHex(foreground))
                // Mark as read / unread (web InboxContextMenu): message-square-check / message-square-dot.
                ActivitySwipeButton.Read ->
                    RaftLucideIcon(RaftLucideIconName.MessageSquareCheck, Modifier.size(18.dp), color = raftArgbHex(foreground))
                ActivitySwipeButton.Unread ->
                    RaftLucideIcon(RaftLucideIconName.MessageSquareDot, Modifier.size(18.dp), color = raftArgbHex(foreground))
            }
            RaftText(
                text = label,
                // 12sp semibold (mock v1): caption at 600.
                style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = foreground,
                maxLines = 1,
            )
        }
    }
}
