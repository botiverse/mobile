package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockSwipeSelectionTest {
    @Test
    fun acceptsClearHorizontalSwipePastThreshold() {
        assertTrue(
            isClearHorizontalSwipe(
                horizontalDistance = -128f,
                verticalDistance = 24f,
                thresholdPx = 96f,
                horizontalDominanceRatio = 1.45f
            )
        )
    }

    @Test
    fun rejectsMostlyVerticalScrollWithHorizontalDrift() {
        assertFalse(
            isClearHorizontalSwipe(
                horizontalDistance = -128f,
                verticalDistance = 220f,
                thresholdPx = 96f,
                horizontalDominanceRatio = 1.45f
            )
        )
    }

    @Test
    fun rejectsDiagonalSwipeWithoutClearHorizontalIntent() {
        assertFalse(
            isClearHorizontalSwipe(
                horizontalDistance = 120f,
                verticalDistance = 96f,
                thresholdPx = 96f,
                horizontalDominanceRatio = 1.45f
            )
        )
    }

    @Test
    fun rejectsShortHorizontalDrag() {
        assertFalse(
            isClearHorizontalSwipe(
                horizontalDistance = 72f,
                verticalDistance = 0f,
                thresholdPx = 96f,
                horizontalDominanceRatio = 1.45f
            )
        )
    }

    @Test
    fun previewOffsetFollowsHorizontalDragWhenAdjacentTabExists() {
        assertEquals(
            expected = -84f,
            actual =
                slockSwipePreviewOffset(
                    horizontalDistance = -84f,
                    maxOffsetPx = 156f,
                    hasPrevious = true,
                    hasNext = true
                )
        )
    }

    @Test
    fun previewOffsetClampsLongDrag() {
        assertEquals(
            expected = -156f,
            actual =
                slockSwipePreviewOffset(
                    horizontalDistance = -320f,
                    maxOffsetPx = 156f,
                    hasPrevious = true,
                    hasNext = true
                )
        )
    }

    @Test
    fun previewOffsetAppliesResistanceAtEdges() {
        assertEquals(
            expected = 43.68f,
            actual =
                slockSwipePreviewOffset(
                    horizontalDistance = 200f,
                    maxOffsetPx = 156f,
                    hasPrevious = false,
                    hasNext = true
                ),
            absoluteTolerance = 0.001f
        )
    }

    @Test
    fun dragOffsetFollowsFingerAcrossFullTravelWidthWhenAdjacentTabExists() {
        assertEquals(
            expected = -260f,
            actual =
                slockSwipeDragOffset(
                    horizontalDistance = -260f,
                    travelWidthPx = 360f,
                    fallbackOffsetPx = 156f,
                    hasPrevious = true,
                    hasNext = true
                )
        )
    }

    @Test
    fun dragOffsetClampsToFullTravelWidthWhenAdjacentTabExists() {
        assertEquals(
            expected = -360f,
            actual =
                slockSwipeDragOffset(
                    horizontalDistance = -500f,
                    travelWidthPx = 360f,
                    fallbackOffsetPx = 156f,
                    hasPrevious = true,
                    hasNext = true
                )
        )
    }

    @Test
    fun dragOffsetKeepsPreviewSizedResistanceAtEdges() {
        assertEquals(
            expected = 43.68f,
            actual =
                slockSwipeDragOffset(
                    horizontalDistance = 500f,
                    travelWidthPx = 360f,
                    fallbackOffsetPx = 156f,
                    hasPrevious = false,
                    hasNext = true
                ),
            absoluteTolerance = 0.001f
        )
    }

    @Test
    fun swipeDirectionMovesCurrentPageTowardNextTab() {
        assertEquals(
            expected = -1,
            actual = slockSwipeDirectionSign(currentIndex = 0, nextIndex = 1)
        )
        assertEquals(
            expected = 1,
            actual = slockSwipeDirectionSign(currentIndex = 2, nextIndex = 1)
        )
    }

    @Test
    fun dragTransitionTargetsAdjacentPageInDragDirection() {
        assertEquals(
            expected = SlockSwipeDragTransition(to = "tasks", directionSign = -1),
            actual =
                slockSwipeDragTransition(
                    items = listOf("home", "tasks", "members"),
                    selected = "home",
                    visualOffsetPx = -72f
                )
        )
        assertEquals(
            expected = SlockSwipeDragTransition(to = "tasks", directionSign = 1),
            actual =
                slockSwipeDragTransition(
                    items = listOf("home", "tasks", "members"),
                    selected = "members",
                    visualOffsetPx = 72f
                )
        )
    }

    @Test
    fun dragTransitionIsNullAtEdges() {
        assertEquals(
            expected = null,
            actual =
                slockSwipeDragTransition(
                    items = listOf("home", "tasks"),
                    selected = "home",
                    visualOffsetPx = 72f
                )
        )
        assertEquals(
            expected = null,
            actual =
                slockSwipeDragTransition(
                    items = listOf("home", "tasks"),
                    selected = "tasks",
                    visualOffsetPx = -72f
                )
        )
    }

    @Test
    fun incomingOffsetContinuesFromCurrentDragGap() {
        assertEquals(
            expected = 236f,
            actual =
                slockSwipeIncomingOffset(
                    currentOffsetPx = -84f,
                    travelWidthPx = 320f,
                    directionSign = -1
                )
        )
        assertEquals(
            expected = -236f,
            actual =
                slockSwipeIncomingOffset(
                    currentOffsetPx = 84f,
                    travelWidthPx = 320f,
                    directionSign = 1
                )
        )
    }

    @Test
    fun settleTargetMovesCurrentPageFullyOut() {
        assertEquals(
            expected = -320f,
            actual = slockSwipeSettleTargetOffset(travelWidthPx = 320f, directionSign = -1)
        )
        assertEquals(
            expected = 320f,
            actual = slockSwipeSettleTargetOffset(travelWidthPx = 320f, directionSign = 1)
        )
    }
}
