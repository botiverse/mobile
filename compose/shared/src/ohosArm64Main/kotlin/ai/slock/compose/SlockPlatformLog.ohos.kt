package ai.slock.compose

internal actual fun slockPlatformLog(level: SlockLogLevel, tag: String, message: String) {
    println("${level.name}/$tag: $message")
}
