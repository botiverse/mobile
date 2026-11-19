package build.raft.app.data

import androidx.compose.runtime.Composable

// No web step-up here: Android asserts legacy passkeys natively.
internal actual fun raftAppWebStepUpReturnUri(): String? = null

private val unsupported = RaftAppWebStepUpBrowser { _, _ -> RaftAppWebStepUpBrowserResult.Failed("unsupported") }

@Composable
internal actual fun rememberRaftAppWebStepUpBrowser(): RaftAppWebStepUpBrowser = unsupported
