package build.raft.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `Modifier.raftClickable` answers a real mouse (#wg-mobile-sync:#268): moving the pointer over
 * it lays the hover overlay, pressing lays the press overlay, and leaving puts it back to rest.
 * The screenshot case `ds.hover.raft-clickable` shows the looks; this drives the pointer.
 */
@OptIn(ExperimentalTestApi::class)
class RaftClickableHoverTest {
    private fun centrePixel(image: ImageBitmap): Color {
        val pixels = image.toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test
    fun aMouseHoversAndPressesAndLeaves() =
        runComposeUiTest {
            var clicks = 0
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = RaftPointerInput.Fine) {
                    Column(Modifier.background(RaftTheme.colors.layerCanvas)) {
                        Box(
                            Modifier.testTag("target")
                                .size(120.dp, 40.dp)
                                .raftClickable(onClick = { clicks++ }, tier = RaftHoverTier.Medium),
                        )
                        Box(Modifier.testTag("away").size(120.dp, 40.dp))
                    }
                }
            }
            val target = onNodeWithTag("target")
            val rest = centrePixel(target.captureToImage())

            target.performMouseInput { moveTo(center) }
            waitForIdle()
            val hovered = centrePixel(target.captureToImage())
            assertNotEquals(rest, hovered, "a mouse over it lays the hover overlay")

            target.performMouseInput { press() }
            waitForIdle()
            assertNotEquals(rest, centrePixel(target.captureToImage()), "pressed it shows the press overlay")
            target.performMouseInput { release() }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(1, clicks, "a click still clicks")

            onNodeWithTag("away").performMouseInput { moveTo(center) }
            waitForIdle()
            assertEquals(rest, centrePixel(target.captureToImage()), "leaving puts it back to rest")
        }
}
