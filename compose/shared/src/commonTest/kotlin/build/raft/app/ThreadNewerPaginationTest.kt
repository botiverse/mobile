package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThreadNewerPaginationTest {
    @Test
    fun demandTrackerRequiresAnActiveGestureAndUpwardMove() {
        val tracker = ThreadNewerGestureDemandTracker()

        assertEquals(0, tracker.onTouch(TouchType.Move, pointerY = 90f))
        assertEquals(0, tracker.onTouch(TouchType.Down, pointerY = 100f))
        assertEquals(0, tracker.onTouch(TouchType.Move, pointerY = 110f))
        assertEquals(0, tracker.onTouch(TouchType.Move, pointerY = 110f))
        assertEquals(0, tracker.onTouch(TouchType.Up, pointerY = 110f))
    }

    @Test
    fun upwardJitterDoesNotEmitAndFirstMovePastTouchSlopEmitsOnce() {
        val tracker = ThreadNewerGestureDemandTracker(initialGeneration = 7)

        tracker.onTouch(TouchType.Down, pointerY = 100f)
        assertEquals(7, tracker.onTouch(TouchType.Move, pointerY = 99f))
        assertEquals(7, tracker.onTouch(TouchType.Move, pointerY = 92f))
        assertEquals(8, tracker.onTouch(TouchType.Move, pointerY = 80f))
        assertEquals(8, tracker.onTouch(TouchType.Move, pointerY = 120f))
        assertEquals(8, tracker.onTouch(TouchType.Move, pointerY = 100f))
    }

    @Test
    fun firstUpwardMoveAfterAnInitialDownwardMoveStillEmitsOnce() {
        val tracker = ThreadNewerGestureDemandTracker()

        tracker.onTouch(TouchType.Down, pointerY = 100f)
        assertEquals(0, tracker.onTouch(TouchType.Move, pointerY = 120f))
        assertEquals(0, tracker.onTouch(TouchType.Move, pointerY = 119f))
        assertEquals(0, tracker.onTouch(TouchType.Move, pointerY = 99f))
        assertEquals(1, tracker.onTouch(TouchType.Move, pointerY = 80f))
    }

    @Test
    fun upEndsGestureAndNewUpwardGestureRearmsDemand() {
        val tracker = ThreadNewerGestureDemandTracker()

        tracker.onTouch(TouchType.Down, pointerY = 100f)
        assertEquals(1, tracker.onTouch(TouchType.Move, pointerY = 90f))
        assertEquals(1, tracker.onTouch(TouchType.Up, pointerY = 90f))
        assertEquals(1, tracker.onTouch(TouchType.Move, pointerY = 80f))

        assertEquals(1, tracker.onTouch(TouchType.Down, pointerY = 70f))
        assertEquals(2, tracker.onTouch(TouchType.Move, pointerY = 60f))
    }

    @Test
    fun demandTrackerRejectsNegativeInitialGeneration() {
        assertFailsWith<IllegalArgumentException> {
            ThreadNewerGestureDemandTracker(initialGeneration = -1)
        }
    }

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
    fun downwardGestureDoesNotAuthorizeAutoLoadEvenAtNewerBoundary() {
        val tracker = ThreadNewerGestureDemandTracker()

        tracker.onTouch(TouchType.Down, pointerY = 100f)
        tracker.onTouch(TouchType.Move, pointerY = 120f)

        assertFalse(
            shouldAutoLoad(
                newerDemandGeneration = tracker.generation,
                lastSettledNewerDemandGeneration = 0
            )
        )
    }

    @Test
    fun autoLoadRejectsNoFreshDemandAndFarOrInvalidBoundary() {
        assertFalse(
            shouldAutoLoad(
                newerDemandGeneration = 1,
                lastSettledNewerDemandGeneration = 1
            )
        )
        assertFalse(
            shouldAutoLoad(
                lastVisibleIndex = 40,
                latestMessageListItemIndex = 49,
                newerDemandGeneration = 2,
                lastSettledNewerDemandGeneration = 1
            )
        )
        assertFalse(
            shouldAutoLoad(
                lastVisibleIndex = -1,
                newerDemandGeneration = 2,
                lastSettledNewerDemandGeneration = 1
            )
        )
        assertFalse(
            shouldAutoLoad(
                latestMessageListItemIndex = -1,
                newerDemandGeneration = 2,
                lastSettledNewerDemandGeneration = 1
            )
        )
        assertFalse(
            shouldAutoLoad(
                newerDemandGeneration = 2,
                lastSettledNewerDemandGeneration = 1,
                thresholdItems = -1
            )
        )
    }

    @Test
    fun autoLoadRejectsUnreadyExhaustedNonIdleAndActiveCycleStates() {
        assertFalse(
            shouldAutoLoad(
                initialBottomAnchorReady = false,
                newerDemandGeneration = 1
            )
        )
        assertFalse(
            shouldAutoLoad(
                canLoadMoreAfter = false,
                newerDemandGeneration = 1
            )
        )
        listOf("loading", "error", "end").forEach { status ->
            assertFalse(
                shouldAutoLoad(
                    newerMessagesStatus = status,
                    newerDemandGeneration = 1
                ),
                "status=$status"
            )
        }
        assertFalse(
            shouldAutoLoad(
                newerPageCycleActive = true,
                newerDemandGeneration = 1
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
    fun pageCycleSettlementRequiresTheSameRouteAndActiveCycle() {
        assertTrue(
            shouldSettleThreadNewerPageCycle(
                cycleThreadInstanceKey = "thread-a",
                currentThreadInstanceKey = "thread-a",
                cycleId = 3L,
                activeCycleId = 3L
            )
        )
        assertFalse(
            shouldSettleThreadNewerPageCycle(
                cycleThreadInstanceKey = "thread-a",
                currentThreadInstanceKey = "thread-b",
                cycleId = 3L,
                activeCycleId = 3L
            )
        )
        assertFalse(
            shouldSettleThreadNewerPageCycle(
                cycleThreadInstanceKey = "thread-a",
                currentThreadInstanceKey = "thread-a",
                cycleId = 3L,
                activeCycleId = 4L
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
