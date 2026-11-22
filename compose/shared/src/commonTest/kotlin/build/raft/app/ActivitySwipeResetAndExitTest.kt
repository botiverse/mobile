package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
        var animateCollapseStarted: CompletableDeferred<Unit>? = null
        var fullDoneAtCollapseStart: Float? = null
        var revealAtCollapseStart: Float? = null
        var settleDurationMillis: Long = 120L

        override suspend fun snapRevealTo(targetPx: Float) {
            ops += Op(OpKind.SnapReveal, targetPx)
            val gate = snapRevealGate
            if (gate != null) {
                // Hold the in-flight snap across cancellation to simulate a snapTo mid-critical-section.
                withContext(NonCancellable) { gate.await() }
            }
            revealPx = targetPx
        }

        override suspend fun animateRevealTo(targetPx: Float, kind: ActivitySwipeAnimationKind) {
            ops += Op(OpKind.AnimateReveal, targetPx, kind)
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
        advanceUntilIdle()

        assertEquals(0f, controller.revealPx)
        assertTrue(
            driver.ops.none { it.kind == OpKind.SnapReveal },
            "cancelled same-frame drag snap must be fenced before touching driver, ops=${driver.ops}",
        )
    }

    @Test
    fun sameFrameDragThatSnappedBeforeEndDragStillAnimatesBackToZero() = runTest {
        val driver = FakeRevealDriver()
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        assertTrue(controller.beginDrag())
        controller.dragTo(52f)
        runCurrent()
        assertEquals(52f, controller.revealPx)

        // End drag within the same composition frame (openSide stayed null across frames).
        controller.endDrag(0f)
        assertEquals(ActivitySwipePhase.Idle, controller.phase)
        advanceUntilIdle()

        assertEquals(0f, controller.revealPx)
        assertTrue(
            driver.ops.any { it.kind == OpKind.AnimateReveal && it.target == 0f },
            "endDrag must directly schedule close animation instead of relying on LaunchedEffect(busy), ops=${driver.ops}",
        )
    }

    @Test
    fun inFlightDragSnapCannotInterruptOrOverwriteSettleCloseAnimation() = runTest {
        val driver = FakeRevealDriver()
        val snapGate = CompletableDeferred<Unit>()
        driver.snapRevealGate = snapGate
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        assertTrue(controller.beginDrag())
        controller.dragTo(64f)
        runCurrent()
        assertEquals(1, driver.ops.count { it.kind == OpKind.SnapReveal })

        // Rapid extra MOVE events coalesce into pendingDragTarget while the first snap is in flight.
        controller.dragTo(76f)
        controller.dragTo(88f)
        controller.dragTo(96f)
        runCurrent()
        assertEquals(1, driver.ops.count { it.kind == OpKind.SnapReveal }, "rapid MOVEs must coalesce into one tracked job")

        // Pointer cancel / lift arrives while the first snap is still suspended.
        controller.endDrag(0f)
        runCurrent()
        assertTrue(
            driver.ops.none { it.kind == OpKind.AnimateReveal },
            "settle animateTo must wait for in-flight snap job to finish before driving reveal",
        )

        // Release the in-flight snap: later coalesced drag targets must NOT run, and settle animates to 0.
        snapGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0f, controller.revealPx)
        assertEquals(ActivitySwipePhase.Idle, controller.phase)
        val snapOps = driver.ops.filter { it.kind == OpKind.SnapReveal }
        assertEquals(1, snapOps.size, "stale coalesced drag targets must not run after endDrag, ops=${driver.ops}")
        assertEquals(OpKind.AnimateReveal, driver.ops.last().kind)
        assertEquals(0f, driver.ops.last().target)
    }

    @Test
    fun stuckNonZeroOffsetWithClosedOpenRowRecoversOnSyncRest() = runTest {
        val driver = FakeRevealDriver(initialRevealPx = 54f, initialCollapse = 0.5f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        controller.syncRest(0f)
        advanceUntilIdle()

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
        advanceUntilIdle()
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
    fun completeDoneOnTwoButtonRowImmediatelySignalsFullDoneAndFillsBeforeCollapseAndRemoval() = runTest {
        val widthPx = 390f
        val driver = FakeRevealDriver(initialRevealPx = 144f, initialFullDone = 0f, initialCollapse = 1f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        assertFalse(
            activitySwipeShowsFullDone(revealPx = 144f, widthPx = widthPx, doneEnabled = true, completingDone = false),
            "at resting 2-button open width, Done is not full-width yet",
        )

        var beforeDoneCalls = 0
        var doneCalls = 0
        controller.completeDone(
            widthPx = widthPx,
            onBeforeDone = { beforeDoneCalls += 1 },
            onDone = { doneCalls += 1 },
        )

        // Synchronously on tap: completion phase is active so Done immediately sweeps over Unfollow/Mute.
        assertEquals(1, beforeDoneCalls)
        assertEquals(ActivitySwipePhase.CompletingDone, controller.phase)
        assertTrue(controller.completingDone)
        assertTrue(
            activitySwipeShowsFullDone(revealPx = 144f, widthPx = widthPx, doneEnabled = true, completingDone = controller.completingDone),
            "tapping Done must signal fullDone immediately instead of staying 50/50 until 60%",
        )

        // At 140ms: fullDoneProgress is already 1f (Done covers 100% of uncovered width) while slide finishes.
        advanceTimeBy(ActivitySwipeFullDoneExpandMillis.toLong())
        runCurrent()
        assertEquals(1f, controller.fullDoneProgress)
        assertEquals(1f, controller.collapseProgress)
        assertEquals(0, doneCalls)

        // At 200ms: slide-out completes at full row width; height collapse begins next.
        advanceTimeBy((ActivitySwipeDoneSlideOutMillis - ActivitySwipeFullDoneExpandMillis).toLong())
        runCurrent()
        assertEquals(widthPx, controller.revealPx)
        assertEquals(1f, controller.fullDoneProgress)
        assertEquals(0, doneCalls)

        // Halfway through collapse: height shrinks smoothly, Done callback has not removed the row yet.
        advanceTimeBy(ActivitySwipeDoneCollapseMillis.toLong() / 2)
        runCurrent()
        assertTrue(controller.collapseProgress in 0.1f..0.9f, "collapseProgress=${controller.collapseProgress}")
        assertEquals(40, activitySwipeCollapsedHeightPx(80, controller.collapseProgress))
        assertEquals(0, doneCalls)

        // End of collapse: row is 0px tall, enters Exited, fires onDone once, and does NOT snap back to 0f.
        advanceTimeBy(ActivitySwipeDoneCollapseMillis.toLong() - ActivitySwipeDoneCollapseMillis.toLong() / 2)
        runCurrent()
        assertEquals(0f, controller.collapseProgress)
        assertEquals(0, activitySwipeCollapsedHeightPx(80, controller.collapseProgress))
        assertEquals(ActivitySwipePhase.Exited, controller.phase)
        assertEquals(1, doneCalls)
        assertEquals(widthPx, controller.revealPx, "row must not snap back to 0f right after onDone")
    }

    @Test
    fun fullSwipeDoneReleasedWithPartialFullDoneProgressFinishesFullWidthBeforeCollapse() = runTest {
        val widthPx = 390f
        val driver = FakeRevealDriver(initialRevealPx = 250f, initialFullDone = 0.25f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        val collapseStarted = CompletableDeferred<Unit>()
        driver.animateCollapseStarted = collapseStarted

        controller.completeDone(widthPx = widthPx, onBeforeDone = {}, onDone = {})
        advanceTimeBy(ActivitySwipeDoneSlideOutMillis.toLong())
        runCurrent()

        assertTrue(collapseStarted.isCompleted, "collapse should start once slide and fullDone finish")
        assertEquals(1f, driver.fullDoneAtCollapseStart, "fullDone must reach 1f before collapse starts")
        assertEquals(widthPx, driver.revealAtCollapseStart, "reveal must reach full width before collapse starts")
    }

    @Test
    fun objectRowDoneDoesNotSnapBackToZeroBeforeAsyncDisposal() = runTest {
        val rowScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val driver = FakeRevealDriver(initialRevealPx = 144f)
        val controller = ActivitySwipeRowController(driver = driver, scope = rowScope)

        var doneCalls = 0
        controller.completeDone(widthPx = 390f, onBeforeDone = {}, onDone = { doneCalls += 1 })
        advanceTimeBy(ActivitySwipeDoneSlideOutMillis.toLong() + ActivitySwipeDoneCollapseMillis.toLong())
        runCurrent()

        assertEquals(1, doneCalls)
        assertEquals(ActivitySwipePhase.Exited, controller.phase)
        assertEquals(390f, controller.revealPx)
        assertEquals(0f, controller.collapseProgress)

        // Simulate 2-frame (~32ms) StateFlow round-trip for InboxItemKind.Object MarkDone removal.
        advanceTimeBy(32L)
        runCurrent()
        assertEquals(ActivitySwipePhase.Exited, controller.phase)
        assertEquals(390f, controller.revealPx)
        assertEquals(0f, controller.collapseProgress)
        assertTrue(
            driver.ops.none { it.kind == OpKind.SnapReveal && it.target == 0f },
            "InboxItemKind.Object row must never snapTo(0f) before removal, ops=${driver.ops}",
        )

        // Row leaves LazyColumn and disposes its scope cleanly.
        rowScope.cancel()
        advanceUntilIdle()
        assertTrue(
            driver.ops.none { it.kind == OpKind.SnapReveal && it.target == 0f },
            "disposed row must never snap back to 0f, ops=${driver.ops}",
        )
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
        advanceUntilIdle()
        assertEquals(ActivitySwipePhase.Idle, controller.phase)
        assertEquals(0f, controller.revealPx)
        assertEquals(0f, controller.fullDoneProgress)
        assertEquals(1f, controller.collapseProgress)
    }

    @Test
    fun tryFinallyCleansUpPhaseOnExceptionAndCancellationInToggleAndDone() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val safeScope =
            CoroutineScope(
                backgroundScope.coroutineContext +
                    SupervisorJob() +
                    CoroutineExceptionHandler { _, throwable -> uncaught += throwable },
            )
        val toggleDriver = FakeRevealDriver(initialRevealPx = -260f)
        val toggleController = ActivitySwipeRowController(driver = toggleDriver, scope = safeScope)

        toggleController.completeToggle(
            onBeforeToggle = {},
            onToggleRead = { throw IllegalStateException("toggle boom") },
        )
        runCurrent()

        assertEquals(1, uncaught.size)
        assertEquals(ActivitySwipePhase.Idle, toggleController.phase, "try/finally must clear CompletingToggle on failure")
        assertTrue(toggleController.settleVersion > 0)
        // Self-healing syncRest recovers the offset cleanly back to 0.
        toggleController.syncRest(0f)
        advanceUntilIdle()
        assertEquals(0f, toggleController.revealPx)
        assertTrue(toggleController.beginDrag(), "row must remain interactive after toggle error")

        // Cancellation mid-Done resets phase cleanly via try/finally.
        val cancelScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val doneDriver = FakeRevealDriver(initialRevealPx = 144f)
        val doneController = ActivitySwipeRowController(driver = doneDriver, scope = cancelScope)
        doneController.completeDone(widthPx = 390f, onBeforeDone = {}, onDone = {})
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(ActivitySwipePhase.CompletingDone, doneController.phase)
        cancelScope.cancel()
        runCurrent()
        assertEquals(ActivitySwipePhase.Idle, doneController.phase, "try/finally must reset phase on cancellation")
        safeScope.cancel()
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
    fun swipeUnfollowCollapsesOutWithoutExpandingDoneWhileMuteKeepsRow() = runTest {
        assertTrue(activitySwipeEndActionRemovesRow(ActivitySwipeEndAction.UnfollowThread))
        assertFalse(activitySwipeEndActionRemovesRow(ActivitySwipeEndAction.MuteActivity))
        assertTrue(activityRowActionRemovesRow("done"))
        assertTrue(activityRowActionRemovesRow("unfollow-thread"))
        assertFalse(activityRowActionRemovesRow("unfollow-object"))
        assertFalse(activityRowActionRemovesRow("mute-activity"))
        assertFalse(activityRowActionRemovesRow("toggle-read"))

        val driver = FakeRevealDriver(initialRevealPx = 144f, initialFullDone = 0f)
        val controller = ActivitySwipeRowController(driver = driver, scope = backgroundScope)

        var unfollowCalls = 0
        controller.completeCollapseExit(onBeforeExit = {}, onExit = { unfollowCalls += 1 })
        assertEquals(ActivitySwipePhase.CompletingExit, controller.phase)
        assertFalse(controller.completingDone, "Unfollow exit must not expand the green Done button")

        advanceTimeBy(ActivitySwipeDoneCollapseMillis.toLong())
        runCurrent()
        assertEquals(ActivitySwipePhase.Exited, controller.phase)
        assertEquals(0f, controller.collapseProgress)
        assertEquals(0f, controller.fullDoneProgress)
        assertEquals(1, unfollowCalls)
    }

    @Test
    fun nonSwipeRowExitStateAndTopAnchoringStayConsistentAcrossCollapseAndRemoval() {
        val timeContext =
            RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h")
                .refreshedAt(parseRaftTimestampEpochMillisOrNull("2026-11-05T12:00:00Z")!!)
        val head = sampleItem("row-0", at = "2026-11-05T11:50:00Z")
        val second = sampleItem("row-1", at = "2026-11-05T11:40:00Z")
        val objectRow = sampleItem("object:art-1", at = "2026-11-05T11:30:00Z", kind = InboxItemKind.Object)
        val initialItems = listOf(head, second, objectRow)

        // Starting a non-swipe exit keeps the row in the list while its height collapses to 0,
        // so LazyColumn keys and top-anchor head signature remain stable during the animation.
        val started = ActivityRowExitState().start(head, "done")
        assertTrue(started.isExiting(head.key))
        assertEquals(started, started.start(head, "done"))
        val headBeforeCommit = activityListHeadSignature(activitySwipeListedItems(initialItems, null, emptySet()), timeContext)
        assertEquals(listOf(head, second, objectRow), activitySwipeListedItems(initialItems, null, emptySet()))
        assertEquals(headBeforeCommit, activityListHeadSignature(initialItems, timeContext))

        // Completing collapse pops the pending exit and removes the head row cleanly:
        // removing the head is NOT treated as a new top row by the top-anchor policy.
        val (afterComplete, committed) = started.complete(head.key)
        assertEquals(ActivityPendingRowExit(head, "done"), committed)
        assertFalse(afterComplete.isExiting(head.key))
        val afterRemoval = activitySwipeListedItems(initialItems, hiddenKey = head.key, committedUnfollowKeys = emptySet())
        assertEquals(listOf(second, objectRow), afterRemoval)
        assertFalse(
            activityHasNewTopRow(initialItems.map(InboxItem::key), afterRemoval.map(InboxItem::key)),
            "removing the head row via Done must not trigger a new-top-row anchor jump",
        )

        // Disposal flushes any in-flight exits so leaving Activity / switching filters never drops Done.
        val twoExits = ActivityRowExitState().start(second, "done").start(objectRow, "done")
        val retained = twoExits.retain(listOf(second.key))
        assertTrue(retained.isExiting(second.key))
        assertFalse(retained.isExiting(objectRow.key))
        val (flushedState, flushedItems) = retained.flushAll()
        assertFalse(flushedState.isExiting(second.key))
        assertEquals(listOf(ActivityPendingRowExit(second, "done")), flushedItems)
    }
}
