package ai.slock.compose.markdown

/**
 * js actual for `SlockMarkdownTrace`. No-op; #192 smoke runs on Android.
 */
internal actual fun traceBegin(name: String) {
    // No-op
}

internal actual fun traceEnd() {
    // No-op
}

internal actual fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: String
) {
    // No-op
}