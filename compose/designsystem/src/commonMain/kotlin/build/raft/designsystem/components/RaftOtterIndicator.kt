package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.designsystem.resources.Res
import build.raft.designsystem.resources.ottie_error
import build.raft.designsystem.resources.ottie_finish
import build.raft.designsystem.resources.ottie_process
import build.raft.designsystem.resources.ottie_wait
import org.jetbrains.compose.resources.imageResource
import kotlin.math.roundToInt

/**
 * raft-ui `OtterIndicator` (steps, raft-design `packages/ui/src/components/steps/otter-indicator.tsx`):
 * Ottie in one pose per [status], resting ([RaftStepStatus.Wait]), typing
 * ([RaftStepStatus.Process]), tilting its head with a question mark ([RaftStepStatus.Error]) or
 * after its hop ([RaftStepStatus.Finish]). Decorative (no semantics): the status is said in text
 * beside it. Give it [size] 40dp or more (raft-ui: "40px or larger").
 *
 * The artwork is raft-ui's own sheets, byte for byte (the WebP sheets in `ottie-assets`, 256px a frame: the
 * resting pose alone, the others three by two). Fixed brand art, the same in light and dark.
 *
 * Difference from raft-ui: the poses are still, as raft-ui draws them with reduced motion (the
 * rest, the typing pose's first frame, the head tilt's and the hop's last frame). The breathing,
 * typing loop and the one-time tilt and hop are not ported.
 */
@Composable
fun RaftOtterIndicator(
    status: RaftStepStatus,
    modifier: Modifier = Modifier,
    size: Dp = RaftOtterIndicatorDefaults.Size,
) {
    val sheet =
        imageResource(
            when (status) {
                RaftStepStatus.Wait -> Res.drawable.ottie_wait
                RaftStepStatus.Process -> Res.drawable.ottie_process
                RaftStepStatus.Error -> Res.drawable.ottie_error
                RaftStepStatus.Finish -> Res.drawable.ottie_finish
            }
        )
    val measurer = rememberTextMeasurer()
    // raft-ui's `otter-indicator` slot, for the visual-testing component comparison.
    Canvas(Modifier.raftSlot("otter-indicator").then(modifier).size(size).clearAndSetSemantics {}) {
        when (status) {
            // `.r-otter-indicator__rest`: the whole image, `contain`.
            RaftStepStatus.Wait -> drawOttieCell(sheet, columns = 1, rows = 1, column = 0, row = 0)
            // The base frame; the typing frames only move the paws over it.
            RaftStepStatus.Process -> drawOttieCell(sheet, column = 0, row = 0)
            // The last frame alone (no base under it).
            RaftStepStatus.Finish -> drawOttieCell(sheet, column = 2, row = 1)
            RaftStepStatus.Error -> {
                drawOttieCell(sheet, column = 0, row = 0)
                // The tilted head over the base: `mask-image: linear-gradient(black 0 58%, transparent 64%)`.
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, this.size), Paint())
                    drawOttieCell(sheet, column = 2, row = 1)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            0.58f to Color.Black,
                            0.64f to Color.Transparent,
                            startY = 0f,
                            endY = this.size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    canvas.restore()
                }
                // The question mark on its 48-unit box: x 40.3, baseline 11.4, 11.4 high, weight 800,
                // turned 10° about that point.
                val unit = this.size.width / 48f
                val anchor = Offset(40.3f * unit, 11.4f * unit)
                val layout =
                    measurer.measure(
                        text = "?",
                        style = TextStyle(
                            color = RaftOtterIndicatorDefaults.QuestionInk,
                            fontSize = (11.4f * unit).toSp(),
                            fontWeight = FontWeight.ExtraBold,
                        ),
                    )
                rotate(degrees = 10f, pivot = anchor) {
                    drawText(layout, topLeft = Offset(anchor.x, anchor.y - layout.firstBaseline))
                }
            }
        }
    }
}

object RaftOtterIndicatorDefaults {
    /** raft-ui: 40px or larger. */
    val Size: Dp = 40.dp

    /** `.r-otter-indicator__question` fill, oklch(0.57 0.09 75): part of the fixed art, not a theme colour. */
    val QuestionInk: Color = Color(0xFF966F35) // raft-color: fixed Ottie brand art (raft-ui otter-indicator.css)
}

/** One frame of a [columns] by [rows] sheet (`background-size: 300% 200%`), scaled to the whole box. */
private fun DrawScope.drawOttieCell(
    sheet: ImageBitmap,
    column: Int,
    row: Int,
    columns: Int = 3,
    rows: Int = 2,
) {
    val cellWidth = sheet.width / columns
    val cellHeight = sheet.height / rows
    // An image not decoded yet is an empty bitmap: nothing to draw.
    if (cellWidth <= 0 || cellHeight <= 0) return
    drawImage(
        image = sheet,
        srcOffset = IntOffset(column * cellWidth, row * cellHeight),
        srcSize = IntSize(cellWidth, cellHeight),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}
