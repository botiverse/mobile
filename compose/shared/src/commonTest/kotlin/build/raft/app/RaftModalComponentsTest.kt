package build.raft.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RaftModalComponentsTest {
    @Test
    fun unifiedDialogOuterClearanceKeepsCardInsideSafeViewport() {
        val clearance =
            raftUnifiedDialogOuterClearance(
                outerPadding = 16.dp,
                topInset = 40.dp,
                bottomInset = 88.dp
            )

        assertEquals(16.dp, clearance.start)
        assertEquals(56.dp, clearance.top)
        assertEquals(16.dp, clearance.end)
        assertEquals(104.dp, clearance.bottom)
    }

    @Test
    fun dialogHostBottomInsetNeverDropsBelowTheNavigationBar() {
        // Tabs hidden or no host: the shell reports 0, the navigation bar still needs room.
        assertEquals(48.dp, raftUnifiedDialogHostBottomInset(hostBottomInset = 0.dp, bottomSafeArea = 48.dp))
        // Tabs shown: the tab bar height already includes the safe area and wins.
        assertEquals(98.dp, raftUnifiedDialogHostBottomInset(hostBottomInset = 98.dp, bottomSafeArea = 48.dp))
        // No navigation bar (e.g. gesture-less host reporting 0): unchanged.
        assertEquals(0.dp, raftUnifiedDialogHostBottomInset(hostBottomInset = 0.dp, bottomSafeArea = 0.dp))
    }

    @Test
    fun keyboardAwareDialogUsesNativeKeyboardHeightAsBottomInset() {
        assertEquals(
            312.dp,
            raftUnifiedDialogEffectiveBottomInset(
                hostBottomInset = 34.dp,
                keyboardHeightDp = 312f,
                keyboardAvoidanceEnabled = true
            )
        )
        assertEquals(
            34.dp,
            raftUnifiedDialogEffectiveBottomInset(
                hostBottomInset = 34.dp,
                keyboardHeightDp = -1f,
                keyboardAvoidanceEnabled = true
            )
        )
    }

    @Test
    fun onlyASheetDialogInACompactWindowDrawsThePhoneSheet() {
        // Phones and split-screen halves: below the app's wide layout (744dp).
        listOf(320.dp, 390.dp, 430.dp, 600.dp, 743.dp).forEach { width ->
            assertTrue(raftUnifiedDialogDrawsSheet(RaftDialogPresentation.Sheet, width), "width=$width")
            assertFalse(raftUnifiedDialogDrawsSheet(RaftDialogPresentation.Centered, width), "width=$width")
            assertFalse(raftUnifiedDialogDrawsSheet(RaftDialogPresentation.Top, width), "width=$width")
        }
        // The wide layout keeps today's centred card for every presentation.
        listOf(744.dp, 768.dp, 1024.dp, 1280.dp).forEach { width ->
            RaftDialogPresentation.entries.forEach { presentation ->
                assertFalse(raftUnifiedDialogDrawsSheet(presentation, width), "$presentation width=$width")
            }
        }
    }

    @Test
    fun theSheetSitsOnTheKeyboard() {
        // Keyboard down: on the window's bottom edge.
        assertEquals(0.dp, raftUnifiedDialogSheetKeyboardOffset(0f, bottomSafeArea = 48.dp, keyboardExcludesNavigationBars = true))
        assertEquals(0.dp, raftUnifiedDialogSheetKeyboardOffset(-1f, bottomSafeArea = 34.dp, keyboardExcludesNavigationBars = false))
        // Android reports the keyboard without the navigation bar: add it back.
        assertEquals(348.dp, raftUnifiedDialogSheetKeyboardOffset(300f, bottomSafeArea = 48.dp, keyboardExcludesNavigationBars = true))
        // iOS reports the whole keyboard frame, home indicator included.
        assertEquals(336.dp, raftUnifiedDialogSheetKeyboardOffset(336f, bottomSafeArea = 34.dp, keyboardExcludesNavigationBars = false))
    }

    @Test
    fun focusedEditorScrollDeltaMovesOccludedEditorAboveViewport() {
        assertEquals(
            48f,
            raftUnifiedDialogFocusedEditorScrollDelta(
                focusedTop = 420f,
                focusedBottom = 548f,
                viewportTop = 100f,
                viewportBottom = 500f
            )
        )
        assertEquals(
            -24f,
            raftUnifiedDialogFocusedEditorScrollDelta(
                focusedTop = 76f,
                focusedBottom = 180f,
                viewportTop = 100f,
                viewportBottom = 500f
            )
        )
    }

    @Test
    fun focusedEditorVisibilityYieldsWhenUserStartsScrollingDuringRelayout() = runBlocking {
        var frameCount = 0
        var userScrollInProgress = false
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = {
                frameCount += 1
                if (frameCount == 2) {
                    userScrollInProgress = true
                }
            },
            focusedBounds = { Rect(0f, 420f, 100f, 548f) },
            viewportBounds = { Rect(0f, 100f, 100f, 500f) },
            currentScrollPosition = {
                RaftUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = { index, scrollOffset ->
                targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { userScrollInProgress },
            isRequestCurrent = { true }
        )

        assertEquals(2, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun targetItemScrollWaitsForRelayoutThenScrollsToItem() = runBlocking {
        val events = mutableListOf<String>()
        var target: RaftUnifiedDialogListScrollPosition? = null

        raftUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = {
                events += "frame"
            },
            scrollToItem = { index, scrollOffset ->
                events += "scroll"
                target = RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )

        assertEquals(listOf("frame", "frame", "scroll"), events)
        assertEquals(RaftUnifiedDialogListScrollPosition(index = 3, scrollOffset = 0), target)
    }
}

