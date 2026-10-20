package build.raft.app.markdown

import android.os.Trace

/**
 * Android actual for `RaftMarkdownTrace`. Delegates to `android.os.Trace.beginSection` /
 * `endSection` which are surfaced in `adb shell perfetto` / Android Studio CPU profiler /
 * `simpleperf`. Trace marker names are short string literals — no allocations.
 *
 * Only takes effect when an active trace session is recording; otherwise these
 * are cheap no-ops that return immediately.
 */
internal actual fun traceBegin(name: String) {
    Trace.beginSection(name)
}

internal actual fun raftNanoTimeNanos(): Long = System.nanoTime()

internal actual fun traceEnd() {
    Trace.endSection()
}

internal actual fun raftTraceDiagnosticsEnabled(): Boolean = false

private const val SpikeLogTag = "SlockMarkdownSpike"

internal actual fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: () -> String
) {
    if (!raftTraceDiagnosticsEnabled()) return
    val extraText = extra()
    val extras = if (extraText.isNotEmpty()) " $extraText" else ""
    android.util.Log.i(
        SpikeLogTag,
        "phase=$phase durationUs=$durationUs bodyLength=$bodyLength$extras"
    )
}
