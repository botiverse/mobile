package build.raft.app.core.data.diagnostics

import java.util.Locale

/**
 * Rate limit for the persistent diagnostics ring, for the few events that fire many times a second.
 *
 * Hands fb-52cc0499 (1.21.0, Huawei) wrote 28 x 1 MB of JSONL in about 30 minutes; a dozen routine
 * events made up most of it (realtime duplicate skips and heartbeats, IME/layout probes, the
 * back-to-bottom pill, repository parse start/result pairs). Once the 30 MiB ring fills, older
 * launches are pruned, so the launch before a kill or crash was the first thing lost.
 *
 * For each listed event (keyed by tag, event and, for a `prefix sub_event ...` family, the sub
 * event) a [windowMillis] window keeps:
 * - the first [budget] lines, and
 * - every line whose shape is new in the window (ids and numbers removed), up to [maxShapes],
 *   so a state change (e.g. `visible=false` to `visible=true`) is still written;
 * - every line that reads like a failure.
 * The rest are counted, and one `diagnostics_coalesced` line with the count replaces them when the
 * window closes. Warn/error lines and events not listed here are never touched.
 *
 * Thread-safe: [admit] runs on the diagnostics dispatch thread, [drain] on export threads and
 * [checkpoint] on the memory-telemetry thread every 30s while the app is in the foreground.
 */
internal class RaftDiagnosticsCoalescer(
    private val rules: Map<String, Rule> = DefaultRules,
    private val windowMillis: Long = DefaultWindowMillis,
    private val budget: Int = DefaultBudget,
    private val maxShapes: Int = DefaultMaxShapes,
    private val maxKeys: Int = DefaultMaxKeys,
) {
    /** [budget] overrides the default; 0 keeps only failures and the count (pure-noise probes). */
    data class Rule(val budget: Int? = null, val dropWhen: String? = null)

    data class Admission(val persist: Boolean, val summaries: List<String>)

    private class Window(val key: String, val startMillis: Long) {
        var kept = 0
        var suppressed = 0L
        val shapes = HashSet<Int>()
    }

    private val lock = Any()
    private val windows = LinkedHashMap<String, Window>()
    private var lastSweepMillis = Long.MIN_VALUE

    fun admit(level: String, tag: String, message: String, nowMillis: Long): Admission {
        val summaries = ArrayList<String>(0)
        val persist =
            synchronized(lock) {
                sweepLocked(nowMillis, summaries)
                admitLocked(level, tag, message, nowMillis, summaries)
            }
        return Admission(persist, summaries)
    }

    /** Closes every open window; returns the summary lines for the ones that suppressed anything. */
    fun drain(nowMillis: Long): List<String> =
        synchronized(lock) {
            val summaries = ArrayList<String>()
            windows.values.forEach { window -> window.summary(nowMillis)?.let(summaries::add) }
            windows.clear()
            summaries
        }

    /**
     * Writes out the counts suppressed so far without closing the windows (budgets and shapes stay),
     * so a kill loses at most the counts since the last checkpoint. Each count is reported once:
     * a later checkpoint or the window's closing line covers only what was suppressed after it.
     */
    fun checkpoint(nowMillis: Long): List<String> =
        synchronized(lock) {
            val summaries = ArrayList<String>()
            windows.values.forEach { window ->
                window.summary(nowMillis, partial = true)?.let(summaries::add)
                window.suppressed = 0L
            }
            summaries
        }

    private fun admitLocked(
        level: String,
        tag: String,
        message: String,
        nowMillis: Long,
        summaries: MutableList<String>,
    ): Boolean {
        val normalizedLevel = level.lowercase(Locale.US)
        if (normalizedLevel != "info" && normalizedLevel != "debug" && normalizedLevel != "verbose") return true
        val event = message.substringBefore(' ')
        val rule = rules[event] ?: return true
        if (looksLikeFailure(message)) return true
        val key = coalescerKey(tag, message)
        var window = windows[key]
        if (window != null && nowMillis - window.startMillis >= windowMillis) {
            window.summary(nowMillis)?.let(summaries::add)
            windows.remove(key)
            window = null
        }
        if (window == null) {
            if (windows.size >= maxKeys) return true
            window = Window(key, nowMillis)
            windows[key] = window
        }
        val dropped = rule.dropWhen != null && message.contains(rule.dropWhen)
        val effectiveBudget = if (dropped) 0 else rule.budget ?: budget
        if (window.kept < effectiveBudget) {
            window.kept += 1
            window.shapes.add(diagnosticsMessageShape(message).hashCode())
            return true
        }
        if (effectiveBudget > 0 && window.shapes.size < maxShapes &&
            window.shapes.add(diagnosticsMessageShape(message).hashCode())
        ) {
            window.kept += 1
            return true
        }
        window.suppressed += 1
        return false
    }

    /** Closes expired windows at most once a second, so a quiet event's count is not held back. */
    private fun sweepLocked(nowMillis: Long, summaries: MutableList<String>) {
        if (lastSweepMillis != Long.MIN_VALUE && nowMillis - lastSweepMillis < SweepIntervalMillis) return
        lastSweepMillis = nowMillis
        val iterator = windows.values.iterator()
        while (iterator.hasNext()) {
            val window = iterator.next()
            if (nowMillis - window.startMillis >= windowMillis) {
                window.summary(nowMillis)?.let(summaries::add)
                iterator.remove()
            }
        }
    }

    private fun Window.summary(nowMillis: Long, partial: Boolean = false): String? {
        if (suppressed <= 0L) return null
        return "$SummaryEvent key=${key.replace(' ', ':')} suppressed=$suppressed kept=$kept " +
            "windowMs=${(nowMillis - startMillis).coerceAtLeast(0L)}" + if (partial) " partial=true" else ""
    }

    companion object {
        const val SummaryTag = "SlockDiagnosticsCoalescer"
        const val SummaryEvent = "diagnostics_coalesced"
        const val DefaultWindowMillis = 60_000L
        const val DefaultBudget = 3
        const val DefaultMaxShapes = 8
        const val DefaultMaxKeys = 128
        private const val SweepIntervalMillis = 1_000L

        /**
         * Counts per 30 minutes are from Hands fb-52cc0499. `shared_network` is not here: its
         * routine request start/finish lines are dropped by [RaftPersistentDiagnosticsPolicy].
         */
        val DefaultRules: Map<String, Rule> =
            mapOf(
                // 4121: one line per duplicate realtime event already applied.
                "realtime_domain_accept_skip" to Rule(),
                // 1384, nearly all `type=heartbeat`.
                "realtime_domain_accept" to Rule(),
                // 3123: identity merge summaries and home parse start/result per refresh.
                "shared_home" to Rule(),
                // 2284: one per composer inset relayout frame.
                "thread_composer_layout_decision" to Rule(),
                // 2126: IME tail draw probe decisions, repeated per frame.
                "thread_ime_tail_draw_probe" to Rule(),
                // 1063, 919 of them `stale_before_claim`: the probe found an old thread instance
                // and did nothing. Pure noise; only the count is kept.
                "thread_ime_tail_pixel_probe" to Rule(dropWhen = "status=stale_before_claim"),
                // 1802: request start/load/fact per realtime invalidation.
                "process_activity_unread" to Rule(),
                // 1359: inbox body/parse start/parse result per load.
                "process_activity_unread_repository" to Rule(),
                // 1536: summary joins and parse start/result (named "warning", logged at info).
                "shared_activity_thread_warning" to Rule(),
                // 1500: summary joins and parse start/result.
                "thread_conversation_repository" to Rule(),
                // 1310, 1132 of them `emitted=false reason=no_projection`.
                "thread_realtime_apply_result" to Rule(),
                // 1244: pill inputs on every scroll frame.
                "thread_back_to_bottom_pill" to Rule(),
            )

        private val FailureMarkers = listOf("fail", "exception", "result=error", "timed out", "timeout=true")

        internal fun looksLikeFailure(message: String): Boolean {
            val lower = message.lowercase(Locale.US)
            return FailureMarkers.any(lower::contains)
        }

        /** `tag event` or, when the second token is not `key=value`, `tag event sub_event`. */
        internal fun coalescerKey(tag: String, message: String): String {
            val tokens = message.split(' ', limit = 3)
            val event = tokens[0]
            val sub = tokens.getOrNull(1)?.takeIf { it.isNotEmpty() && !it.contains('=') }
            return if (sub == null) "$tag $event" else "$tag $event $sub"
        }

        private val UuidPattern =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val NumberPattern = Regex("""\d+""")

        /** The message with ids and numbers blanked: two lines differing only in those are one shape. */
        internal fun diagnosticsMessageShape(message: String): String =
            NumberPattern.replace(UuidPattern.replace(message, "U"), "N")
    }
}
