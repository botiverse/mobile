package build.raft.app

import platform.Foundation.NSNotificationCenter

internal actual fun logPixelAvatarDrawCache(message: String) {
    // Keep iOS quiet; counters still update in common state.
}

internal actual fun isRaftNativePixelAvatarViewSupported(): Boolean = false

internal actual fun raftPlatformLog(
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

internal actual suspend fun flushRaftPlatformDiagnosticsDownstream(): Boolean = true

private const val RaftIosSharedLogNotificationName = "slockSharedLog"
