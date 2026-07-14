package build.raft.app

import platform.Foundation.NSNotificationCenter

internal actual fun logPixelAvatarDrawCache(message: String) {
    // Keep iOS quiet; counters still update in common state.
}

internal actual fun isSlockNativePixelAvatarViewSupported(): Boolean = false

internal actual fun slockPlatformLog(
    level: SlockLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: SlockPlatformLogHint
) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        aName = SlockIosSharedLogNotificationName,
        `object` = null,
        userInfo =
            mapOf(
                "level" to level.wireValue,
                "tag" to tag,
                "message" to message,
                "timestampMillis" to timestampMillis,
                "critical" to (hint == SlockPlatformLogHint.AuthCritical)
            )
    )
}

private const val SlockIosSharedLogNotificationName = "slockSharedLog"
