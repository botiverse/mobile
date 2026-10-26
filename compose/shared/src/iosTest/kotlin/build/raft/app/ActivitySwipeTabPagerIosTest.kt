package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.designsystem.gesture.LocalRaftBackSwipeRegions
import build.raft.designsystem.gesture.RaftBackSwipeRegions
import androidx.compose.ui.text.font.FontFamily
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftFonts
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS 1.15.5 (artin): swiping an Activity row left did nothing; the whole list slid toward the next
 * filter page instead. Runs the real filter [TabPager] and [ActivitySwipeActionsBox] on the simulator,
 * on a host with a full-page back swipe as RaftIosPage provides it, and drags with small finger steps.
 * raft-design #793 adds the swipe toward the end (Read / Unread): the pager must not take that one either.
 * Activity itself no longer pages its filters (slock #11383: a Filter menu); this keeps guarding
 * [TabPager] against swipe rows for the pagers that remain.
 */
@OptIn(ExperimentalTestApi::class)
class ActivitySwipeTabPagerIosTest {
    private var openKey by mutableStateOf<String?>(null)
    private var openSide by mutableStateOf<ActivitySwipeSide?>(null)
    private var toggles by mutableStateOf(0)
    private var selectedPage by mutableStateOf(0)

    private fun ComposeUiTest.setActivityPager() {
        setContent {
            CompositionLocalProvider(
                LocalRaftBackSwipeRegions provides RaftBackSwipeRegions(),
                // The test binary bundles no MR strings.
                LocalActivitySwipeButtonTestLabels provides { it.name },
            ) {
                // System faces: the test binary does not bundle the design system's font resources.
                RaftTheme(appearance = RaftAppearance.Light, fonts = RaftFonts(FontFamily.Default, FontFamily.Monospace)) {
                    TabPager(
                        selectedIndex = selectedPage,
                        onSelectedIndexChange = { selectedPage = it },
                        pageCount = 4,
                        gesturePolicy = TabPagerGesturePolicy(startEdgeInset = raftPlatformTabPagerStartEdgeInsetDp().dp),
                        keepItemAlive = true,
                        modifier = Modifier.size(390.dp, 640.dp),
                    ) { page, _ ->
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(12) { index ->
                                val key = "p$page-$index"
                                ActivitySwipeActionsBox(
                                    gestureKey = key,
                                    buttons = listOf(ActivitySwipeButton.Done),
                                    openSide = openSide.takeIf { openKey == key },
                                    onOpenChange = { side ->
                                        if (side != null) {
                                            openKey = key
                                            openSide = side
                                        } else if (openKey == key) {
                                            openKey = null
                                            openSide = null
                                        }
                                    },
                                    onDone = {},
                                    onAction = {},
                                    // raft-design #793: the swipe toward the end uncovers Read.
                                    startButton = ActivitySwipeButton.Read,
                                    onToggleRead = { toggles += 1 },
                                ) {
                                    Box(Modifier.fillMaxWidth().height(72.dp).testTag("row-$key"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A finger drag from [startXFraction] of the row's width, [steps] moves of [stepPx]. */
    private fun ComposeUiTest.dragRow(tag: String, startXFraction: Float, stepPx: Float, steps: Int) {
        onNodeWithTag(tag).performTouchInput {
            down(Offset(width * startXFraction, height / 2f))
            repeat(steps) { moveBy(Offset(stepPx, 0f), delayMillis = 8) }
            up()
        }
        waitForIdle()
    }

    @Test
    fun leftSwipeOnARowUncoversItsButtonsAndLeavesTheFilterPage() = runComposeUiTest {
        setActivityPager()
        dragRow("row-p0-2", startXFraction = 0.6f, stepPx = -3f, steps = 60)
        assertEquals("p0-2", openKey, "the row opened on its buttons")
        assertEquals(0, selectedPage, "the filter pager did not move")
        val buttonsPx = with(density) { ActivitySwipeButtonWidth.toPx() }
        val rowX = onNodeWithTag("row-p0-2").fetchSemanticsNode().positionInRoot.x
        assertTrue(abs(rowX + buttonsPx) < 2f, "the row slid by the Done button's width (x=$rowX, buttons=$buttonsPx)")
    }

    @Test
    fun leftSwipeOnARowPastTheFirstFilterPageOpensItToo() = runComposeUiTest {
        selectedPage = 1
        setActivityPager()
        waitForIdle()
        dragRow("row-p1-3", startXFraction = 0.6f, stepPx = -3f, steps = 60)
        assertEquals("p1-3", openKey)
        assertEquals(1, selectedPage)
    }

    @Test
    fun rightSwipeOnARowUncoversReadAndLeavesTheFilterPage() = runComposeUiTest {
        // From the middle of a row on the second filter page, where a rightward page swipe would go back.
        selectedPage = 1
        setActivityPager()
        waitForIdle()
        dragRow("row-p1-2", startXFraction = 0.4f, stepPx = 3f, steps = 30)
        assertEquals("p1-2", openKey, "the row opened on its start button")
        assertEquals(ActivitySwipeSide.Start, openSide)
        assertEquals(1, selectedPage, "the filter pager did not move")
        assertEquals(0, toggles, "opening is not toggling")
        val buttonPx = with(density) { ActivitySwipeButtonWidth.toPx() }
        val rowX = onNodeWithTag("row-p1-2").fetchSemanticsNode().positionInRoot.x
        assertTrue(abs(rowX - buttonPx) < 2f, "the row slid right by the Read button's width (x=$rowX, button=$buttonPx)")
    }

    @Test
    fun aFullRightPullTogglesReadInPlaceWithoutTurningThePage() = runComposeUiTest {
        selectedPage = 1
        setActivityPager()
        waitForIdle()
        // 3px steps over 75% of the row: past 60% at the release.
        val widthPx = with(density) { 390.dp.toPx() }
        val steps = (widthPx * 0.75f / 3f).toInt()
        dragRow("row-p1-3", startXFraction = 0.2f, stepPx = 3f, steps = steps)
        assertEquals(1, toggles, "the release past 60% toggled once")
        assertNull(openKey, "the row closed again")
        assertEquals(1, selectedPage, "the filter pager did not move")
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val rowX = onNodeWithTag("row-p1-3").fetchSemanticsNode().positionInRoot.x
        assertTrue(abs(rowX) < 2f, "the row is back in place (x=$rowX)")
    }

    @Test
    fun aDragFromTheRowsTrailingEdgeStillTurnsTheFilterPage() = runComposeUiTest {
        // The row never claims from its edge zones, so the pager keeps its own slop there.
        setActivityPager()
        dragRow("row-p0-4", startXFraction = 0.95f, stepPx = -12f, steps = 40)
        assertNull(openKey, "a drag from the trailing edge zone is not the row's")
        assertTrue(selectedPage >= 1, "the pager turned toward the next filters (page $selectedPage)")
    }
}
