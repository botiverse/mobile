package build.raft.designsystem.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A swipe-paged gallery surface (file preview image gallery) on a host with a full-page back swipe. */
@OptIn(ExperimentalTestApi::class)
class RaftBackSwipePagingUiTest {
    private var movementConsumed = false
    private var lastDragEnd: Float? = null

    @Composable
    private fun Gallery(regions: RaftBackSwipeRegions?, canPageBackward: Boolean) {
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
                    .raftBackSwipePagingDrag(
                        canPageBackward = canPageBackward,
                        onDrag = {},
                        onDragEnd = { lastDragEnd = it },
                    )
                    .testTag("gallery"),
            )
        }
    }

    @Test
    fun backSwipeOnTheFirstImageIsLeftToTheHost() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { Gallery(regions, canPageBackward = false) }
        waitForIdle()
        assertFalse(regions.contentConsumesBackSwipeAt(150f, 200f), "the host may begin its pop")
        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 100f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertFalse(movementConsumed, "no rubber-band: the drag must stay unconsumed for the native pop")
        assertNull(lastDragEnd)
    }

    @Test
    fun pastTheFirstImageTheBodyPagesBackButTheEdgePops() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { Gallery(regions, canPageBackward = true) }
        waitForIdle()
        assertTrue(regions.contentConsumesBackSwipeAt(150f, 200f), "body: the gallery keeps the drag")
        assertFalse(regions.contentConsumesBackSwipeAt(20f, 200f), "edge strip: the host pops")

        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 100f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertTrue(movementConsumed)
        assertTrue(assertNotNull(lastDragEnd) > 56f, "pages back")

        movementConsumed = false
        lastDragEnd = null
        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 10f, endX = 200f, durationMillis = 150) }
        waitForIdle()
        assertFalse(movementConsumed, "an edge back swipe is left to the host")
        assertNull(lastDragEnd)
    }

    @Test
    fun forwardSwipePagesOnTheFirstImage() = runComposeUiTest {
        val regions = RaftBackSwipeRegions()
        setContent { Gallery(regions, canPageBackward = false) }
        onNodeWithTag("gallery").performTouchInput { swipeLeft(startX = 280f, endX = 100f, durationMillis = 150) }
        waitForIdle()
        assertTrue(assertNotNull(lastDragEnd) < -56f)
    }

    @Test
    fun withoutAHostBackSwipeTheFirstImageKeepsItsRubberBand() = runComposeUiTest {
        setContent { Gallery(regions = null, canPageBackward = false) }
        onNodeWithTag("gallery").performTouchInput { swipeRight(startX = 100f, endX = 280f, durationMillis = 150) }
        waitForIdle()
        assertTrue(movementConsumed)
        assertEquals(180f, assertNotNull(lastDragEnd), 1f)
    }
}
