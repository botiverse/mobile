package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.session
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Rule L (raft-design #562, touch.md#swipe) swipes on the Activity conversation list: policy, eligibility, undo. */
class ActivityConversationSwipeTest {

    private fun row(key: String, seq: String = "41", at: String = "2026-10-20T10:00:00Z") =
        InboxItem(
            key = key,
            kind = InboxItemKind.Channel,
            channelId = ChannelId("ch-$key"),
            channelName = "general",
            latestActivityAt = at,
            doneFrontierSeq = seq,
            doneFrontierSeqPresent = true,
        )

    @Test
    fun doneIsAvailableBySwipeAndInTheLongPressMenuForEveryRow() {
        // Rule L: the row has no Done button; the swipe is never the only way, so Done is in the menu too.
        val rows =
            InboxItemKind.entries.flatMap { kind ->
                listOf(null, true, false).map { following -> row("k-$kind-$following").copy(kind = kind, isFollowing = following) }
            }
        rows.forEach { item ->
            assertTrue(item.activityShowsDoneAction(), "swipe Done for ${item.key}")
            assertTrue("done" in activityContextMenuActionIds(item), "menu Done for ${item.key}")
        }
    }

    @Test
    fun dragIsClaimedOnlyWhenHorizontalAndTowardASideWithAnAction() {
        val slop = 10f
        data class Case(val dx: Float, val dy: Float, val isLtr: Boolean, val end: Boolean, val expected: ActivitySwipeDragDecision)
        val cases =
            listOf(
                Case(-4f, 3f, true, end = true, ActivitySwipeDragDecision.Undecided),
                // Under the claim distance (2 x slop = 20): still undecided, horizontal or not.
                Case(-18f, 9f, true, end = true, ActivitySwipeDragDecision.Undecided),
                Case(-19f, 0f, true, end = true, ActivitySwipeDragDecision.Undecided),
                Case(-30f, 5f, true, end = false, ActivitySwipeDragDecision.ClaimDone),
                // Toward the end: the row's end action when it has one, else left to the host's back swipe.
                Case(30f, 5f, true, end = true, ActivitySwipeDragDecision.ClaimEnd),
                Case(30f, 5f, true, end = false, ActivitySwipeDragDecision.Release),
                // Vertical-dominant (and a tie) belongs to the list.
                Case(-12f, 40f, true, end = true, ActivitySwipeDragDecision.Release),
                Case(20f, 20f, true, end = true, ActivitySwipeDragDecision.Release),
                // Diagonal and mostly vertical: past the slop vertically without 2x horizontal dominance.
                Case(-40f, 25f, true, end = true, ActivitySwipeDragDecision.Release),
                Case(-22f, 12f, true, end = true, ActivitySwipeDragDecision.Release),
                Case(-14f, 10f, true, end = true, ActivitySwipeDragDecision.Release),
                Case(-24f, 12f, true, end = true, ActivitySwipeDragDecision.Release), // exactly 2x is not more than 2x
                // Clear horizontal intent claims, even with a little vertical drift.
                Case(-30f, 14f, true, end = true, ActivitySwipeDragDecision.ClaimDone),
                Case(-21f, 0f, true, end = true, ActivitySwipeDragDecision.ClaimDone),
                // RTL mirrors: the row's start is on the right, the end (and back) is leftward.
                Case(30f, 5f, false, end = true, ActivitySwipeDragDecision.ClaimDone),
                Case(-30f, 5f, false, end = true, ActivitySwipeDragDecision.ClaimEnd),
                Case(-30f, 5f, false, end = false, ActivitySwipeDragDecision.Release),
            )
        cases.forEach { case ->
            assertEquals(
                case.expected,
                activitySwipeDragDecision(case.dx, case.dy, slop, case.isLtr, doneEnabled = true, endEnabled = case.end),
                "$case",
            )
        }
        // A row without Done (none today) would leave the start direction alone too.
        assertEquals(ActivitySwipeDragDecision.Release, activitySwipeDragDecision(-30f, 5f, slop, true, doneEnabled = false, endEnabled = true))
    }

    @Test
    fun claimDistanceIsTwiceTheTouchSlopWithinSixteenToTwentyFourDp() {
        // Android: 8dp slop -> 16dp; iOS-sized slop (18dp) is capped at 24dp; a tiny slop keeps the 16dp floor.
        assertEquals(16f, activitySwipeClaimThresholdPx(8f, 16f, 24f))
        assertEquals(24f, activitySwipeClaimThresholdPx(18f, 16f, 24f))
        assertEquals(16f, activitySwipeClaimThresholdPx(4f, 16f, 24f))
        assertEquals(2f, ActivitySwipeDominanceRatio)
    }

    private fun policy(isLtr: Boolean = true, edge: Boolean = false, end: Boolean = true) =
        ActivitySwipeDragPolicy(
            touchSlopPx = 10f,
            claimThresholdPx = 20f,
            isLtr = isLtr,
            doneEnabled = true,
            endEnabled = end,
            startsInEdgeZone = edge,
        )

    @Test
    fun aStreamThatTurnedVerticalIsLockedOutEvenIfItLaterGoesHorizontal() {
        val p = policy()
        assertEquals(ActivitySwipeDragDecision.Undecided, p.onMove(-2f, 6f))
        // Scrolling: vertical-dominant past the slop hands the gesture to the list...
        assertEquals(ActivitySwipeDragDecision.Release, p.onMove(-4f, 14f))
        // ...and the rest of the stream stays with it, however horizontal the finger gets afterwards.
        assertEquals(ActivitySwipeDragDecision.Release, p.onMove(-80f, 14f))
        assertEquals(ActivitySwipeDragDecision.Release, p.onMove(-200f, 14f))
    }

    @Test
    fun aClearHorizontalStreamClaimsAndStaysClaimedWhenItDriftsVertically() {
        val p = policy()
        assertEquals(ActivitySwipeDragDecision.Undecided, p.onMove(-12f, 1f))
        assertEquals(ActivitySwipeDragDecision.ClaimDone, p.onMove(-24f, 3f))
        // Once claimed, it is horizontal for good: no later release.
        assertEquals(ActivitySwipeDragDecision.ClaimDone, p.onMove(-30f, 60f))
    }

    @Test
    fun aDiagonalStreamIsReleasedAndNeverClaimedLater() {
        val p = policy()
        assertEquals(ActivitySwipeDragDecision.Release, p.onMove(-18f, 12f))
        assertEquals(ActivitySwipeDragDecision.Release, p.onMove(-60f, 12f))
    }

    @Test
    fun edgeZoneStreamsAreNeverClaimedAndMirrorInRtl() {
        listOf(30f, -30f).forEach { dx ->
            val p = policy(edge = true)
            assertEquals(ActivitySwipeDragDecision.Release, p.onMove(dx, 0f))
            assertEquals(ActivitySwipeDragDecision.Release, p.onMove(dx * 3, 0f))
        }
        val rtl = policy(isLtr = false)
        assertEquals(ActivitySwipeDragDecision.ClaimDone, rtl.onMove(30f, 2f))
        assertEquals(ActivitySwipeDragDecision.ClaimEnd, policy(isLtr = false).onMove(-30f, 2f))
    }

    @Test
    fun aDragFromAnEdgeZoneIsNeverClaimed() {
        // Zones: the largest of 24dp, the system-gesture inset and (start only) the host's pop edge.
        assertEquals(24f to 24f, activitySwipeEdgeZonesPx(24f, 0f, 0f, 0f))
        assertEquals(40f to 30f, activitySwipeEdgeZonesPx(24f, 30f, 30f, 40f))
        assertEquals(48f to 24f, activitySwipeEdgeZonesPx(24f, 48f, 0f, 40f))

        val width = 400f
        val (start, end) = 40f to 24f
        data class Case(val downX: Float, val isLtr: Boolean, val inZone: Boolean)
        listOf(
            Case(10f, true, true), // leading edge: iOS pop / Android back
            Case(39f, true, true),
            Case(40f, true, false),
            Case(200f, true, false),
            Case(377f, true, true), // trailing edge: Android back from the right
            Case(390f, false, true), // RTL: the leading edge is on the right
            Case(10f, false, true),
            Case(30f, false, false),
        ).forEach { case ->
            assertEquals(case.inZone, activitySwipeStartsInEdgeZone(case.downX, width, start, end, case.isLtr), "$case")
        }
        // In the zone, even a clear horizontal drag either way is left to the system / host.
        listOf(30f, -30f).forEach { dx ->
            assertEquals(
                ActivitySwipeDragDecision.Release,
                activitySwipeDragDecision(dx, 2f, 10f, isLtr = true, endEnabled = true, startsInEdgeZone = true),
            )
        }
    }

    @Test
    fun revealFollowsTheFingerWithinTheRow() {
        assertEquals(30f, activitySwipeRevealPx(0f, -30f, 300f, isLtr = true))
        assertEquals(30f, activitySwipeRevealPx(0f, 30f, 300f, isLtr = false))
        // Back past closed stops at 0; past the row width stops at the width.
        assertEquals(0f, activitySwipeRevealPx(20f, 50f, 300f, isLtr = true))
        assertEquals(300f, activitySwipeRevealPx(280f, -50f, 300f, isLtr = true))
    }

    @Test
    fun revealStaysOnTheClaimedSide() {
        val end = ActivitySwipeSide.End
        assertEquals(-30f, activitySwipeRevealPx(0f, 30f, 300f, isLtr = true, side = end))
        assertEquals(-30f, activitySwipeRevealPx(0f, -30f, 300f, isLtr = false, side = end))
        // Dragging back past closed stops at 0 rather than opening Done; past the width stops at -width.
        assertEquals(0f, activitySwipeRevealPx(-20f, -50f, 300f, isLtr = true, side = end))
        assertEquals(-300f, activitySwipeRevealPx(-280f, 50f, 300f, isLtr = true, side = end))
    }

    @Test
    fun releaseThresholdHoldsInBothDirections() {
        val width = 400f // threshold 160
        val fling = 800f
        data class Case(val side: ActivitySwipeSide, val signedReveal: Float, val fingerVelocityX: Float, val isLtr: Boolean, val applies: Boolean)
        listOf(
            // Done (toward the start, positive reveal); LTR finger moving left is negative x.
            Case(ActivitySwipeSide.Done, 159f, 0f, true, false),
            Case(ActivitySwipeSide.Done, 160f, 0f, true, true),
            Case(ActivitySwipeSide.Done, 300f, fling, true, false), // flicked back toward closed
            Case(ActivitySwipeSide.Done, 80f, -fling, true, true), // fast flick further open
            // End (toward the end, negative reveal); LTR finger moving right is positive x.
            Case(ActivitySwipeSide.End, -159f, 0f, true, false),
            Case(ActivitySwipeSide.End, -160f, 0f, true, true),
            Case(ActivitySwipeSide.End, -300f, -fling, true, false),
            Case(ActivitySwipeSide.End, -80f, fling, true, true),
            Case(ActivitySwipeSide.End, -79f, fling, true, false),
            // RTL: the end is leftward, so a further-open flick is negative x.
            Case(ActivitySwipeSide.End, -80f, -fling, false, true),
            Case(ActivitySwipeSide.End, -300f, fling, false, false),
            Case(ActivitySwipeSide.End, 0f, fling, true, false),
        ).forEach { case ->
            assertEquals(
                case.applies,
                activitySwipeReleaseApplies(case.side, case.signedReveal, case.fingerVelocityX, width, fling, case.isLtr),
                "$case",
            )
        }
    }

    @Test
    fun releaseAppliesDonePastTheThresholdOrOnAForwardFlick() {
        val width = 400f // threshold 160
        val fling = 800f
        assertFalse(activitySwipeReleaseAppliesDone(159f, 0f, width, fling))
        assertTrue(activitySwipeReleaseAppliesDone(160f, 0f, width, fling))
        // A flick back toward the end cancels even past the threshold.
        assertFalse(activitySwipeReleaseAppliesDone(300f, -fling, width, fling))
        // A fast flick toward the start applies from half the threshold, not before.
        assertTrue(activitySwipeReleaseAppliesDone(80f, fling, width, fling))
        assertFalse(activitySwipeReleaseAppliesDone(79f, fling, width, fling))
        // Nothing revealed, or no measured width: never.
        assertFalse(activitySwipeReleaseAppliesDone(0f, fling, width, fling))
        assertFalse(activitySwipeReleaseAppliesDone(100f, fling, 0f, fling))
    }

    @Test
    fun swipeHidesTheRowAndUndoBringsItBackWithoutCommitting() {
        val a = row("a")
        val swiped = ActivitySwipeDoneUndoState().swipe(a)
        assertNull(swiped.commit)
        assertEquals("a", swiped.state.hiddenKey)

        val token = swiped.state.pending!!.token
        val undone = swiped.state.undo(token)
        assertNull(undone.hiddenKey)
        // The toast's timer firing after Undo commits nothing.
        assertNull(undone.expire(token).commit)
    }

    @Test
    fun expiryCommitsOnlyTheCurrentPendingRow() {
        val first = ActivitySwipeDoneUndoState().swipe(row("a")).state
        val staleToken = first.pending!!.token
        // A second swipe commits the first row now and starts its own window.
        val second = first.swipe(row("b"))
        assertEquals("a", second.commit?.key)
        assertEquals("b", second.state.hiddenKey)

        val stale = second.state.expire(staleToken)
        assertNull(stale.commit)
        assertSame(second.state, stale.state)
        // An undo for the older toast does not bring back the newer row either.
        assertEquals("b", second.state.undo(staleToken).hiddenKey)

        val expired = second.state.expire(second.state.pending!!.token)
        assertEquals("b", expired.commit?.key)
        assertNull(expired.state.hiddenKey)
    }

    @Test
    fun swipingTheSamePendingRowAgainKeepsOneWindow() {
        val state = ActivitySwipeDoneUndoState().swipe(row("a")).state
        val again = state.swipe(row("a"))
        assertNull(again.commit)
        assertSame(state, again.state)
    }

    @Test
    fun flushCommitsAWaitingDoneAndIsANoOpWhenIdle() {
        val pending = ActivitySwipeDoneUndoState().swipe(row("a")).state
        val flushed = pending.flush()
        assertEquals("a", flushed.commit?.key)
        assertNull(flushed.state.hiddenKey)
        assertNull(flushed.state.flush().commit)
    }

    @Test
    fun commitSkipsARowThatMovedOnDuringTheUndoWindow() {
        val swiped = row("a")
        // Same frontier and time: the current copy (newest content) is sent.
        val sameButRead = swiped.copy(unreadCount = 3)
        assertSame(sameButRead, activitySwipeDoneCommitItem(swiped, sameButRead))
        // New activity arrived: nothing is written, the row comes back.
        assertNull(activitySwipeDoneCommitItem(swiped, swiped.copy(doneFrontierSeq = "42")))
        assertNull(activitySwipeDoneCommitItem(swiped, swiped.copy(latestActivityAt = "2026-10-20T10:00:05Z")))
        // No longer presented: still sent; the store decides from the rows it holds.
        assertSame(swiped, activitySwipeDoneCommitItem(swiped, null))
    }

    private fun thread(key: String, following: Boolean?) =
        InboxItem(
            key = "thread:$key",
            kind = InboxItemKind.Thread,
            channelId = ChannelId(key),
            channelName = "thread",
            threadChannelId = ChannelId(key),
            parentChannelId = ChannelId("mobile"),
            parentChannelName = "mobile",
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("parent-$key"),
            isFollowing = following,
        )

    @Test
    fun endSwipeEligibilityMatchesWebInboxContextMenu() {
        // Joined channels with a reachable Activity mute, as [activityMuteByChannelId] lists them.
        val mutes = mapOf("general" to false, "random" to true)
        data class Case(val name: String, val item: InboxItem, val expected: ActivitySwipeEndAction?)
        listOf(
            Case("followed thread", thread("a", following = true), ActivitySwipeEndAction.UnfollowThread),
            Case("legacy thread (no follow field)", thread("b", following = null), ActivitySwipeEndAction.UnfollowThread),
            Case("unfollowed thread", thread("c", following = false), null),
            Case("joined unmuted channel", row("general").copy(channelId = ChannelId("general")), ActivitySwipeEndAction.MuteActivity),
            Case("already muted channel", row("random").copy(channelId = ChannelId("random")), null),
            Case("unknown or unjoined channel", row("browse").copy(channelId = ChannelId("browse")), null),
            Case("DM", row("dm").copy(kind = InboxItemKind.DirectMessage, channelId = ChannelId("general")), null),
            Case("mention", row("m").copy(kind = InboxItemKind.MentionAction, channelId = ChannelId("general")), null),
            Case("system", row("s").copy(kind = InboxItemKind.System, channelId = ChannelId("general")), null),
        ).forEach { case ->
            val action = case.item.activitySwipeEndAction(mutes)
            assertEquals(case.expected, action, case.name)
            // The swipe is never the only way: the press-and-hold menu has the same item.
            action?.let { assertTrue(it.menuActionId in activityContextMenuActionIds(case.item, mutes), "menu for ${case.name}") }
        }
    }

    @Test
    fun muteEligibilityListsOnlyJoinedChannelsWithAReachableMute() {
        fun channel(id: String, joined: Boolean, supported: Boolean, muted: Boolean = false) =
            HomeChannelRow(
                threadId = ThreadId(id),
                channelId = ChannelId(id),
                name = id,
                joined = joined,
                activityNotificationPreference = ChannelActivityNotificationPreference(activityMuted = muted, activityMuteSupported = supported),
            )
        val home =
            SharedHomeContentState.Content(
                snapshot =
                    HomeSnapshot(
                        server = ServerSummary(id = ServerId("server-1"), name = "Raft"),
                        currentMember = MemberProfile(id = MemberId("me"), displayName = "Me", handle = "me", kind = MemberKind.Human, presence = Presence.Active),
                        channels =
                            listOf(
                                channel("general", joined = true, supported = true),
                                channel("random", joined = true, supported = true, muted = true),
                                channel("browse", joined = false, supported = true),
                                channel("legacy", joined = true, supported = false),
                            ),
                        directMessages = emptyList(),
                        unreadTotal = 0,
                    ),
                session = session(),
            )
        assertEquals(mapOf("general" to false, "random" to true), home.activityMuteByChannelId("server-1"))
        assertEquals(emptyMap(), home.activityMuteByChannelId("server-2"))
    }

    @Test
    fun endSwipeWritesWhatTheMenuDoesAndUndoWritesTheReverse() {
        val followed = thread("a", following = true)
        assertEquals(
            ActivitySwipeEndCommand.Activity(SharedActivityIntent.UnfollowThread(followed)),
            activitySwipeEndApplyCommand(followed, ActivitySwipeEndAction.UnfollowThread),
        )
        assertEquals(
            ActivitySwipeEndCommand.Activity(SharedActivityIntent.FollowThread(followed.copy(isFollowing = false))),
            activitySwipeEndUndoCommand(followed, ActivitySwipeEndAction.UnfollowThread),
        )
        val channel = row("general").copy(channelId = ChannelId("general"))
        assertEquals(ActivitySwipeEndCommand.ChannelMute("general", muted = true), activitySwipeEndApplyCommand(channel, ActivitySwipeEndAction.MuteActivity))
        assertEquals(ActivitySwipeEndCommand.ChannelMute("general", muted = false), activitySwipeEndUndoCommand(channel, ActivitySwipeEndAction.MuteActivity))
    }

    @Test
    fun endUndoReversesOnlyTheCurrentToast() {
        val a = thread("a", following = true)
        val first = ActivitySwipeEndUndoState().swipe(a, ActivitySwipeEndAction.UnfollowThread)
        val staleToken = first.pending!!.token
        val second = first.swipe(row("general"), ActivitySwipeEndAction.MuteActivity)
        // The older toast is gone; its Undo does nothing (its write stands).
        assertNull(second.undo(staleToken).reverse)
        assertSame(second, second.expire(staleToken))

        val undone = second.undo(second.pending!!.token)
        assertEquals(ActivitySwipeEndAction.MuteActivity, undone.reverse?.action)
        assertNull(undone.state.pending)
        // Undo twice (a double tap) reverses once.
        assertNull(undone.state.undo(second.pending!!.token).reverse)

        // The window running out, or the owner leaving, drops the toast without a write.
        assertNull(first.expire(staleToken).pending)
        assertNull(first.dismiss().pending)
        val idle = ActivitySwipeEndUndoState()
        assertSame(idle, idle.dismiss())
    }

    @Test
    fun theToastNamesTheConversation() {
        assertEquals("#mobile", thread("a", following = true).activitySwipeConversationLabel())
        assertEquals("#launch-week", row("x").copy(channelName = "#launch-week").activitySwipeConversationLabel())
        val dmThread = thread("d", following = true).copy(parentChannelName = "Lagoon", parentChannelKind = ConversationKind.DirectMessage)
        assertEquals("@Lagoon", dmThread.activitySwipeConversationLabel())
    }
}
