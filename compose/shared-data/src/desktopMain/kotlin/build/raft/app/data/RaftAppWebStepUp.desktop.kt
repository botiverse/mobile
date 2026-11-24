package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable

// No web step-up here: the desktop app keeps Open on the web.
@RaftCoreInternalApi actual fun raftAppWebStepUpReturnUri(): String? = null

private val unsupported = RaftAppWebStepUpBrowser { _, _ -> RaftAppWebStepUpBrowserResult.Failed("unsupported") }

@Composable
@RaftCoreInternalApi actual fun rememberRaftAppWebStepUpBrowser(): RaftAppWebStepUpBrowser = unsupported
