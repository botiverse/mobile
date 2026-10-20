package build.raft.app.markdown

/**
 * js actual for `RaftMarkdownTrace`. No-op; #192 smoke runs on Android.
 */
internal actual fun traceBegin(name: String) {
    // No-op
}

internal actual fun traceEnd() {
    // No-op
}

internal actual fun raftTraceDiagnosticsEnabled(): Boolean = false

internal actual fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: () -> String
) {
    // No-op
}

/**
 * js actual for monotonic clock. Uses the standard browser `performance.now()`
 * which returns DOMHighResTimeStamp in milliseconds; we multiply to nanoseconds
 * for consistency with the Android actual. `System.nanoTime()` is JVM-only and
 * does NOT exist on JS targets.
 *
 * `performance.now()` is monotonic and not affected by wall-clock changes —
 * correct for #192 phase duration measurement.
 */
internal actual fun raftNanoTimeNanos(): Long =
    (js("performance.now()") as Double * 1_000_000.0).toLong()
