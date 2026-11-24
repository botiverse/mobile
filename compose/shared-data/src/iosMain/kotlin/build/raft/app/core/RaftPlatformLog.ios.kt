package build.raft.app

import platform.Foundation.NSNotificationCenter

@RaftCoreInternalApi actual fun raftPlatformLog(
    level: RaftLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: RaftPlatformLogHint
) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        aName = RaftIosSharedLogNotificationName,
        `object` = null,
        userInfo =
            mapOf(
                "level" to level.wireValue,
                "tag" to tag,
                "message" to message,
                "timestampMillis" to timestampMillis,
                "critical" to (hint == RaftPlatformLogHint.AuthCritical)
            )
    )
}

@RaftCoreInternalApi actual suspend fun flushRaftPlatformDiagnosticsDownstream(): Boolean = true

private const val RaftIosSharedLogNotificationName = "slockSharedLog"
