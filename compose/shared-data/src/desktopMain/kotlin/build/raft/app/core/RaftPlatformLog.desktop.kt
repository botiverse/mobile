package build.raft.app

@RaftCoreInternalApi actual fun raftPlatformLog(
    level: RaftLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: RaftPlatformLogHint,
) {
    if (level == RaftLogLevel.Debug && System.getProperty("raft.desktop.debugLogs") != "true") return
    val stream = if (level == RaftLogLevel.Error || level == RaftLogLevel.Warn) System.err else System.out
    stream.println("${level.wireValue.uppercase()} $tag: $message")
    // The packaged app's stdout goes nowhere: the log file is what a bug report can attach.
    RaftDesktopFileLog.write(level.wireValue, tag, message)
}

@RaftCoreInternalApi actual suspend fun flushRaftPlatformDiagnosticsDownstream(): Boolean = true
