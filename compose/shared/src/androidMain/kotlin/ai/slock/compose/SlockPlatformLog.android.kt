package ai.slock.compose

internal actual fun slockPlatformLog(level: SlockLogLevel, tag: String, message: String) {
    when (level) {
        SlockLogLevel.Debug -> android.util.Log.d(tag, message)
        SlockLogLevel.Info -> android.util.Log.i(tag, message)
        SlockLogLevel.Warn -> android.util.Log.w(tag, message)
        SlockLogLevel.Error -> android.util.Log.e(tag, message)
    }
}
