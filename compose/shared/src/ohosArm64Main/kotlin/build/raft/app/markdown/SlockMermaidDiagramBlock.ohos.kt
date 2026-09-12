package build.raft.app.markdown

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.text.TextStyle

/**
 * OHOS `actual` for `SlockMermaidDiagramBlock` (PR-A): no Mermaid rendering yet.
 *
 * OHOS keeps zero `build.raft.mermaid` dependency on this landing, so any
 * mermaid code block falls back to the ordinary code block. Real OHOS mermaid
 * rendering (Kuikly Canvas) lands in a follow-up PR that upgrades this `actual`
 * to consume the `0.1.2-ohos` artifacts.
 */
@Composable
internal actual fun SlockMermaidDiagramBlock(
    code: String,
    style: TextStyle,
    fallback: @Composable () -> Unit
) {
    fallback()
}
