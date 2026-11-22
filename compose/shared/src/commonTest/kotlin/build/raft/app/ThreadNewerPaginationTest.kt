package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun shouldAutoLoad(
        initialBottomAnchorReady: Boolean = true,
        canLoadMoreAfter: Boolean = true,
        newerMessagesStatus: String = "idle",
        newerPageCycleActive: Boolean = false,
        lastVisibleIndex: Int = 49,
        latestMessageListItemIndex: Int = 49,
        newerDemandGeneration: Int = 0,
        lastSettledNewerDemandGeneration: Int = 0,
        thresholdItems: Int = ThreadNewerAutoLoadVisibleItemThreshold
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
            thresholdItems = thresholdItems
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
