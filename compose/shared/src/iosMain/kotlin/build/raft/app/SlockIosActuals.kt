package build.raft.app

internal actual fun logPixelAvatarDrawCache(message: String) {
    // Keep iOS quiet; counters still update in common state.
}

internal actual fun isSlockNativePixelAvatarViewSupported(): Boolean = false

internal actual fun slockPlatformLog(level: SlockLogLevel, tag: String, message: String) {
    println("[$tag][${level.name}] $message")
}
