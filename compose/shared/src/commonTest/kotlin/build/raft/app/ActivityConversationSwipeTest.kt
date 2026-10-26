package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftActivityMarkAction
import build.raft.app.data.isUnfollowedActivityThread
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
    fun dragIsClaimedOnlyWhenHorizontalTowardTheStartOrBackOnAnOpenRow() {
        val slop = 10f
        data class Case(val dx: Float, val dy: Float, val isLtr: Boolean, val open: Boolean, val expected: ActivitySwipeDragDecision)
        val cases =
            listOf(
                Case(-4f, 3f, true, open = false, ActivitySwipeDragDecision.Undecided),
                // Under the claim distance (2 x slop = 20): still undecided, horizontal or not.
                Case(-18f, 9f, true, open = false, ActivitySwipeDragDecision.Undecided),
                Case(-19f, 0f, true, open = false, ActivitySwipeDragDecision.Undecided),
                Case(-30f, 5f, true, open = false, ActivitySwipeDragDecision.Claim),
                // No right swipe: toward the end a closed row leaves the drag to the host (back, pager)...
                Case(30f, 5f, true, open = false, ActivitySwipeDragDecision.Release),
                // ...and an open row takes it, to close.
                Case(30f, 5f, true, open = true, ActivitySwipeDragDecision.Claim),
                Case(-30f, 5f, true, open = true, ActivitySwipeDragDecision.Claim),
                // Vertical-dominant (and a tie) belongs to the list.
                Case(-12f, 40f, true, open = true, ActivitySwipeDragDecision.Release),
                Case(20f, 20f, true, open = true, ActivitySwipeDragDecision.Release),
                // Diagonal and mostly vertical: past the slop vertically without 2x horizontal dominance.
                Case(-40f, 25f, true, open = false, ActivitySwipeDragDecision.Release),
                Case(-22f, 12f, true, open = false, ActivitySwipeDragDecision.Release),
                Case(-14f, 10f, true, open = false, ActivitySwipeDragDecision.Release),
                Case(-24f, 12f, true, open = false, ActivitySwipeDragDecision.Release), // exactly 2x is not more than 2x
                // Clear horizontal intent claims, even with a little vertical drift.
                Case(-30f, 14f, true, open = false, ActivitySwipeDragDecision.Claim),
                Case(-21f, 0f, true, open = false, ActivitySwipeDragDecision.Claim),
                // RTL mirrors: the row's start is on the right, the end (and back) is leftward.
                Case(30f, 5f, false, open = false, ActivitySwipeDragDecision.Claim),
                Case(-30f, 5f, false, open = false, ActivitySwipeDragDecision.Release),
                Case(-30f, 5f, false, open = true, ActivitySwipeDragDecision.Claim),
            )
        cases.forEach { case ->
            assertEquals(
                case.expected,
                activitySwipeDragDecision(case.dx, case.dy, slop, case.isLtr, canOpen = true, canClose = case.open),
                "$case",
            )
        }
        // A row with no buttons would leave the start direction alone too.
        assertEquals(ActivitySwipeDragDecision.Release, activitySwipeDragDecision(-30f, 5f, slop, true, canOpen = false))
    }

    @Test
    fun claimDistanceIsTwiceTheTouchSlopWithinSixteenToTwentyFourDp() {
        // Android: 8dp slop -> 16dp; iOS-sized slop (18dp) is capped at 24dp; a tiny slop keeps the 16dp floor.
        assertEquals(16f, activitySwipeClaimThresholdPx(8f, 16f, 24f))
        assertEquals(24f, activitySwipeClaimThresholdPx(18f, 16f, 24f))
        assertEquals(16f, activitySwipeClaimThresholdPx(4f, 16f, 24f))
        assertEquals(2f, ActivitySwipeDominanceRatio)
    }

    private fun policy(isLtr: Boolean = true, edge: Boolean = false, open: Boolean = false) =
        ActivitySwipeDragPolicy(
            touchSlopPx = 10f,
            claimThresholdPx = 20f,
            isLtr = isLtr,
            canOpen = true,
            canClose = open,
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
        assertEquals(ActivitySwipeDragDecision.Claim, p.onMove(-24f, 3f))
        // Once claimed, it is horizontal for good: no later release.
        assertEquals(ActivitySwipeDragDecision.Claim, p.onMove(-30f, 60f))
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
            val p = policy(edge = true, open = true)
            assertEquals(ActivitySwipeDragDecision.Release, p.onMove(dx, 0f))
            assertEquals(ActivitySwipeDragDecision.Release, p.onMove(dx * 3, 0f))
        }
        assertEquals(ActivitySwipeDragDecision.Claim, policy(isLtr = false).onMove(30f, 2f))
        assertEquals(ActivitySwipeDragDecision.Release, policy(isLtr = false).onMove(-30f, 2f))
        assertEquals(ActivitySwipeDragDecision.Claim, policy(isLtr = false, open = true).onMove(-30f, 2f))
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
                activitySwipeDragDecision(dx, 2f, 10f, isLtr = true, canClose = true, startsInEdgeZone = true),
            )
        }
    }

    @Test
    fun revealFollowsTheFingerWithinTheRow() {
        assertEquals(30f, activitySwipeRevealPx(0f, -30f, 300f, isLtr = true))
        assertEquals(30f, activitySwipeRevealPx(0f, 30f, 300f, isLtr = false))
        // Swiping an open row back closes it, and stops at closed rather than sliding the other way.
        assertEquals(94f, activitySwipeRevealPx(144f, 50f, 300f, isLtr = true))
        assertEquals(0f, activitySwipeRevealPx(20f, 50f, 300f, isLtr = true))
        assertEquals(0f, activitySwipeRevealPx(20f, -50f, 300f, isLtr = false))
        // Past the row width stops at the width.
        assertEquals(300f, activitySwipeRevealPx(280f, -50f, 300f, isLtr = true))
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

/** raft-design #785: the buttons, their distances, the one open row, the full-Done tick, the 8 s undo. */
class ActivitySwipeButtonsSpecTest {
    private val width = 400f // full Done from 240
    private val button = 72f

    @Test
    fun buttonsAreDoneAtTheOuterEdgeThenTheRowsOtherActions() {
        assertEquals(
            listOf(ActivitySwipeButton.Done, ActivitySwipeButton.Unfollow),
            activitySwipeButtons(doneEnabled = true, endActions = listOf(ActivitySwipeEndAction.UnfollowThread)),
        )
        assertEquals(
            listOf(ActivitySwipeButton.Done, ActivitySwipeButton.Mute),
            activitySwipeButtons(doneEnabled = true, endActions = listOf(ActivitySwipeEndAction.MuteActivity)),
        )
        // Both, in the spec's order (outer to inner): Done, Unfollow, Mute.
        assertEquals(
            listOf(ActivitySwipeButton.Done, ActivitySwipeButton.Unfollow, ActivitySwipeButton.Mute),
            activitySwipeButtons(
                doneEnabled = true,
                endActions = listOf(ActivitySwipeEndAction.MuteActivity, ActivitySwipeEndAction.UnfollowThread),
            ),
        )
        // A DM or mention row: Done only. Nothing at all: no swipe.
        assertEquals(listOf(ActivitySwipeButton.Done), activitySwipeButtons(doneEnabled = true, endActions = emptyList()))
        assertEquals(emptyList(), activitySwipeButtons(doneEnabled = false, endActions = emptyList()))
        // Each non-Done button applies its row action; Done goes through the Done path.
        assertNull(ActivitySwipeButton.Done.endAction)
        assertEquals(ActivitySwipeEndAction.UnfollowThread, ActivitySwipeButton.Unfollow.endAction)
        assertEquals(ActivitySwipeEndAction.MuteActivity, ActivitySwipeButton.Mute.endAction)
        assertEquals(72f, ActivitySwipeButtonWidth.value)
    }

    @Test
    fun releaseOpensPastHalfTheButtonsClosesShortOfItAndAppliesDonePastSixtyPercent() {
        val two = 2 * button // 144: open past 72
        data class Case(val reveal: Float, val buttons: Float, val expected: ActivitySwipeRelease)
        listOf(
            Case(0f, two, ActivitySwipeRelease.Close),
            Case(40f, two, ActivitySwipeRelease.Close),
            Case(72f, two, ActivitySwipeRelease.Close), // exactly half is not past it
            Case(73f, two, ActivitySwipeRelease.Open),
            Case(144f, two, ActivitySwipeRelease.Open),
            Case(239f, two, ActivitySwipeRelease.Open),
            Case(240f, two, ActivitySwipeRelease.Done),
            Case(400f, two, ActivitySwipeRelease.Done),
            // One button (Done only): open past 36.
            Case(36f, button, ActivitySwipeRelease.Close),
            Case(37f, button, ActivitySwipeRelease.Open),
            // Three buttons: open past 108.
            Case(108f, 3 * button, ActivitySwipeRelease.Close),
            Case(109f, 3 * button, ActivitySwipeRelease.Open),
        ).forEach { case ->
            assertEquals(case.expected, activitySwipeReleaseTarget(case.reveal, case.buttons, width), "$case")
        }
        // No measured width, or a row without Done: never Done.
        assertEquals(ActivitySwipeRelease.Close, activitySwipeReleaseTarget(300f, two, 0f))
        assertEquals(ActivitySwipeRelease.Open, activitySwipeReleaseTarget(300f, two, width, doneEnabled = false))
    }

    @Test
    fun quickFlickOpensOrClosesByDirectionButNeverAppliesDone() {
        val two = 2 * button
        val flick = 1000f
        fun release(reveal: Float, velocity: Float) =
            activitySwipeReleaseTarget(reveal, two, width, revealVelocityPxPerSec = velocity, flickVelocityPxPerSec = flick)
        // A short, fast flick toward the buttons opens; a fast flick back closes even past halfway.
        assertEquals(ActivitySwipeRelease.Open, release(20f, flick))
        assertEquals(ActivitySwipeRelease.Close, release(120f, -flick))
        // Slower than a flick: distance decides as before.
        assertEquals(ActivitySwipeRelease.Close, release(20f, flick - 1f))
        assertEquals(ActivitySwipeRelease.Open, release(120f, -(flick - 1f)))
        // Past 60% is Done whatever the speed; a flick alone never reaches Done.
        assertEquals(ActivitySwipeRelease.Done, release(240f, -flick))
        assertEquals(ActivitySwipeRelease.Open, release(200f, 10 * flick))
    }

    @Test
    fun fullDoneIsPastSixtyPercentOfTheRowAndGoesAwayWhenTheFingerComesBack() {
        assertEquals(0.6f, ActivitySwipeFullDoneFraction)
        val path = listOf(50f, 150f, 239f, 240f, 300f, 230f, 260f)
        assertEquals(listOf(false, false, false, true, true, false, true), path.map { activitySwipeFullDone(it, width) })
        assertFalse(activitySwipeFullDone(100f, 0f))
        // Full Done and the release agree: what the finger sees is what letting go does.
        path.forEach { reveal ->
            assertEquals(
                activitySwipeFullDone(reveal, width),
                activitySwipeReleaseTarget(reveal, 2 * button, width) == ActivitySwipeRelease.Done,
                "reveal $reveal",
            )
        }
    }

    @Test
    fun tickFiresOncePerOutwardCrossingOnly() {
        val gate = ActivitySwipeFullDoneTickGate()
        val path = listOf(50f, 150f, 250f, 300f, 390f, 200f, 100f, 260f, 280f, 50f)
        val ticks = path.map { gate.update(activitySwipeFullDone(it, width)) }
        // Crosses out at 250 and again at 260; coming back under and staying past it never ticks.
        assertEquals(listOf(false, false, true, false, false, false, false, true, false, false), ticks)
    }

    @Test
    fun buttonsShareTheUncoveredWidthAndDoneGrowsOverThemPastFullDone() {
        // Open at rest: exactly 72 each.
        assertEquals(listOf(72f, 72f), activitySwipeButtonWidthsPx(144f, 2, 0f))
        assertEquals(listOf(72f, 72f, 72f), activitySwipeButtonWidthsPx(216f, 3, 0f))
        // Sliding in: narrower, equal shares; pulled further: wider.
        assertEquals(listOf(30f, 30f), activitySwipeButtonWidthsPx(60f, 2, 0f))
        assertEquals(listOf(100f, 100f), activitySwipeButtonWidthsPx(200f, 2, 0f))
        // Full Done: Done (outer) takes the whole uncovered width over the others, and halfway on the way.
        assertEquals(listOf(260f, 130f), activitySwipeButtonWidthsPx(260f, 2, 1f))
        assertEquals(listOf(195f, 130f), activitySwipeButtonWidthsPx(260f, 2, 0.5f))
        assertEquals(emptyList(), activitySwipeButtonWidthsPx(100f, 0, 0f))
        assertEquals(listOf(0f), activitySwipeButtonWidthsPx(-5f, 1, 0f))
    }

    @Test
    fun oneRowIsOpenAtATime() {
        val none = ActivitySwipeOpenRow()
        val a = none.change("a", open = true)
        assertTrue(a.isOpen("a"))
        // Opening (or starting to swipe) another row closes the first.
        val b = a.change("b", open = true)
        assertTrue(b.isOpen("b"))
        assertFalse(b.isOpen("a"))
        // The first row settling closed after that does not close the second.
        assertSame(b, b.change("a", open = false))
        // The open row swiped back (or a button tapped) closes it.
        assertNull(b.change("b", open = false).key)
        // Re-opening the open row keeps the same state.
        assertSame(b, b.change("b", open = true))
    }

    @Test
    fun aTapElsewhereBackOrTheRowLeavingTheListClosesIt() {
        val open = ActivitySwipeOpenRow("a")
        // A pointer down on the open row itself is the row's (its buttons, its own tap-to-close, a drag).
        assertSame(open, open.onPointerDown(insideOpenRow = true))
        // Anywhere else closes it.
        assertNull(open.onPointerDown(insideOpenRow = false).key)
        // Back, a scroll, the long-press menu.
        assertNull(open.close().key)
        val idle = ActivitySwipeOpenRow()
        assertSame(idle, idle.close())
        assertSame(idle, idle.onPointerDown(insideOpenRow = false))
        // The open row was Done (hidden) or filtered out: nothing stays open, so Back is not held.
        assertNull(open.retain(listOf("b", "c")).key)
        assertSame(open, open.retain(listOf("a", "b")))
    }

    // raft-design #793: the swipe toward the end, Read / Unread at the row's start.

    private fun row(key: String) =
        InboxItem(key = key, kind = InboxItemKind.Channel, channelId = ChannelId("ch-$key"), channelName = "general")

    @Test
    fun startButtonIsReadOnAnUnreadRowUnreadOnAReadOneAndNoneWithoutTheMenuToggle() {
        val unread = row("u").copy(unreadCount = 3)
        val read = row("r")
        assertEquals(ActivitySwipeButton.Read, unread.activitySwipeStartButton())
        assertEquals(ActivitySwipeButton.Unread, read.activitySwipeStartButton())
        // An unfollowed thread's menu has no read toggle, so it has no start button either.
        val unfollowed = row("t").copy(kind = InboxItemKind.Thread, unreadCount = 2, isFollowing = false)
        assertTrue(unfollowed.isUnfollowedActivityThread)
        assertNull(unfollowed.activitySwipeStartButton())
        listOf(unread, read, unfollowed).forEach { item ->
            assertEquals("toggle-read" in activityContextMenuActionIds(item), item.activitySwipeStartButton() != null, item.key)
        }
        // The start buttons apply no end action (they are not Done, Unfollow or Mute).
        assertNull(ActivitySwipeButton.Read.endAction)
        assertNull(ActivitySwipeButton.Unread.endAction)
    }

    @Test
    fun theToggleWritesWhatTheMenuDoesAndSendsTheSameAnalytics() {
        val unread = row("u").copy(unreadCount = 3)
        val read = row("r")
        assertEquals(SharedActivityIntent.MarkRead(unread), activityReadToggleIntent(unread))
        assertEquals(SharedActivityIntent.MarkUnread(read), activityReadToggleIntent(read))
        assertEquals(RaftActivityMarkAction.Read, activityReadToggleMarkAction(unread))
        assertEquals(RaftActivityMarkAction.Unread, activityReadToggleMarkAction(read))
        assertEquals("read", activityReadToggleMarkAction(unread).wire)
        assertEquals("unread", activityReadToggleMarkAction(read).wire)
        // Swiping again switches it back: a marked-read row offers Unread, a marked-unread one Read.
        val markedRead = unread.copy(unreadCount = 0)
        assertEquals(ActivitySwipeButton.Unread, markedRead.activitySwipeStartButton())
        assertEquals(SharedActivityIntent.MarkUnread(markedRead), activityReadToggleIntent(markedRead))
        val markedUnread = read.copy(unreadCount = 1)
        assertEquals(ActivitySwipeButton.Read, markedUnread.activitySwipeStartButton())
        assertEquals(SharedActivityIntent.MarkRead(markedUnread), activityReadToggleIntent(markedUnread))
    }

    @Test
    fun startReleaseOpensPastHalfTheButtonAndTogglesPastSixtyPercent() {
        data class Case(val reveal: Float, val expected: ActivitySwipeStartRelease)
        listOf(
            Case(0f, ActivitySwipeStartRelease.Close),
            Case(20f, ActivitySwipeStartRelease.Close),
            Case(36f, ActivitySwipeStartRelease.Close), // exactly half the 72 button is not past it
            Case(37f, ActivitySwipeStartRelease.Open),
            Case(72f, ActivitySwipeStartRelease.Open),
            Case(239f, ActivitySwipeStartRelease.Open),
            Case(240f, ActivitySwipeStartRelease.Toggle),
            Case(400f, ActivitySwipeStartRelease.Toggle),
        ).forEach { case ->
            assertEquals(case.expected, activitySwipeStartReleaseTarget(case.reveal, button, width), "$case")
        }
        assertEquals(ActivitySwipeStartRelease.Close, activitySwipeStartReleaseTarget(300f, button, 0f))
        assertEquals(ActivitySwipeStartRelease.Close, activitySwipeStartReleaseTarget(300f, 0f, width))
        // A flick opens or closes by direction, and never toggles; past 60% toggles at any speed.
        val flick = 1000f
        fun release(reveal: Float, velocity: Float) =
            activitySwipeStartReleaseTarget(reveal, button, width, revealVelocityPxPerSec = velocity, flickVelocityPxPerSec = flick)
        assertEquals(ActivitySwipeStartRelease.Open, release(10f, flick))
        assertEquals(ActivitySwipeStartRelease.Close, release(60f, -flick))
        assertEquals(ActivitySwipeStartRelease.Open, release(200f, 10 * flick))
        assertEquals(ActivitySwipeStartRelease.Toggle, release(240f, -flick))
    }

    @Test
    fun theTickArmsOnEitherSideOnlyWhereThatSideCanApply() {
        // Past 60% toward the start with Done, or toward the end with a start button.
        assertTrue(activitySwipeArmed(240f, width, doneEnabled = true, hasStart = false))
        assertTrue(activitySwipeArmed(-240f, width, doneEnabled = false, hasStart = true))
        assertFalse(activitySwipeArmed(-239f, width, doneEnabled = true, hasStart = true))
        assertFalse(activitySwipeArmed(-300f, width, doneEnabled = true, hasStart = false))
        assertFalse(activitySwipeArmed(300f, width, doneEnabled = false, hasStart = true))
        assertFalse(activitySwipeArmed(0f, width, doneEnabled = true, hasStart = true))
        // The same gate ticks once per outward crossing on the start side too.
        val gate = ActivitySwipeFullDoneTickGate()
        val ticks = listOf(-50f, -250f, -300f, -100f, -260f).map { gate.update(activitySwipeArmed(it, width, false, true)) }
        assertEquals(listOf(false, true, false, false, true), ticks)
    }

    @Test
    fun revealGoesNegativeTowardTheEndOnlyWithAStartButtonAndMirrorsInRtl() {
        // LTR: rightward (+dx) uncovers the start button as a negative reveal.
        assertEquals(-30f, activitySwipeRevealPx(0f, 30f, 300f, isLtr = true, hasStart = true))
        assertEquals(-30f, activitySwipeRevealPx(0f, -30f, 300f, isLtr = false, hasStart = true))
        // Without one it stops at 0, as before.
        assertEquals(0f, activitySwipeRevealPx(0f, 30f, 300f, isLtr = true))
        // A row with only a start button never slides toward the start.
        assertEquals(0f, activitySwipeRevealPx(0f, -30f, 300f, isLtr = true, hasStart = true, hasEnd = false))
        assertEquals(-42f, activitySwipeRevealPx(-72f, -30f, 300f, isLtr = true, hasStart = true, hasEnd = false))
        // Within the row: never past its width either way.
        assertEquals(-300f, activitySwipeRevealPx(-280f, 50f, 300f, isLtr = true, hasStart = true))
        // One drag can pass through closed from one side to the other.
        assertEquals(-20f, activitySwipeRevealPx(30f, 50f, 300f, isLtr = true, hasStart = true))
    }

    @Test
    fun aRightSwipeIsClaimedWithAStartButtonAndMirrorsInRtl() {
        val slop = 8f
        // Closed row with a start button: toward the end claims (LTR right, RTL left).
        assertEquals(ActivitySwipeDragDecision.Claim, activitySwipeDragDecision(30f, 2f, slop, isLtr = true, canOpen = true, canClose = true))
        assertEquals(ActivitySwipeDragDecision.Claim, activitySwipeDragDecision(-30f, 2f, slop, isLtr = false, canOpen = true, canClose = true))
        // Without one, a closed row still leaves it to the host (back swipe, pager).
        assertEquals(ActivitySwipeDragDecision.Release, activitySwipeDragDecision(30f, 2f, slop, isLtr = true, canOpen = true, canClose = false))
        // From the leading edge zone (the system back gesture) it is never claimed.
        assertEquals(
            ActivitySwipeDragDecision.Release,
            activitySwipeDragDecision(30f, 2f, slop, isLtr = true, canOpen = true, canClose = true, startsInEdgeZone = true),
        )
        // The drag's side: from closed by direction; covering an open side first.
        assertEquals(ActivitySwipeSide.Start, activitySwipeDragSide(0f, towardStart = false))
        assertEquals(ActivitySwipeSide.End, activitySwipeDragSide(0f, towardStart = true))
        assertEquals(ActivitySwipeSide.Start, activitySwipeDragSide(-72f, towardStart = true))
        assertEquals(ActivitySwipeSide.End, activitySwipeDragSide(144f, towardStart = false))
    }

    @Test
    fun oneRowIsOpenAtATimeOnEitherSide() {
        val start = ActivitySwipeOpenRow().change("a", ActivitySwipeSide.Start)
        assertEquals(ActivitySwipeSide.Start, start.openSide("a"))
        assertNull(start.openSide("b"))
        // Another row opening on its end buttons closes it.
        val end = start.change("b", ActivitySwipeSide.End)
        assertNull(end.openSide("a"))
        assertEquals(ActivitySwipeSide.End, end.openSide("b"))
        // The same row moving to its other side.
        assertEquals(ActivitySwipeSide.End, start.change("a", ActivitySwipeSide.End).openSide("a"))
        assertSame(start, start.change("a", ActivitySwipeSide.Start))
        // Settling closed.
        assertNull(start.change("a", null).key)
        assertSame(end, end.change("a", null))
    }

    @Test
    fun rowActionsWriteWhatTheSwipeAndMenuWriteWithTheMenusAnalytics() {
        // raft-design #776 / #777: the menu and the row's screen-reader actions share one write per item,
        // the same as the swipe's buttons.
        val unread = row("u").copy(unreadCount = 3)
        val read = row("r")
        val followed = row("f").copy(kind = InboxItemKind.Thread, isFollowing = true)
        val unfollowed = row("n").copy(kind = InboxItemKind.Thread, isFollowing = false)
        assertEquals(ActivityRowActionWrite(ActivitySwipeEndCommand.Activity(activityReadToggleIntent(unread)), RaftActivityMarkAction.Read), activityRowActionWrite(unread, "toggle-read"))
        assertEquals(ActivityRowActionWrite(ActivitySwipeEndCommand.Activity(SharedActivityIntent.MarkUnread(read)), RaftActivityMarkAction.Unread), activityRowActionWrite(read, "toggle-read"))
        assertEquals(ActivityRowActionWrite(ActivitySwipeEndCommand.Activity(SharedActivityIntent.MarkDone(read)), RaftActivityMarkAction.Done), activityRowActionWrite(read, "done"))
        assertEquals(ActivityRowActionWrite(activitySwipeEndApplyCommand(followed, ActivitySwipeEndAction.UnfollowThread)), activityRowActionWrite(followed, "unfollow-thread"))
        assertEquals(ActivityRowActionWrite(ActivitySwipeEndCommand.Activity(SharedActivityIntent.FollowThread(unfollowed))), activityRowActionWrite(unfollowed, "follow-thread"))
        assertEquals(ActivityRowActionWrite(activitySwipeEndApplyCommand(read, ActivitySwipeEndAction.MuteActivity)), activityRowActionWrite(read, "mute-activity"))
        assertEquals(ActivityRowActionWrite(ActivitySwipeEndCommand.ChannelMute(read.channelId.value, muted = false)), activityRowActionWrite(read, "unmute-activity"))
        // Every item any row's menu lists has a write.
        val mutes = mapOf(read.channelId.value to false, unread.channelId.value to true)
        listOf(unread, read, followed, unfollowed).forEach { item ->
            activityContextMenuActionIds(item, mutes).forEach { id -> activityRowActionWrite(item, id) }
        }
    }

    @Test
    fun undoWindowIsEightSeconds() {
        assertEquals(8_000L, ActivitySwipeDoneUndoWindowMillis)
    }
}
