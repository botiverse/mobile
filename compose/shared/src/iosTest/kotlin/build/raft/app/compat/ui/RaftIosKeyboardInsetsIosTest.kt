package build.raft.app.compat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftIosKeyboardInsetsIosTest {
    @Test
    fun overlapMatchesCmpForAFullScreenView() {
        // iPhone 16 Pro: 874 pt screen, keyboard top at 538 pt -> 336 pt keyboard.
        assertEquals(336.0, raftKeyboardOverlapPoints(874.0, keyboardEmpty = false, keyboardMinY = 538.0, viewBottomInScreen = 874.0))
        // Hidden keyboard (end frame below the screen or empty).
        assertEquals(0.0, raftKeyboardOverlapPoints(874.0, keyboardEmpty = false, keyboardMinY = 874.0, viewBottomInScreen = 874.0))
        assertEquals(0.0, raftKeyboardOverlapPoints(874.0, keyboardEmpty = true, keyboardMinY = 0.0, viewBottomInScreen = 874.0))
    }

    @Test
    fun overlapOnlyCountsTheCoveredPartOfAViewEndingAboveTheScreenBottom() {
        // A view ending 83 pt above the screen bottom: only 336 - 83 pt of it is covered.
        assertEquals(253.0, raftKeyboardOverlapPoints(874.0, keyboardEmpty = false, keyboardMinY = 538.0, viewBottomInScreen = 791.0))
        // A keyboard shorter than the gap covers nothing.
        assertEquals(0.0, raftKeyboardOverlapPoints(874.0, keyboardEmpty = false, keyboardMinY = 800.0, viewBottomInScreen = 791.0))
    }

    @Test
    fun floatingIpadKeyboardAndDisjointWindowReportZeroOverlap() {
        // Floating iPad keyboard hovering 120 pt above the bottom edge of the screen.
        assertEquals(
            0.0,
            raftKeyboardOverlapPoints(
                screenHeight = 1024.0,
                keyboardEmpty = false,
                keyboardMinY = 600.0,
                viewBottomInScreen = 1024.0,
                keyboardMaxY = 904.0,
            ),
        )
        // Horizontally disjoint Stage Manager / Split View window.
        assertEquals(
            0.0,
            raftKeyboardOverlapPoints(
                screenHeight = 1024.0,
                keyboardEmpty = false,
                keyboardMinY = 688.0,
                viewBottomInScreen = 1024.0,
                keyboardMaxY = 1024.0,
                keyboardIntersectsViewHorizontally = false,
            ),
        )
    }

    @Test
    fun cancelledSlideNeverSnapsToCancelledTargetAndPreservesInterruptionPixel() {
        var presentationProgress: Double? = 0.45
        val slide =
            RaftIosKeyboardSlideState(
                fromPx = 0,
                toPx = 1000,
                targetPoints = 333.0,
                readPresentationProgress = { presentationProgress },
            )
        assertTrue(slide.isActive)
        assertEquals(450, slide.sampleInsetPx())

        // Interruption cancels the slide: must NOT snap to 1000 or report a final settled inset.
        slide.cancel()
        assertFalse(slide.isActive)
        assertNull(slide.sampleInsetPx())
        assertNull(slide.finalSettledInsetPx())

        // Late UIKit completion after cancel must remain ignored.
        slide.markCompleted()
        assertNull(slide.sampleInsetPx())
        assertNull(slide.finalSettledInsetPx())
    }

    @Test
    fun nilPresentationLayerPreservesLastInsetInsteadOfSnappingToZero() {
        var presentationProgress: Double? = null
        val slide =
            RaftIosKeyboardSlideState(
                fromPx = 400,
                toPx = 900,
                targetPoints = 300.0,
                readPresentationProgress = { presentationProgress },
            )
        // Frame 1 before CoreAnimation commits presentationLayer returns null (caller keeps 400).
        assertNull(slide.sampleInsetPx())

        presentationProgress = 0.5
        assertEquals(650, slide.sampleInsetPx())

        slide.markCompleted()
        assertFalse(slide.isActive)
        assertEquals(900, slide.sampleInsetPx())
        assertEquals(900, slide.finalSettledInsetPx())
    }
}
