package build.raft.app

internal actual fun slockPlatformLog(
    level: SlockLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: SlockPlatformLogHint
) {
    try {
        when (level) {
            SlockLogLevel.Debug -> android.util.Log.d(tag, message)
            SlockLogLevel.Info -> android.util.Log.i(tag, message)
            SlockLogLevel.Warn -> android.util.Log.w(tag, message)
            SlockLogLevel.Error -> android.util.Log.e(tag, message)
        }
    } catch (_: RuntimeException) {
        println("$tag: $message")
    }
}
