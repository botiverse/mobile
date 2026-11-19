package build.raft.app.data

import androidx.compose.runtime.Composable

internal actual fun raftNativePasskeyAssertionAvailable(): Boolean = false

internal actual fun raftNativePasskeyRpGroup(): String? = null

private val unsupported = RaftNativePasskeyAssertion { RaftNativePasskeyAssertionResult.Unsupported }

@Composable
internal actual fun rememberRaftNativePasskeyAssertion(): RaftNativePasskeyAssertion = unsupported
