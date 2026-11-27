package build.raft.app

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.pointer.PointerEventType
import build.raft.app.thread.ThreadNewerAutoLoadVisibleItemThreshold
import build.raft.app.thread.ThreadNewerGestureDemandTracker
import build.raft.app.thread.shouldThreadAutoLoadNewerMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ThreadNewerPaginationTest {
    @Test
    fun autoLoadAcceptsFreshUpwardDemandNearLatestMessage() {
        assertTrue(
            shouldAutoLoad(
                lastVisibleIndex = 41,
                latestMessageListItemIndex = 49,
                newerDemandGeneration = 2,
                lastSettledNewerDemandGeneration = 1
            )
        )
        assertTrue(
            shouldAutoLoad(
                lastVisibleIndex = 49,
                latestMessageListItemIndex = 49,
                newerDemandGeneration = 2,
                lastSettledNewerDemandGeneration = 1
            )
        )
    }

    @Test
    fun oneGestureCannotCascadeThirtyToSixtyToNinetyButNewGestureRearms() {
        val tracker = ThreadNewerGestureDemandTracker()
        var settledGeneration = tracker.generation

        tracker.onTouch(TouchType.Down, pointerY = 100f)
        tracker.onTouch(TouchType.Move, pointerY = 90f)
        assertTrue(
            shouldAutoLoad(
                newerDemandGeneration = tracker.generation,
                lastSettledNewerDemandGeneration = settledGeneration
            )
        )

        assertFalse(
            shouldAutoLoad(
                newerPageCycleActive = true,
                newerDemandGeneration = tracker.generation,
                lastSettledNewerDemandGeneration = settledGeneration
            )
        )
        tracker.onTouch(TouchType.Move, pointerY = 60f)
        settledGeneration = tracker.generation
        assertFalse(
            shouldAutoLoad(
                newerDemandGeneration = tracker.generation,
                lastSettledNewerDemandGeneration = settledGeneration
            )
        )

        tracker.onTouch(TouchType.Up, pointerY = 60f)
        tracker.onTouch(TouchType.Down, pointerY = 50f)
        tracker.onTouch(TouchType.Move, pointerY = 40f)
        assertTrue(
            shouldAutoLoad(
                newerDemandGeneration = tracker.generation,
                lastSettledNewerDemandGeneration = settledGeneration
            )
        )
    }

    @Test
    fun wheelOrTrackpadScrollDownAfterAnchorOpenAuthorizesNewerPageWithoutCascading() = runTest {
        val tracker = ThreadNewerGestureDemandTracker()
        val observed = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { tracker.observedGeneration }.collect { observed += it }
        }
        var settledGeneration = tracker.generation

        // Hover, sideways swipe, and upward scroll (toward older messages) must not authorize newer loading.
        tracker.onListPointerEvent(PointerEventType.Move, scrollDeltaY = 0f)
        tracker.onListPointerEvent(PointerEventType.Scroll, scrollDeltaY = 0f)
        tracker.onListPointerEvent(PointerEventType.Scroll, scrollDeltaY = -1f)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(listOf(0), observed)
        assertFalse(
            shouldAutoLoad(
                newerDemandGeneration = tracker.observedGeneration,
                lastSettledNewerDemandGeneration = settledGeneration,
            ),
            "scrolling up or hovering must not trigger newer pagination",
        )

        // Downward wheel/trackpad scroll (positive scrollDeltaY) wakes the observed check and requests one page.
        tracker.onListPointerEvent(PointerEventType.Scroll, scrollDeltaY = 1f)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(listOf(0, 1), observed)
        assertTrue(
            shouldAutoLoad(
                newerDemandGeneration = tracker.observedGeneration,
                lastSettledNewerDemandGeneration = settledGeneration,
            ),
            "downward wheel/trackpad scroll near the loaded tail authorizes the newer page",
        )

        // Momentum ticks during an active cycle are consumed when the cycle settles: no cascade.
        tracker.onListPointerEvent(PointerEventType.Scroll, scrollDeltaY = 1f)
        settledGeneration = tracker.generation
        assertFalse(
            shouldAutoLoad(
                newerDemandGeneration = tracker.generation,
                lastSettledNewerDemandGeneration = settledGeneration,
            ),
        )

        // A fresh downward scroll after settlement authorizes the next newer page.
        tracker.onListPointerEvent(PointerEventType.Scroll, scrollDeltaY = 1f)
        assertTrue(
            shouldAutoLoad(
                newerDemandGeneration = tracker.generation,
                lastSettledNewerDemandGeneration = settledGeneration,
            ),
        )
    }

    @Test
    fun shortAnchoredListTooShortToScrollRequestsNewerPageWithoutScroll() {
        assertTrue(
            shouldAutoLoad(
                lastVisibleIndex = 15,
                latestMessageListItemIndex = 15,
                newerDemandGeneration = 0,
                lastSettledNewerDemandGeneration = 0,
                listScrolls = false,
            ),
            "a list too short to scroll at an anchor loads newer messages on its own",
        )
        assertFalse(
            shouldAutoLoad(
                canLoadMoreAfter = false,
                lastVisibleIndex = 15,
                latestMessageListItemIndex = 15,
                newerDemandGeneration = 0,
                lastSettledNewerDemandGeneration = 0,
                listScrolls = false,
            ),
        )
    }

    private fun shouldAutoLoad(
        initialBottomAnchorReady: Boolean = true,
        canLoadMoreAfter: Boolean = true,
        newerMessagesStatus: String = "idle",
        newerPageCycleActive: Boolean = false,
        lastVisibleIndex: Int = 49,
        latestMessageListItemIndex: Int = 49,
        newerDemandGeneration: Int = 0,
        lastSettledNewerDemandGeneration: Int = 0,
        thresholdItems: Int = ThreadNewerAutoLoadVisibleItemThreshold,
        listScrolls: Boolean = true,
    ): Boolean =
        shouldThreadAutoLoadNewerMessages(
            initialBottomAnchorReady = initialBottomAnchorReady,
            canLoadMoreAfter = canLoadMoreAfter,
            newerMessagesStatus = newerMessagesStatus,
            newerPageCycleActive = newerPageCycleActive,
            lastVisibleIndex = lastVisibleIndex,
            latestMessageListItemIndex = latestMessageListItemIndex,
            newerDemandGeneration = newerDemandGeneration,
            lastSettledNewerDemandGeneration = lastSettledNewerDemandGeneration,
            thresholdItems = thresholdItems,
            listScrolls = listScrolls,
        )
}

private const val TestThreadNewerTouchSlop = 8f

private fun ThreadNewerGestureDemandTracker.onTouch(
    type: TouchType,
    pointerY: Float
): Int =
    onTouch(
        type = type,
        pointerY = pointerY,
        touchSlop = TestThreadNewerTouchSlop
    )
