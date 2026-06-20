package ai.slock.compose.markdown

/**
 * OHOS actual for `SlockMarkdownTrace`. OHOS does not expose `android.os.Trace` in
 * the same way Android does (different logging subsystem). For #192 phase
 * attribution we only need this on Android (smoke runs there); OHOS is a no-op.
 *
 * If/when OHOS performance profiling needs the same markers, replace these with
 * `HiTraceMeter` calls (the OHOS equivalent) — but that is out of scope for
 * the current #192 visible-Markdown cost cycle.
 */
internal actual fun traceBegin(name: String) {
    // No-op: OHOS tracing is out of scope for #192 phase attribution.
}

internal actual fun traceEnd() {
    // No-op: OHOS tracing is out of scope for #192 phase attribution.
}

internal actual fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: String
) {
    // No-op: OHOS spike debug log is out of scope for #192 phase attribution.
}