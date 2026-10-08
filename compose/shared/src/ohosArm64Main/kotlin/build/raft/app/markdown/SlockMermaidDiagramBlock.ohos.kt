package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp
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
        SlockMermaidCard(
            code = code,
            diagramWidth = layoutScene.width,
            codeContent = fallback,
            diagramContent = { scale ->
                MakeKuiklyComposeNode<CanvasView>(
                    factory = { CanvasView() },
                    modifier = Modifier
                        .width((layoutScene.width * scale).dp)
                        .height((layoutScene.height * scale).dp),
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
            }
        )
    } else {
        fallback()
    }
}
