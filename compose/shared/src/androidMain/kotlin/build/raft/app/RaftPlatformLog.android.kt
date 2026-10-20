package build.raft.app

internal actual fun raftPlatformLog(
    level: RaftLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: RaftPlatformLogHint
) {
    try {
        when (level) {
            RaftLogLevel.Debug -> android.util.Log.d(tag, message)
            RaftLogLevel.Info -> android.util.Log.i(tag, message)
            RaftLogLevel.Warn -> android.util.Log.w(tag, message)
            RaftLogLevel.Error -> android.util.Log.e(tag, message)
        }
    } catch (_: RuntimeException) {
        println("$tag: $message")
    }
}

internal actual suspend fun flushRaftPlatformDiagnosticsDownstream(): Boolean = true
