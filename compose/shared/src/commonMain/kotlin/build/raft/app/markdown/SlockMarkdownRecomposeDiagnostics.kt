package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

// task #304 CI gate hooks (owner: XiShi). A SideEffect runs after EVERY
// successful re-execution of the calling composition scope, so these count
// real recompositions — including the "global validity/label lambda identity
// churn pierces every row" class that signature-keyed LaunchedEffect markers
// cannot see (#1055 post-device review finding ②).
//
// Gate v2 (per the #1076 source review): two scopes, one marker vocabulary.
//   scope=body — inside SlockMarkdownBody (markdown subtree work)
//   scope=card — at the OUTER ThreadMessageCard level, the review's escape
//                hatch: the outer row/Card can recompose while the body hook
//                stays silent. Production wiring is one call in the row
//                composable; the gate checker REQUIRES at least one
//                scope=card marker per run (MISSING_CARD_SCOPE otherwise),
//                so a build without the card hook cannot green the gate.
//
// Marker: thread_row_recompose scope=<body|card> key=<performanceKey> n=<count> bodyLen=<len>
// Only the first SlockRowRecomposeLogCap recompositions log per instance
// (proving a violation needs n=2; scroll-driven recompositions later in the
// session must not spam logcat).

private const val SlockRowRecomposeLogCap = 10

private class SlockRowRecomposeCounter {
    var n: Int = 0
}

@Composable
internal fun SlockThreadRowRecomposeMarker(
    scope: String,
    performanceKey: String?,
    bodyLength: Int,
    performanceLogger: (String) -> Unit
) {
    if (performanceKey == null) return
    val counter = remember { SlockRowRecomposeCounter() }
    SideEffect {
        counter.n++
        if (counter.n <= SlockRowRecomposeLogCap) {
            performanceLogger(
                "thread_row_recompose scope=$scope key=$performanceKey n=${counter.n} bodyLen=$bodyLength"
            )
        }
    }
}

// Body-scope convenience kept for the existing SlockMarkdownBody call site.
@Composable
internal fun SlockMarkdownBodyRecomposeMarker(
    performanceKey: String?,
    bodyLength: Int,
    performanceLogger: (String) -> Unit
) {
    SlockThreadRowRecomposeMarker(
        scope = "body",
        performanceKey = performanceKey,
        bodyLength = bodyLength,
        performanceLogger = performanceLogger
    )
}
