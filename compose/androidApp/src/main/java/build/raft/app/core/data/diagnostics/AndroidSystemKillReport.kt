package build.raft.app.core.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import build.hands.update.HandsFeedback
import build.hands.update.HandsFeedbackException
import build.raft.app.shared.RaftBuildStamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Why a previous exit is, or is not, sent to Hands as a `system_kill` crash ticket. */
internal enum class SystemKillDecision(val label: String, val report: Boolean) {
    /** A foreground kill: the system recorded it visible, or the app has recent foreground evidence. */
    Report("report", true),
    /**
     * A background kill, sent too: Hands archives and counts these by `crash_exit_importance`.
     * Includes a snapshot that still said "in foreground" with no sample or interaction in the last
     * [SystemKillFreshSampleMillis] (Hands 4a5504d3).
     */
    ReportBackground("report_background", true),
    /** The user (swipe away, force stop, revoked permission) or the app itself ended it. */
    SkipUserInitiated("user_initiated", false),
    /** An install or package change; not a failure. */
    SkipPackageChange("package_change", false),
    /** Java/native crashes go through HandsCrash, ANRs through HandsCrash's ANR pass. */
    SkipReportedElsewhere("reported_elsewhere", false),
    /**
     * Not the main app process (a `:name` service process, or an isolated/sandboxed one such as a
     * WebView renderer). It carries no build stamp or telemetry, the user does not see the app
     * vanish when it dies, and WebView renderer deaths already reach `onRenderProcessGone`.
     */
    SkipSecondaryProcess("secondary_process", false),
}

private val UserInitiatedReasons =
    setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
    )

private val PackageChangeReasons =
    setOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)

private val ReportedElsewhereReasons =
    setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

/**
 * Crash signals (SIGILL 4, SIGABRT 6, SIGBUS 7, SIGFPE 8, SIGSEGV 11): some devices record a native
 * crash as `signaled` with the signal as status instead of `crash_native`, and HandsCrash already
 * filed it (Hands b00e67c3 and its duplicate SystemKill c9061aa6, 11-09). SIGKILL (9) stays a kill.
 */
private val CrashSignals = setOf(4, 6, 7, 8, 11)

/** The importances that mean an activity was on screen: foreground (100) and visible (200). */
private val VisibleImportances =
    setOf(
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
    )

/**
 * How long before the exit the app's last sample may be for its "in foreground" to decide. While an
 * activity is started, telemetry samples every [RaftMemoryTelemetry.SampleIntervalMillis] (30 s)
 * and commits at least every 15 s, so a live foreground process is never further behind than this.
 * An older sample means the process stopped sampling long before the kill (Hands 4a5504d3: sampled
 * 570 s before an exit the system recorded at importance 300).
 */
internal const val SystemKillFreshSampleMillis = 60_000L

/**
 * How long before the exit the last interaction may be to count: [SystemKillFreshSampleMillis]
 * plus the interaction write interval, since the stored time can be up to
 * [RaftMemoryTelemetry.InteractionWriteIntervalMillis] older than the user's real last action. A
 * false positive only means a ticket instead of an archive.
 */
internal const val SystemKillFreshInteractionMillis =
    SystemKillFreshSampleMillis + RaftMemoryTelemetry.InteractionWriteIntervalMillis

/**
 * Whether [telemetry]'s last sample was taken 0..[SystemKillFreshSampleMillis] before the exit. No
 * sample (-1) or one after the exit (a clock change) is not. Samples are only taken in the
 * foreground and a move to background does not reset the time, so this is the last foreground
 * sample even when the snapshot now says "in background".
 */
internal fun isTelemetrySampleFresh(telemetry: MemoryTelemetrySnapshot, exitTimestampMillis: Long): Boolean =
    isWithin(telemetry.sampledAtMillis, exitTimestampMillis, SystemKillFreshSampleMillis)

/**
 * Whether the user touched the app or pressed a key 0..[SystemKillFreshInteractionMillis] before
 * the exit ([MemoryTelemetrySnapshot.lastInteractionAtMillis]). The Huawei kill fb-52cc0499 was
 * recorded at importance 400 one second after the user scrolled.
 */
internal fun isTelemetryInteractionFresh(telemetry: MemoryTelemetrySnapshot, exitTimestampMillis: Long): Boolean =
    isWithin(telemetry.lastInteractionAtMillis, exitTimestampMillis, SystemKillFreshInteractionMillis)

/** What says the app was in the foreground shortly before the exit, and how long before. */
internal data class ForegroundEvidence(val kind: Kind, val ageMillis: Long) {
    enum class Kind(val label: String) { Sample("sample"), Interaction("interaction") }
}

/**
 * Fresh foreground evidence from the app: the user's last interaction
 * ([isTelemetryInteractionFresh]) or the last foreground sample ([isTelemetrySampleFresh]), the
 * interaction when both are (it is the user, not a timer); null when neither, even if the other
 * source is missing. Either counts whatever the snapshot's flag says now: the user may have acted,
 * switched away, and the app was killed.
 */
internal fun recentForegroundEvidence(telemetry: MemoryTelemetrySnapshot?, exitTimestampMillis: Long): ForegroundEvidence? {
    if (telemetry == null) return null
    return when {
        isTelemetryInteractionFresh(telemetry, exitTimestampMillis) ->
            ForegroundEvidence(ForegroundEvidence.Kind.Interaction, exitTimestampMillis - telemetry.lastInteractionAtMillis)
        isTelemetrySampleFresh(telemetry, exitTimestampMillis) ->
            ForegroundEvidence(ForegroundEvidence.Kind.Sample, exitTimestampMillis - telemetry.sampledAtMillis)
        else -> null
    }
}

internal fun hasFreshForegroundEvidence(telemetry: MemoryTelemetrySnapshot, exitTimestampMillis: Long): Boolean =
    recentForegroundEvidence(telemetry, exitTimestampMillis) != null

/**
 * `crash_recent_foreground` and its source (botiverse/hands#955): [value] true with the fresh
 * evidence; false (source `none`) only when the sample and the interaction are both recorded,
 * valid (at or before the exit) and older than their windows; null (omitted, source `unknown`)
 * when neither is fresh and either is missing or invalid.
 */
internal data class RecentForeground(val value: Boolean?, val source: String, val ageSeconds: Long?)

internal fun recentForeground(telemetry: MemoryTelemetrySnapshot?, exitTimestampMillis: Long): RecentForeground {
    val evidence = recentForegroundEvidence(telemetry, exitTimestampMillis)
    return when {
        evidence != null -> RecentForeground(true, evidence.kind.label, evidence.ageMillis / 1000L)
        telemetry != null &&
            isKnownBeforeExit(telemetry.sampledAtMillis, exitTimestampMillis) &&
            isKnownBeforeExit(telemetry.lastInteractionAtMillis, exitTimestampMillis) -> RecentForeground(false, "none", null)
        else -> RecentForeground(null, "unknown", null)
    }
}

/** A recorded time (not -1) at or before the exit. */
private fun isKnownBeforeExit(atMillis: Long, exitTimestampMillis: Long): Boolean =
    atMillis >= 0L && atMillis <= exitTimestampMillis

private fun isWithin(atMillis: Long, exitTimestampMillis: Long, windowMillis: Long): Boolean =
    atMillis >= 0L && exitTimestampMillis - atMillis in 0L..windowMillis

/**
 * Exit reasons Hands treats as actionable and never auto-archives (botiverse/hands main
 * `worker/src/routes/feedback.ts`, `ACTIONABLE_SYSTEM_KILL_REASON_CODES` / `_REASONS`, 40cb90df):
 * crash (4), crash_native (5), anr (6), excessive_resource_usage (9). Keep in sync.
 */
internal val HandsActionableSystemKillReasonCodes =
    setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

/** Which report budget a system kill uses ([planSystemKillReports]). */
internal enum class SystemKillBudget(val label: String) {
    /** Everything Hands would not auto-archive under botiverse/hands#955, and anything unknown. */
    Protected("protected"),
    /** Confirmed background only: Hands archives it, and it may never use up [Protected]. */
    Background("background"),
}

/**
 * [SystemKillBudget.Background] only for a confirmed background kill: `crash_recent_foreground`
 * false, importance foreground service (125) or above but not visible (200), and a reason Hands
 * does not treat as actionable ([HandsActionableSystemKillReasonCodes]). Everything else,
 * including unknown recent foreground, is [SystemKillBudget.Protected].
 */
internal fun systemKillBudget(exit: SystemKillExit, telemetry: MemoryTelemetrySnapshot?): SystemKillBudget =
    if (
        recentForeground(telemetry, exit.timestampMillis).value == false &&
        exit.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE &&
        exit.importance !in VisibleImportances &&
        exit.reasonCode !in HandsActionableSystemKillReasonCodes
    ) {
        SystemKillBudget.Background
    } else {
        SystemKillBudget.Protected
    }

/**
 * Every reason not listed above is a system kill: unknown, signaled, low_memory,
 * initialization_failure, excessive_resource_usage, dependency_died, other, freezer, and codes
 * added after API 35. On a device that does not explain its kills (the Huawei in Hands
 * fb-52cc0499 recorded `reason=other`) any of them can be the "app vanished" users describe.
 *
 * Every system kill is sent; this only says whether it was a foreground or a background one (the
 * decision label in the local log; the ticket carries the raw fields and the evidence separately,
 * see [systemKillExtras]):
 * - foreground ([SystemKillDecision.Report]) when the exit record says importance foreground
 *   (100) or visible (200), whatever the app's telemetry says or how old it is (the app may have
 *   come back to the foreground after its last snapshot was written); or when the app's telemetry
 *   for the exited process ([telemetry], matched by the caller) has recent foreground evidence, a
 *   sample or a user interaction at most [SystemKillFreshSampleMillis] before the exit
 *   ([recentForegroundEvidence]), whatever the importance. The Huawei kill (fb-52cc0499)
 *   recorded `importance=400` one second after the user scrolled.
 * - background ([SystemKillDecision.ReportBackground]) otherwise: foreground service (125),
 *   perceptible (230), service (300), cached (400) without such evidence, including a snapshot
 *   that says "in foreground" from long before the kill (Hands 4a5504d3: foreground 600 s, sample
 *   570 s before an exit at importance 300).
 */
internal fun classifySystemKill(
    reasonCode: Int,
    importance: Int,
    telemetry: MemoryTelemetrySnapshot?,
    exitTimestampMillis: Long,
    status: Int = 0,
): SystemKillDecision =
    when {
        reasonCode in UserInitiatedReasons -> SystemKillDecision.SkipUserInitiated
        reasonCode in PackageChangeReasons -> SystemKillDecision.SkipPackageChange
        reasonCode in ReportedElsewhereReasons -> SystemKillDecision.SkipReportedElsewhere
        reasonCode == ApplicationExitInfo.REASON_SIGNALED && status in CrashSignals -> SystemKillDecision.SkipReportedElsewhere
        importance in VisibleImportances -> SystemKillDecision.Report
        recentForegroundEvidence(telemetry, exitTimestampMillis) != null -> SystemKillDecision.Report
        else -> SystemKillDecision.ReportBackground
    }

/** The fields of one [ApplicationExitInfo] the report uses; a plain value so planning is testable. */
internal data class SystemKillExit(
    val pid: Int,
    val timestampMillis: Long,
    val reasonCode: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    /** The build the exited process stamped ([RaftBuildStamp.stampProcess]); null if none. */
    val build: RaftBuildStamp.StampedBuild?,
    /** [ApplicationExitInfo.getProcessName]; "" when the system gave none. */
    val processName: String = "",
    /** [ApplicationExitInfo.getDescription], as the system wrote it; null when none. */
    val description: String? = null,
) {
    fun toSnapshot(): HistoricalProcessExitSnapshot =
        HistoricalProcessExitSnapshot(
            timestampMillis = timestampMillis,
            reasonCode = reasonCode,
            status = status,
            importance = importance,
            pssKb = pssKb,
            rssKb = rssKb,
            traceAvailable = false,
            pid = pid,
        )
}

internal enum class SystemKillAction(val label: String) {
    /** Submit; advance the watermark only when Hands accepted it (or rejected it for good). */
    Submit("report"),
    /** Not reportable: advance the watermark. */
    Skip("skip"),
    /**
     * Over the daily cap, or a background kill over this launch's background cap: advance the
     * watermark, so a burst never backs up (and background kills never hold up a foreground one).
     */
    RateLimited("rate_limited"),
    /** Over this launch's cap: leave the watermark, the next launch continues here. */
    Deferred("deferred"),
}

internal data class SystemKillPlanItem(
    val exit: SystemKillExit,
    val decision: SystemKillDecision,
    val action: SystemKillAction,
    val telemetry: MemoryTelemetrySnapshot?,
    val budget: SystemKillBudget = SystemKillBudget.Protected,
) {
    val key: SystemKillExitKey get() = SystemKillExitKey(exit.pid, exit.timestampMillis)
}

/** One exit: pid and timestamp (an exit list can repeat an entry). */
internal data class SystemKillExitKey(val pid: Int, val timestampMillis: Long) {
    fun encode(): String = "$pid@$timestampMillis"

    companion object {
        fun decodeAll(encoded: String?): Set<SystemKillExitKey> =
            encoded.orEmpty().split(',').mapNotNull { part ->
                val pid = part.substringBefore('@', "").toIntOrNull() ?: return@mapNotNull null
                val at = part.substringAfter('@', "").toLongOrNull() ?: return@mapNotNull null
                SystemKillExitKey(pid, at)
            }.toSet()

        fun encodeAll(keys: Set<SystemKillExitKey>): String = keys.joinToString(",") { it.encode() }
    }
}

/**
 * What to do with each exit since the watermark, oldest first.
 *
 * Every exit newer than the install of the first build with this reporter and the watermark (the
 * newest exit handled with everything before it), and not in [handledAboveWatermark], is
 * classified; only submissions are capped, in two separate budgets ([systemKillBudget]) so
 * background kills never use up the one for kills Hands keeps:
 * - [SystemKillBudget.Protected]: at most [perLaunchCap] now (the rest are
 *   [SystemKillAction.Deferred], watermark not passed) and at most [dailyCap] in any 24 hours
 *   counting [recentReportMillis] (the rest are [SystemKillAction.RateLimited]).
 * - [SystemKillBudget.Background]: at most [backgroundPerLaunchCap] now and [backgroundDailyCap]
 *   in any 24 hours counting [recentBackgroundReportMillis]; the rest are
 *   [SystemKillAction.RateLimited] (never deferred, so they cannot hold up a later kill) and
 *   counted in the [BackgroundDropLedger].
 * Telemetry is matched per exit ([MemoryTelemetryRing.match]).
 */
internal fun planSystemKillReports(
    exits: List<SystemKillExit>,
    snapshots: List<MemoryTelemetrySnapshot>,
    cutoffMillis: Long,
    watermarkMillis: Long,
    recentReportMillis: List<Long>,
    nowMillis: Long,
    perLaunchCap: Int,
    dailyCap: Int,
    /** The main app process name; exits of any other process are [SystemKillDecision.SkipSecondaryProcess]. */
    mainProcessName: String? = null,
    recentBackgroundReportMillis: List<Long> = emptyList(),
    backgroundPerLaunchCap: Int = perLaunchCap,
    backgroundDailyCap: Int = dailyCap,
    handledAboveWatermark: Set<SystemKillExitKey> = emptySet(),
): List<SystemKillPlanItem> {
    val floor = maxOf(cutoffMillis, watermarkMillis)
    val reportedToday = recentReportMillis.filter { nowMillis - it < DayMillis }.toMutableList()
    val backgroundReportedToday = recentBackgroundReportMillis.filter { nowMillis - it < DayMillis }.toMutableList()
    var submitted = 0
    var backgroundSubmitted = 0
    var deferring = false
    return exits
        .filter { it.timestampMillis > floor && SystemKillExitKey(it.pid, it.timestampMillis) !in handledAboveWatermark }
        .distinctBy { it.pid to it.timestampMillis }
        .sortedBy { it.timestampMillis }
        .map { exit ->
            val telemetry = MemoryTelemetryRing.match(snapshots, exit.pid, exit.timestampMillis)
            val decision =
                // An exit without a process name is kept: nothing says it was another process.
                if (mainProcessName != null && exit.processName.isNotEmpty() && exit.processName != mainProcessName) {
                    SystemKillDecision.SkipSecondaryProcess
                } else {
                    classifySystemKill(exit.reasonCode, exit.importance, telemetry, exit.timestampMillis, exit.status)
                }
            val budget = systemKillBudget(exit, telemetry)
            val action =
                when {
                    // Once deferred, everything after waits too, as the per-launch cap always did.
                    deferring -> SystemKillAction.Deferred
                    !decision.report -> SystemKillAction.Skip
                    budget == SystemKillBudget.Background ->
                        if (backgroundReportedToday.size >= backgroundDailyCap || backgroundSubmitted >= backgroundPerLaunchCap) {
                            SystemKillAction.RateLimited
                        } else {
                            backgroundSubmitted += 1
                            backgroundReportedToday += nowMillis
                            SystemKillAction.Submit
                        }
                    reportedToday.size >= dailyCap -> SystemKillAction.RateLimited
                    submitted >= perLaunchCap -> {
                        deferring = true
                        SystemKillAction.Deferred
                    }
                    else -> {
                        submitted += 1
                        reportedToday += nowMillis
                        SystemKillAction.Submit
                    }
                }
            SystemKillPlanItem(exit, decision, action, telemetry, budget)
        }
}

/**
 * The order one launch handles [plan] in: every protected item (and every skip, rate limit and
 * deferral) in time order first, then the background submissions. A retryable upload error stops
 * the pass, so this way one on a background report can never delay a protected one.
 */
internal fun systemKillProcessingOrder(plan: List<SystemKillPlanItem>): List<SystemKillPlanItem> =
    plan.sortedBy { if (it.action == SystemKillAction.Submit && it.budget == SystemKillBudget.Background) 1 else 0 }

/**
 * The single watermark after handling [handled] (items of [plan], handled out of time order, plus
 * those kept from earlier launches): it passes the longest time-ordered prefix of [plan] that is
 * all handled; handled exits above it are kept so the next plan does not send them again.
 */
internal fun advanceSystemKillWatermark(
    watermarkMillis: Long,
    plan: List<SystemKillPlanItem>,
    handled: Set<SystemKillExitKey>,
): Pair<Long, Set<SystemKillExitKey>> {
    var watermark = watermarkMillis
    for (item in plan.sortedBy { it.exit.timestampMillis }) {
        if (item.key !in handled) break
        watermark = maxOf(watermark, item.exit.timestampMillis)
    }
    return watermark to handled.filterTo(mutableSetOf()) { it.timestampMillis > watermark }
}

/** Dropped background reports of one exit build and UTC exit day. */
internal data class BackgroundDropEntry(val versionCode: Long, val exitDayUtc: String, val count: Int)

/** Entries sent together under one random [id], so Hands can dedupe a resent batch. */
internal data class BackgroundDropBatch(val id: String, val entries: List<BackgroundDropEntry>) {
    /** `crash_background_dropped`: `[{version_code, exit_day_utc, count}]`. */
    fun toJson(): JSONArray =
        JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("version_code", it.versionCode).put("exit_day_utc", it.exitDayUtc).put("count", it.count))
            }
        }
}

/**
 * Background reports dropped over their budget, kept on the device until a later system-kill
 * report carries them (best effort; they stay here if no later report is ever sent).
 *
 * New drops go to [accumulating]. The next upload freezes them into [pending] under a fresh id
 * ([attach]); a failed upload leaves [pending] as it was, so the retry sends the same id and
 * entries, and only a successful one clears it ([uploaded]). At most [MaxEntries] per list; the
 * oldest days go first.
 */
internal data class BackgroundDropLedger(
    val pending: BackgroundDropBatch? = null,
    val accumulating: List<BackgroundDropEntry> = emptyList(),
) {
    fun record(versionCode: Long, exitTimestampMillis: Long): BackgroundDropLedger {
        val day = utcDay(exitTimestampMillis)
        val existing = accumulating.firstOrNull { it.versionCode == versionCode && it.exitDayUtc == day }
        val next =
            if (existing != null) {
                accumulating.map { if (it === existing) it.copy(count = it.count + 1) else it }
            } else {
                accumulating + BackgroundDropEntry(versionCode, day, 1)
            }
        return copy(accumulating = next.sortedWith(compareBy({ it.exitDayUtc }, { it.versionCode })).takeLast(MaxEntries))
    }

    /** The batch the next upload carries (null when nothing was dropped), and the ledger after. */
    fun attach(newId: () -> String): Pair<BackgroundDropLedger, BackgroundDropBatch?> =
        when {
            pending != null -> this to pending
            accumulating.isEmpty() -> this to null
            else -> BackgroundDropBatch(newId(), accumulating).let { BackgroundDropLedger(it, emptyList()) to it }
        }

    fun uploaded(batchId: String?): BackgroundDropLedger =
        if (batchId != null && pending?.id == batchId) copy(pending = null) else this

    fun encode(): String =
        JSONObject().apply {
            pending?.let { put("pending_id", it.id).put("pending", it.toJson()) }
            put("accumulating", BackgroundDropBatch("", accumulating).toJson())
        }.toString()

    companion object {
        const val MaxEntries = 32

        fun decode(encoded: String?): BackgroundDropLedger {
            if (encoded.isNullOrBlank()) return BackgroundDropLedger()
            return runCatching {
                val json = JSONObject(encoded)
                val pendingId = json.optString("pending_id", "")
                BackgroundDropLedger(
                    pending = if (pendingId.isNotEmpty()) BackgroundDropBatch(pendingId, entries(json.optJSONArray("pending"))) else null,
                    accumulating = entries(json.optJSONArray("accumulating")),
                )
            }.getOrDefault(BackgroundDropLedger())
        }

        private fun entries(array: JSONArray?): List<BackgroundDropEntry> =
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val entry = array?.optJSONObject(index) ?: return@mapNotNull null
                BackgroundDropEntry(entry.optLong("version_code", -1L), entry.optString("exit_day_utc"), entry.optInt("count"))
            }

        fun utcDay(timestampMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestampMillis))
    }
}

/** The build an exit ran: its stamp, else the version its telemetry recorded, else -1. */
internal fun systemKillExitVersionCode(exit: SystemKillExit, telemetry: MemoryTelemetrySnapshot?): Long =
    exit.build?.versionCode ?: telemetry?.versionCode?.takeIf { it > 0L } ?: -1L

private const val DayMillis = 24L * 60 * 60 * 1000

/** `crash_exit_description` is cut to this many characters. */
internal const val MaxExitDescriptionChars = 200

/**
 * Whether the exit's own importance says an activity was on screen: foreground (100) or visible
 * (200). Foreground service (125), perceptible (230), service (300), cached (400) and anything
 * else are background. This is the system's view, sent as `crash_foreground`; Hands groups the
 * `false` ones as `SystemKill@background`. The app's own view is `crash_telemetry_foreground`.
 */
internal fun exitImportanceForeground(importance: Int): Boolean = importance in VisibleImportances

/** Ticket metadata (merged into Hands `metadata_json`), in the `crash_*` vocabulary ANR tickets use. */
internal fun systemKillExtras(
    exit: SystemKillExit,
    telemetry: MemoryTelemetrySnapshot?,
    droppedBatch: BackgroundDropBatch? = null,
): Map<String, Any?> =
    buildMap {
        val reason = historicalProcessExitReasonLabel(exit.reasonCode)
        // The exited process's own build; nothing when it carried no stamp (never this install's).
        putAll(RaftBuildStamp.versionExtras(exit.build))
        // Some OEM ROMs return an empty processStateSummary (Hands: version "unknown"). Then the
        // build the same process (pid + start) recorded in its telemetry, still never this install's.
        val fallbackVersion = telemetry?.takeIf { it.versionCode > 0L && it.versionName.isNotBlank() }
        when {
            exit.build != null -> put("crash_version_source", "process_state_summary")
            fallbackVersion != null -> {
                put("fallback_version_code", fallbackVersion.versionCode)
                put("fallback_version_name", fallbackVersion.versionName)
                put("crash_version_source", "last_process_state")
            }
            else -> put("crash_version_source", "unknown")
        }
        put("crash_type", "system_kill")
        put("crash_reason", reason)
        put("crash_exception_class", "SystemKill")
        put("crash_top_frame", reason)
        put("crash_at", exit.timestampMillis)
        put("crash_process_id", exit.pid)
        put("crash_trace_attached", false)
        put("crash_exit_reason", reason)
        put("crash_exit_reason_code", exit.reasonCode)
        put("crash_exit_status", exit.status)
        put("crash_exit_importance", exit.importance)
        put("crash_exit_pss", exit.pssKb)
        put("crash_exit_rss", exit.rssKb)
        put("crash_exit_timestamp", exit.timestampMillis)
        put("crash_foreground", exitImportanceForeground(exit.importance))
        put("crash_process_name", exit.processName)
        exit.description?.trim()?.takeIf(String::isNotEmpty)?.let {
            put("crash_exit_description", it.take(MaxExitDescriptionChars))
        }
        put("crash_memory_sample", if (telemetry == null) "none" else "matched")
        // The app's recent foreground evidence (see classifySystemKill), next to the raw fields:
        // crash_exit_importance, crash_foreground (the exit importance) and crash_trim are never
        // replaced by an inference. Names proposed in botiverse/hands#955.
        val recent = recentForeground(telemetry, exit.timestampMillis)
        recent.value?.let { put("crash_recent_foreground", it) }
        put("crash_recent_foreground_source", recent.source)
        recent.ageSeconds?.let { put("crash_recent_foreground_age_s", it) }
        // Dropped background reports this one carries (best effort; see BackgroundDropLedger).
        if (droppedBatch != null && droppedBatch.entries.isNotEmpty()) {
            put("crash_background_dropped_id", droppedBatch.id)
            put("crash_background_dropped", droppedBatch.toJson())
        }
        if (telemetry != null) {
            val at = exit.timestampMillis
            put("crash_process_uptime_ms", at - telemetry.processStartMillis)
            put("crash_telemetry_foreground", telemetry.foreground)
            put("crash_foreground_change_age_ms", at - telemetry.foregroundChangedAtMillis)
            // The app's last move to background (committed at once, see RaftMemoryTelemetry);
            // unknown, so omitted, when it still saw itself in the foreground.
            if (!telemetry.foreground) {
                put("crash_background_duration_ms", (at - telemetry.foregroundChangedAtMillis).coerceAtLeast(0L))
            }
            put("crash_memory_sample_age_ms", MemoryTelemetrySnapshot.ageAt(at, telemetry.sampledAtMillis))
            // Raw, with the 5 s write granularity (RaftMemoryTelemetry.InteractionWriteIntervalMillis):
            // the real last action can be up to 5 s more recent.
            put("crash_last_interaction_age_ms", MemoryTelemetrySnapshot.ageAt(at, telemetry.lastInteractionAtMillis))
            put("crash_memory_rss_kb", telemetry.rssKb)
            put("crash_memory_pss_kb", telemetry.pssKb)
            put("crash_memory_pss_age_ms", MemoryTelemetrySnapshot.ageAt(at, telemetry.pssSampledAtMillis))
            put("crash_memory_java_heap_used_kb", telemetry.javaHeapUsedKb)
            put("crash_memory_java_heap_max_kb", telemetry.javaHeapMaxKb)
            put("crash_memory_native_heap_kb", telemetry.nativeHeapKb)
            put("crash_memory_system_avail_kb", telemetry.systemAvailKb)
            put("crash_memory_system_threshold_kb", telemetry.systemThresholdKb)
            put("crash_memory_system_low", telemetry.systemLowMemory)
            put("crash_trim_level", telemetry.lastTrimLevel)
            put("crash_trim", trimMemoryLevelLabel(telemetry.lastTrimLevel))
            put("crash_trim_max_level", telemetry.maxTrimLevel)
            put("crash_trim_age_ms", MemoryTelemetrySnapshot.ageAt(at, telemetry.lastTrimAtMillis))
        }
    }

/**
 * The ticket text. The exit record is the system's view at the kill; every detail from the app's
 * telemetry carries how long before the exit it was recorded, since the last snapshot can be many
 * minutes old (Hands 4a5504d3: "in foreground ... trim ui_hidden" from 600 s and 1241 s before an
 * exit at importance 300).
 */
internal fun systemKillMessage(exit: SystemKillExit, telemetry: MemoryTelemetrySnapshot?): String =
    buildString {
        val at = exit.timestampMillis
        append("System kill: ").append(historicalProcessExitReasonLabel(exit.reasonCode))
        append(" (reasonCode=").append(exit.reasonCode)
        append(" status=").append(exit.status)
        append(" importance=").append(exit.importance).append(')')
        if (telemetry == null) return@buildString
        val system =
            "system: " + (if (exitImportanceForeground(exit.importance)) "foreground" else "background") +
                ", importance " + exit.importance
        append('\n')
        if (telemetry.foreground) {
            if (telemetry.sampledAtMillis >= 0L) {
                append("last seen in foreground ").append(beforeExit(at, telemetry.sampledAtMillis))
                append(" (in foreground since ").append(beforeExit(at, telemetry.foregroundChangedAtMillis)).append("; ")
            } else {
                append("in foreground since ").append(beforeExit(at, telemetry.foregroundChangedAtMillis))
                append(", never sampled (")
            }
            append(system)
            if (!hasFreshForegroundEvidence(telemetry, at)) {
                append("; no sample or interaction in the last ").append(SystemKillFreshSampleMillis / 1000L)
                    .append("s, not trusted")
            }
            append(')')
        } else {
            append("in background since ").append(beforeExit(at, telemetry.foregroundChangedAtMillis))
            append(" (").append(system).append(')')
        }
        append("\n").append((at - telemetry.processStartMillis) / 1000L).append("s after launch")
        if (telemetry.lastInteractionAtMillis >= 0L) {
            append(", last interaction ").append(beforeExit(at, telemetry.lastInteractionAtMillis))
        }
        if (telemetry.rssKb >= 0L) {
            append(", last RSS ").append(telemetry.rssKb / 1024L).append(" MB (")
                .append(beforeExit(at, telemetry.sampledAtMillis)).append(')')
        }
        if (telemetry.pssKb >= 0L) {
            append(", last PSS ").append(telemetry.pssKb / 1024L).append(" MB (")
                .append(beforeExit(at, telemetry.pssSampledAtMillis)).append(')')
        }
        if (telemetry.lastTrimLevel == 0 || telemetry.lastTrimAtMillis < 0L) {
            append(", no trim")
        } else {
            append(", last trim ").append(trimMemoryLevelLabel(telemetry.lastTrimLevel))
            append(" (").append(beforeExit(at, telemetry.lastTrimAtMillis))
            // A trim from before the last foreground change describes the earlier state, not this one.
            if (telemetry.lastTrimAtMillis < telemetry.foregroundChangedAtMillis) {
                append(", before the move to ").append(if (telemetry.foreground) "foreground" else "background")
            }
            append(')')
        }
    }

/** "570s before exit"; "unknown time" for an unset (-1) timestamp, "Ns after exit" after a clock change. */
private fun beforeExit(exitMillis: Long, atMillis: Long): String {
    if (atMillis < 0L) return "unknown time"
    val age = exitMillis - atMillis
    return if (age >= 0L) "${age / 1000L}s before exit" else "${-age / 1000L}s after exit"
}

/** Report timestamps in the last 24 hours, as persisted (comma separated). */
internal fun decodeRecentReports(encoded: String?, nowMillis: Long): List<Long> =
    encoded.orEmpty().split(',').mapNotNull(String::toLongOrNull).filter { nowMillis - it < DayMillis }

/**
 * Sends system kills of earlier processes (foreground and background) to Hands as `kind=crash` tickets, the way
 * ANRs are sent, so a kill like Hands fb-52cc0499 shows up without anyone filing feedback.
 *
 * Exactly-once: an exit is handled once its ticket was accepted, it was not reportable or rate
 * limited, or Hands rejected it for good. Protected reports go before background ones
 * ([systemKillProcessingOrder]), so the single watermark passes only the time-ordered prefix that
 * is all handled, and handled exits above it are kept ([advanceSystemKillWatermark]). A failed
 * upload stops the pass and is retried next launch; its telemetry is still in the ring
 * ([MemoryTelemetryRing]), so the retry classifies it the same way.
 */
internal object AndroidSystemKillReporter {
    private const val Tag = "SlockAndroidProcessExit"
    private const val PreferencesName = "raft-process-exit-diagnostics"
    private const val WatermarkKey = "system-kill-reported-timestamp-millis"
    /** Foreground reports (and every report before the background budget existed). */
    private const val RecentReportsKey = "system-kill-recent-reports"
    private const val RecentBackgroundReportsKey = "system-kill-recent-background-reports"
    private const val HandledAboveWatermarkKey = "system-kill-handled-above-watermark"
    private const val DroppedLedgerKey = "system-kill-background-dropped"
    private const val MaxExitsQueried = 16
    internal const val MaxReportsPerLaunch = 3
    internal const val MaxReportsPerDay = 5
    internal const val MaxBackgroundReportsPerLaunch = 3
    internal const val MaxBackgroundReportsPerDay = 5
    private val started = AtomicBoolean(false)
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs in the background; the Hands client is only built when there is something to send. */
    fun start(context: Context, baseUrl: String, appSlug: String, channel: String?, clientKey: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        uploadScope.launch {
            runCatching {
                val feedback by lazy {
                    // Version comes from each exit's stamp in the extras, never this install.
                    HandsFeedback(
                        context = appContext,
                        baseUrl = baseUrl,
                        appSlug = appSlug,
                        versionName = null,
                        versionCode = null,
                        channel = channel,
                        clientKey = clientKey,
                    )
                }
                reportPending(appContext) { message, extras ->
                    feedback.submit(message = message, kind = "crash", extras = extras)
                }
            }.onFailure { error ->
                RaftAndroidLocalDiagnostics.recordLog("warn", Tag, "system_kill_report_failed class=${error.javaClass.name}")
            }
        }
    }

    private suspend fun reportPending(
        context: Context,
        submit: suspend (message: String, extras: Map<String, Any?>) -> String,
    ) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val snapshots = RaftMemoryTelemetry.previousSnapshots.await()
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var watermark = preferences.getLong(WatermarkKey, 0L)
        val handled = SystemKillExitKey.decodeAll(preferences.getString(HandledAboveWatermarkKey, null)).toMutableSet()
        val plan =
            planSystemKillReports(
                exits =
                    activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MaxExitsQueried)
                        .map { it.toSystemKillExit() },
                snapshots = snapshots,
                // Before the first launch with this reporter nothing was decided; do not look back.
                cutoffMillis = firstSeenMillis(preferences, now),
                watermarkMillis = watermark,
                recentReportMillis = decodeRecentReports(preferences.getString(RecentReportsKey, null), now),
                nowMillis = now,
                perLaunchCap = MaxReportsPerLaunch,
                dailyCap = MaxReportsPerDay,
                recentBackgroundReportMillis =
                    decodeRecentReports(preferences.getString(RecentBackgroundReportsKey, null), now),
                backgroundPerLaunchCap = MaxBackgroundReportsPerLaunch,
                backgroundDailyCap = MaxBackgroundReportsPerDay,
                mainProcessName = context.applicationInfo.processName ?: context.packageName,
                handledAboveWatermark = handled,
            )
        for (item in systemKillProcessingOrder(plan)) {
            val exit = item.exit
            var ticket = "none"
            when (item.action) {
                SystemKillAction.Deferred -> {
                    log(item, ticket)
                    continue
                }
                SystemKillAction.Submit -> {
                    val (ledger, dropped) = readDropLedger(preferences).attach { UUID.randomUUID().toString() }
                    writeDropLedger(preferences, ledger)
                    val result =
                        runCatching {
                            submit(systemKillMessage(exit, item.telemetry), systemKillExtras(exit, item.telemetry, dropped))
                        }
                    val error = result.exceptionOrNull()
                    if (error != null && !isPermanentHandsRejection(error)) {
                        RaftAndroidLocalDiagnostics.recordLog(
                            "warn",
                            Tag,
                            "system_kill_report_upload_failed timestampMillis=${exit.timestampMillis} " +
                                "class=${error.javaClass.name} retry=next_launch",
                        )
                        return
                    }
                    ticket = result.getOrNull()?.take(8) ?: "rejected"
                    // Only an accepted upload clears the dropped entries it carried.
                    if (result.isSuccess) writeDropLedger(preferences, readDropLedger(preferences).uploaded(dropped?.id))
                    recordReport(
                        preferences,
                        if (item.budget == SystemKillBudget.Background) RecentBackgroundReportsKey else RecentReportsKey,
                        now,
                    )
                }
                SystemKillAction.RateLimited ->
                    if (item.budget == SystemKillBudget.Background) {
                        writeDropLedger(
                            preferences,
                            readDropLedger(preferences).record(systemKillExitVersionCode(exit, item.telemetry), exit.timestampMillis),
                        )
                    }
                SystemKillAction.Skip -> Unit
            }
            handled += item.key
            val (nextWatermark, above) = advanceSystemKillWatermark(watermark, plan, handled)
            watermark = nextWatermark
            handled.retainAll(above)
            preferences.edit()
                .putLong(WatermarkKey, watermark)
                .putString(HandledAboveWatermarkKey, SystemKillExitKey.encodeAll(above))
                .commit()
            log(item, ticket)
        }
    }

    private fun log(item: SystemKillPlanItem, ticket: String) {
        RaftAndroidLocalDiagnostics.recordLog(
            if (item.action == SystemKillAction.Submit) "warn" else "info",
            Tag,
            "system_kill_report action=${item.action.label} decision=${item.decision.label} " +
                "timestampMillis=${item.exit.timestampMillis} reason=${historicalProcessExitReasonLabel(item.exit.reasonCode)} " +
                "importance=${item.exit.importance} budget=${item.budget.label} telemetry=${if (item.telemetry != null) "matched" else "none"} " +
                "stamped=${item.exit.build != null} process=${item.exit.processName} ticket=$ticket",
        )
    }

    private fun firstSeenMillis(preferences: SharedPreferences, now: Long): Long {
        val key = "system-kill-first-seen-millis"
        if (!preferences.contains(key)) preferences.edit().putLong(key, now).commit()
        return preferences.getLong(key, now)
    }

    private fun readDropLedger(preferences: SharedPreferences): BackgroundDropLedger =
        BackgroundDropLedger.decode(preferences.getString(DroppedLedgerKey, null))

    private fun writeDropLedger(preferences: SharedPreferences, ledger: BackgroundDropLedger) {
        preferences.edit().putString(DroppedLedgerKey, ledger.encode()).commit()
    }

    private fun recordReport(preferences: SharedPreferences, key: String, now: Long) {
        val recent = decodeRecentReports(preferences.getString(key, null), now) + now
        preferences.edit().putString(key, recent.joinToString(",")).commit()
    }

    private fun ApplicationExitInfo.toSystemKillExit(): SystemKillExit =
        SystemKillExit(
            pid = pid,
            timestampMillis = timestamp,
            reasonCode = reason,
            status = status,
            importance = importance,
            pssKb = pss,
            rssKb = rss,
            build = RaftBuildStamp.decodeStamp(processStateSummary),
            processName = processName.orEmpty(),
            description = description,
        )
}

/** Hands answers 4xx other than timeout/rate limit for a ticket that will never be accepted. */
internal fun isPermanentHandsRejection(error: Throwable): Boolean {
    val code = (error as? HandsFeedbackException)?.code ?: return false
    return code in 400..499 && code != 408 && code != 429
}
