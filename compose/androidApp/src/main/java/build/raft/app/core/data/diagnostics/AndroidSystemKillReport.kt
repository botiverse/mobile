package build.raft.app.core.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import build.hands.update.HandsFeedbackException
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
    /** Java/native crashes and ANRs already reach Hands through HandsCrash. */
    SkipReportedByHandsCrash("hands_crash", false),
    /** Killed while cached/background: routine memory management the user never sees. */
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

private val HandsCrashReasons =
    setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

/**
 * Every other reason is a system kill: unknown, signaled, low_memory, initialization_failure,
 * excessive_resource_usage, dependency_died, other, freezer, and codes added after API 35. On a
 * device that does not explain its kills (the Huawei in Hands fb-52cc0499 recorded
 * `reason=other`) any of them can be the "app vanished" users describe.
 *
 * Report a system kill when the user could see it:
 * - the system's own importance at death is perceptible or better (foreground, foreground
 *   service, visible, perceptible), or
 * - this app's telemetry for the same pid says an activity was started. The Huawei kill in
 *   fb-52cc0499 recorded `importance=400` (cached) one second after the user scrolled, so the
 *   recorded importance alone misses exactly the case this exists for.
 */
internal fun classifySystemKill(
    reasonCode: Int,
    importance: Int,
    telemetryForeground: Boolean?,
): SystemKillDecision =
    when {
        reasonCode in UserInitiatedReasons -> SystemKillDecision.SkipUserInitiated
        reasonCode in PackageChangeReasons -> SystemKillDecision.SkipPackageChange
        reasonCode in HandsCrashReasons -> SystemKillDecision.SkipReportedByHandsCrash
        importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> SystemKillDecision.Report
        telemetryForeground == true -> SystemKillDecision.Report
        else -> SystemKillDecision.SkipNotImportant
    }

internal data class SystemKillCandidate(
    val pid: Int,
    val timestampMillis: Long,
)

/**
 * Exits not yet considered, oldest first: newer than both the install of this build and the
 * watermark (the newest exit already handled), one per (pid, timestamp), newest [limit] kept.
 * The watermark is what makes one exit one ticket: it only moves once an exit is handled.
 */
internal fun <T> selectUnreportedExits(
    exits: List<T>,
    candidate: (T) -> SystemKillCandidate,
    packageInstalledAtMillis: Long,
    watermarkMillis: Long,
    limit: Int,
): List<T> {
    val cutoff = maxOf(packageInstalledAtMillis, watermarkMillis)
    return exits
        .filter { candidate(it).timestampMillis > cutoff }
        .distinctBy { candidate(it) }
        .sortedBy { candidate(it).timestampMillis }
        .takeLast(limit)
}

/** Ticket metadata (merged into Hands `metadata_json`), in the `crash_*` vocabulary HandsAnr uses. */
internal fun systemKillExtras(
    snapshot: HistoricalProcessExitSnapshot,
    telemetry: MemoryTelemetrySnapshot?,
    versionName: String,
    versionCode: Long,
): Map<String, Any?> =
    buildMap {
        val reason = historicalProcessExitReasonLabel(snapshot.reasonCode)
        put("crash_type", "system_kill")
        put("crash_reason", reason)
        put("crash_exception_class", "SystemKill")
        put("crash_top_frame", reason)
        put("crash_at", snapshot.timestampMillis)
        put("crash_process_id", snapshot.pid)
        put("crash_trace_attached", false)
        put("crash_exit_reason", reason)
        put("crash_exit_reason_code", snapshot.reasonCode)
        put("crash_exit_status", snapshot.status)
        put("crash_exit_importance", snapshot.importance)
        put("crash_exit_pss", snapshot.pssKb)
        put("crash_exit_rss", snapshot.rssKb)
        put("crash_exit_timestamp", snapshot.timestampMillis)
        put("version_name", versionName)
        put("version_code", versionCode)
        put("crash_memory_sample", if (telemetry == null) "none" else "matched")
        if (telemetry != null) {
            put("crash_process_uptime_ms", snapshot.timestampMillis - telemetry.processStartMillis)
            put("crash_foreground", telemetry.foreground)
            put("crash_foreground_change_age_ms", snapshot.timestampMillis - telemetry.foregroundChangedAtMillis)
            put(
                "crash_memory_sample_age_ms",
                if (telemetry.sampledAtMillis >= 0L) snapshot.timestampMillis - telemetry.sampledAtMillis else null,
            )
            put("crash_memory_pss_kb", telemetry.pssKb)
            put("crash_memory_java_heap_used_kb", telemetry.javaHeapUsedKb)
            put("crash_memory_java_heap_max_kb", telemetry.javaHeapMaxKb)
            put("crash_memory_native_heap_kb", telemetry.nativeHeapKb)
            put("crash_memory_system_avail_kb", telemetry.systemAvailKb)
            put("crash_memory_system_threshold_kb", telemetry.systemThresholdKb)
            put("crash_memory_system_low", telemetry.systemLowMemory)
            put("crash_trim_level", telemetry.lastTrimLevel)
            put("crash_trim", trimMemoryLevelLabel(telemetry.lastTrimLevel))
            put("crash_trim_max_level", telemetry.maxTrimLevel)
            put(
                "crash_trim_age_ms",
                if (telemetry.lastTrimAtMillis >= 0L) snapshot.timestampMillis - telemetry.lastTrimAtMillis else null,
            )
        }
    }

internal fun systemKillMessage(snapshot: HistoricalProcessExitSnapshot, telemetry: MemoryTelemetrySnapshot?): String =
    buildString {
        append("System kill: ").append(historicalProcessExitReasonLabel(snapshot.reasonCode))
        append(" (reasonCode=").append(snapshot.reasonCode)
        append(" status=").append(snapshot.status)
        append(" importance=").append(snapshot.importance).append(')')
        if (telemetry != null) {
            append("\n").append(if (telemetry.foreground) "in foreground" else "in background")
            append(", ").append((snapshot.timestampMillis - telemetry.processStartMillis) / 1000L).append("s after launch")
            if (telemetry.pssKb >= 0L) append(", last PSS ").append(telemetry.pssKb / 1024L).append(" MB")
            append(", trim ").append(trimMemoryLevelLabel(telemetry.lastTrimLevel))
        }
    }

/**
 * Sends system kills of the previous process(es) to Hands as `kind=crash` tickets, the way the
 * Hands SDK sends ANRs, so a kill like Hands fb-52cc0499 shows up without anyone filing feedback.
 *
 * Exactly-once: a watermark (newest handled exit timestamp) is persisted only after the ticket
 * was accepted, or the exit was classified as not reportable, or rejected permanently. A failed
 * upload stops the pass and is retried next launch.
 */
internal object AndroidSystemKillReporter {
    private const val Tag = "SlockAndroidProcessExit"
    private const val PreferencesName = "raft-process-exit-diagnostics"
    private const val WatermarkKey = "system-kill-reported-timestamp-millis"
    private const val MaxExitsQueried = 16
    internal const val MaxReportsPerLaunch = 3
    private val started = AtomicBoolean(false)

    /** One upload; returns the ticket id or throws. */
    fun interface Submitter {
        suspend fun submit(message: String, extras: Map<String, Any?>): String
    }

    fun start(
        context: Context,
        versionName: String,
        versionCode: Long,
        submitter: Submitter,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val telemetry = RaftMemoryTelemetry.previousProcessSnapshot()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { reportPending(appContext, telemetry, versionName, versionCode, submitter) }
                .onFailure { error ->
                    RaftAndroidLocalDiagnostics.recordLog(
                        "warn",
                        Tag,
                        "system_kill_report_failed class=${error.javaClass.name}",
                    )
                }
        }
    }

    private suspend fun reportPending(
        context: Context,
        telemetry: MemoryTelemetrySnapshot?,
        versionName: String,
        versionCode: Long,
        submitter: Submitter,
    ) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val packageInstalledAtMillis =
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MaxExitsQueried)
        val pending =
            selectUnreportedExits(
                exits = exits,
                candidate = { SystemKillCandidate(it.pid, it.timestamp) },
                packageInstalledAtMillis = packageInstalledAtMillis,
                watermarkMillis = preferences.getLong(WatermarkKey, 0L),
                limit = MaxReportsPerLaunch,
            )
        for (exit in pending) {
            // Exits are newer than this build's install, so they ran this version.
            val snapshot = exit.toSnapshot()
            val matched = telemetry?.takeIf { it.pid == exit.pid }
            val decision = classifySystemKill(exit.reason, exit.importance, matched?.foreground)
            var ticket = "none"
            if (decision.report) {
                val result =
                    runCatching {
                        submitter.submit(
                            message = systemKillMessage(snapshot, matched),
                            extras = systemKillExtras(snapshot, matched, versionName, versionCode),
                        )
                    }
                val error = result.exceptionOrNull()
                if (error != null && !isPermanentHandsRejection(error)) {
                    RaftAndroidLocalDiagnostics.recordLog(
                        "warn",
                        Tag,
                        "system_kill_report_upload_failed timestampMillis=${exit.timestamp} " +
                            "class=${error.javaClass.name} retry=next_launch",
                    )
                    return
                }
                ticket = result.getOrNull()?.take(8) ?: "rejected"
            }
            preferences.edit().putLong(WatermarkKey, exit.timestamp).commit()
            RaftAndroidLocalDiagnostics.recordLog(
                if (decision.report) "warn" else "info",
                Tag,
                "system_kill_report decision=${decision.label} timestampMillis=${exit.timestamp} " +
                    "reason=${historicalProcessExitReasonLabel(exit.reason)} importance=${exit.importance} " +
                    "telemetry=${if (matched != null) "matched" else "none"} ticket=$ticket",
            )
        }
    }

    private fun ApplicationExitInfo.toSnapshot(): HistoricalProcessExitSnapshot =
        HistoricalProcessExitSnapshot(
            timestampMillis = timestamp,
            reasonCode = reason,
            status = status,
            importance = importance,
            pssKb = pss,
            rssKb = rss,
            traceAvailable = false,
            pid = pid,
        )
}

/** Hands answers 4xx other than timeout/rate limit for a ticket that will never be accepted. */
internal fun isPermanentHandsRejection(error: Throwable): Boolean {
    val code = (error as? HandsFeedbackException)?.code ?: return false
    return code in 400..499 && code != 408 && code != 429
}
