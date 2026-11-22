package build.raft.app.compat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import platform.UIKit.UIViewAnimationOptionCurveEaseIn
import platform.UIKit.UIViewAnimationOptionCurveEaseInOut
import platform.UIKit.UIViewAnimationOptionCurveLinear

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
}
