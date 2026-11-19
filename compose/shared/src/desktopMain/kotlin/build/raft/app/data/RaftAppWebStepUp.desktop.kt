package build.raft.app.data

import androidx.compose.runtime.Composable

// No web step-up here: the desktop app keeps Open on the web.
internal actual fun raftAppWebStepUpReturnUri(): String? = null

private val unsupported = RaftAppWebStepUpBrowser { _, _ -> RaftAppWebStepUpBrowserResult.Failed("unsupported") }

@Composable
internal actual fun rememberRaftAppWebStepUpBrowser(): RaftAppWebStepUpBrowser = unsupported
