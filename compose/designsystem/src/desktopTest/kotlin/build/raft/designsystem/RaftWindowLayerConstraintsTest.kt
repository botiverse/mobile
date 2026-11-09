package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import build.raft.designsystem.components.raftBoundedWindowLayer
import build.raft.designsystem.components.raftWindowLayerConstraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A window layer (every Raft dialog, sheet and the app's `Modal`) measured with unbounded
 * constraints: CMP iOS does that for a dialog layer whose scene has no size yet, and the image
 * lightbox's pager aborted the app (Hands f36226a4, 1.20.0, iPhone in landscape).
 */
@OptIn(ExperimentalTestApi::class)
class RaftWindowLayerConstraintsTest {
    @Test
    fun boundedConstraintsPassThroughUnchanged() {
        val constraints = Constraints(minWidth = 10, maxWidth = 800, minHeight = 0, maxHeight = 600)
        assertEquals(constraints, raftWindowLayerConstraints(constraints, IntSize(1, 1)))
    }

    @Test
    fun anUnboundedAxisTakesTheWindowSize() {
        val bounded = raftWindowLayerConstraints(Constraints(maxHeight = 600), IntSize(951, 669))
        assertEquals(Constraints(minWidth = 0, maxWidth = 951, minHeight = 0, maxHeight = 600), bounded)

        val both = raftWindowLayerConstraints(Constraints(), IntSize(951, 669))
        assertEquals(Constraints(minWidth = 0, maxWidth = 951, minHeight = 0, maxHeight = 669), both)
    }

    @Test
    fun noWindowSizeYetMeasuresNothing() {
        assertNull(raftWindowLayerConstraints(Constraints(), IntSize.Zero))
        assertNull(raftWindowLayerConstraints(Constraints(maxHeight = 600), IntSize(0, 669)))
    }

    @Composable
    private fun LightboxLikePager() {
        HorizontalPager(rememberPagerState { 3 }, Modifier.testTag("pager").fillMaxSize()) { page ->
            BasicText("Page $page")
        }
    }

    /** The production exception, so the guarded test below is known to cover it. */
    @Test
    fun aFullWindowPagerUnderUnboundedConstraintsThrowsWithoutTheGuard() {
        val error =
            assertFailsWith<IllegalStateException> {
                runComposeUiTest {
                    setContent {
                        Box(Modifier.wrapContentSize(unbounded = true)) { LightboxLikePager() }
                    }
                    waitForIdle()
                }
            }
        assertTrue(error.message.orEmpty().contains("infinity maximum width"), error.message)
    }

    @Test
    fun theGuardLaysTheLayerOutAtTheWindowSize() =
        runComposeUiTest {
            setContent {
                Box(Modifier.wrapContentSize(unbounded = true)) {
                    Box(Modifier.raftBoundedWindowLayer { IntSize(400, 300) }.fillMaxSize()) {
                        LightboxLikePager()
                    }
                }
            }
            waitForIdle()
            val size = onNodeWithTag("pager").fetchSemanticsNode().size
            assertEquals(IntSize(400, 300), size)
        }

    @Test
    fun theGuardSkipsTheLayerUntilAWindowSizeExists() =
        runComposeUiTest {
            setContent {
                Box(Modifier.wrapContentSize(unbounded = true)) {
                    Box(Modifier.raftBoundedWindowLayer { IntSize.Zero }.fillMaxSize()) {
                        LightboxLikePager()
                    }
                }
            }
            waitForIdle()
            // Composed, but not measured for this pass: no throw, nothing laid out.
            assertEquals(IntSize.Zero, onNodeWithTag("pager").fetchSemanticsNode().size)
        }
}
