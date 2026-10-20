package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The fit scale is the one piece of arithmetic behind desktop mode, and task #514 gave it three
 * platform callers. These tests pin the contract so Android, iOS and OHOS cannot each end up with
 * their own slightly different formula and their own edge-case behaviour.
 *
 * Two behaviours matter beyond the happy path, both carried over from the Android implementation
 * this function replaces:
 *  - "no fit needed" is expressed as `null`, not as 100, so callers can distinguish "leave the view
 *    alone" from "scale it to its current size";
 *  - the result is clamped to a floor, because `setInitialScale` takes an integer percent and a
 *    near-zero ratio would render nothing usable.
 */
class RaftFilePreviewDesktopScaleTest {
    @Test
    fun aPhoneViewportGetsTheFitRatioInPercent() {
        // The measured Android case: 720px view, dpr 1.5, 1280 CSS px desktop layout.
        // 720 / (1280 * 1.5) = 0.375 -> 37%.
        assertEquals(37, raftFilePreviewDesktopScalePercent(viewportPx = 720, dpr = 1.5f))
    }

    @Test
    fun aWiderViewportNeedsMoreScale() {
        // Same dpr, roughly double the width: the layout occupies a larger share of the view.
        assertEquals(75, raftFilePreviewDesktopScalePercent(viewportPx = 1440, dpr = 1.5f))
    }

    @Test
    fun aViewportAlreadyAtDesktopWidthNeedsNoFit() {
        // 1280 CSS px at dpr 1 is exactly the desktop layout: nothing to shrink.
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 1280, dpr = 1f))
    }

    @Test
    fun aViewportWiderThanDesktopNeedsNoFit() {
        // Shrinking here would make the layout smaller than the view, which is not what desktop
        // mode means. The Android implementation returned early for the same reason.
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 2400, dpr = 1f))
    }

    @Test
    fun anUnmeasuredViewportYieldsNoScaleYet() {
        // The prop can arrive before the first layout pass. Callers retry once measured.
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 0, dpr = 2f))
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = -1, dpr = 2f))
    }

    @Test
    fun anUnusableDensityYieldsNoScale() {
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 720, dpr = 0f))
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 720, dpr = -1.5f))
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 720, dpr = Float.NaN))
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 720, dpr = Float.POSITIVE_INFINITY))
    }

    @Test
    fun anImpossiblyNarrowViewportIsClampedToTheFloorNotRoundedToNothing() {
        // 10 / (1280 * 3) = 0.26% -> would round to 0 and render nothing. The floor keeps the
        // layout visible; every platform clamps at this same value.
        assertEquals(
            RaftFilePreviewDesktopScaleMinPercent,
            raftFilePreviewDesktopScalePercent(viewportPx = 10, dpr = 3f),
        )
    }

    @Test
    fun theScaleNeverExceedsFullSize() {
        // Any input that produces a fit ratio must stay below 100, since >= 1 returns null.
        val percent = raftFilePreviewDesktopScalePercent(viewportPx = 1279, dpr = 1f)
        assertEquals(99, percent)
    }

    @Test
    fun aNonPositiveDesktopWidthIsRejectedRatherThanDividingByZero() {
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 720, dpr = 1.5f, desktopCssWidth = 0))
        assertNull(raftFilePreviewDesktopScalePercent(viewportPx = 720, dpr = 1.5f, desktopCssWidth = -1280))
    }
}
