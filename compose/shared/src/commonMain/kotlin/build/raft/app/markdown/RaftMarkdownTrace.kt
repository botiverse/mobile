package build.raft.app.markdown

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
 *   - `markdown.prepare` — `RaftMarkdownBody` body prepare phase.
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
internal expect fun raftTraceDiagnosticsEnabled(): Boolean

/**
 * KMP-friendly monotonic clock for #192 phase duration measurement.
 * Wrapped as `expect/actual` because `System.nanoTime()` exists on Android/JVM
 * but is NOT available on Kotlin/Native (OHOS) or JS — commonMain cannot call
 * it directly without breaking OHOS compile. Android actual uses `System.nanoTime()`
 * for Perfetto alignment; OHOS/JS use platform monotonic clocks (`TimeSource.Monotonic`
 * for OHOS Kotlin/Native, `performance.now()` for JS) so phase durations stay
 * monotonic and consistent across platforms.
 */
internal expect fun raftNanoTimeNanos(): Long

/**
 * Non-Composable variant of [traceMarkdownSection] for call sites that are NOT
 * inside a `@Composable` function, such as content normalization and parser preparation.
 * Same threshold + log behavior, but the block lambda is `() -> T` (not
 * `@Composable () -> T`) and the function is not annotated `@Composable` so it can be
 * invoked from regular code.
 */
internal inline fun <T> traceMarkdownSectionNonComposable(
    name: String,
    block: () -> T
): T {
    if (!raftTraceDiagnosticsEnabled()) {
        return block()
    }
    traceBegin(name)
    val t0 = raftNanoTimeNanos()
    val result = block()
    traceEnd()
    val durationUs = (raftNanoTimeNanos() - t0) / 1_000L
    if (durationUs >= MarkdownSpikeThresholdUs) {
        logMarkdownSpike(
            phase = name,
            durationUs = durationUs,
            bodyLength = -1
        )
    }
    return result
}

/**
 * #192 spike debug log helper. Called from `traceMarkdownSection` when the wrapped
 * phase duration exceeds `MarkdownSpikeThresholdUs`. Single-line log so we can grep
 * logcat by tag (e.g., `adb logcat -d | grep SlockMarkdownSpike`).
 *
 * On Android logs at INFO level via `android.util.Log`; on OHOS / js / native this
 * is a no-op (we only run smoke on Android). Pure expect/actual keeps commonMain
 * portable without forcing OHOS / native to know about the logger.
 *
 * `extra` is a lambda so call sites do not pay string interpolation (or the
 * annotation scans some sites embed in it) when diagnostics are disabled — the
 * Android actual checks the gate before invoking it.
 */
internal expect fun logMarkdownSpike(
    phase: String,
    durationUs: Long,
    bodyLength: Int,
    extra: () -> String = { "" }
)

@androidx.compose.runtime.Composable
internal fun <T> traceMarkdownSection(
    name: String,
    block: @androidx.compose.runtime.Composable () -> T
): T = traceMarkdownSection(name = name, bodyLength = -1, block = block)

/**
 * Variant of [traceMarkdownSection] that includes the row body length in the spike
 * report. Use this at the row-level call site (RaftMarkdownBody) where `body.length`
 * is available; the threshold check + log line otherwise are identical.
 *
 * Non-inline because inline functions calling `expect` declarations can have
 * cross-module visibility issues in KMP (the inline body needs the actual to be
 * visible at every call site). Non-inline defers the call to the actual at
 * runtime via the dispatch table.
 *
 * The function is itself `@Composable` so the `block: @Composable () -> T` lambda
 * can invoke Compose primitives (`KuiklyText(...)`, `Markdown(...)`) from inside
 * the trace span without breaking the Compose scope. Without `@Composable` on the
 * function the lambda body would be evaluated in a non-Composable scope, causing
 * "Composable invocations can only happen from the context of a @Composable
 * function" at every call site that wraps a Composable lambda.
 */
@androidx.compose.runtime.Composable
internal fun <T> traceMarkdownSection(
    name: String,
    bodyLength: Int,
    block: @androidx.compose.runtime.Composable () -> T
): T {
    if (!raftTraceDiagnosticsEnabled()) {
        return block()
    }
    traceBegin(name)
    val t0 = raftNanoTimeNanos()
    val result = block()
    traceEnd()
    val durationUs = (raftNanoTimeNanos() - t0) / 1_000L
    if (durationUs >= MarkdownSpikeThresholdUs) {
        logMarkdownSpike(
            phase = name,
            durationUs = durationUs,
            bodyLength = bodyLength
        )
    }
    return result
}

/** Stable phase names used at call sites. Centralized so renames stay in sync. */
internal object RaftMarkdownTraceNames {
    const val PREPARE = "markdown.prepare"
    const val PARSE = "markdown.parse"
    const val ANNOTATE = "markdown.annotate"
    const val LAYOUT = "markdown.layout"
    // #192 / long-body investigation: sub-traces for the markdown.layout phase
    // so we can localize 5-10ms spikes on long bodies (1000+ chars). Each fires
    // once per RaftMarkdownBody recomposition. bodyLength tag is the body length.
    const val LAYOUT_INNER = "markdown.layout_inner" // pre-Markdown call setup
    const val LAYOUT_BLOCK = "markdown.layout_block" // third-party Markdown() call
    const val LAYOUT_KUIKLY = "markdown.layout_kuikly" // KuiklyText measure (if isolated)
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
