package build.raft.app

import androidx.compose.runtime.Composable

// No WebView keep-alive here yet: every page creates and destroys its own WebView.
internal actual fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument? = null

internal actual fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean = false

internal actual fun raftWebViewKeepAliveClear() = Unit

@Composable
internal actual fun RaftWebViewPrewarmEffect(config: RaftWebViewConfig?) = Unit
