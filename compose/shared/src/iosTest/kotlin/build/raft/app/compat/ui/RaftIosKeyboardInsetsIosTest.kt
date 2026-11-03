package build.raft.app.compat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import platform.UIKit.UIViewAnimationOptionCurveEaseIn
import platform.UIKit.UIViewAnimationOptionCurveEaseInOut
import platform.UIKit.UIViewAnimationOptionCurveLinear

class RaftIosKeyboardInsetsIosTest {
    @Test
    fun keyboardCurveIsShiftedIntoTheCurveBits() {
        // UIViewAnimationCurve raw values: easeInOut 0, easeIn 1, easeOut 2, linear 3.
        assertEquals(UIViewAnimationOptionCurveEaseInOut, raftKeyboardCurveAnimationOptions(0uL))
        assertEquals(UIViewAnimationOptionCurveEaseIn, raftKeyboardCurveAnimationOptions(1uL))
        assertEquals(UIViewAnimationOptionCurveLinear, raftKeyboardCurveAnimationOptions(3uL))
        // The keyboard's private curve 7: CMP 1.12.2 passes the raw 7, whose curve bits are
        // ease-in-out's 0 (CMP-10448). Shifted, it keeps the keyboard's curve.
        val keyboard = raftKeyboardCurveAnimationOptions(7uL)
        assertEquals(7uL shl 16, keyboard)
        assertNotEquals(UIViewAnimationOptionCurveEaseInOut, keyboard and (0xFuL shl 16))
    }

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
    fun insetFollowsProgressBetweenTheEnds() {
        assertEquals(0, raftKeyboardInsetAt(0, 1008, 0.0))
        assertEquals(504, raftKeyboardInsetAt(0, 1008, 0.5))
        assertEquals(1008, raftKeyboardInsetAt(0, 1008, 1.0))
        // Hiding, and an overshooting/undershooting sample is clamped to the ends.
        assertEquals(252, raftKeyboardInsetAt(1008, 0, 0.75))
        assertEquals(1008, raftKeyboardInsetAt(0, 1008, 1.02))
        assertEquals(0, raftKeyboardInsetAt(0, 1008, -0.01))
    }
}
