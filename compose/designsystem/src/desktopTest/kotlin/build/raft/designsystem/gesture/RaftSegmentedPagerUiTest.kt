package build.raft.designsystem.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSegmentedPager
import build.raft.designsystem.components.RaftSegmentedPagerState
import build.raft.designsystem.components.rememberRaftSegmentedPagerState
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [RaftSegmentedPager] (#proj-mobile:#257): a segmented switch over a pager that follows the finger,
 * alone, inside an outer tab pager wired as `TabPager` is (nested-drag host), and on an iOS-style
 * back-swipe host. Density 1: 1dp = 1px; the segmented pager is 300 x 400.
 */
@OptIn(ExperimentalTestApi::class)
class RaftSegmentedPagerUiTest {
    private lateinit var state: RaftSegmentedPagerState
    private var outer: PagerState? = null
    private var selected by mutableIntStateOf(0)
    private var movementConsumed = false

    @Composable
    private fun Segments(initial: Int) {
        state = rememberRaftSegmentedPagerState(initialIndex = initial) { Labels.size }
        RaftSegmentedPager(
            state = state,
            selectedIndex = selected,
            onSelectedIndexChange = { selected = it },
            modifier = Modifier.fillMaxSize(),
            header = {
                RaftSegmentedControl(Modifier.testTag(SwitchTag), selectionPosition = { state.position }) {
                    Labels.forEachIndexed { index, label ->
                        RaftSegment(label, selected = index == selected, onClick = { selected = index })
                    }
                }
            },
        ) { page ->
            Box(Modifier.fillMaxSize().testTag("page-$page")) { RaftText("Page ${Labels[page]}") }
        }
    }

    @Composable
    private fun Host(regions: RaftBackSwipeRegions? = null, outerPager: Boolean = false, initial: Int = 0) {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalRaftBackSwipeRegions provides regions) {
                Box(
                    Modifier
                        .size(300.dp, 400.dp)
                        // What the iOS Compose touch recogniser uses to claim a gesture against the pop.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    if (event.changes.any { it.isConsumed && it.position != it.previousPosition }) {
                                        movementConsumed = true
                                    }
                                }
                            }
                        }
                        .testTag(HostTag),
                ) {
                    if (outerPager) {
                        // The outer tab pager as TabPager builds it on Android: its own drag, a
                        // nested-drag host that lets the segments decide first.
                        val pager = rememberPagerState(initialPage = 1) { 3 }
                        outer = pager
                        val drags = rememberRaftNestedHorizontalDragHost()
                        Box(Modifier.fillMaxSize().then(drags.modifier)) {
                            drags.ProvideScrollerConfiguration {
                                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                                    drags.ProvideContent {
                                        if (page == 1) Segments(initial) else Box(Modifier.fillMaxSize().testTag("outer-$page"))
                                    }
                                }
                            }
                        }
                    } else {
                        Segments(initial)
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.show(regions: RaftBackSwipeRegions? = null, outerPager: Boolean = false, initial: Int = 0) {
        selected = initial
        setContent { Host(regions, outerPager, initial) }
        waitForIdle()
    }

    /** The x range of the yellow sticker on the switch's middle line, in root px. */
    private fun ComposeUiTest.stickerSpan(): ClosedFloatingPointRange<Float> {
        val switch = onNodeWithTag(SwitchTag).getBoundsInRoot()
        val y = ((switch.top + switch.bottom).value / 2f).toInt()
        val pixels = onRoot().captureToImage().toPixelMap()
        val yellow = (0 until pixels.width).filter { x -> pixels[x, y].near(RaftUiPalette.selected) }
        assertTrue(yellow.isNotEmpty(), "no sticker on the switch")
        return yellow.first().toFloat()..yellow.last().toFloat()
    }

    private fun Color.near(other: Color): Boolean =
        abs(red - other.red) <= 8f / 255f && abs(green - other.green) <= 8f / 255f && abs(blue - other.blue) <= 8f / 255f

    @Test
    fun aDragMovesTheContentAndTheStickerWithTheFinger() = runComposeUiTest {
        show()
        val first = onNodeWithText(Labels[0]).getBoundsInRoot()
        val second = onNodeWithText(Labels[1]).getBoundsInRoot()
        onNodeWithTag(HostTag).performTouchInput {
            down(Offset(250f, 300f))
            repeat(8) { moveBy(Offset(-15f, 0f), delayMillis = 16) }
        }
        waitForIdle()
        val position = state.position
        assertTrue(position > 0.15f && position < 0.45f, "mid-drag position: $position")
        // The content follows: the first page is moved left by that much, the second is in view.
        val page0 = onNodeWithTag("page-0").getUnclippedBoundsInRoot()
        assertEquals(-position * 300f, page0.left.value, 2f)
        onNodeWithTag("page-1").assertExists()
        // The sticker is between the two segments by the same fraction.
        val span = stickerSpan()
        val expectedCenter =
            ((first.left + first.right).value / 2f) * (1 - position) + ((second.left + second.right).value / 2f) * position
        assertEquals(expectedCenter, (span.start + span.endInclusive) / 2f, 3f, "sticker centre mid-drag")
        assertEquals(0, selected, "nearer the first segment: still selected")
        onNodeWithTag(HostTag).performTouchInput { up() }
        waitForIdle()
        assertEquals(0f, state.position % 1f, "settled on a segment")
    }

    @Test
    fun aSlowReleasePastHalfwaySnapsToTheNextSegment() = runComposeUiTest {
        show()
        onNodeWithTag(HostTag).performTouchInput {
            down(Offset(280f, 300f))
            repeat(20) { moveBy(Offset(-10f, 0f), delayMillis = 60) }
            // Hold still, so the release carries no fling.
            moveBy(Offset(0f, 0f), delayMillis = 200)
            up()
        }
        waitForIdle()
        assertEquals(1, state.currentIndex)
        assertEquals(1f, state.position)
        assertEquals(1, selected)
        onNodeWithText(Labels[1]).assertIsSelected()
        val span = stickerSpan()
        val second = onNodeWithText(Labels[1]).getBoundsInRoot()
        assertEquals((second.left + second.right).value / 2f, (span.start + span.endInclusive) / 2f, 2f)
    }

    @Test
    fun aSlowShortDragSnapsBack() = runComposeUiTest {
        show()
        onNodeWithTag(HostTag).performTouchInput {
            down(Offset(280f, 300f))
            repeat(10) { moveBy(Offset(-8f, 0f), delayMillis = 60) }
            moveBy(Offset(0f, 0f), delayMillis = 200)
            up()
        }
        waitForIdle()
        assertEquals(0f, state.position)
        assertEquals(0, selected)
    }

    @Test
    fun aTapOnASegmentSlidesTheContentToIt() = runComposeUiTest {
        show()
        mainClock.autoAdvance = false
        onNodeWithText(Labels[2]).performClick()
        mainClock.advanceTimeBy(150)
        val midway = state.position
        assertTrue(midway > 0f && midway < 2f, "mid-slide position: $midway")
        mainClock.advanceTimeBy(1_000)
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(2f, state.position)
        assertEquals(2, selected, "the slide past the middle segment reports nothing on the way")
        onNodeWithText(Labels[2]).assertIsSelected()
    }

    @Test
    fun aSwipeOnTheSwitchItselfTurnsTheSegment() = runComposeUiTest {
        show()
        val switch = onNodeWithTag(SwitchTag).getBoundsInRoot()
        onNodeWithTag(SwitchTag).performTouchInput {
            swipeLeft(startX = width - 4f, endX = 4f, durationMillis = 150)
        }
        waitForIdle()
        assertTrue(switch.right.value > 100f, "the switch is wide enough to swipe")
        assertEquals(1, state.currentIndex)
        assertEquals(1, selected)
    }

    @Test
    fun segmentsTurnFirstAndOnlyTheLastHandsAForwardDragToTheOuterPager() = runComposeUiTest {
        show(outerPager = true, initial = 1)
        onNodeWithTag(HostTag).performTouchInput { swipeLeft(startX = 280f, endX = 20f, durationMillis = 150) }
        waitForIdle()
        assertEquals(2, state.currentIndex, "the swipe turns the segment")
        assertEquals(1, outer!!.currentPage, "the outer pager stays put")

        onNodeWithTag(HostTag).performTouchInput { swipeLeft(startX = 280f, endX = 20f, durationMillis = 150) }
        waitForIdle()
        assertEquals(2, outer!!.currentPage, "past the last segment the swipe reaches the outer pager")
    }

    @Test
    fun theFirstSegmentHandsABackDragToTheOuterPager() = runComposeUiTest {
        show(outerPager = true, initial = 0)
        onNodeWithTag(HostTag).performTouchInput { swipeRight(startX = 20f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertEquals(0, outer!!.currentPage)
    }

    @Test
    fun onABackSwipeHostAnEdgeStripBackDragIsLeftToThePop() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        show(regions = regions, initial = 1)
        assertTrue(regions.contentConsumesBackSwipeAt(150f, 300f), "body: the segments keep a back drag")
        assertFalse(regions.contentConsumesBackSwipeAt(20f, 300f), "edge strip: the host pops")

        onNodeWithTag(HostTag).performTouchInput { swipeRight(startX = 10f, endX = 250f, durationMillis = 150) }
        waitForIdle()
        assertFalse(movementConsumed, "a drag from the strip stays unconsumed for the native pop")
        assertEquals(1, state.currentIndex)

        onNodeWithTag(HostTag).performTouchInput { swipeRight(startX = 100f, endX = 290f, durationMillis = 150) }
        waitForIdle()
        assertTrue(movementConsumed)
        assertEquals(0, state.currentIndex, "outside the strip a back drag turns the segment")
        assertFalse(regions.contentConsumesBackSwipeAt(150f, 300f), "on the first segment the host may pop")
    }

    @Test
    fun withoutABackSwipeHostTheStripTurnsSegmentsToo() = runComposeUiTest {
        show(initial = 1)
        onNodeWithTag(HostTag).performTouchInput { swipeRight(startX = 10f, endX = 250f, durationMillis = 150) }
        waitForIdle()
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun onlyTheVisibleSegmentIsComposedAtRest() = runComposeUiTest {
        show(initial = 1)
        onNodeWithTag("page-1").assertExists()
        onNodeWithTag("page-0").assertDoesNotExist()
        onNodeWithTag("page-2").assertDoesNotExist()
        assertNotNull(state)
    }

    private companion object {
        const val HostTag = "host"
        const val SwitchTag = "switch"
        val Labels = listOf("Overview", "Raw Log", "Subagents")
    }
}
