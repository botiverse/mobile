package build.raft.designsystem.gesture

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An overlay (a conversation's Tasks / Files card) that the iOS back swipe dismisses. */
@OptIn(ExperimentalTestApi::class)
class RaftBackSwipeDismissUiTest {
    private var dismissed = 0
    private lateinit var row: ScrollState

    @Composable
    private fun Overlay(regions: RaftBackSwipeRegions?, open: Boolean = true) {
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalRaftBackSwipeRegions provides regions) {
            if (!open) return@CompositionLocalProvider
            val swipe = rememberRaftBackSwipeDismiss { dismissed++ }
            Box(Modifier.size(300.dp, 400.dp).then(swipe).testTag("overlay")) {
                row = rememberScrollState()
                Row(Modifier.width(300.dp).horizontalScroll(row).testTag("row")) {
                    repeat(10) { Text("chip $it", Modifier.width(80.dp)) }
                }
            }
        }
    }

    @Test
    fun anOpenOverlayBlocksThePagePopAndReleasesItWhenClosed() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        var open by mutableStateOf(true)
        setContent { Overlay(regions, open) }
        assertTrue(regions.contentConsumesBackSwipeAt(150f, 200f))
        open = false
        waitForIdle()
        assertFalse(regions.contentConsumesBackSwipeAt(150f, 200f))
    }

    @Test
    fun backSwipePastAThirdDismisses() = runComposeUiTest {
        setContent { Overlay(RaftBackSwipeRegions()) }
        onNodeWithTag("overlay").performTouchInput {
            down(Offset(20f, 300f))
            repeat(20) { moveBy(Offset(8f, 0f), delayMillis = 40) }
            up()
        }
        waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun shortSlowDragSlidesBackOpen() = runComposeUiTest {
        setContent { Overlay(RaftBackSwipeRegions()) }
        onNodeWithTag("overlay").performTouchInput {
            down(Offset(20f, 300f))
            repeat(10) { moveBy(Offset(4f, 0f), delayMillis = 60) }
            up()
        }
        waitForIdle()
        assertEquals(0, dismissed)
    }

    @Test
    fun verticalAndForwardDragsDoNotDismiss() = runComposeUiTest {
        setContent { Overlay(RaftBackSwipeRegions()) }
        onNodeWithTag("overlay").performTouchInput { swipeUp(startY = bottom - 20f, endY = top + 40f, durationMillis = 200) }
        onNodeWithTag("overlay").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 20f, durationMillis = 150) }
        waitForIdle()
        assertEquals(0, dismissed)
    }

    @Test
    fun aScrollerAwayFromItsStartKeepsTheBackDrag() = runComposeUiTest {
        setContent { Overlay(RaftBackSwipeRegions()) }
        onNodeWithTag("row").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 200) }
        waitForIdle()
        assertTrue(row.value > 0)
        onNodeWithTag("row").performTouchInput { swipeRight(startX = left + 10f, endX = left + 200f, durationMillis = 200) }
        waitForIdle()
        assertEquals(0, dismissed)
    }

    @Test
    fun inertWithoutAHostBackSwipe() = runComposeUiTest {
        setContent { Overlay(regions = null) }
        onNodeWithTag("overlay").performTouchInput { swipeRight(startX = 20f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertEquals(0, dismissed)
    }
}
