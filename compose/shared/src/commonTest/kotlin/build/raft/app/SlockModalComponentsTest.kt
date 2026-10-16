package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SlockModalComponentsTest {
    @Test
    fun unifiedDialogOuterClearanceKeepsCardInsideSafeViewport() {
        val clearance =
            slockUnifiedDialogOuterClearance(
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
            slockUnifiedDialogOuterClearance(
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
        assertEquals(48.dp, slockUnifiedDialogHostBottomInset(hostBottomInset = 0.dp, bottomSafeArea = 48.dp))
        // Tabs shown: the tab bar height already includes the safe area and wins.
        assertEquals(98.dp, slockUnifiedDialogHostBottomInset(hostBottomInset = 98.dp, bottomSafeArea = 48.dp))
        // No navigation bar (e.g. gesture-less host reporting 0): unchanged.
        assertEquals(0.dp, slockUnifiedDialogHostBottomInset(hostBottomInset = 0.dp, bottomSafeArea = 0.dp))
    }

    @Test
    fun keyboardAwareDialogUsesNativeKeyboardHeightAsBottomInset() {
        assertEquals(
            312.dp,
            slockUnifiedDialogEffectiveBottomInset(
                hostBottomInset = 34.dp,
                keyboardHeightDp = 312f,
                keyboardAvoidanceEnabled = true
            )
        )
        assertEquals(
            34.dp,
            slockUnifiedDialogEffectiveBottomInset(
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
            slockUnifiedDialogEffectiveBottomInset(
                hostBottomInset = 34.dp,
                keyboardHeightDp = 312f,
                keyboardAvoidanceEnabled = false
            )
        )
    }

    @Test
    fun focusedEditorScrollDeltaMovesOccludedEditorAboveViewport() {
        assertEquals(
            48f,
            slockUnifiedDialogFocusedEditorScrollDelta(
                focusedTop = 420f,
                focusedBottom = 548f,
                viewportTop = 100f,
                viewportBottom = 500f
            )
        )
        assertEquals(
            -24f,
            slockUnifiedDialogFocusedEditorScrollDelta(
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
            slockUnifiedDialogFocusedEditorScrollDelta(
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
        var target: SlockUnifiedDialogListScrollPosition? = null

        slockUnifiedDialogKeepFocusedEditorVisible(
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
                SlockUnifiedDialogListScrollPosition(index = 3, scrollOffset = 40)
            },
            scrollToItem = { index, scrollOffset ->
                events += "scroll"
                target = SlockUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )

        assertEquals(
            listOf("frame", "frame", "focused", "viewport", "position", "scroll"),
            events
        )
        assertEquals(SlockUnifiedDialogListScrollPosition(index = 3, scrollOffset = 89), target)
    }

    @Test
    fun focusedEditorVisibilityDoesNotScrollForClosedKeyboardOrVisibleEditor() = runBlocking {
        var frameCount = 0
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()
        val awaitFrame: suspend () -> Unit = { frameCount += 1 }
        val scrollToItem: suspend (Int, Int) -> Unit = { index, scrollOffset ->
            targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
        }

        slockUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 0f,
            awaitRelayoutFrame = awaitFrame,
            focusedBounds = { Rect(0f, 420f, 100f, 548f) },
            viewportBounds = { Rect(0f, 100f, 100f, 500f) },
            currentScrollPosition = {
                SlockUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = scrollToItem,
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )
        assertEquals(0, frameCount)

        slockUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = awaitFrame,
            focusedBounds = { Rect(0f, 180f, 100f, 280f) },
            viewportBounds = { Rect(0f, 100f, 100f, 500f) },
            currentScrollPosition = {
                SlockUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogKeepFocusedEditorVisible(
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
                SlockUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = { index, scrollOffset ->
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogKeepFocusedEditorVisible(
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
                SlockUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = { index, scrollOffset ->
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogKeepFocusedEditorVisible(
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
                SlockUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = { index, scrollOffset ->
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogKeepFocusedEditorVisible(
            keyboardHeight = 312f,
            awaitRelayoutFrame = { frameCount += 1 },
            focusedBounds = { Rect(0f, 420f, 100f, 548f) },
            viewportBounds = { Rect(0f, 100f, 100f, 500f) },
            currentScrollPosition = {
                SlockUnifiedDialogListScrollPosition(index = 2, scrollOffset = 24)
            },
            scrollToItem = { index, scrollOffset ->
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val coordinator = SlockFocusedEditorScrollRequestCoordinator()
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
        var target: SlockUnifiedDialogListScrollPosition? = null

        slockUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = {
                events += "frame"
            },
            scrollToItem = { index, scrollOffset ->
                events += "scroll"
                target = SlockUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true }
        )

        assertEquals(listOf("frame", "frame", "scroll"), events)
        assertEquals(SlockUnifiedDialogListScrollPosition(index = 3, scrollOffset = 0), target)
    }

    @Test
    fun targetItemScrollDoesNotScrollForClosedKeyboard() = runBlocking {
        var frameCount = 0
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogScrollToTargetItem(
            keyboardHeight = 0f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = { frameCount += 1 },
            scrollToItem = { index, scrollOffset ->
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogScrollToTargetItem(
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
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogScrollToTargetItem(
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
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogScrollToTargetItem(
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
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val coordinator = SlockFocusedEditorScrollRequestCoordinator()
        val generation = coordinator.begin()
        var frameCount = 0
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogScrollToTargetItem(
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
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
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
        val targets = mutableListOf<SlockUnifiedDialogListScrollPosition>()

        slockUnifiedDialogScrollToTargetItem(
            keyboardHeight = 312f,
            targetItemIndex = 3,
            targetItemScrollOffset = 0,
            awaitRelayoutFrame = { frameCount += 1 },
            scrollToItem = { index, scrollOffset ->
                targets += SlockUnifiedDialogListScrollPosition(index, scrollOffset)
            },
            isScrollInProgress = { false },
            isRequestCurrent = { true },
            isTargetActive = { false }
        )

        assertEquals(0, frameCount)
        assertEquals(emptyList(), targets)
    }
}

