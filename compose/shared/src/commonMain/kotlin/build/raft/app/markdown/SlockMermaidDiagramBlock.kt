package build.raft.app.markdown

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.text.TextStyle

/**
 * Native Kuikly Compose component that renders a Mermaid diagram block.
 *
 * `expect`/`actual` split (see #329 / #373): the real Mermaid renderer
 * (parse → layout → Kuikly Canvas draw) lives in the target-specific `actual`
 * source sets where the `build.raft.mermaid` artifacts are resolvable.
 * commonMain keeps only this signature so mermaid types never enter the
 * common classpath. Android and iOS consume the `build.raft.mermaid` artifacts
 * from their own source sets.
 *
 * Contract: if Mermaid source parses and lays out, render the native Canvas diagram
 * with interactive zoom; otherwise gracefully fall back to [fallback].
 */
@Composable
internal expect fun SlockMermaidDiagramBlock(
    code: String,
    style: TextStyle,
    fallback: @Composable () -> Unit
)
