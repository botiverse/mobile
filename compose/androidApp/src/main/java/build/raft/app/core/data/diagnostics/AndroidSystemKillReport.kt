package build.raft.app.core.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import build.hands.update.HandsFeedback
import build.hands.update.HandsFeedbackException
import build.raft.app.shared.RaftBuildStamp
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Why a previous exit is, or is not, sent to Hands as a `system_kill` crash ticket. */
internal enum class SystemKillDecision(val label: String, val report: Boolean) {
    Report("report", true),
    /** The user (swipe away, force stop, revoked permission) or the app itself ended it. */
    SkipUserInitiated("user_initiated", false),
    /** An install or package change; not a failure. */
    SkipPackageChange("package_change", false),
    /** Java/native crashes go through HandsCrash, ANRs through HandsCrash's ANR pass. */
    SkipReportedElsewhere("reported_elsewhere", false),
    /** Killed while no activity was visible: routine memory/power management. */
    SkipNotImportant("not_important", false),
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

/** The importances that mean an activity was on screen: foreground (100) and visible (200). */
private val VisibleImportances =
    setOf(
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
    )

/**
 * Every reason not listed above is a system kill: unknown, signaled, low_memory,
 * initialization_failure, excessive_resource_usage, dependency_died, other, freezer, and codes
 * added after API 35. On a device that does not explain its kills (the Huawei in Hands
 * fb-52cc0499 recorded `reason=other`) any of them can be the "app vanished" users describe.
 *
 * A system kill is reported only when an activity was on screen:
 * - when this app's telemetry for the exited process exists ([telemetryForeground] non-null),
 *   it decides. The Huawei kill recorded `importance=400` one second after the user scrolled,
 *   and a background kill of the process kept alive by the foreground service
 *   (RaftBackgroundConnectionService, on by default without Play services) records 125;
 *   telemetry gets both right.
 * - without telemetry, only importance foreground (100) or visible (200) counts. Foreground
 *   service (125) and perceptible (230) do not: that is the service-held background process,
 *   and reporting its swipe-away or power-manager kills would flood Hands.
 */
internal fun classifySystemKill(
    reasonCode: Int,
    importance: Int,
    telemetryForeground: Boolean?,
): SystemKillDecision =
    when {
        reasonCode in UserInitiatedReasons -> SystemKillDecision.SkipUserInitiated
        reasonCode in PackageChangeReasons -> SystemKillDecision.SkipPackageChange
        reasonCode in ReportedElsewhereReasons -> SystemKillDecision.SkipReportedElsewhere
        telemetryForeground != null ->
            if (telemetryForeground) SystemKillDecision.Report else SystemKillDecision.SkipNotImportant
        importance in VisibleImportances -> SystemKillDecision.Report
        else -> SystemKillDecision.SkipNotImportant
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
    /** Over the daily cap: advance the watermark, so a burst never backs up. */
    RateLimited("rate_limited"),
    /** Over this launch's cap: leave the watermark, the next launch continues here. */
    Deferred("deferred"),
}

internal data class SystemKillPlanItem(
    val exit: SystemKillExit,
    val decision: SystemKillDecision,
    val action: SystemKillAction,
    val telemetry: MemoryTelemetrySnapshot?,
)

/**
 * What to do with each exit since the watermark, oldest first.
 *
 * Every exit newer than the install of the first build with this reporter and the watermark (the
 * newest exit already handled) is classified; only submissions are capped. At most
 * [perLaunchCap] are submitted now (the rest are [SystemKillAction.Deferred], watermark not
 * passed), and at most [dailyCap] in any 24 hours counting [recentReportMillis] (the rest are
 * [SystemKillAction.RateLimited]). Telemetry is matched per exit ([MemoryTelemetryRing.match]).
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
): List<SystemKillPlanItem> {
    val floor = maxOf(cutoffMillis, watermarkMillis)
    val reportedToday = recentReportMillis.filter { nowMillis - it < DayMillis }.toMutableList()
    var submitted = 0
    var deferring = false
    return exits
        .filter { it.timestampMillis > floor }
        .distinctBy { it.pid to it.timestampMillis }
        .sortedBy { it.timestampMillis }
        .map { exit ->
            val telemetry = MemoryTelemetryRing.match(snapshots, exit.pid, exit.timestampMillis)
            val decision = classifySystemKill(exit.reasonCode, exit.importance, telemetry?.foreground)
            val action =
                when {
                    // Once deferred, everything after waits too: the watermark cannot skip over it.
                    deferring -> SystemKillAction.Deferred
                    !decision.report -> SystemKillAction.Skip
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
            SystemKillPlanItem(exit, decision, action, telemetry)
        }
}

private const val DayMillis = 24L * 60 * 60 * 1000

/** Ticket metadata (merged into Hands `metadata_json`), in the `crash_*` vocabulary ANR tickets use. */
internal fun systemKillExtras(
    exit: SystemKillExit,
    telemetry: MemoryTelemetrySnapshot?,
): Map<String, Any?> =
    buildMap {
        val reason = historicalProcessExitReasonLabel(exit.reasonCode)
        // The exited process's own build; nothing when it carried no stamp (never this install's).
        putAll(RaftBuildStamp.versionExtras(exit.build))
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
        put("crash_memory_sample", if (telemetry == null) "none" else "matched")
        if (telemetry != null) {
            val at = exit.timestampMillis
            put("crash_process_uptime_ms", at - telemetry.processStartMillis)
            put("crash_foreground", telemetry.foreground)
            put("crash_foreground_change_age_ms", at - telemetry.foregroundChangedAtMillis)
            put("crash_memory_sample_age_ms", MemoryTelemetrySnapshot.ageAt(at, telemetry.sampledAtMillis))
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

internal fun systemKillMessage(exit: SystemKillExit, telemetry: MemoryTelemetrySnapshot?): String =
    buildString {
        append("System kill: ").append(historicalProcessExitReasonLabel(exit.reasonCode))
        append(" (reasonCode=").append(exit.reasonCode)
        append(" status=").append(exit.status)
        append(" importance=").append(exit.importance).append(')')
        if (telemetry != null) {
            append("\n").append(if (telemetry.foreground) "in foreground" else "in background")
            append(", ").append((exit.timestampMillis - telemetry.processStartMillis) / 1000L).append("s after launch")
            if (telemetry.rssKb >= 0L) append(", last RSS ").append(telemetry.rssKb / 1024L).append(" MB")
            if (telemetry.pssKb >= 0L) append(", last PSS ").append(telemetry.pssKb / 1024L).append(" MB")
            append(", trim ").append(trimMemoryLevelLabel(telemetry.lastTrimLevel))
        }
    }

/** Report timestamps in the last 24 hours, as persisted (comma separated). */
internal fun decodeRecentReports(encoded: String?, nowMillis: Long): List<Long> =
    encoded.orEmpty().split(',').mapNotNull(String::toLongOrNull).filter { nowMillis - it < DayMillis }

/**
 * Sends foreground system kills of earlier processes to Hands as `kind=crash` tickets, the way
 * ANRs are sent, so a kill like Hands fb-52cc0499 shows up without anyone filing feedback.
 *
 * Exactly-once: a watermark (newest handled exit timestamp) moves only after the ticket was
 * accepted, the exit was not reportable or rate limited, or Hands rejected it for good. A failed
 * upload stops the pass and is retried next launch; its telemetry is still in the ring
 * ([MemoryTelemetryRing]), so the retry classifies it the same way.
 */
internal object AndroidSystemKillReporter {
    private const val Tag = "SlockAndroidProcessExit"
    private const val PreferencesName = "raft-process-exit-diagnostics"
    private const val WatermarkKey = "system-kill-reported-timestamp-millis"
    private const val RecentReportsKey = "system-kill-recent-reports"
    private const val MaxExitsQueried = 16
    internal const val MaxReportsPerLaunch = 3
    internal const val MaxReportsPerDay = 5
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
        val plan =
            planSystemKillReports(
                exits =
                    activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MaxExitsQueried)
                        .map { it.toSystemKillExit() },
                snapshots = snapshots,
                // Before the first launch with this reporter nothing was decided; do not look back.
                cutoffMillis = firstSeenMillis(preferences, now),
                watermarkMillis = preferences.getLong(WatermarkKey, 0L),
                recentReportMillis = decodeRecentReports(preferences.getString(RecentReportsKey, null), now),
                nowMillis = now,
                perLaunchCap = MaxReportsPerLaunch,
                dailyCap = MaxReportsPerDay,
            )
        for (item in plan) {
            val exit = item.exit
            var ticket = "none"
            when (item.action) {
                SystemKillAction.Deferred -> {
                    log(item, ticket)
                    continue
                }
                SystemKillAction.Submit -> {
                    val result =
                        runCatching { submit(systemKillMessage(exit, item.telemetry), systemKillExtras(exit, item.telemetry)) }
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
                    recordReport(preferences, now)
                }
                SystemKillAction.Skip, SystemKillAction.RateLimited -> Unit
            }
            preferences.edit().putLong(WatermarkKey, exit.timestampMillis).commit()
            log(item, ticket)
        }
    }

    private fun log(item: SystemKillPlanItem, ticket: String) {
        RaftAndroidLocalDiagnostics.recordLog(
            if (item.action == SystemKillAction.Submit) "warn" else "info",
            Tag,
            "system_kill_report action=${item.action.label} decision=${item.decision.label} " +
                "timestampMillis=${item.exit.timestampMillis} reason=${historicalProcessExitReasonLabel(item.exit.reasonCode)} " +
                "importance=${item.exit.importance} telemetry=${if (item.telemetry != null) "matched" else "none"} " +
                "stamped=${item.exit.build != null} ticket=$ticket",
        )
    }

    private fun firstSeenMillis(preferences: SharedPreferences, now: Long): Long {
        val key = "system-kill-first-seen-millis"
        if (!preferences.contains(key)) preferences.edit().putLong(key, now).commit()
        return preferences.getLong(key, now)
    }

    private fun recordReport(preferences: SharedPreferences, now: Long) {
        val recent = decodeRecentReports(preferences.getString(RecentReportsKey, null), now) + now
        preferences.edit().putString(RecentReportsKey, recent.joinToString(",")).commit()
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
        )
}

/** Hands answers 4xx other than timeout/rate limit for a ticket that will never be accepted. */
internal fun isPermanentHandsRejection(error: Throwable): Boolean {
    val code = (error as? HandsFeedbackException)?.code ?: return false
    return code in 400..499 && code != 408 && code != 429
}
