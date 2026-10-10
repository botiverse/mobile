package build.raft.app.debug

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.rememberScrollState
import com.tencent.kuikly.compose.foundation.verticalScroll
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.PathEffect
import com.tencent.kuikly.compose.ui.graphics.StrokeCap
import com.tencent.kuikly.compose.ui.graphics.drawscope.DrawScope
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.graphics.drawscope.clipRect
import com.tencent.kuikly.compose.ui.graphics.drawscope.drawText
import com.tencent.kuikly.compose.ui.graphics.drawscope.withTransform
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

/**
 * Verification-only carrier for the Kuikly 2.28.0-raft.11 canvas restore fix.
 *
 * Same five cases as `ComposeCanvasStateDemo` in the KuiklyUI fork demo
 * (botiverse/KuiklyUI staging4 7deab841, patch
 * feat_demo_add_a_compose_canvas_state_demo_for_save_restore). It lives on the
 * non-production branch only because the fork demo HAP cannot be signed with
 * the Raft profile (different bundle). Open it by page name.
 */
const val COMPOSE_CANVAS_STATE_DEMO_PAGE = "ComposeCanvasStateDemo"

@Page(COMPOSE_CANVAS_STATE_DEMO_PAGE, supportInLocal = true)
internal class ComposeCanvasStateDemoPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { CanvasStateScreen() }
    }
}

@Composable
private fun CanvasStateScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Canvas state after restore (Kuikly raft.11)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        StateCase("1. Dash after withTransform", "Top line dashed. Middle and bottom lines solid.") {
            val y = size.height / 4
            drawLine(Color.Red, Offset(16f, y), Offset(size.width - 16f, y), 6f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 12f)))
            withTransform({ translate(0f, y) }) {
                drawLine(Color.Blue, Offset(16f, y), Offset(size.width - 16f, y), 6f)
            }
            drawLine(Color.Black, Offset(16f, 3 * y), Offset(size.width - 16f, 3 * y), 6f)
        }
        StateCase("2. Dash set inside the block", "Middle line dashed. Top and bottom lines solid.") {
            val y = size.height / 4
            drawLine(Color.Black, Offset(16f, y), Offset(size.width - 16f, y), 6f)
            withTransform({ translate(0f, y) }) {
                drawLine(Color.Blue, Offset(16f, y), Offset(size.width - 16f, y), 6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 12f)))
            }
            drawLine(Color.Black, Offset(16f, 3 * y), Offset(size.width - 16f, 3 * y), 6f)
        }
        StateCase("3. Line cap after clipRect", "Top and bottom lines have square (butt) ends. Middle line has round ends.") {
            val y = size.height / 4
            val wide = 28f
            drawLine(Color.Black, Offset(48f, y), Offset(size.width - 48f, y), wide, cap = StrokeCap.Butt)
            clipRect {
                drawLine(Color.Blue, Offset(48f, 2 * y), Offset(size.width - 48f, 2 * y), wide, cap = StrokeCap.Round)
            }
            drawLine(Color.Black, Offset(48f, 3 * y), Offset(size.width - 48f, 3 * y), wide, cap = StrokeCap.Butt)
        }
        StateCase("4. Dashed rectangle then solid rectangle", "Left rectangle dashed. Right rectangle solid.") {
            val side = size.height - 32f
            withTransform({ translate(16f, 16f) }) {
                drawRect(Color.Red, size = Size(side, side),
                    style = Stroke(width = 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f))))
            }
            drawRect(Color.Black, topLeft = Offset(size.width - side - 16f, 16f), size = Size(side, side),
                style = Stroke(width = 6f))
        }
        StateCase("5. Font and alignment after withTransform",
            "Middle line large, bold and centred. Top and bottom lines small, regular and starting at the left edge.") {
            val small = TextStyle(color = Color.Black, fontSize = 13.sp)
            val large = TextStyle(color = Color.Blue, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val line = size.height / 4
            drawText("left, small, regular", Offset(16f, line), small)
            withTransform({ translate(0f, line) }) {
                drawText("centred, large, bold", Offset(size.width / 2, line), large)
            }
            drawText("left, small, regular", Offset(16f, 3 * line), small)
        }
    }
}

@Composable
private fun StateCase(title: String, expected: String, draw: DrawScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Expected: $expected", fontSize = 12.sp, color = Color(0xFF555555))
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp).background(Color(0xFFF2F2F2)), onDraw = draw)
    }
}
