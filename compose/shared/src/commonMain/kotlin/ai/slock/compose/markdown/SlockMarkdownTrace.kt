package ai.slock.compose.markdown

/**
 * #192 phase attribution via Perfetto / `android.os.Trace`.
 *
 * Scope per Cindy 2026-06-20 #KMP:07e34453 approval: add four `Trace.beginSection` /
 * `Trace.endSection` markers around the visible-row Markdown hot path so that
 * `adb shell perfetto` / Android Studio CPU profiler traces can localize jank
 * frames to a specific phase, instead of guessing from gfxinfo aggregate
 * numbers alone.
 *
 * Markers used at call sites:
 *   - `markdown.prepare` — `SlockMarkdownBody` body prepare phase.
 *   - `markdown.parse` — `MarkdownState.setMarkdownContent` AST construction.
 *   - `markdown.annotate` — inline `AnnotatedString` build / `inlineContent` map.
 *   - `markdown.layout` — `KuiklyText` compose + layout for block / inline mode.
 *
 * Implementation: `expect`/`actual` for cross-platform trace. On Android
 * (visible in Perfetto / Studio CPU profiler / `simpleperf`) the actual
 * implementation uses `android.os.Trace.beginSection/endSection`. On OHOS /
 * js / native targets the actual is a no-op — trace markers cost nothing.
 * (Earlier attempt used `androidx.tracing:tracing:1.3.0` which broke OHOS
 * compile; switched to expect/actual to keep OHOS green.)
 *
 * These markers are **only** used as phase attribution input. They do NOT
 * participate in PR gate metrics (perf numbers still come from gfxinfo
 * framestats), and they do NOT influence any cache hit/miss counter.
 */
internal expect fun traceBegin(name: String)
internal expect fun traceEnd()

/**
 * #192 spike debug log helper. Called from `traceMarkdownSection` when the wrapped
 * phase duration exceeds `MarkdownSpikeThresholdUs`. Single-line log so we can grep
 * logcat by tag (e.g., `adb logcat -d | grep SlockMarkdownSpike`).
 *
 * On Android logs at INFO level via `android.util.Log`; on OHOS / js / native this
 * is a no-op (we only run smoke on Android). Pure expect/actual keeps commonMain
 * portable without forcing OHOS / native to know about the logger.
 */
internal expect fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: String = ""
)

internal inline fun <T> traceMarkdownSection(name: String, block: () -> T): T {
    return traceMarkdownSection(name = name, bodyLength = -1, block = block)
}

/**
 * Variant of [traceMarkdownSection] that includes the row body length in the spike
 * report. Use this at the row-level call site (SlockMarkdownBody) where `body.length`
 * is available; the threshold check + log line otherwise are identical.
 */
internal inline fun <T> traceMarkdownSection(
    name: String,
    bodyLength: Int,
    block: () -> T
): T {
    traceBegin(name)
    val t0 = System.nanoTime()
    return try {
        block()
    } finally {
        traceEnd()
        val durationUs = (System.nanoTime() - t0) / 1_000L
        if (durationUs >= MarkdownSpikeThresholdUs) {
            logMarkdownSpike(
                phase = name,
                durationUs = durationUs,
                bodyLength = bodyLength
            )
        }
    }
}

/** Stable phase names used at call sites. Centralized so renames stay in sync. */
internal object SlockMarkdownTraceNames {
    const val PREPARE = "markdown.prepare"
    const val PARSE = "markdown.parse"
    const val ANNOTATE = "markdown.annotate"
    const val LAYOUT = "markdown.layout"
}

/**
 * #192 spike debug threshold (microseconds). When any `markdown.*` phase duration
 * exceeds this, the wrapped block logs a one-line spike report via `logMarkdownSpike`
 * (which goes to `android.util.Log` on Android, no-op elsewhere) so we can read
 * logcat to identify which row body / phase / time crossed the threshold.
 *
 * Default 200us is informed by the 2026-06-20 atrace data on emulator-5556:
 * - layout mean 130us / median 49us / p95 353us / max 671us (after colors hoist)
 * - prepare mean 65us / median 72us / max 127us
 * - 200us is ~1.5x of the layout p95; spikes above this are the rows that
 *   consume enough wall-time to push a 16.67ms frame over budget when N rows
 *   recompose in the same frame.
 *
 * Set to a very high value (Long.MAX_VALUE) to disable; or set via env / debug
 * build flag in a follow-up if needed. Pure local constant for now.
 */
internal const val MarkdownSpikeThresholdUs: Long = 200L