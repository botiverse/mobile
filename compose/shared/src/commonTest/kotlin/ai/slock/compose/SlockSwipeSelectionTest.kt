package ai.slock.compose

import kotlin.test.Test
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
}
