package build.raft.app

internal enum class SlockLogLevel {
    Debug,
    Info,
    Warn,
    Error
}

/**
 * Shared logging bridge for commonMain diagnostics.
 *
 * commonMain must not call platform logging APIs such as android.util.Log
 * directly. Add a helper here, then implement the platform-specific behavior in
 * each target source set.
 */
internal expect fun slockPlatformLog(level: SlockLogLevel, tag: String, message: String)

internal fun slockLogDebug(tag: String, message: String) {
    slockPlatformLog(SlockLogLevel.Debug, tag, message)
}

internal fun slockLogInfo(tag: String, message: String) {
    slockPlatformLog(SlockLogLevel.Info, tag, message)
}

internal fun slockLogWarn(tag: String, message: String) {
    slockPlatformLog(SlockLogLevel.Warn, tag, message)
}

internal fun slockLogError(tag: String, message: String) {
    slockPlatformLog(SlockLogLevel.Error, tag, message)
}
