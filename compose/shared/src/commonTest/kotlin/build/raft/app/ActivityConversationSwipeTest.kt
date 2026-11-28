package build.raft.app

import build.raft.app.data.ActivityDoneCommitTrigger
import build.raft.app.data.ActivityReappearCause
import build.raft.app.data.ActivitySeqChange
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftActivityMarkAction
import build.raft.app.data.isUnfollowedActivityThread
import build.raft.app.data.SharedActivityIntent
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.thread.key
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
        assertEquals("a", second.commit?.item?.key)
        assertEquals("b", second.state.hiddenKey)

        val stale = second.state.expire(staleToken)
        assertNull(stale.commit)
        assertSame(second.state, stale.state)
        // An undo for the older toast does not bring back the newer row either.
        assertEquals("b", second.state.undo(staleToken).hiddenKey)

        val expired = second.state.expire(second.state.pending!!.token)
        assertEquals("b", expired.commit?.item?.key)
        assertNull(expired.state.hiddenKey)
    }

    @Test
    fun flushCommitsAWaitingDoneAndIsANoOpWhenIdle() {
        val pending = ActivitySwipeDoneUndoState().swipe(row("a")).state
        val flushed = pending.flush()
        assertEquals("a", flushed.commit?.item?.key)
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

    @Test
    fun evaluateSwipeDoneHandlesFrontierCatchUpCallOffsAndCommitMetadata() {
        val swiped =
            row("a", seq = "10").copy(
                unreadCount = 2,
                latestActivitySeq = "12",
                readStateLatestActivitySeq = "10",
                doneFrontierSeq = "10",
            )
        // Frontier catches up to already-shown seq 12 on a non-joint row -> Commit with frontierCaughtUp=true.
        val caughtUp = swiped.copy(unreadCount = 0, doneFrontierSeq = "12", readStateLatestActivitySeq = "12")
        val catchUpDecision = evaluateActivitySwipeDone(swiped, caughtUp) as ActivitySwipeDoneDecision.Commit
        assertSame(caughtUp, catchUpDecision.item)
        assertTrue(catchUpDecision.frontierCaughtUp)

        val pending = ActivitySwipeDoneUndoState().swipe(swiped, clickedAtMillis = 1_000L).state.pending!!
        assertTrue(pending.wasUnreadAtClick)
        val intent =
            activitySwipeDeferredCommitIntent(
                pending = pending,
                current = caughtUp,
                trigger = ActivityDoneCommitTrigger.ToastClosed,
                nowMillis = 9_000L,
            ) as SharedActivityIntent.MarkDone
        assertEquals(ActivityDoneCommitTrigger.ToastClosed, intent.commitTrigger)
        assertEquals(8_000L, intent.waitMs)
        assertEquals(true, intent.wasUnreadAtClick)
        assertEquals(true, intent.frontierCaughtUp)

        // Frontier past shown seq 12 -> CallOff(CalloffFrontierMoved, Frontier).
        val frontierPast = swiped.copy(doneFrontierSeq = "13", readStateLatestActivitySeq = "13")
        assertEquals(
            ActivitySwipeDoneDecision.CallOff(ActivityReappearCause.CalloffFrontierMoved, ActivitySeqChange.Frontier),
            evaluateActivitySwipeDone(swiped, frontierPast),
        )

        // Joint row never catches up frontier -> CallOff(CalloffFrontierMoved, Frontier).
        val jointSwiped = swiped.copy(channelKind = ConversationKind.Joint, channelType = "joint")
        val jointCaughtUp = jointSwiped.copy(doneFrontierSeq = "12", readStateLatestActivitySeq = "12")
        assertEquals(
            ActivitySwipeDoneDecision.CallOff(ActivityReappearCause.CalloffFrontierMoved, ActivitySeqChange.Frontier),
            evaluateActivitySwipeDone(jointSwiped, jointCaughtUp),
        )

        // Newer activity during wait: latest only vs both.
        assertEquals(
            ActivitySwipeDoneDecision.CallOff(ActivityReappearCause.CalloffNewerActivity, ActivitySeqChange.Latest),
            evaluateActivitySwipeDone(swiped, swiped.copy(latestActivitySeq = "13")),
        )
        assertEquals(
            ActivitySwipeDoneDecision.CallOff(ActivityReappearCause.CalloffNewerActivity, ActivitySeqChange.Both),
            evaluateActivitySwipeDone(swiped, swiped.copy(latestActivitySeq = "13", doneFrontierSeq = "13")),
        )
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
        // Joined channels and App-bot DMs with a reachable Activity mute, as [activityMuteByChannelId] lists them.
        val mutes =
            mapOf(
                "general" to false,
                "random" to true,
                "dm-app-bot" to false,
                "dm-app-bot-muted" to true,
            )
        data class Case(val name: String, val item: InboxItem, val expected: ActivitySwipeEndAction?)
        listOf(
            Case("followed thread", thread("a", following = true), ActivitySwipeEndAction.UnfollowThread),
            Case("legacy thread (no follow field)", thread("b", following = null), ActivitySwipeEndAction.UnfollowThread),
            Case("unfollowed thread", thread("c", following = false), null),
            Case("joined unmuted channel", row("general").copy(channelId = ChannelId("general")), ActivitySwipeEndAction.MuteActivity),
            Case("already muted channel", row("random").copy(channelId = ChannelId("random")), null),
            Case("unknown or unjoined channel", row("browse").copy(channelId = ChannelId("browse")), null),
            Case("ordinary DM", row("dm").copy(kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm-human")), null),
            Case("unmuted App-bot DM", row("dm-app-bot").copy(kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm-app-bot")), ActivitySwipeEndAction.MuteActivity),
            Case("already muted App-bot DM", row("dm-app-bot-muted").copy(kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm-app-bot-muted")), null),
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
        val appBotDm = row("dm-app-bot").copy(kind = InboxItemKind.DirectMessage, channelId = ChannelId("dm-app-bot"), channelName = "github-bot")
        assertEquals(ActivitySwipeEndCommand.ChannelMute("dm-app-bot", muted = true), activitySwipeEndApplyCommand(appBotDm, ActivitySwipeEndAction.MuteActivity))
        assertEquals(ActivitySwipeEndCommand.ChannelMute("dm-app-bot", muted = false), activitySwipeEndUndoCommand(appBotDm, ActivitySwipeEndAction.MuteActivity))
    }

    @Test
    fun theToastNamesTheConversation() {
        assertEquals("#mobile", thread("a", following = true).activitySwipeConversationLabel())
        assertEquals("#launch-week", row("x").copy(channelName = "#launch-week").activitySwipeConversationLabel())
        val dmThread = thread("d", following = true).copy(parentChannelName = "Lagoon", parentChannelKind = ConversationKind.DirectMessage)
        assertEquals("@Lagoon", dmThread.activitySwipeConversationLabel())
        val dmRow = row("dm-bot").copy(kind = InboxItemKind.DirectMessage, channelKind = null, channelName = "github-bot")
        assertEquals("@github-bot", dmRow.activitySwipeConversationLabel())
        assertEquals("@GitHub Bot", dmRow.activitySwipeConversationLabel(resolvedDmPeerName = "GitHub Bot"))
        assertEquals("@Lagoon Bot", dmThread.activitySwipeConversationLabel(resolvedDmPeerName = "Lagoon Bot"))
    }
}

/** raft-design #785: the buttons, their distances, the one open row, the full-Done tick, the 8 s undo. */
class ActivitySwipeButtonsSpecTest {
    private val width = 400f // full Done from 240
    private val button = 72f

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
}
