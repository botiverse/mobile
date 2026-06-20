package ai.slock.compose.markdown

import androidx.tracing.Trace

/**
 * #192 phase attribution via Perfetto / `android.os.Trace`.
 *
 * Scope per Cindy 2026-06-20 #KMP:07e34453 approval: add three `Trace.beginSection` /
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
 * Implementation: `androidx.tracing.Trace` is a multiplatform-aware wrapper that
 * delegates to `android.os.Trace.beginSection/endSection` on Android (visible in
 * Perfetto / Studio CPU profiler / `simpleperf`) and is a no-op on OHOS / js /
 * native. No allocations in the hot path — marker names are literal constants.
 *
 * These markers are **only** used as phase attribution input. They do NOT
 * participate in PR gate metrics (perf numbers still come from gfxinfo
 * framestats), and they do NOT influence any cache hit/miss counter.
 */
internal inline fun <T> traceMarkdownSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

/** Stable phase names used at call sites. Centralized so renames stay in sync. */
internal object SlockMarkdownTraceNames {
    const val PREPARE = "markdown.prepare"
    const val PARSE = "markdown.parse"
    const val ANNOTATE = "markdown.annotate"
    const val LAYOUT = "markdown.layout"
}