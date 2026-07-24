package build.raft.app.markdown

import kotlin.time.TimeSource

/**
 * OHOS actual for `SlockMarkdownTrace`. OHOS does not expose `android.os.Trace` in
 * the same way Android does (different logging subsystem). For #192 phase
 * attribution we only need this on Android (smoke runs there); OHOS is a no-op.
 *
 * If/when OHOS performance profiling needs the same markers, replace these with
 * `HiTraceMeter` calls (the OHOS equivalent) — but that is out of scope for the
 * current #192 visible-Markdown cost cycle.
 */
internal actual fun traceBegin(name: String) {
    // No-op: OHOS tracing is out of scope for #192 phase attribution.
}

internal actual fun traceEnd() {
    // No-op: OHOS tracing is out of scope for #192 phase attribution.
}

internal actual fun slockTraceDiagnosticsEnabled(): Boolean = false

internal actual fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: () -> String
) {
    // No-op: OHOS spike debug log is out of scope for #192 phase attribution.
}

/**
 * OHOS actual for monotonic clock. Uses Kotlin's `TimeSource.Monotonic` which is
 * the standard KMP-compatible monotonic clock for Kotlin/Native (OHOS) targets.
 * `System.nanoTime()` is JVM/Android-only and does NOT exist on Kotlin/Native.
 * The `TimeSource.Monotonic` values are nanoseconds-resolution-equivalent and
 * monotonic across the process lifetime — sufficient for #192 phase duration
 * measurement (we only need relative durations within a single Compose frame).
 */
private val ohosMonotonic = TimeSource.Monotonic
internal actual fun slockNanoTimeNanos(): Long =
    ohosMonotonic.markNow().elapsedNow().inWholeNanoseconds
