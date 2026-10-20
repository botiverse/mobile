package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Rule L (raft-design #562) swipe-to-Done on the Activity conversation list: drag policy and undo. */
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
    fun theRowsPersistentDoneIsAlsoInTheLongPressMenuForEveryRow() {
        // Rule L: the swipe is never the only way; Done is on the row and in the menu for every kind.
        val rows =
            InboxItemKind.entries.flatMap { kind ->
                listOf(null, true, false).map { following -> row("k-$kind-$following").copy(kind = kind, isFollowing = following) }
            }
        rows.forEach { item ->
            assertTrue(item.activityShowsDoneAction(), "row Done for ${item.key}")
            assertTrue("done" in activityContextMenuActionIds(item), "menu Done for ${item.key}")
        }
    }

    @Test
    fun dragIsClaimedOnlyWhenHorizontalAndTowardTheRowStart() {
        val slop = 10f
        // (dx, dy, isLtr) to the expected decision.
        val cases =
            listOf(
                Triple(-4f, 3f, true) to ActivitySwipeDragDecision.Undecided,
                Triple(-30f, 5f, true) to ActivitySwipeDragDecision.Claim,
                // Toward the end is the back-swipe direction: left to the host.
                Triple(30f, 5f, true) to ActivitySwipeDragDecision.Release,
                // Vertical-dominant (and a tie) belongs to the list.
                Triple(-12f, 40f, true) to ActivitySwipeDragDecision.Release,
                Triple(-20f, 20f, true) to ActivitySwipeDragDecision.Release,
                // RTL mirrors: the row's start is on the right, back is leftward.
                Triple(30f, 5f, false) to ActivitySwipeDragDecision.Claim,
                Triple(-30f, 5f, false) to ActivitySwipeDragDecision.Release,
            )
        cases.forEach { (input, expected) ->
            val (dx, dy, isLtr) = input
            assertEquals(expected, activitySwipeDragDecision(dx, dy, slop, isLtr), "dx=$dx dy=$dy ltr=$isLtr")
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
}
