package build.raft.app

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Developer setting "Verbose diagnostics" (Settings > Developer). Off by default: the noisy
 * categories of [RaftDiagnosticsVolumePolicy] (duplicate realtime skips, avatar resolution,
 * markdown preparse/recompose, viewport intents, per-request network success lines) are not
 * written to the diagnostics log a feedback report carries; only their per-minute counts are.
 * On, they are written in full again.
 *
 * Process-scoped like the text input trace ([RaftTextInputDiagnostics]): it resets on restart, so a
 * forgotten switch cannot keep feedback reports large.
 */
object RaftVerboseDiagnostics {
    private val enabledFlag = atomic(false)

    val enabled: Boolean
        get() = enabledFlag.value

    fun setEnabled(enabled: Boolean) {
        if (enabledFlag.getAndSet(enabled) == enabled) return
        raftLogWarn(RaftDiagnosticsVolumePolicy.SummaryTag, "diagnostics_verbose ${if (enabled) "on" else "off"}")
    }
}

/**
 * What of the routine diagnostics stream is written to the persistent log (`raft-diagnostics.jsonl`)
 * a feedback report attaches. Logcat / the console still get every line.
 *
 * Hands fb-c04c1199 (Android 1.22.0): 70k lines / 29 MB over four launches; the newest 11-minute
 * launch alone wrote 10k lines / 4 MB, a line every 65 ms, most of it per-frame or per-row
 * probes and repeated parse/merge bookkeeping. Per line, in order:
 *
 * 1. auth-trail lines and error lines are always written; warn lines too, except the routine
 *    warn-level markers of [WarnSummarizedRules] (per-scroll frame timings, IME animation timings,
 *    caller-cancelled requests), which are summarized like rule 3 with min..max / last of their
 *    timings; info lines that read like a failure are written when they carry a request / trace
 *    id, otherwise [FailureBudget] per key and minute (counted as `failed:<key>`);
 * 2. [VerboseCategories] (by event) are written only while [RaftVerboseDiagnostics] is on; off,
 *    one `diagnostics_verbose_off` line per minute carries their counts per category;
 * 3. [SummarizedRules] (high-frequency routine events): the first `budget` lines of each key per
 *    minute, plus lines carrying one of the rule's `keepWhen` markers, are written; the rest are
 *    counted. One `diagnostics_summary` line per minute lists every key's count with a few sample
 *    ids and the number of distinct ids (`thread_realtime_fold=39/40[b7610ab9,87b96b4f;2]`), so
 *    "which threads, how often" survives without every line;
 * 4. [KeptEventMarkers] / [KeptTags] (auth, sends, navigation, artifact viewer / WebView, thread
 *    reads, activity reconcile, tasks, lifecycle, crash/exit context, feedback) are always written;
 * 5. every other info line has a [routineBudget] per key and minute, summarized the same way, so
 *    a new chatty log point cannot flood the ring.
 *
 * 1.25 (Hands fb-93123592 / 7e198cab / 9cf9735f, Android 1.24.0): the 1.24 writer still wrote
 * 400-700 lines (~250 KB) per active minute, so the 1 MiB report budget held only the last 3-5
 * minutes. Routine budget 10 -> 2, per-keystroke / per-scroll / perf lines summarized, failure
 * look-alikes (`failedAttachments=false`) no longer count as failures: about a third fewer bytes
 * per active minute on those reports. Lines summarized out of the log ([Admission.recent]) still
 * go to the platform's in-memory ring, which a report attaches as the last minutes at full detail.
 *
 * A key is `event` or, for a `prefix sub_event key=value` family, `event:sub_event`. The minute is
 * one window for all keys, opened by the first counted line.
 * Thread-safe; [admit] and [drain] may run on different threads.
 */
class RaftDiagnosticsVolumePolicy(
    private val windowMillis: Long = DefaultWindowMillis,
    private val routineBudget: Int = DefaultRoutineBudget,
    private val maxKeys: Int = DefaultMaxKeys,
    private val maxSampleIds: Int = DefaultMaxSampleIds,
) {
    /**
     * [persist]: written to the persistent log. [recent]: worth the in-memory full-detail ring of
     * the last minutes (`recent-memory.jsonl`) even though it was summarized out of the log; false
     * for verbose-gated categories, which stay out of the report unless the developer switch is on.
     */
    class Admission(val persist: Boolean, val summaries: List<String>, val recent: Boolean = persist)

    /**
     * [budget] lines per key and window are written, the rest counted. [keepWhen]: a line carrying
     * one of these is always written. [onlyWhen] (warn rules): the rule applies only to lines
     * carrying one of these, every other line of the event is written. [metrics]: numeric
     * `name=value` fields whose min..max and last value over the window go into the summary.
     */
    internal data class Rule(
        val budget: Int = 1,
        val keepWhen: List<String> = emptyList(),
        val onlyWhen: List<String> = emptyList(),
        val metrics: List<String> = emptyList(),
    )

    private class Metric(var min: Double, var max: Double, var last: Double)

    private class Counter {
        var seen = 0L
        var kept = 0
        var suppressed = 0L
        val sampleIds = ArrayList<String>(DefaultMaxSampleIds)
        val distinctIds = HashSet<String>()
        val metrics = LinkedHashMap<String, Metric>()
    }

    private val lock = SynchronizedObject()
    // One window for every key: when it closes, one summary line (split past MaxSummaryChars)
    // carries all keys, instead of a JSONL line per key (the per-key lines of the 1.22
    // coalescer were 1,631 of the report's lines).
    private val counters = LinkedHashMap<String, Counter>()
    private val verboseOffCounts = LinkedHashMap<String, Long>()
    private var windowStartMillis = Long.MIN_VALUE

    fun admit(level: String, tag: String, message: String, nowMillis: Long, verbose: Boolean): Admission {
        val summaries = ArrayList<String>(0)
        val decision =
            synchronized(lock) {
                if (windowExpired(nowMillis)) closeWindowLocked(nowMillis, summaries)
                admitLocked(level, tag, message, nowMillis, verbose)
            }
        return Admission(persist = decision == Decision.Persist, summaries = summaries, recent = decision != Decision.Gated)
    }

    private enum class Decision { Persist, Summarized, Gated }

    /** Closes the window once it is a minute old (the periodic flush while the stream is quiet). */
    fun sweep(nowMillis: Long): List<String> =
        synchronized(lock) {
            ArrayList<String>().also { if (windowExpired(nowMillis)) closeWindowLocked(nowMillis, it) }
        }

    /** Closes the open window: the counts so far, before a feedback export reads the log. */
    fun drain(nowMillis: Long): List<String> =
        synchronized(lock) {
            ArrayList<String>().also { closeWindowLocked(nowMillis, it) }
        }

    private fun windowExpired(nowMillis: Long): Boolean =
        windowStartMillis != Long.MIN_VALUE && nowMillis - windowStartMillis >= windowMillis

    private fun admitLocked(
        level: String,
        tag: String,
        message: String,
        nowMillis: Long,
        verbose: Boolean,
    ): Decision {
        if (tag == SummaryTag) return Decision.Persist
        val normalizedLevel = level.trim().lowercase()
        if (isAuthRelated(message)) return Decision.Persist
        val tokens = message.split(' ', limit = 3)
        val event = tokens[0]
        val sub = tokens.getOrNull(1)?.takeIf { it.isNotEmpty() && !it.contains('=') }
        val key = if (sub == null) event else "$event:$sub"
        val failure = looksLikeFailure(message)
        if (normalizedLevel != "info" && normalizedLevel != "debug" && normalizedLevel != "verbose") {
            // Warnings and errors are written, except the routine warn-level markers (frame
            // timings, cancelled requests) of [WarnSummarizedRules].
            if (normalizedLevel != "warn" || failure) return Decision.Persist
            val rule =
                (sub?.let { WarnSummarizedRules["$event $it"] } ?: WarnSummarizedRules[event])
                    ?.takeIf { rule -> rule.onlyWhen.isEmpty() || rule.onlyWhen.any(message::contains) }
                    ?: return Decision.Persist
            if (rule.keepWhen.any(message::contains)) return Decision.Persist
            return countLocked(key, rule, message, nowMillis)
        }
        if (failure) {
            // An info line that reads like a failure: written with its trace / request id; without
            // one, [FailureBudget] per key and window (offline retries repeat the same failure for
            // every channel), the rest counted under `failed:<key>`.
            if (TraceIdPattern.containsMatchIn(message)) return Decision.Persist
            return countLocked("failed:$key", FailureRule, message, nowMillis)
        }
        val verboseCategory = verboseCategory(event, sub, message)
        if (verboseCategory != null) {
            if (verbose) return Decision.Persist
            if (windowStartMillis == Long.MIN_VALUE) windowStartMillis = nowMillis
            verboseOffCounts[verboseCategory] = (verboseOffCounts[verboseCategory] ?: 0L) + 1L
            return Decision.Gated
        }
        val rule =
            (sub?.let { SummarizedRules["$event $it"] } ?: SummarizedRules[event])
                ?: if (isKept(tag, event)) return Decision.Persist else Rule(budget = routineBudget)
        if (rule.keepWhen.any(message::contains)) return Decision.Persist
        return countLocked(key, rule, message, nowMillis)
    }

    private fun countLocked(key: String, rule: Rule, message: String, nowMillis: Long): Decision {
        val counter =
            counters[key] ?: run {
                if (counters.size >= maxKeys) return Decision.Persist
                Counter().also { counters[key] = it }
            }
        if (windowStartMillis == Long.MIN_VALUE) windowStartMillis = nowMillis
        counter.seen += 1
        rule.metrics.forEach { name ->
            val value = metricValue(message, name) ?: return@forEach
            val metric = counter.metrics[name]
            if (metric == null) {
                counter.metrics[name] = Metric(value, value, value)
            } else {
                if (value < metric.min) metric.min = value
                if (value > metric.max) metric.max = value
                metric.last = value
            }
        }
        if (counter.kept < rule.budget) {
            counter.kept += 1
            return Decision.Persist
        }
        counter.suppressed += 1
        sampleId(message)?.let { id ->
            if (counter.distinctIds.size < MaxDistinctIds) counter.distinctIds.add(id)
            if (counter.sampleIds.size < maxSampleIds && id !in counter.sampleIds) counter.sampleIds.add(id)
        }
        return Decision.Summarized
    }

    /**
     * `diagnostics_summary windowMs=… keys=… suppressed=… <key>=<suppressed>/<seen>[ids;distinct]{metric=min..max,last=v} …`
     * (most suppressed first; `part=i/n` when split), then `diagnostics_verbose_off windowMs=…
     * <category>=<count> …`. Resets every key's budget.
     */
    private fun closeWindowLocked(nowMillis: Long, summaries: MutableList<String>) {
        if (windowStartMillis == Long.MIN_VALUE) return
        val windowMs = (nowMillis - windowStartMillis).coerceAtLeast(0L)
        val entries =
            counters.entries
                .filter { it.value.suppressed > 0L }
                .sortedByDescending { it.value.suppressed }
                .map { (key, counter) -> counter.entry(key) }
        if (entries.isNotEmpty()) {
            val head = "keys=${entries.size} suppressed=${counters.values.sumOf { it.suppressed }}"
            val parts = ArrayList<StringBuilder>()
            entries.forEach { entry ->
                val current = parts.lastOrNull()
                if (current == null || current.length + entry.length + 1 > MaxSummaryChars) {
                    parts += StringBuilder(entry)
                } else {
                    current.append(' ').append(entry)
                }
            }
            parts.forEachIndexed { index, body ->
                val part = if (parts.size > 1) " part=${index + 1}/${parts.size}" else ""
                summaries += "$SummaryEvent windowMs=$windowMs $head$part $body"
            }
        }
        if (verboseOffCounts.isNotEmpty()) {
            val counts = verboseOffCounts.entries.joinToString(" ") { (category, count) -> "$category=$count" }
            summaries += "$VerboseOffEvent windowMs=$windowMs $counts"
        }
        counters.clear()
        verboseOffCounts.clear()
        windowStartMillis = Long.MIN_VALUE
    }

    private fun Counter.entry(key: String): String {
        val ids =
            if (sampleIds.isEmpty()) {
                ""
            } else {
                val distinct = if (distinctIds.size >= MaxDistinctIds) "$MaxDistinctIds+" else distinctIds.size.toString()
                "[${sampleIds.joinToString(",")};$distinct]"
            }
        val values =
            if (metrics.isEmpty()) {
                ""
            } else {
                metrics.entries.joinToString(";", prefix = "{", postfix = "}") { (name, metric) ->
                    "$name=${metric.min.metricText()}..${metric.max.metricText()},last=${metric.last.metricText()}"
                }
            }
        return "$key=$suppressed/$seen$ids$values"
    }

    private fun Double.metricText(): String = if (this == toLong().toDouble()) toLong().toString() else toString()

    companion object {
        const val SummaryTag = "RaftDiagnostics"
        const val SummaryEvent = "diagnostics_summary"
        const val VerboseOffEvent = "diagnostics_verbose_off"
        const val DefaultWindowMillis = 60_000L
        // 1.25: 10 -> 2. With 10 the 1.24 reports still wrote 400-700 lines a minute of active use
        // (fb-93123592 / 7e198cab / 9cf9735f: the 1 MiB budget held only the last 3-5 minutes),
        // spread over ~200 keys, each under its own budget. The last minutes at full detail are
        // in the in-memory ring (`recent-memory.jsonl`), not here.
        const val DefaultRoutineBudget = 2
        const val DefaultMaxKeys = 256
        const val DefaultMaxSampleIds = 2

        /** Info lines that read like a failure but carry no trace / request id: per key and window. */
        const val FailureBudget = 5
        private val FailureRule = Rule(budget = FailureBudget)
        private val FrameMetrics = listOf("durationMs", "jankFrames", "longestFrameMs")
        private const val MaxDistinctIds = 32
        private const val MaxSummaryChars = 1_700

        /**
         * Off unless [RaftVerboseDiagnostics] is on. Counts in the 11-minute launch of
         * fb-c04c1199 (10k lines total).
         */
        internal val VerboseCategories: Map<String, String> =
            mapOf(
                // 198 (3,601 over the whole report): one line per realtime event already applied.
                "realtime_domain_accept_skip" to "duplicate",
                // 1,276 / 814 / 456 / 361: avatar resolution per row and per paint.
                "thread_avatar_member" to "avatar",
                "thread_avatar_row" to "avatar",
                "thread_avatar_painter" to "avatar",
                "server_avatar_painter" to "avatar",
                "thread_api thread_avatar_member_seed" to "avatar",
                "dm_avatar_missing" to "avatar",
                "image_preview_painter" to "avatar",
                // 1,192 / 964 / 747 / 690: markdown preparse plans, body recomposition, per-row bodies.
                "thread_markdown_preparse" to "markdown",
                "thread_markdown_preparse_plan" to "markdown",
                "thread_markdown_body_recompose" to "markdown",
                "thread_markdown_body" to "markdown",
                "thread_markdown_row" to "markdown",
                // 1,029: every viewport intent transition while dragging / following.
                "thread_viewport_intent" to "viewport",
            )

        /** High-frequency routine events: `budget` lines per key and minute, the rest summarized. */
        internal val SummarizedRules: Map<String, Rule> =
            mapOf(
                // Realtime heartbeats and per-event accepts.
                "realtime_domain_accept" to Rule(),
                "realtime_domain_apply" to Rule(budget = 3),
                // Home: identity merge summaries, parse start/result and body sizes per refresh.
                // State changes keep a few per minute (selected conversation, refreshing).
                "shared_home" to Rule(),
                "shared_home shared_home_state_change" to Rule(budget = 3),
                "shared_home_render_branch" to Rule(),
                "shared_home_persist" to Rule(),
                "shared_home_row_hints" to Rule(),
                "shared_home_pinned_resolution" to Rule(),
                "shared_home_dm_status_rows" to Rule(),
                "shared_home_sidebar_order_warning" to Rule(),
                "conversation_directory_hub" to Rule(),
                // Badge / inbox reads. The fetch itself (`result=start` with its trigger source), a
                // dropped or deferred ask, a load whose unread fact changed or failed, and every
                // applied unread fact are kept; queued/coalesced asks and no-change loads are counted.
                "process_activity_unread process_activity_unread_request" to
                    Rule(keepWhen = listOf("result=start", "result=drop", "result=deferred")),
                "process_activity_unread process_activity_unread_load" to
                    Rule(keepWhen = listOf("factChanged=true", "result=stale_fact", "result=deferred")),
                "process_activity_unread_repository" to Rule(),
                "shared_activity inbox_network_body_received" to Rule(),
                "shared_activity inbox_parse_start" to Rule(budget = 0),
                "shared_activity inbox_parse_result" to Rule(),
                "shared_activity_thread_warning" to Rule(),
                "shared_activity_thread_prewarm" to Rule(),
                "shared_activity_persist" to Rule(),
                "shared_home_activity" to Rule(budget = 3),
                // Thread data: summary joins and parse bookkeeping (loads and results are kept).
                "thread_conversation_repository summary_join" to Rule(),
                "thread_conversation_repository conversation_network_body_received" to Rule(),
                "thread_conversation_repository conversation_parse_start" to Rule(budget = 0),
                "thread_conversation_repository conversation_parse_result" to Rule(),
                "thread_conversation_repository thread_summaries_loaded" to Rule(),
                "thread_api" to Rule(budget = 3),
                // Realtime folding into an open thread: one line per thread event. Kept once a
                // minute per key; the summary names the threads (sample ids) and how many.
                "thread_realtime_identity" to Rule(),
                "thread_realtime_fold" to Rule(),
                "thread_realtime_apply_result" to Rule(keepWhen = listOf("emitted=true")),
                "canonical_v2_realtime" to Rule(budget = 3),
                "canonical_v2_canary" to Rule(budget = 3),
                "sync_core_shadow" to Rule(),
                // Per-frame / per-scroll layout probes.
                "thread_composer_layout_decision" to Rule(),
                "thread_composer_layout_settled" to Rule(),
                "thread_ime_tail_draw_probe" to Rule(),
                "thread_ime_tail_pixel_probe" to Rule(budget = 0),
                "thread_back_to_bottom_pill" to Rule(),
                "thread_lazy_window" to Rule(),
                "thread_header_reply_diag" to Rule(),
                "thread_header_dm_diag" to Rule(),
                "live_time_context_timer_armed" to Rule(budget = 0),
                "live_time_context_timer_delivered" to Rule(budget = 0),
                // Composer draft bookkeeping (persist save/saved and send lines are kept).
                "thread_composer_draft_active_records" to Rule(),
                "thread_composer_draft_transition_prepared" to Rule(),
                "thread_composer_draft_transition_finalized" to Rule(),
                "thread_composer_draft_transition_completed" to Rule(),
                // Per keystroke (1.25): change / change result / draft save / saved. Send lines
                // (gate, snapshot, result) stay always written.
                "thread_composer_change" to Rule(budget = 3),
                "thread_composer_change_result" to Rule(budget = 3),
                "thread_composer_draft_persist_save" to Rule(budget = 3),
                "thread_composer_draft_persist_saved" to Rule(budget = 3),
                // Per scroll position (1.25); thread read start / result / skip stay always written.
                "thread_read_tail" to Rule(budget = 3),
                // Shell re-checks on every home state emission (1.25); tab dispatches stay written.
                "app_shell_parent_home_start_check" to Rule(budget = 2),
                "app_shell_child_tab_render" to Rule(budget = 2),
                // Realtime task events for tasks no longer shown (1.25).
                "tasks_realtime_ignore" to Rule(),
                // Members / presence / read receipts.
                "shell_server_members_repository" to Rule(),
                "process_server_members_repository" to Rule(),
                "process_server_members" to Rule(),
                "store_load" to Rule(budget = 3),
                "roster_presence_projection" to Rule(),
                "process_read_receipt" to Rule(),
                "scroll_frames" to Rule(budget = 3, metrics = FrameMetrics),
            )

        /**
         * Routine warn-level lines (1.25): per-scroll frame timings, IME animation timings and
         * requests the caller cancelled. `budget` per key and window, the rest summarized with
         * min..max / last of the metrics. Slow and failed requests, frame stalls carried in
         * `keepWhen`, and every other warning are always written. Navigation transition frames
         * (`nav_transition_frames`, from / to route) are navigation, not summarized.
         */
        internal val WarnSummarizedRules: Map<String, Rule> =
            mapOf(
                "scroll_frames" to Rule(budget = 3, metrics = FrameMetrics),
                "ime_animation_end" to Rule(budget = 2, keepWhen = listOf("timedOut=true"), metrics = listOf("durationMs", "progressFrames")),
                "shared_network slock_http_execute_finish" to
                    Rule(budget = 2, onlyWhen = listOf("kind=CANCELLED"), metrics = listOf("durationMs")),
                "slock_http_execute_finish" to Rule(budget = 2, onlyWhen = listOf("kind=CANCELLED"), metrics = listOf("durationMs")),
            )

        /**
         * Always written (no per-minute budget): an event containing one of these. Auth, sends,
         * navigation and page opens, the artifact viewer and its WebView (#4455), thread reads
         * (read-after-leave), activity reconcile, tasks (task open), lifecycle, crash / exit /
         * memory context, feedback and the diagnostics' own lines.
         */
        internal val KeptEventMarkers: List<String> =
            listOf(
                "auth", "send", "nav_transition", "artifact", "webview", "thread_read_", "thread_start",
                "thread_route_open", "thread_reply_open", "home_open_", "conversation_open", "task",
                "app_shell_", "app_back", "lifecycle", "process_exit", "system_kill", "memory", "crash",
                "anr", "diagnostics_", "settings_", "feedback", "background_transition", "shared_activity",
                "foreground_reconcile", "activity_keep_top_pin", "sync_changes", "realtime_authority",
                "reconnect", "rebaseline", "socket", "push", "notification",
            )

        internal val KeptTags: Set<String> =
            setOf(
                "SlockWebView", "SlockArtifactViewer", "SlockSharedAuth", "SlockNavTransitionFrames",
                "SlockAndroidDiagnostics", "SlockMemoryTelemetry", "SlockAuthCritical", "RaftTextInput",
            )

        private val RoutineNetworkLine =
            Regex(
                """^shared_network (?:slock_http_(?:execute|multipart|binary)_(?:start|finish)|""" +
                    """slock_networkkmm_(?:info|execute_start|execute_finish|multipart_start|multipart_finish)|endpoint) """
            )

        private val FailureMarkers =
            listOf("fail", "exception", "result=error", "_error", "timed out", "timeout=true", "crash")

        /** `failedAttachments=false`, `mainLoadFailed=false`, `error=none`: fields that say "no failure". */
        private val NeutralFailureField = Regex("""\S*(?:fail|error|exception|timeout|crash)\S*=(?:false|0|none|null)(?=\s|$)""", RegexOption.IGNORE_CASE)

        internal fun looksLikeFailure(message: String): Boolean {
            val lower = message.lowercase()
            if (FailureMarkers.none(lower::contains)) return false
            return FailureMarkers.any(NeutralFailureField.replace(lower, "")::contains)
        }

        /** A request / trace id that ties a failure to the server side: such lines are always written. */
        private val TraceIdPattern =
            Regex("""(?:^|\s)(?:traceId|trace_id|requestId|request_id|clientRequestId|xRequestId|x-request-id)=\S""", RegexOption.IGNORE_CASE)

        internal fun metricValue(message: String, name: String): Double? {
            var from = 0
            while (true) {
                val at = message.indexOf("$name=", from)
                if (at < 0) return null
                if (at == 0 || message[at - 1] == ' ') {
                    val start = at + name.length + 1
                    var end = start
                    while (end < message.length && (message[end].isDigit() || message[end] == '.' || message[end] == '-')) end++
                    return message.substring(start, end).toDoubleOrNull()
                }
                from = at + 1
            }
        }

        /** The verbose category of an info line, or null when it is not gated. */
        internal fun verboseCategory(event: String, sub: String?, message: String): String? {
            sub?.let { VerboseCategories["$event $it"] }?.let { return it }
            VerboseCategories[event]?.let { return it }
            // A per-request success line (failures, slow requests and cancellations are warn).
            return if (RoutineNetworkLine.containsMatchIn(message)) "network" else null
        }

        private val AuthMarkers =
            listOf("/auth/", "path=auth/", "auth_session", "auth_realtime_refresh", "token_provider", "current_user")

        /** The auth trail (requests to auth endpoints, session and token lines) is always written. */
        internal fun isAuthRelated(message: String): Boolean = AuthMarkers.any(message::contains)

        internal fun isKept(tag: String, event: String): Boolean =
            tag in KeptTags || KeptEventMarkers.any(event::contains)

        private val UuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val IdValuePattern = Regex("""(?:^|\s)(?:\w*[Ii]d|key|\w*[Hh]ash|instance)=([^\s,]{1,48})""")

        /** A short id for the summary: the first UUID (8 chars), else the first `*Id=` / `key=` value. */
        internal fun sampleId(message: String): String? {
            UuidPattern.find(message)?.let { return it.value.take(8) }
            return IdValuePattern.find(message)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.take(24)
        }
    }
}
