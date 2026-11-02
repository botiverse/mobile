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
    fun unifiedDialogOuterClearancePreservesZeroInsetFixtures() {
        val clearance =
            raftUnifiedDialogOuterClearance(
                outerPadding = 16.dp,
                topInset = 0.dp,
                bottomInset = 0.dp
            )

        assertEquals(16.dp, clearance.start)
        assertEquals(16.dp, clearance.top)
        assertEquals(16.dp, clearance.end)
        assertEquals(16.dp, clearance.bottom)
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
    fun ordinaryDialogIgnoresKeyboardHeight() {
        assertEquals(
            34.dp,
            raftUnifiedDialogEffectiveBottomInset(
                hostBottomInset = 34.dp,
                keyboardHeightDp = 312f,
                keyboardAvoidanceEnabled = false
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
    fun aTopDialogMovesUpOnlyOnAPhoneWithTheKeyboardUp() {
        assertTrue(raftUnifiedDialogTopAnchored(RaftDialogPresentation.Top, 390.dp, keyboardHeightDp = 300f))
        assertFalse(raftUnifiedDialogTopAnchored(RaftDialogPresentation.Top, 390.dp, keyboardHeightDp = 0f))
        assertFalse(raftUnifiedDialogTopAnchored(RaftDialogPresentation.Top, 1024.dp, keyboardHeightDp = 300f))
        assertFalse(raftUnifiedDialogTopAnchored(RaftDialogPresentation.Centered, 390.dp, keyboardHeightDp = 300f))
        assertFalse(raftUnifiedDialogTopAnchored(RaftDialogPresentation.Sheet, 390.dp, keyboardHeightDp = 300f))
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
    fun theSheetIsNinetyPercentTallAndShrinksForTheKeyboard() {
        // Keyboard down: 90% of the window.
        assertEquals(759.6.dp.value, raftUnifiedDialogSheetHeight(844.dp, topInset = 47.dp, keyboardOffset = 0.dp).value, 0.01f)
        // Keyboard up: from under the status bar down to the keyboard, so the head stays in view.
        assertEquals(461.dp, raftUnifiedDialogSheetHeight(844.dp, topInset = 47.dp, keyboardOffset = 336.dp))
        // Never negative.
        assertEquals(0.dp, raftUnifiedDialogSheetHeight(300.dp, topInset = 47.dp, keyboardOffset = 336.dp))
    }

    @Test
    fun closeRelayFallsBackToTheDismissWithoutASheet() {
        val relay = RaftUnifiedDialogCloseRelay()
        var dismissed = 0
        relay.close { dismissed++ }
        assertEquals(1, dismissed)
        var animated = 0
        relay.animatedClose = { animated++ }
        relay.close { dismissed++ }
        assertEquals(1, dismissed, "the sheet slides out and dismisses itself")
        assertEquals(1, animated)
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
    fun focusedEditorScrollDeltaDoesNothingWhenEditorIsVisible() {
        assertEquals(
            0f,
            raftUnifiedDialogFocusedEditorScrollDelta(
                focusedTop = 180f,
                focusedBottom = 280f,
                viewportTop = 100f,
                viewportBottom = 500f
            )
        )
    }

    @Test
    fun focusedEditorVisibilityWaitsForRelayoutThenScrollsCurrentPosition() = runBlocking {
        val events = mutableListOf<String>()
        var frameCount = 0
        var focusedBounds: Rect? = null
        var target: RaftUnifiedDialogListScrollPosition? = null

        raftUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = {
                events += "frame"
                frameCount += 1
                if (frameCount == 2) {
                    focusedBounds = Rect(0f, 420f, 100f, 548.4f)
                }
            },
            focusedBounds = {
                events += "focused"
                focusedBounds
            },
            viewportBounds = {
                events += "viewport"
                Rect(0f, 100f, 100f, 500f)
            },
            currentScrollPosition = {
                events += "position"
                RaftUnifiedDialogListScrollPosition(index = 3, scrollOffset = 40)
            },
            scrollToItem = { index, scrollOffset ->
                events += "scroll"
                target = RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )

        assertEquals(
            listOf("frame", "frame", "focused", "viewport", "position", "scroll"),
            events
        )
        assertEquals(RaftUnifiedDialogListScrollPosition(index = 3, scrollOffset = 89), target)
    }

    @Test
    fun focusedEditorVisibilityDoesNotScrollForClosedKeyboardOrVisibleEditor() = runBlocking {
        var frameCount = 0
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()
        val awaitFrame: suspend () -> Unit = { frameCount += 1 }
        val scrollToItem: suspend (Int, Int) -> Unit = { index, scrollOffset ->
            targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
        }

        raftUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 0f,
            awaitRelayoutFrame = awaitFrame,
            focusedBounds = { Rect(0f, 420f, 100f, 548f) },
            viewportBounds = { Rect(0f, 100f, 100f, 500f) },
            currentScrollPosition = {
                RaftUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = scrollToItem,
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )
        assertEquals(0, frameCount)

        raftUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = awaitFrame,
            focusedBounds = { Rect(0f, 180f, 100f, 280f) },
            viewportBounds = { Rect(0f, 100f, 100f, 500f) },
            currentScrollPosition = {
                RaftUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = scrollToItem,
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )

        assertEquals(2, frameCount)
        assertEquals(emptyList(), targets)
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
    fun focusedEditorVisibilityYieldsWhenANewerRequestReplacesIt() = runBlocking {
        var frameCount = 0
        var requestCurrent = true
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = {
                frameCount += 1
                if (frameCount == 2) {
                    requestCurrent = false
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
            isScrollInProgress = { false },
            isRequestCurrent = { requestCurrent }
        )

        assertEquals(2, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun focusedEditorVisibilityYieldsWhenTargetDeactivatesDuringRelayout() = runBlocking {
        var frameCount = 0
        var targetActive = true
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = {
                frameCount += 1
                if (frameCount == 1) {
                    targetActive = false
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
            isScrollInProgress = { false },
            isRequestCurrent = { true },
            isTargetActive = { targetActive }
        )

        assertEquals(1, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun focusedEditorVisibilityBypassesWhenTargetIsNotActive() = runBlocking {
        var frameCount = 0
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = { frameCount += 1 },
            focusedBounds = { Rect(0f, 420f, 100f, 548f) },
            viewportBounds = { Rect(0f, 100f, 100f, 500f) },
            currentScrollPosition = {
                RaftUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = { index, scrollOffset ->
                targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true },
            isTargetActive = { false }
        )

        assertEquals(0, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun focusedEditorScrollCoordinatorInvalidatesSupersededAndDisposedRequests() {
        val coordinator = RaftFocusedEditorScrollRequestCoordinator()
        val firstRequest = coordinator.begin()
        assertTrue(coordinator.isCurrent(firstRequest))

        val secondRequest = coordinator.begin()
        assertFalse(coordinator.isCurrent(firstRequest))
        assertTrue(coordinator.isCurrent(secondRequest))

        coordinator.invalidate()
        assertFalse(coordinator.isCurrent(secondRequest))
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

    @Test
    fun targetItemScrollDoesNotScrollForClosedKeyboard() = runBlocking {
        var frameCount = 0
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogScrollToTargetItem(
            keyboardHeight = 0f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = { frameCount += 1 },
            scrollToItem = { index, scrollOffset ->
                targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )

        assertEquals(0, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun targetItemScrollYieldsWhenUserStartsScrollingDuringRelayout() = runBlocking {
        var frameCount = 0
        var userScrollInProgress = false
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = {
                frameCount += 1
                if (frameCount == 2) {
                    userScrollInProgress = true
                }
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
    fun targetItemScrollYieldsWhenANewerRequestReplacesIt() = runBlocking {
        var frameCount = 0
        var requestCurrent = true
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = {
                frameCount += 1
                if (frameCount == 2) {
                    requestCurrent = false
                }
            },
            scrollToItem = { index, scrollOffset ->
                targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { requestCurrent }
        )

        assertEquals(2, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun targetItemScrollYieldsWhenFocusChangesBetweenRelayoutFrames() = runBlocking {
        var frameCount = 0
        var targetActive = true
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = {
                frameCount += 1
                if (frameCount == 1) {
                    targetActive = false
                }
            },
            scrollToItem = { index, scrollOffset ->
                targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true },
            isTargetActive = { targetActive }
        )

        assertEquals(1, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun targetItemScrollAbortsWhenCoordinatorInvalidatedBetweenFrames() = runBlocking {
        val coordinator = RaftFocusedEditorScrollRequestCoordinator()
        val generation = coordinator.begin()
        var frameCount = 0
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = {
                frameCount += 1
                if (frameCount == 1) {
                    coordinator.invalidate()
                }
            },
            scrollToItem = { index, scrollOffset ->
                targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { coordinator.isCurrent(generation) }
        )

        assertEquals(1, frameCount)
        assertEquals(emptyList(), targets)
    }

    @Test
    fun targetItemScrollBypassesImmediatelyWhenTargetIsNotActive() = runBlocking {
        var frameCount = 0
        val targets = mutableListOf<RaftUnifiedDialogListScrollPosition>()

        raftUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = { frameCount += 1 },
            scrollToItem = { index, scrollOffset ->
                targets += RaftUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true },
            isTargetActive = { false }
        )

        assertEquals(0, frameCount)
        assertEquals(emptyList(), targets)
    }
}

