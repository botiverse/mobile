package build.raft.app

/** Stable cross-platform contract for the feedback diagnostics WebView bridge. */
internal object FeedbackDiagnosticsBridge {
    const val CAPABILITY = "diagnostics-upload-v1"
    const val EVENT = "raft:feedback-diagnostics"
    const val START_KIND = "diagnostics:start"
    const val CANCEL_KIND = "diagnostics:cancel"
    const val REQUEST_ID = "requestId"
    const val BINDING = "binding"
    const val TYPE = "type"

    fun validRequestId(value: String): Boolean = value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "._:-" }
    fun validBinding(value: String): Boolean = value.length in 1..256 && value.none { it == '\u0000' || it == '\n' || it == '\r' }
}
