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
 * 1. warn / error lines, and info lines that read like a failure or belong to the auth trail,
 *    are always written;
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
    class Admission(val persist: Boolean, val summaries: List<String>)

    internal data class Rule(val budget: Int = 1, val keepWhen: List<String> = emptyList())

    private class Counter {
        var seen = 0L
        var kept = 0
        var suppressed = 0L
        val sampleIds = ArrayList<String>(DefaultMaxSampleIds)
        val distinctIds = HashSet<String>()
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
        val persist =
            synchronized(lock) {
                if (windowExpired(nowMillis)) closeWindowLocked(nowMillis, summaries)
                admitLocked(level, tag, message, nowMillis, verbose)
            }
        return Admission(persist, summaries)
    }

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
    ): Boolean {
        if (tag == SummaryTag) return true
        val normalizedLevel = level.trim().lowercase()
        if (normalizedLevel != "info" && normalizedLevel != "debug" && normalizedLevel != "verbose") return true
        if (looksLikeFailure(message) || isAuthRelated(message)) return true
        val tokens = message.split(' ', limit = 3)
        val event = tokens[0]
        val sub = tokens.getOrNull(1)?.takeIf { it.isNotEmpty() && !it.contains('=') }
        val verboseCategory = verboseCategory(event, sub, message)
        if (verboseCategory != null) {
            if (verbose) return true
            if (windowStartMillis == Long.MIN_VALUE) windowStartMillis = nowMillis
            verboseOffCounts[verboseCategory] = (verboseOffCounts[verboseCategory] ?: 0L) + 1L
            return false
        }
        val rule =
            (sub?.let { SummarizedRules["$event $it"] } ?: SummarizedRules[event])
                ?: if (isKept(tag, event)) return true else Rule(budget = routineBudget)
        if (rule.keepWhen.any(message::contains)) return true
        val key = if (sub == null) event else "$event:$sub"
        val counter =
            counters[key] ?: run {
                if (counters.size >= maxKeys) return true
                Counter().also { counters[key] = it }
            }
        if (windowStartMillis == Long.MIN_VALUE) windowStartMillis = nowMillis
        counter.seen += 1
        if (counter.kept < rule.budget) {
            counter.kept += 1
            return true
        }
        counter.suppressed += 1
        sampleId(message)?.let { id ->
            if (counter.distinctIds.size < MaxDistinctIds) counter.distinctIds.add(id)
            if (counter.sampleIds.size < maxSampleIds && id !in counter.sampleIds) counter.sampleIds.add(id)
        }
        return false
    }

    /**
     * `diagnostics_summary windowMs=… keys=… suppressed=… <key>=<suppressed>/<seen>[ids;distinct] …`
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
        return "$key=$suppressed/$seen$ids"
    }

    companion object {
        const val SummaryTag = "RaftDiagnostics"
        const val SummaryEvent = "diagnostics_summary"
        const val VerboseOffEvent = "diagnostics_verbose_off"
        const val DefaultWindowMillis = 60_000L
        const val DefaultRoutineBudget = 10
        const val DefaultMaxKeys = 256
        const val DefaultMaxSampleIds = 3
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
                "shared_home_activity" to Rule(budget = 10),
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
                "canonical_v2_canary" to Rule(budget = 5),
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
                // Members / presence / read receipts.
                "shell_server_members_repository" to Rule(),
                "process_server_members_repository" to Rule(),
                "process_server_members" to Rule(),
                "store_load" to Rule(budget = 3),
                "roster_presence_projection" to Rule(),
                "process_read_receipt" to Rule(),
                "scroll_frames" to Rule(budget = 3),
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

        internal fun looksLikeFailure(message: String): Boolean {
            val lower = message.lowercase()
            return FailureMarkers.any(lower::contains)
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
