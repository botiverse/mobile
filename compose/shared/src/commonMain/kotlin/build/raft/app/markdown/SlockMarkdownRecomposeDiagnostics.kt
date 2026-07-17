package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

// task #304 CI gate hook (owner: XiShi; called from SlockMarkdownBody — HanXin's
// entry rewrite keeps the call). A SideEffect runs after EVERY successful
// re-execution of the calling composition scope, so this counts real
// recompositions — including the "global validity/label lambda identity churn
// pierces every row" class that signature-keyed LaunchedEffect markers cannot
// see (#1055 post-device review finding ②). The gate checker
// (scripts/perf/check_thread_open_contract.mjs) asserts tag-less rows stay at
// n=1 across members 0→N hydration.
//
// Marker: thread_markdown_body_recompose key=<performanceKey> n=<count> bodyLen=<len>
// Only the first SlockMarkdownRecomposeLogCap recompositions log (proving a
// violation needs n=2; scroll-driven recompositions later in the session
// should not spam logcat).

private const val SlockMarkdownRecomposeLogCap = 10

private class SlockMarkdownRecomposeCounter {
    var n: Int = 0
}

@Composable
internal fun SlockMarkdownBodyRecomposeMarker(
    performanceKey: String?,
    bodyLength: Int,
    performanceLogger: (String) -> Unit
) {
    if (performanceKey == null) return
    val counter = remember { SlockMarkdownRecomposeCounter() }
    SideEffect {
        counter.n++
        if (counter.n <= SlockMarkdownRecomposeLogCap) {
            performanceLogger(
                "thread_markdown_body_recompose key=$performanceKey n=${counter.n} bodyLen=$bodyLength"
            )
        }
    }
}
