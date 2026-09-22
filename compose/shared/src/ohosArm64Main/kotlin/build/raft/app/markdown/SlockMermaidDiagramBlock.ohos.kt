package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.wrapContentHeight
import com.tencent.kuikly.compose.material3.Text as KuiklyText
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import build.raft.app.SlockColor
import build.raft.app.slockLogInfo
import build.raft.mermaid.core.MermaidParseResult
import build.raft.mermaid.core.MermaidParser
import build.raft.mermaid.kuikly.MermaidKuiklyRenderer
import com.tencent.kuikly.core.views.CanvasView
import build.raft.mermaid.layout.LayoutConfig
import build.raft.mermaid.layout.LayoutScene
import build.raft.mermaid.layout.simple.FixedWidthTextMeasurer
import build.raft.mermaid.layout.simple.SimpleMermaidLayout

@Composable
internal actual fun SlockMermaidDiagramBlock(
    code: String,
    style: TextStyle,
    fallback: @Composable () -> Unit
) {
    val layoutScene: LayoutScene? = remember(code) {
        try {
            val parseResult = MermaidParser.parse(code.trim())
            if (parseResult is MermaidParseResult.Success) {
                SimpleMermaidLayout.layout(
                    diagram = parseResult.diagram,
                    textMeasurer = FixedWidthTextMeasurer,
                    config = LayoutConfig()
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    if (layoutScene != null && layoutScene.width > 0 && layoutScene.height > 0) {
        var scale by remember { mutableStateOf(1.0f) }
        val diagramWidthDp = (layoutScene.width * scale).dp
        val diagramHeightDp = (layoutScene.height * scale).dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(vertical = 4.dp)
                .background(Color.White)
                .border(1.dp, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            MakeKuiklyComposeNode<CanvasView>(
                factory = { CanvasView() },
                modifier = Modifier
                    .width(diagramWidthDp)
                    .height(diagramHeightDp),
                viewInit = {
                    drawCallback = { context, _, _ ->
                        slockLogInfo("SlockMermaid", "mermaid_drawcb_entry site=viewInit layoutFrameDefault=${isLayoutFrameDefault()}")
                        MermaidKuiklyRenderer.render(
                            scene = layoutScene,
                            context = context,
                            scale = scale,
                            batchDraw = true,
                        )
                    }
                    slockLogInfo("SlockMermaid", "mermaid_drawcb_redraw_enter site=viewInit layoutFrameDefault=${isLayoutFrameDefault()} renderViewNull=${renderView == null} nativeRef=${nativeRef}")
                    redraw()
                },
                viewUpdate = { view ->
                    view.drawCallback = { context, _, _ ->
                        slockLogInfo("SlockMermaid", "mermaid_drawcb_entry site=viewUpdate layoutFrameDefault=${view.isLayoutFrameDefault()}")
                        MermaidKuiklyRenderer.render(
                            scene = layoutScene,
                            context = context,
                            scale = scale,
                            batchDraw = true,
                        )
                    }
                    slockLogInfo("SlockMermaid", "mermaid_drawcb_redraw_enter site=viewUpdate layoutFrameDefault=${view.isLayoutFrameDefault()} renderViewNull=${view.renderView == null} nativeRef=${view.nativeRef}")
                    view.redraw()
                }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0xCCFFFFFF))
                    .border(1.dp, SlockColor.BorderBlack)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            if (scale > 0.5f) scale = (scale - 0.25f).coerceAtLeast(0.5f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    KuiklyText(
                        text = "−",
                        style = TextStyle(fontSize = 14.sp, color = SlockColor.TextBlack)
                    )
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { scale = 1.0f },
                    contentAlignment = Alignment.Center
                ) {
                    KuiklyText(
                        text = "${(scale * 100).toInt()}%",
                        style = TextStyle(fontSize = 11.sp, color = SlockColor.TextBlack)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            if (scale < 3.0f) scale = (scale + 0.25f).coerceAtMost(3.0f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    KuiklyText(
                        text = "+",
                        style = TextStyle(fontSize = 14.sp, color = SlockColor.TextBlack)
                    )
                }
            }
        }
    } else {
        fallback()
    }
}
