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
 * Whether [telemetry]'s last sample is recent enough at [exitTimestampMillis] to trust its
 * foreground flag: taken 0..[SystemKillFreshSampleMillis] before the exit. No sample (-1) or one
 * after the exit (a clock change) is not.
 */
internal fun isTelemetrySampleFresh(telemetry: MemoryTelemetrySnapshot, exitTimestampMillis: Long): Boolean =
    isWithinFreshWindow(telemetry.sampledAtMillis, exitTimestampMillis)

/**
 * Whether the user touched the app or pressed a key 0..[SystemKillFreshSampleMillis] before the
 * exit ([MemoryTelemetrySnapshot.lastInteractionAtMillis]). The Huawei kill fb-52cc0499 was
 * recorded at importance 400 one second after the user scrolled.
 */
internal fun isTelemetryInteractionFresh(telemetry: MemoryTelemetrySnapshot, exitTimestampMillis: Long): Boolean =
    isWithinFreshWindow(telemetry.lastInteractionAtMillis, exitTimestampMillis)

/** What says the app was in the foreground shortly before the exit, and how long before. */
internal data class ForegroundEvidence(val kind: Kind, val ageMillis: Long) {
    enum class Kind(val label: String) { Sample("sample"), Interaction("interaction") }
}

/**
 * Fresh foreground evidence from the app, either source at most [SystemKillFreshSampleMillis]
 * before the exit (the interaction when both are: it is the user, not a timer); null when neither,
 * even if the other source is missing:
 * - the user's last interaction, whatever the snapshot's flag says now: the user may have acted,
 *   switched away, and the app was killed.
 * - the last sample, only while the snapshot still says "in foreground" (a background snapshot's
 *   sample is from before its move to background).
 */
internal fun recentForegroundEvidence(telemetry: MemoryTelemetrySnapshot?, exitTimestampMillis: Long): ForegroundEvidence? {
    if (telemetry == null) return null
    return when {
        isTelemetryInteractionFresh(telemetry, exitTimestampMillis) ->
            ForegroundEvidence(ForegroundEvidence.Kind.Interaction, exitTimestampMillis - telemetry.lastInteractionAtMillis)
        telemetry.foreground && isTelemetrySampleFresh(telemetry, exitTimestampMillis) ->
            ForegroundEvidence(ForegroundEvidence.Kind.Sample, exitTimestampMillis - telemetry.sampledAtMillis)
        else -> null
    }
}

internal fun hasFreshForegroundEvidence(telemetry: MemoryTelemetrySnapshot, exitTimestampMillis: Long): Boolean =
    recentForegroundEvidence(telemetry, exitTimestampMillis) != null

/** A recorded time (not -1) at or before the exit. */
private fun isKnownBeforeExit(atMillis: Long, exitTimestampMillis: Long): Boolean =
    atMillis >= 0L && atMillis <= exitTimestampMillis

private fun isWithinFreshWindow(atMillis: Long, exitTimestampMillis: Long): Boolean =
    atMillis >= 0L && exitTimestampMillis - atMillis in 0L..SystemKillFreshSampleMillis

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
)

/**
 * What to do with each exit since the watermark, oldest first.
 *
 * Every exit newer than the install of the first build with this reporter and the watermark (the
 * newest exit already handled) is classified; only submissions are capped, in two separate
 * budgets so background kills never use up the one for kills the user saw:
 * - foreground ([SystemKillDecision.Report]: importance 100/200 or recent foreground evidence): at
 *   most [perLaunchCap] now (the rest are [SystemKillAction.Deferred], watermark not passed) and
 *   at most [dailyCap] in any 24 hours counting [recentReportMillis] (the rest are
 *   [SystemKillAction.RateLimited]).
 * - background ([SystemKillDecision.ReportBackground]): at most [backgroundPerLaunchCap] now and
 *   [backgroundDailyCap] in any 24 hours counting [recentBackgroundReportMillis]; the rest are
 *   [SystemKillAction.RateLimited] (never deferred, so they cannot hold up a later foreground kill).
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
): List<SystemKillPlanItem> {
    val floor = maxOf(cutoffMillis, watermarkMillis)
    val reportedToday = recentReportMillis.filter { nowMillis - it < DayMillis }.toMutableList()
    val backgroundReportedToday = recentBackgroundReportMillis.filter { nowMillis - it < DayMillis }.toMutableList()
    var submitted = 0
    var backgroundSubmitted = 0
    var deferring = false
    return exits
        .filter { it.timestampMillis > floor }
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
            val action =
                when {
                    // Once deferred, everything after waits too: the watermark cannot skip over it.
                    deferring -> SystemKillAction.Deferred
                    !decision.report -> SystemKillAction.Skip
                    decision == SystemKillDecision.ReportBackground ->
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
            SystemKillPlanItem(exit, decision, action, telemetry)
        }
}

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
        val evidence = recentForegroundEvidence(telemetry, exit.timestampMillis)
        when {
            evidence != null -> {
                put("crash_recent_foreground", true)
                put("crash_recent_foreground_source", evidence.kind.label)
                put("crash_recent_foreground_age_s", evidence.ageMillis / 1000L)
            }
            // "No" only when both the sample and the interaction time are known and not evidence.
            telemetry != null &&
                isKnownBeforeExit(telemetry.sampledAtMillis, exit.timestampMillis) &&
                isKnownBeforeExit(telemetry.lastInteractionAtMillis, exit.timestampMillis) -> {
                put("crash_recent_foreground", false)
                put("crash_recent_foreground_source", "none")
            }
            // No snapshot, never sampled, no interaction recorded (or an older build's snapshot),
            // or a time after the exit: unknown, so crash_recent_foreground is omitted.
            else -> put("crash_recent_foreground_source", "unknown")
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
 * Exactly-once: a watermark (newest handled exit timestamp) moves only after the ticket was
 * accepted, the exit was not reportable or rate limited, or Hands rejected it for good. A failed
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
                recentBackgroundReportMillis =
                    decodeRecentReports(preferences.getString(RecentBackgroundReportsKey, null), now),
                backgroundPerLaunchCap = MaxBackgroundReportsPerLaunch,
                backgroundDailyCap = MaxBackgroundReportsPerDay,
                mainProcessName = context.applicationInfo.processName ?: context.packageName,
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
                    recordReport(
                        preferences,
                        if (item.decision == SystemKillDecision.ReportBackground) RecentBackgroundReportsKey else RecentReportsKey,
                        now,
                    )
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
                "stamped=${item.exit.build != null} process=${item.exit.processName} ticket=$ticket",
        )
    }

    private fun firstSeenMillis(preferences: SharedPreferences, now: Long): Long {
        val key = "system-kill-first-seen-millis"
        if (!preferences.contains(key)) preferences.edit().putLong(key, now).commit()
        return preferences.getLong(key, now)
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
