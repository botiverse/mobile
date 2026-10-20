package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-decision coverage for the foreground banner swipe-up dismissal:
 * the banner follows the finger upward only, and a release past the
 * threshold dismisses instead of snapping back.
 */
class RaftForegroundBannerSwipeTest {

    @Test
    fun dragOffsetFollowsUpwardMovementOnly() {
        assertEquals(-10f, foregroundBannerDragOffsetAfterDelta(currentOffsetYDp = 0f, deltaYDp = -10f))
        assertEquals(-25f, foregroundBannerDragOffsetAfterDelta(currentOffsetYDp = -10f, deltaYDp = -15f))
        // Downward movement can return toward the seated position but never past it.
        assertEquals(-5f, foregroundBannerDragOffsetAfterDelta(currentOffsetYDp = -10f, deltaYDp = 5f))
        assertEquals(0f, foregroundBannerDragOffsetAfterDelta(currentOffsetYDp = -10f, deltaYDp = 25f))
        assertEquals(0f, foregroundBannerDragOffsetAfterDelta(currentOffsetYDp = 0f, deltaYDp = 12f))
    }

    @Test
    fun releasePastThresholdDismisses() {
        assertTrue(shouldDismissForegroundBannerOnSwipeRelease(dragOffsetYDp = -24f))
        assertTrue(shouldDismissForegroundBannerOnSwipeRelease(dragOffsetYDp = -60f))
    }

    @Test
    fun releaseShortOfThresholdSnapsBack() {
        assertFalse(shouldDismissForegroundBannerOnSwipeRelease(dragOffsetYDp = -23.9f))
        assertFalse(shouldDismissForegroundBannerOnSwipeRelease(dragOffsetYDp = 0f))
    }
}
