package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable

@RaftCoreInternalApi actual fun raftNativePasskeyAssertionAvailable(): Boolean = false

@RaftCoreInternalApi actual fun raftNativePasskeyRpGroup(): String? = null

private val unsupported = RaftNativePasskeyAssertion { RaftNativePasskeyAssertionResult.Unsupported }

@Composable
@RaftCoreInternalApi actual fun rememberRaftNativePasskeyAssertion(): RaftNativePasskeyAssertion = unsupported
