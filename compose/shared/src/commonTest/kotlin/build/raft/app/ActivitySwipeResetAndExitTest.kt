package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * #proj-mobile:#68 items 3 & 4: serialized Activity swipe reset state machine (no same-frame stuck
 * offset or snapTo vs animateTo MutatorMutex race, try/finally cleanup, tab-switch dismissal) and
 * coordinated Done full-width expansion + height-collapse exit without snap-back flash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivitySwipeResetAndExitTest {

    /** Controller jobs run in `backgroundScope`, which `advanceUntilIdle()` skips in coroutines-test 1.11. */
    private fun TestScope.settle(millis: Long = 10_000L) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private enum class OpKind {
        SnapReveal,
        AnimateReveal,
        SnapFullDone,
        AnimateFullDone,
        SnapCollapse,
        AnimateCollapse,
    }

    private data class Op(val kind: OpKind, val target: Float, val animKind: ActivitySwipeAnimationKind? = null)

    private class FakeRevealDriver(
        initialRevealPx: Float = 0f,
        initialFullDone: Float = 0f,
        initialCollapse: Float = 1f,
    ) : ActivitySwipeRevealDriver {
        override var revealPx: Float = initialRevealPx
        override var fullDoneProgress: Float = initialFullDone
        override var collapseProgress: Float = initialCollapse

        val ops = mutableListOf<Op>()
        var snapRevealGate: CompletableDeferred<Unit>? = null
        var animateRevealGate: CompletableDeferred<Unit>? = null
        var animateCollapseStarted: CompletableDeferred<Unit>? = null
        var fullDoneAtCollapseStart: Float? = null
        var revealAtCollapseStart: Float? = null
        var settleDurationMillis: Long = 120L
        var activeRevealDrivers: Int = 0
        var maxConcurrentRevealDrivers: Int = 0

        override suspend fun snapRevealTo(targetPx: Float) {
            activeRevealDrivers += 1
            if (activeRevealDrivers > maxConcurrentRevealDrivers) {
                maxConcurrentRevealDrivers = activeRevealDrivers
            }
            try {
                ops += Op(OpKind.SnapReveal, targetPx)
                val gate = snapRevealGate
                if (gate != null) {
                    // Hold the in-flight snap across cancellation to simulate a snapTo mid-critical-section.
                    withContext(NonCancellable) { gate.await() }
                }
                revealPx = targetPx
            } finally {
                activeRevealDrivers -= 1
            }
        }

        override suspend fun animateRevealTo(targetPx: Float, kind: ActivitySwipeAnimationKind) {
            activeRevealDrivers += 1
            if (activeRevealDrivers > maxConcurrentRevealDrivers) {
                maxConcurrentRevealDrivers = activeRevealDrivers
            }
            try {
                ops += Op(OpKind.AnimateReveal, targetPx, kind)
                val gate = animateRevealGate
                if (gate != null) {
                    // Hold the in-flight animate across cancellation to simulate an Animatable mid-mutex.
                    withContext(NonCancellable) { gate.await() }
                }
                val duration =
                    when (kind) {
                        ActivitySwipeAnimationKind.DoneSlideOut -> ActivitySwipeDoneSlideOutMillis.toLong()
                        else -> settleDurationMillis
                    }
                val start = revealPx
                if (duration > 0L) {
                    delay(duration / 2)
                    revealPx = (start + targetPx) / 2f
                    delay(duration - duration / 2)
                }
                revealPx = targetPx
            } finally {
                activeRevealDrivers -= 1
            }
        }

        override suspend fun snapFullDoneTo(target: Float) {
            ops += Op(OpKind.SnapFullDone, target)
            fullDoneProgress = target
        }

        override suspend fun animateFullDoneTo(target: Float) {
            ops += Op(OpKind.AnimateFullDone, target)
            val start = fullDoneProgress
            val duration = ActivitySwipeFullDoneExpandMillis.toLong()
            delay(duration / 2)
            fullDoneProgress = (start + target) / 2f
            delay(duration - duration / 2)
            fullDoneProgress = target
        }

        override suspend fun snapCollapseTo(target: Float) {
            ops += Op(OpKind.SnapCollapse, target)
            collapseProgress = target
        }

        override suspend fun animateCollapseTo(target: Float, kind: ActivitySwipeAnimationKind) {
            ops += Op(OpKind.AnimateCollapse, target, kind)
            if (kind == ActivitySwipeAnimationKind.DoneCollapse) {
                fullDoneAtCollapseStart = fullDoneProgress
                revealAtCollapseStart = revealPx
            }
            animateCollapseStarted?.complete(Unit)
            val start = collapseProgress
            val duration = ActivitySwipeDoneCollapseMillis.toLong()
            delay(duration / 2)
            collapseProgress = (start + target) / 2f
            delay(duration - duration / 2)
            collapseProgress = target
        }
    }

    private fun sampleItem(key: String, at: String = "2026-11-05T10:00:00Z", kind: InboxItemKind = InboxItemKind.Channel) =
        InboxItem(
            key = key,
            kind = kind,
            channelId = ChannelId("ch-$key"),
            channelName = key,
            latestActivityAt = at,
            lastMessageAt = at,
        )

    @Test
    fun sameFrameDragAndEndDragBeforeSnapDispatchResetsCleanlyToZero() = runTest {
        val driver = FakeRevealDriver()
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        assertTrue(controller.beginDrag())
        assertEquals(ActivitySwipePhase.Dragging, controller.phase)
        controller.dragTo(48f)
        controller.endDrag(0f)

        assertEquals(ActivitySwipePhase.Idle, controller.phase)
        assertEquals(1, controller.settleVersion)
        settle()

        assertEquals(0f, controller.revealPx)
        assertTrue(
            driver.ops.none { it.kind == OpKind.SnapReveal },
            "cancelled same-frame drag snap must be fenced before touching driver, ops=${driver.ops}",
        )
    }

    @Test
    fun stuckNonZeroOffsetWithClosedOpenRowRecoversOnSyncRest() = runTest {
        val driver = FakeRevealDriver(initialRevealPx = 54f, initialCollapse = 0.5f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        controller.syncRest(0f)
        settle()

        assertEquals(0f, controller.revealPx)
        assertEquals(1f, controller.collapseProgress)
        assertEquals(ActivitySwipePhase.Idle, controller.phase)
    }

    @Test
    fun tabSwitchDeactivationClosesHostOpenRowAndAnimatesRowBackToZero() = runTest {
        val host = ActivitySwipeListHostState()
        host.openRow = host.openRow.change("row-a", ActivitySwipeSide.End)
        assertEquals(ActivitySwipeSide.End, host.openRow.openSide("row-a"))

        val driver = FakeRevealDriver(initialRevealPx = 144f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        // Switching to another bottom tab (active = false) closes the panel's open row.
        host.close()
        assertNull(host.openRow.key)
        assertNull(host.openRow.openSide("row-a"))

        controller.syncRest(0f)
        settle()
        assertEquals(0f, controller.revealPx)

        // An already-committed Done animation must NOT be aborted by a concurrent syncRest(0f).
        val doneDriver = FakeRevealDriver(initialRevealPx = 144f)
        val doneController = ActivitySwipeRowController(driver = doneDriver, scope = backgroundScope)
        var doneCalls = 0
        doneController.completeDone(widthPx = 390f, onBeforeDone = {}, onDone = { doneCalls += 1 })
        advanceTimeBy(100L)
        runCurrent()
        doneController.syncRest(0f)
        advanceTimeBy(ActivitySwipeDoneSlideOutMillis.toLong() + ActivitySwipeDoneCollapseMillis.toLong())
        runCurrent()
        assertEquals(1, doneCalls, "tab deactivation syncRest(0f) must not cancel an already-committed Done")
    }

    @Test
    fun completeDoneRestoresClosedCleanlyIfRowRemainsInCompositionPastHoldWindow() = runTest {
        val driver = FakeRevealDriver(initialRevealPx = 144f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        controller.completeDone(widthPx = 390f, onBeforeDone = {}, onDone = {})
        advanceTimeBy(ActivitySwipeDoneSlideOutMillis.toLong() + ActivitySwipeDoneCollapseMillis.toLong())
        runCurrent()
        assertEquals(ActivitySwipePhase.Exited, controller.phase)

        // Still held at 0 height before the grace window expires.
        advanceTimeBy(ActivitySwipeDoneExitHoldMillis - 10L)
        runCurrent()
        assertEquals(ActivitySwipePhase.Exited, controller.phase)
        assertEquals(0f, controller.collapseProgress)

        // Past the hold window, a row that was not removed restores cleanly to closed rest.
        settle()
        assertEquals(ActivitySwipePhase.Idle, controller.phase)
        assertEquals(0f, controller.revealPx)
        assertEquals(0f, controller.fullDoneProgress)
        assertEquals(1f, controller.collapseProgress)
    }

    @Test
    fun beginDragDuringCompletingToggleDoesNotDropToggleRead() = runTest {
        val driver = FakeRevealDriver(initialRevealPx = -260f)
        val snapGate = CompletableDeferred<Unit>()
        driver.snapRevealGate = snapGate
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        // Hold a drag snap in flight when completeToggle commits.
        assertTrue(controller.beginDrag())
        controller.dragTo(-260f)
        runCurrent()

        var toggles = 0
        controller.completeToggle(
            onBeforeToggle = {},
            onToggleRead = { toggles += 1 },
        )
        assertEquals(1, toggles, "onToggleRead must commit synchronously so spring-back cancellation cannot drop it")
        assertEquals(ActivitySwipePhase.CompletingToggle, controller.phase)
        assertFalse(controller.beginDrag(), "beginDrag must be rejected while CompletingToggle is active")

        driver.snapRevealGate = null
        snapGate.complete(Unit)
        advanceTimeBy(40L)
        runCurrent()
        assertEquals(ActivitySwipePhase.CompletingToggle, controller.phase)
        assertFalse(controller.beginDrag(), "beginDrag must stay rejected while toggle springs back")

        settle()
        assertEquals(ActivitySwipePhase.Idle, controller.phase)
        assertEquals(0f, controller.revealPx)
        assertEquals(1, toggles)
        assertTrue(controller.beginDrag(), "row becomes draggable again once toggle finishes")

        // Even cancelling mid-spring-back never drops a committed toggle write.
        val cancelDriver = FakeRevealDriver(initialRevealPx = -260f)
        val cancelController = ActivitySwipeRowController(driver = cancelDriver, scope = backgroundScope)
        var cancelledToggles = 0
        cancelController.completeToggle(onBeforeToggle = {}, onToggleRead = { cancelledToggles += 1 })
        advanceTimeBy(30L)
        runCurrent()
        cancelController.cancel()
        settle()
        assertEquals(1, cancelledToggles, "cancelling spring-back must never drop onToggleRead")
        assertEquals(ActivitySwipePhase.Idle, cancelController.phase)
    }

    @Test
    fun reentrantDragAndButtonActionsAreIgnoredWhileExiting() = runTest {
        val driver = FakeRevealDriver(initialRevealPx = 144f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        var doneCalls = 0
        var exitCalls = 0
        var toggleCalls = 0
        controller.completeDone(widthPx = 390f, onBeforeDone = {}, onDone = { doneCalls += 1 })
        assertFalse(controller.beginDrag(), "cannot drag a row while Done is completing")
        controller.completeDone(widthPx = 390f, onBeforeDone = {}, onDone = { doneCalls += 1 })
        controller.completeCollapseExit(onBeforeExit = {}, onExit = { exitCalls += 1 })
        controller.completeToggle(onBeforeToggle = {}, onToggleRead = { toggleCalls += 1 })

        advanceTimeBy(ActivitySwipeDoneSlideOutMillis.toLong() + ActivitySwipeDoneCollapseMillis.toLong())
        runCurrent()

        assertEquals(ActivitySwipePhase.Exited, controller.phase)
        assertFalse(controller.beginDrag(), "cannot drag a row while Exited")
        assertEquals(1, doneCalls)
        assertEquals(0, exitCalls)
        assertEquals(0, toggleCalls)
    }

    @Test
    fun inFlightSwipeDoneAndUnfollowFlushOnScrollOffDisposalAndListDropWithoutDoubleCommit() {
        val swipedDone = sampleItem("row-done", at = "2026-11-05T11:50:00Z")
        val swipedUnfollow = sampleItem("thread-unfollow", at = "2026-11-05T11:40:00Z", kind = InboxItemKind.Thread)
        val collapseDone = sampleItem("row-menu", at = "2026-11-05T11:30:00Z")

        val state =
            ActivityRowExitState()
                .start(
                    item = swipedDone,
                    actionId = "done",
                    mode = ActivityRowExitMode.Swipe,
                    ownerIdentity = "owner-a",
                    listOwnerUserId = "user-1",
                    listOwnerServerId = "server-1",
                )
                .start(
                    item = swipedUnfollow,
                    actionId = "unfollow-thread",
                    mode = ActivityRowExitMode.Swipe,
                    ownerIdentity = "owner-a",
                    listOwnerUserId = "user-1",
                    listOwnerServerId = "server-1",
                )
                .start(
                    item = collapseDone,
                    actionId = "done",
                    mode = ActivityRowExitMode.Collapse,
                    ownerIdentity = "owner-a",
                    listOwnerUserId = "user-1",
                    listOwnerServerId = "server-1",
                )

        // Swipe exits are tracked in the unified map without triggering the non-swipe exiting collapse flag.
        assertTrue(state.isExiting(swipedDone.key))
        assertFalse(state.isCollapseExiting(swipedDone.key, "owner-a"))
        assertTrue(state.isExiting(swipedUnfollow.key))
        assertFalse(state.isCollapseExiting(swipedUnfollow.key, "owner-a"))
        assertTrue(state.isCollapseExiting(collapseDone.key, "owner-a"))
        assertFalse(state.isCollapseExiting(collapseDone.key, "owner-b"))

        // 1. Row scrolled off and disposed mid-swipe-Done: complete(key) commits once; later flush skips it.
        val committed = mutableListOf<ActivityPendingRowExit>()
        val (afterRowDispose, rowDisposedExit) = state.complete(swipedDone.key)
        rowDisposedExit?.let(committed::add)
        assertEquals(1, committed.size)
        assertEquals(swipedDone, committed[0].item)
        assertEquals("done", committed[0].actionId)
        assertNull(afterRowDispose.complete(swipedDone.key).second, "second complete must be a no-op")

        // 2. lazyItemKeys drops swipedUnfollow mid-collapse: retainOrCommitDropped commits it with captured owner.
        val (afterDrop, dropped) = afterRowDispose.retainOrCommitDropped(listOf(collapseDone.key))
        committed += dropped
        assertEquals(2, committed.size)
        assertEquals(swipedUnfollow, committed[1].item)
        assertEquals("unfollow-thread", committed[1].actionId)
        assertEquals("user-1", committed[1].listOwnerUserId)
        assertEquals("server-1", committed[1].listOwnerServerId)
        assertNull(afterDrop.complete(swipedUnfollow.key).second, "dropped exit must not double-commit on dispose")

        // 3. Owner disposal flushes remaining exit; any late row disposal afterward is a no-op.
        val (afterOwnerFlush, ownerFlushed) = afterDrop.flushAll()
        committed += ownerFlushed
        assertEquals(3, committed.size)
        assertEquals(collapseDone, committed[2].item)
        assertNull(afterOwnerFlush.complete(collapseDone.key).second, "late row dispose after owner flush must not double-commit")
        assertTrue(afterOwnerFlush.flushAll().second.isEmpty())
    }

    @Test
    fun activitySwipeCollapsedHeightPxHandlesNonFiniteAndOutOfRangeValuesSafely() {
        assertEquals(80, activitySwipeCollapsedHeightPx(80, Float.NaN))
        assertEquals(80, activitySwipeCollapsedHeightPx(80, Float.POSITIVE_INFINITY))
        assertEquals(80, activitySwipeCollapsedHeightPx(80, Float.NEGATIVE_INFINITY))
        assertEquals(0, activitySwipeCollapsedHeightPx(0, Float.NaN))
        assertEquals(0, activitySwipeCollapsedHeightPx(-10, 1f))
        assertEquals(0, activitySwipeCollapsedHeightPx(80, -0.5f))
        assertEquals(0, activitySwipeCollapsedHeightPx(80, 0f))
        assertEquals(40, activitySwipeCollapsedHeightPx(80, 0.5f))
        assertEquals(80, activitySwipeCollapsedHeightPx(80, 1f))
        assertEquals(80, activitySwipeCollapsedHeightPx(80, 1.5f))
    }

    @Test
    fun activitySwipeListHostStateUpdateCloseAndBeforeFilterChangeFlushWorkTogether() {
        val host = ActivitySwipeListHostState()
        host.updateOpenRow(host.openRow.change("row-a", ActivitySwipeSide.End))
        assertEquals("row-a", host.openRow.key)
        assertEquals(ActivitySwipeSide.End, host.openRow.openSide("row-a"))

        var flushed = 0
        host.beforeFilterChangeFlush = {
            flushed += 1
            host.close()
        }
        host.flushBeforeFilterChange()
        assertEquals(1, flushed)
        assertNull(host.openRow.key)
        assertNull(host.openRow.openSide("row-a"))

        host.updateOpenRow(host.openRow.change("row-b", ActivitySwipeSide.Start))
        assertEquals(ActivitySwipeSide.Start, host.openRow.openSide("row-b"))
        host.updateOpenRow(host.openRow.retain(emptyList()))
        assertNull(host.openRow.key)
    }
}
