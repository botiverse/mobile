package build.raft.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.components.LocalRaftFrameInsets
import build.raft.designsystem.components.LocalRaftTooltipPreview
import build.raft.designsystem.components.LocalRaftTooltipSide
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.RaftTooltipPlacement
import build.raft.designsystem.components.RaftTooltipSide
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * artin: "tooltip 的小三角形，要晚一帧才出现". The first frame that draws a tooltip's bubble must
 * already draw its caret: rendered frame by frame, the bubble-coloured pixels of the first frame
 * with any are those of the settled tooltip, caret included, for every way it can open.
 */
class RaftTooltipCaretFrameTest {
    @Test
    fun aboveItsAnchorTheCaretComesWithTheBubble() = assertCaretInFirstFrame("above") { Anchor() }

    @Test
    fun belowItsAnchorTheCaretComesWithTheBubble() =
        assertCaretInFirstFrame("below") { Anchor(placement = RaftTooltipPlacement.Below) }

    @Test
    fun besideItsAnchorTheCaretComesWithTheBubble() =
        assertCaretInFirstFrame("beside") {
            CompositionLocalProvider(LocalRaftTooltipSide provides RaftTooltipSide.Right) { Anchor() }
        }

    @Test
    fun movedOutOfTheFrameInsetsTheCaretComesWithTheBubble() =
        assertCaretInFirstFrame("framed") {
            // Under a 64dp top band: it opens below, out of the band.
            CompositionLocalProvider(LocalRaftFrameInsets provides RaftFrameInsets(top = 64.dp)) { Anchor(top = 72) }
        }

    @Composable
    private fun Anchor(placement: RaftTooltipPlacement = RaftTooltipPlacement.Above, top: Int = 104) {
        RaftTooltip(text = "Notifications", placement = placement, modifier = Modifier.padding(start = 164.dp, top = top.dp)) {
            Box(Modifier.size(32.dp).background(Color.Red))
        }
    }

    private fun assertCaretInFirstFrame(name: String, content: @Composable () -> Unit) {
        var hud = Color.Unspecified
        val frames =
            ImageComposeScene(width = (WIDTH_DP * DENSITY).toInt(), height = (HEIGHT_DP * DENSITY).toInt(), density = Density(DENSITY)) {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    hud = RaftTheme.colors.layerHud
                    CompositionLocalProvider(LocalRaftTooltipPreview provides true) {
                        Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) { content() }
                    }
                }
            }.use { scene ->
                List(FRAMES) { frame ->
                    val png = scene.render(frame * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
                    ImageIO.read(ByteArrayInputStream(png))
                }
            }
        val masks = frames.map { it.hudMask(hud.toArgb()) }
        val settled = masks.last()
        assertTrue(settled.count { it } > 0, "$name: the tooltip never showed")
        val first = masks.indexOfFirst { mask -> mask.any { it } }
        val differing = masks[first].indices.count { masks[first][it] != settled[it] }
        assertTrue(
            differing <= MAX_DIFFERING_PIXELS,
            "$name: frame $first is the first to draw the tooltip, but $differing of its bubble-coloured pixels " +
                "differ from the settled tooltip's (${masks[first].count { it }} vs ${settled.count { it }}): " +
                "its caret is missing or elsewhere",
        )
    }

    /** The pixels in the bubble's own colour (opaque, every channel within 2). */
    private fun BufferedImage.hudMask(hud: Int): BooleanArray =
        BooleanArray(width * height) { i ->
            val p = getRGB(i % width, i / width)
            p ushr 24 == 0xFF && (0..2).all { c -> abs((p shr (c * 8) and 0xFF) - (hud shr (c * 8) and 0xFF)) <= 2 }
        }

    private companion object {
        const val DENSITY = 2f
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 240
        const val FRAMES = 8
        const val FRAME_NANOS = 16_000_000L

        // Anti-aliasing slack only: a missing 10 x 5.25dp caret is about 100 pixels at 2x.
        const val MAX_DIFFERING_PIXELS = 6
    }
}
