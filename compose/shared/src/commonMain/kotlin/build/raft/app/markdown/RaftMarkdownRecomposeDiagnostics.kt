package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

// task #304 CI gate hook (owner: XiShi; called from RaftMarkdownBody — HanXin's
// entry rewrite keeps the call). A SideEffect runs after EVERY successful
// re-execution of the calling composition scope, so this counts real
// recompositions — including the "global validity/label lambda identity churn
// pierces every row" class that signature-keyed LaunchedEffect markers cannot
// see (#1055 post-device review finding ②). Tag-less rows are expected to stay
// at n=1 across members 0→N hydration (the task #304 log checker that asserted
// this was removed in 2026-10 as unused).
//
// Marker: thread_markdown_body_recompose key=<performanceKey> n=<count> bodyLen=<len>
// Only the first RaftMarkdownRecomposeLogCap recompositions log (proving a
// violation needs n=2; scroll-driven recompositions later in the session
// should not spam logcat).

private const val RaftMarkdownRecomposeLogCap = 10

private class RaftMarkdownRecomposeCounter {
    var n: Int = 0
}

@Composable
internal fun RaftMarkdownBodyRecomposeMarker(
    performanceKey: String?,
    bodyLength: Int,
    performanceLogger: (String) -> Unit
) {
    if (performanceKey == null) return
    val counter = remember { RaftMarkdownRecomposeCounter() }
    SideEffect {
        counter.n++
        if (counter.n <= RaftMarkdownRecomposeLogCap) {
            performanceLogger(
                "thread_markdown_body_recompose key=$performanceKey n=${counter.n} bodyLen=$bodyLength"
            )
        }
    }
}
