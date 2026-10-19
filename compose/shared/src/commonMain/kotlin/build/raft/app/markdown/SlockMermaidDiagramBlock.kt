package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import build.raft.mermaid.core.MermaidParseResult
import build.raft.mermaid.core.MermaidParser
import build.raft.mermaid.layout.LayoutConfig
import build.raft.mermaid.layout.LayoutScene
import build.raft.mermaid.layout.simple.FixedWidthTextMeasurer
import build.raft.mermaid.layout.simple.SimpleMermaidLayout

/**
 * Renders a Mermaid diagram block.
 *
 * Parse → layout runs on the multiplatform `build.raft.mermaid` artifacts; the resulting
 * [LayoutScene] is drawn by [RaftMermaidCanvas] (Compose Canvas, replacing the Kuikly
 * `CanvasView` + `MermaidKuiklyRenderer` of the Kuikly build) inside [SlockMermaidCard].
 *
 * Contract: if Mermaid source parses and lays out, render the diagram with interactive zoom;
 * otherwise gracefully fall back to [fallback].
 */
@Composable
internal fun SlockMermaidDiagramBlock(
    code: String,
    style: TextStyle,
    fallback: @Composable () -> Unit
) {
    val layoutScene: LayoutScene? = remember(code) { raftMermaidLayoutScene(code) }

    if (layoutScene != null && layoutScene.width > 0 && layoutScene.height > 0) {
        SlockMermaidCard(
            code = code,
            diagramWidth = layoutScene.width.toFloat(),
            codeContent = fallback,
            diagramContent = { scale ->
                RaftMermaidCanvas(scene = layoutScene, scale = scale, textStyle = style)
            }
        )
    } else {
        fallback()
    }
}

internal fun raftMermaidLayoutScene(code: String): LayoutScene? =
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
