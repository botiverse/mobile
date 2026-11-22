package build.raft.designsystem.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A [HorizontalPager] wired like `TabPager` on a host with a full-page back swipe. */
@OptIn(ExperimentalTestApi::class)
class RaftBackSwipeUiTest {
    private lateinit var pager: PagerState

    @Test
    fun regionsUnregisterOnDispose() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        var shown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalRaftBackSwipeRegions provides regions) {
                if (shown) {
                    Box(Modifier.size(100.dp).raftKeepsBackSwipe())
                    RaftBlockBackSwipe()
                }
            }
        }
        waitForIdle()
        assertEquals(2, regions.regionCount)
        assertTrue(regions.contentConsumesBackSwipeAt(500f, 500f), "an open overlay blocks the whole page")
        shown = false
        waitForIdle()
        assertEquals(0, regions.regionCount)
        assertFalse(regions.contentConsumesBackSwipeAt(10f, 10f))
    }

    @Test
    fun unplacedAndInactiveNodesAreNotReported() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        var active by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalRaftBackSwipeRegions provides regions) {
                // Measured, never placed.
                Layout(content = { Box(Modifier.size(100.dp).raftKeepsBackSwipe()) }) { measurables, constraints ->
                    measurables.forEach { it.measure(constraints) }
                    layout(100, 100) {}
                }
                CompositionLocalProvider(LocalRaftBackSwipeActive provides active) {
                    Box(Modifier.size(50.dp).raftKeepsBackSwipe())
                }
            }
        }
        waitForIdle()
        assertEquals(2, regions.regionCount)
        assertFalse(regions.contentConsumesBackSwipeAt(80f, 80f), "an unplaced node has no bounds")
        assertTrue(regions.contentConsumesBackSwipeAt(20f, 20f))
        active = false
        waitForIdle()
        assertFalse(regions.contentConsumesBackSwipeAt(20f, 20f), "inactive kept-alive content never reports")
    }
}
