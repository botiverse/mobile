package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A face in the design-system sticker ([RaftAppAvatarSticker]: attachment comments, Computers,
 * the agent avatar picker) draws as the same face in the app avatar ([RaftAppAvatar]: message
 * rows, lists). The sticker's face once kept the app avatar's 4dp corner inside raft-ui's 2dp
 * image slot, so the sticker's fill showed in the four inner corners against the ink edge.
 * Compared inside the 1.5dp ink edge (the face's own area), colour against colour: the two
 * frames anti-alias the edge's arcs a step apart.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class RaftAppAvatarStickerCornersUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun stickerFaceFillsItsCornersAsTheAppAvatarDoes() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Row(Modifier.background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
                        RaftAppAvatarSticker(
                            kind = RaftAvatarKind.Agent,
                            avatarWireValue = "pixel:antenna",
                            label = "Niko",
                            size = RaftAvatarSize.Md,
                            modifier = Modifier.testTag("sticker"),
                        )
                        RaftAppAvatar(
                            kind = RaftAvatarKind.Agent,
                            avatarWireValue = "pixel:antenna",
                            label = "Niko",
                            avatarSize = RaftAvatarSize.Md.edge,
                            modifier = Modifier.padding(start = 16.dp).testTag("app"),
                        )
                    }
                }
            }
            val sticker = onNodeWithTag("sticker", useUnmergedTree = true).captureToImage()
            val app = onNodeWithTag("app", useUnmergedTree = true).captureToImage()
            assertEquals(app.width to app.height, sticker.width to sticker.height, "the two faces are the same size")
            val inset = ceil(RaftAppStroke.Control.value * density.density).toInt()
            val differing = differingPixels(sticker, app, inset)
            assertTrue(differing.isEmpty(), "the sticker differs from the app avatar at ${differing.take(12)} (of ${differing.size})")
        }

    /** Pixels (x, y), [inset] in from the edge, whose any channel differs by more than 64/255. */
    private fun differingPixels(a: ImageBitmap, b: ImageBitmap, inset: Int): List<Pair<Int, Int>> {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        val out = mutableListOf<Pair<Int, Int>>()
        for (y in inset until a.height - inset) {
            for (x in inset until a.width - inset) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                val d = maxOf(abs(ca.red - cb.red), abs(ca.green - cb.green), abs(ca.blue - cb.blue), abs(ca.alpha - cb.alpha))
                if (d > 64f / 255f) out += x to y
            }
        }
        return out
    }
}
