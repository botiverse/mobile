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
import androidx.compose.ui.unit.IntSize
import build.raft.designsystem.components.raftBoundedWindowLayer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A window layer (every Raft dialog, sheet and the app's `Modal`) measured with unbounded
 * constraints: CMP iOS does that for a dialog layer whose scene has no size yet, and the image
 * lightbox's pager aborted the app (Hands f36226a4, 1.20.0, iPhone in landscape).
 */
@OptIn(ExperimentalTestApi::class)
class RaftWindowLayerConstraintsTest {
    @Composable
    private fun LightboxLikePager() {
        HorizontalPager(rememberPagerState { 3 }, Modifier.testTag("pager").fillMaxSize()) { page ->
            BasicText("Page $page")
        }
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
}
