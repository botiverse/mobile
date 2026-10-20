package build.raft.app.markdown

import kotlin.time.TimeSource

internal actual fun traceBegin(name: String) {
    // No-op: iOS tracing is not wired for shared Markdown diagnostics yet.
}

internal actual fun traceEnd() {
    // No-op: iOS tracing is not wired for shared Markdown diagnostics yet.
}

internal actual fun raftTraceDiagnosticsEnabled(): Boolean = false

internal actual fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: () -> String
) {
    // No-op: iOS spike logging is out of scope for the framework build restore.
}

private val iosMonotonicStart = TimeSource.Monotonic.markNow()

internal actual fun raftNanoTimeNanos(): Long =
    iosMonotonicStart.elapsedNow().inWholeNanoseconds
