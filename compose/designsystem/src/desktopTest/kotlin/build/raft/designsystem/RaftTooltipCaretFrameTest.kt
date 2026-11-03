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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * artin: "tooltip 的小三角形，要晚一帧才出现". The first frame that draws a tooltip's bubble must
 * already draw its caret, where the settled tooltip has it, for every way it can open: opened by a
 * hovering mouse (fading and growing in) and opened from the start (screenshot preview).
 *
 * Rendered frame by frame on the canvas; in each frame the tooltip is the pixels in the bubble's
 * colour at the frame's opacity. Of the four edges of their bounding box, the caret's is the one
 * where only a narrow run of them touches it (the caret's tip); without a caret every edge is the
 * bubble's long straight side. That holds whatever the entrance animation's opacity and scale.
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
        assertCaretInFirstFrame("framed", anchorTop = 72) {
            // Under a 64dp top band: it opens below, out of the band.
            CompositionLocalProvider(LocalRaftFrameInsets provides RaftFrameInsets(top = 64.dp)) { Anchor(top = 72) }
        }

    @Composable
    private fun Anchor(placement: RaftTooltipPlacement = RaftTooltipPlacement.Above, top: Int = ANCHOR_TOP) {
        RaftTooltip(text = "Notifications", placement = placement, modifier = Modifier.padding(start = ANCHOR_START.dp, top = top.dp)) {
            // Green: never mistaken for the bubble's grey.
            Box(Modifier.size(ANCHOR_SIZE.dp).background(Color(0xFF00C000)))
        }
    }

    private fun assertCaretInFirstFrame(name: String, anchorTop: Int = ANCHOR_TOP, content: @Composable () -> Unit) {
        for (hovered in listOf(true, false)) {
            val how = if (hovered) "$name, hovered" else "$name, preview"
            val (frames, colors) = render(preview = !hovered, anchorTop = anchorTop, content = content)
            val tooltips = frames.map { it.tooltip(colors) }
            val settled = tooltips.last()
            assertTrue(settled.opacity > 0.95f, "$how: the tooltip never settled (${tooltips.map { it.describe() }})")
            val caret = settled.narrowEdges()
            assertEquals(1, caret.size, "$how: the settled tooltip should have one caret, has ${settled.describe()}")
            val first = tooltips.indexOfFirst { it.opacity >= MIN_OPACITY }
            assertEquals(
                caret,
                tooltips[first].narrowEdges(),
                "$how: frame $first is the first to draw the tooltip; frames: ${tooltips.map { it.describe() }}",
            )
        }
    }

    private class Colors(val canvas: Int, val hud: Int)

    private fun render(preview: Boolean, anchorTop: Int, content: @Composable () -> Unit): Pair<List<BufferedImage>, Colors> {
        var canvas = Color.Unspecified
        var hud = Color.Unspecified
        val frames =
            ImageComposeScene(width = (WIDTH_DP * DENSITY).toInt(), height = (HEIGHT_DP * DENSITY).toInt(), density = Density(DENSITY)) {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    canvas = RaftTheme.colors.layerCanvas
                    hud = RaftTheme.colors.layerHud
                    CompositionLocalProvider(LocalRaftTooltipPreview provides preview) {
                        Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) { content() }
                    }
                }
            }.use { scene ->
                var time = 0L
                fun frame(): BufferedImage {
                    val png = scene.render(time).encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed")
                    time += FRAME_NANOS
                    return ImageIO.read(ByteArrayInputStream(png))
                }
                frame()
                frame()
                if (!preview) {
                    val centre = Offset((ANCHOR_START + ANCHOR_SIZE / 2) * DENSITY, (anchorTop + ANCHOR_SIZE / 2) * DENSITY)
                    scene.sendPointerEvent(PointerEventType.Move, centre, type = PointerType.Mouse)
                }
                List(FRAMES) { frame() }
            }
        return frames to Colors(canvas.toArgb(), hud.toArgb())
    }

    /** The tooltip in one frame: its pixels, in the bubble's colour at [opacity] over the canvas. */
    private class Tooltip(val width: Int, val height: Int, val mask: BooleanArray, val opacity: Float) {
        private val set = mask.indices.filter { mask[it] }
        val left = set.minOfOrNull { it % width } ?: 0
        val right = set.maxOfOrNull { it % width } ?: -1
        val top = set.minOfOrNull { it / width } ?: 0
        val bottom = set.maxOfOrNull { it / width } ?: -1

        private fun row(y: Int) = (left..right).count { mask[y * width + it] }

        private fun column(x: Int) = (top..bottom).count { mask[it * width + x] }

        /** The edges of the bounding box only a narrow run touches: the caret's. */
        fun narrowEdges(): Set<String> {
            if (right < left) return emptySet()
            val w = right - left + 1
            val h = bottom - top + 1
            return buildSet {
                if (row(top) < w * NARROW) add("top")
                if (row(bottom) < w * NARROW) add("bottom")
                if (column(left) < h * NARROW) add("left")
                if (column(right) < h * NARROW) add("right")
            }
        }

        fun describe() = "[opacity %.2f, %d..%d x %d..%d, caret %s]".format(opacity, left, right, top, bottom, narrowEdges())
    }

    private fun BufferedImage.tooltip(colors: Colors): Tooltip {
        val n = width * height
        val pixels = getRGB(0, 0, width, height, null, 0, width)
        val coverage = FloatArray(n) { hudCoverage(pixels[it], colors) }
        val opacity = coverage.max().coerceAtLeast(0f)
        val mask = BooleanArray(n) { opacity >= MIN_OPACITY && coverage[it] >= opacity * CORE }
        return Tooltip(width, height, mask, opacity)
    }

    /** How much of the bubble's colour [pixel] is over the canvas, or -1 when it is some other colour. */
    private fun hudCoverage(pixel: Int, colors: Colors): Float {
        if (pixel or OPAQUE == colors.canvas or OPAQUE) return 0f
        var sum = 0f
        var count = 0
        for (shift in SHIFTS) {
            val c = colors.canvas shr shift and 0xFF
            val h = colors.hud shr shift and 0xFF
            if (abs(c - h) >= 40) {
                sum += (c - (pixel shr shift and 0xFF)).toFloat() / (c - h)
                count++
            }
        }
        if (count == 0) return -1f
        val a = sum / count
        if (a < 0f || a > 1.05f) return -1f
        for (shift in SHIFTS) {
            val c = colors.canvas shr shift and 0xFF
            val h = colors.hud shr shift and 0xFF
            if (abs((pixel shr shift and 0xFF) - (c + a * (h - c))) > 3f) return -1f
        }
        return a
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24
        val SHIFTS = intArrayOf(16, 8, 0)
        const val DENSITY = 2f
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 240
        const val ANCHOR_START = 164
        const val ANCHOR_TOP = 104
        const val ANCHOR_SIZE = 32
        const val FRAMES = 30
        const val FRAME_NANOS = 16_000_000L

        /** A frame shows the tooltip from this opacity on. */
        const val MIN_OPACITY = 0.1f

        /** The tooltip's own pixels are at least this share of its frame's opacity (not its shadow). */
        const val CORE = 0.85f

        /** An edge touched along less than this share of its length is the caret's tip. */
        const val NARROW = 0.3f
    }
}
