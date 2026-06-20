package ai.slock.compose.markdown

import android.os.Trace

/**
 * Android actual for `SlockMarkdownTrace`. Delegates to `android.os.Trace.beginSection` /
 * `endSection` which are surfaced in `adb shell perfetto` / Android Studio CPU profiler /
 * `simpleperf`. Trace marker names are short string literals — no allocations.
 *
 * Only takes effect when an active trace session is recording; otherwise these
 * are cheap no-ops that return immediately.
 */
internal actual fun traceBegin(name: String) {
    Trace.beginSection(name)
}

internal actual fun traceEnd() {
    Trace.endSection()
}