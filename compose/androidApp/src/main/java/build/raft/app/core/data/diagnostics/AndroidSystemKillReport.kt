package build.raft.app.core.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import build.hands.update.HandsFeedback
import build.hands.update.HandsDeviceId
import build.hands.update.HandsFeedbackException
import build.raft.app.BuildConfig
import build.raft.app.data.RaftServicesPrivacyGate
import build.raft.app.shared.RaftBuildStamp
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    /** Java crashes go through HandsCrash, ANRs through HandsCrash's ANR pass. */
    SkipReportedElsewhere("reported_elsewhere", false),
    /**
     * A native crash (`crash_native`, or `signaled` with a crash signal). HandsCrash may file its
     * own ticket, but nothing confirms it did, so it is sent unless a confirmed Hands ticket
     * matches the exit ([decideNativeExit], HandsNativeExitCorrelation.kt).
     */
    ReportNativeCrash("report_native_crash", true),
    /**
     * Not the main app process (a `:name` service process, or an isolated/sandboxed one such as a
     * WebView renderer). It carries no build stamp or telemetry, the user does not see the app
     * vanish when it dies, and WebView renderer deaths already reach `onRenderProcessGone`.
     */
    SkipSecondaryProcess("secondary_process", false),
    /**
     * A known non-interactive OEM pre-launch or post-install maintenance kill (Samsung
     * `due to SPEG` profile generation or Xiaomi `PreStartCachedKill`), not a user-visible kill.
     */
    SkipOemPrestart("oem_prestart", false),
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

private val ReportedElsewhereReasons = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR)

/**
 * Crash signals (SIGILL 4, SIGABRT 6, SIGBUS 7, SIGFPE 8, SIGSEGV 11): some devices record a native
 * crash as `signaled` with the signal as status instead of `crash_native` (Hands b00e67c3, whose
 * HandsCrash ticket and SystemKill c9061aa6 were one crash, 11-09). SIGKILL (9) stays a kill.
 */
private val CrashSignals = setOf(4, 6, 7, 8, 11)

/** A native crash: `crash_native`, or `signaled` with a crash signal ([CrashSignals]). */
internal fun isNativeCrashExit(reasonCode: Int, status: Int): Boolean =
    reasonCode == ApplicationExitInfo.REASON_CRASH_NATIVE ||
        (reasonCode == ApplicationExitInfo.REASON_SIGNALED && status in CrashSignals)

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

/**
 * Which report budget a system kill uses ([planSystemKillReports]), in planning priority order.
 * Each has its own per-launch and per-24-hours limit.
 */
internal enum class SystemKillBudget(val label: String) {
    /** The user saw the app: importance 100/200 or `crash_recent_foreground` true. Never dropped. */
    Visible("visible"),
    /** Not confirmed either way (recent foreground unknown), or an actionable reason. Never dropped. */
    Review("review"),
    /** Confirmed background: Hands archives it. Dropped (and counted) over its limits. */
    Background("background"),
}

/**
 * The budget of a system kill:
 * - [SystemKillBudget.Visible]: importance foreground (100) or visible (200), or
 *   `crash_recent_foreground` true.
 * - [SystemKillBudget.Background]: `crash_recent_foreground` false, importance foreground
 *   service (125) or above, and a reason Hands does not treat as actionable
 *   ([HandsActionableSystemKillReasonCodes]).
 * - [SystemKillBudget.Review]: everything else: recent foreground unknown, an actionable reason,
 *   or an importance below 125 that is not visible.
 */
internal fun systemKillBudget(exit: SystemKillExit, telemetry: MemoryTelemetrySnapshot?): SystemKillBudget {
    val recent = recentForeground(telemetry, exit.timestampMillis).value
    return when {
        exit.importance in VisibleImportances || recent == true -> SystemKillBudget.Visible
        recent == false &&
            exit.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE &&
            exit.reasonCode !in HandsActionableSystemKillReasonCodes &&
            // A crash is never archived as a background kill, whatever reason code it was given.
            !isNativeCrashExit(exit.reasonCode, exit.status) -> SystemKillBudget.Background
        else -> SystemKillBudget.Review
    }
}

/**
 * Every reason not listed above is a system kill: unknown, signaled, low_memory,
 * initialization_failure, excessive_resource_usage, dependency_died, other, freezer, and codes
 * added after API 35. On a device that does not explain its kills (the Huawei in Hands
 * fb-52cc0499 recorded `reason=other`) any of them can be the "app vanished" users describe.
 *
 * Every system kill is sent except known non-interactive OEM pre-launch / post-install maintenance
 * kills ([SystemKillDecision.SkipOemPrestart], see [isNonInteractiveOemPrestartExit]); this only
 * says whether a reportable kill was a foreground or a background one (the decision label in the
 * local log; the ticket carries the raw fields and the evidence separately, see [systemKillExtras]):
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
    description: String? = null,
    startup: StartupPhaseRecord? = null,
): SystemKillDecision =
    when {
        reasonCode in UserInitiatedReasons -> SystemKillDecision.SkipUserInitiated
        reasonCode in PackageChangeReasons -> SystemKillDecision.SkipPackageChange
        reasonCode in ReportedElsewhereReasons -> SystemKillDecision.SkipReportedElsewhere
        isNativeCrashExit(reasonCode, status) -> SystemKillDecision.ReportNativeCrash
        isNonInteractiveOemPrestartExit(reasonCode, status, description, telemetry, startup) ->
            SystemKillDecision.SkipOemPrestart
        importance in VisibleImportances -> SystemKillDecision.Report
        recentForegroundEvidence(telemetry, exitTimestampMillis) != null -> SystemKillDecision.Report
        else -> SystemKillDecision.ReportBackground
    }

private val SamsungSpegDescriptionPattern =
    Regex("""\bdue to speg\b""", RegexOption.IGNORE_CASE)

private val XiaomiPreStartCachedKillDescriptionPattern =
    Regex("""\bprestartcachedkill\b""", RegexOption.IGNORE_CASE)

/**
 * Whether an exit is a known non-interactive OEM pre-launch or post-install maintenance kill that
 * never interrupted the user:
 * - Samsung SPEG (`REASON_OTHER`, `status == 0`, description containing `due to SPEG`, e.g. Hands
 *   `e8a3a789`: `"stop build.raft.app due to SPEG"`): One UI runs the app on a hidden `SpegDisplay`
 *   virtual display to collect an ART profile and stops it. Because the hidden display may briefly
 *   start an Activity (setting `importance=100` or taking a foreground sample), this requires only
 *   that the process recorded no user interaction ([MemoryTelemetrySnapshot.lastInteractionAtMillis]).
 * - Xiaomi `PreStartCachedKill` (`REASON_OTHER`, `status == 0`, description matching
 *   `PreStartCachedKill`, e.g. Hands `2fae4a30`): MIUI/HyperOS speculatively pre-starts the process
 *   up to `Application.onCreate` and kills it before any Activity is created. This requires no
 *   Activity reached or sticky service restart ([startup]) and no foreground, sample, or interaction
 *   evidence in [telemetry].
 *
 * Fail-open: any other reason, non-zero status, blank/unmatched description, or recorded user
 * interaction keeps the exit reportable.
 */
internal fun isNonInteractiveOemPrestartExit(
    reasonCode: Int,
    status: Int,
    description: String?,
    telemetry: MemoryTelemetrySnapshot?,
    startup: StartupPhaseRecord? = null,
): Boolean {
    if (reasonCode != ApplicationExitInfo.REASON_OTHER || status != 0) return false
    val trimmed = description?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    if (telemetry != null && telemetry.lastInteractionAtMillis >= 0L) return false
    if (SamsungSpegDescriptionPattern.containsMatchIn(trimmed)) return true
    if (XiaomiPreStartCachedKillDescriptionPattern.containsMatchIn(trimmed)) {
        val reachedActivityOrSticky =
            startup != null &&
                (startup.phase.ordinal >= RaftStartupPhaseMarker.Phase.ActivityCreated.ordinal ||
                    startup.firstActivityAtMillis >= 0L ||
                    startup.firstActivityAfterStartMs >= 0L ||
                    startup.stickyServiceRestart)
        val sawForeground =
            telemetry != null && (telemetry.foreground || telemetry.sampledAtMillis >= 0L)
        return !reachedActivityOrSticky && !sawForeground
    }
    return false
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
    /**
     * Whether the exited process ran under a uid other than the package's: an isolated process
     * (`android:isolatedProcess`) or an app-zygote child ([ApplicationExitInfo.getRealUid] !=
     * [ApplicationExitInfo.getPackageUid]); null when unknown.
     */
    val isolatedProcess: Boolean? = null,
    /** [ApplicationExitInfo.getPackageUid]: the uid assigned at install; null when unavailable. */
    val packageUid: Int? = null,
    /**
     * [ApplicationExitInfo.getDefiningUid]; null when unavailable. The docs say it differs from the
     * real and package uids "if an external service has the `useAppZygote` set to `true` and was
     * bound with the flag `BIND_EXTERNAL_SERVICE`", and is then "the kernel user identifier of the
     * external service provider": the process hosted another package's code.
     */
    val definingUid: Int? = null,
    /** What [ApplicationExitInfo.getProcessStateSummary] held: [ExitSummaryState]; null when not read. */
    val summaryState: ExitSummaryState? = null,
) {
    /**
     * Whether the defining uid is another package's ([definingUid] != [packageUid]); null when
     * either is missing or not a valid app uid (<= 0).
     */
    val definingUidForeign: Boolean?
        get() {
            val defining = definingUid?.takeIf { it > 0 } ?: return null
            val pkg = packageUid?.takeIf { it > 0 } ?: return null
            return defining != pkg
        }

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

/**
 * The exit record's process state summary: [Empty] (none, as some OEM ROMs return), [Unrecognised]
 * (bytes that are not a [RaftBuildStamp]), or [Stamp] (this app's build stamp).
 */
internal enum class ExitSummaryState(val label: String) {
    Empty("empty"),
    Unrecognised("unrecognised"),
    Stamp("stamp"),
    ;

    companion object {
        fun of(summary: ByteArray?): ExitSummaryState =
            when {
                summary == null || summary.isEmpty() -> Empty
                RaftBuildStamp.decodeStamp(summary) != null -> Stamp
                else -> Unrecognised
            }
    }
}

internal enum class SystemKillAction(val label: String) {
    /** Submit; advance the watermark only when Hands accepted it (or rejected it for good). */
    Submit("report"),
    /** Not reportable: advance the watermark. */
    Skip("skip"),
    /** A background kill over its budget: handled without sending, counted in the [BackgroundDropLedger]. */
    RateLimited("rate_limited"),
    /** A visible or review kill over its budget: built and queued on the device; a later launch sends it. */
    Deferred("deferred"),
}

internal data class SystemKillPlanItem(
    val exit: SystemKillExit,
    val decision: SystemKillDecision,
    val action: SystemKillAction,
    val telemetry: MemoryTelemetrySnapshot?,
    val budget: SystemKillBudget = SystemKillBudget.Review,
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

/** One budget's limits and the reports already sent from it in the last 24 hours. */
internal data class SystemKillBudgetLimit(
    val perLaunch: Int,
    val perDay: Int,
    val recentReportMillis: List<Long> = emptyList(),
)

/**
 * The time before which exits are not looked at (nothing was decided about them). Once stored it
 * never moves. Until it is stored it is the time this build was installed or updated, not "now":
 * that value is the same on every launch, so a first launch whose write fails (commit() false,
 * then the process dies) does not let the next launch pick a later baseline and skip the exits in
 * between. A failed write is retried on the next launch. Left: if the write keeps failing and the
 * app is updated in between, the baseline moves to the new update time.
 */
internal fun resolveSystemKillBaseline(
    storedMillis: Long?,
    packageUpdatedMillis: Long?,
    nowMillis: Long,
    persist: (Long) -> Boolean,
    onPersistFailed: () -> Unit = {},
): Long {
    if (storedMillis != null) return storedMillis
    val baseline = packageUpdatedMillis?.takeIf { it in 1..nowMillis } ?: nowMillis
    if (!persist(baseline)) onPersistFailed()
    return baseline
}

/**
 * What to do with each exit since the watermark, oldest first.
 *
 * Every exit newer than the install of the first build with this reporter and the watermark, and
 * not already handled above it ([handledAboveWatermark]), is classified and given a budget
 * ([systemKillBudget]). Budgets are then spent in priority order, visible, then review, then
 * background (each in time order), so lower budgets can never use up a higher one's day. Each has
 * its own limits: [perLaunchCap] / [dailyCap] / [recentReportMillis] for visible, [review] and
 * [background]. Over its limits a visible or review kill is [SystemKillAction.Deferred] (queued on
 * the device, [runSystemKillReports]); a background one is [SystemKillAction.RateLimited] (counted).
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
    review: SystemKillBudgetLimit = SystemKillBudgetLimit(perLaunchCap, dailyCap),
    background: SystemKillBudgetLimit = SystemKillBudgetLimit(perLaunchCap, dailyCap),
    handledAboveWatermark: Set<SystemKillExitKey> = emptySet(),
    startupRecords: List<StartupPhaseRecord> = emptyList(),
): List<SystemKillPlanItem> {
    val floor = maxOf(cutoffMillis, watermarkMillis)
    val classified =
        exits
            .filter { it.timestampMillis > floor && SystemKillExitKey(it.pid, it.timestampMillis) !in handledAboveWatermark }
            .distinctBy { it.pid to it.timestampMillis }
            .sortedBy { it.timestampMillis }
            .map { exit ->
                val telemetry = MemoryTelemetryRing.match(snapshots, exit.pid, exit.timestampMillis)
                val startup = startupRecordForExit(startupRecords, exit.pid, exit.timestampMillis)
                val decision =
                    // An exit without a process name is kept: nothing says it was another process.
                    if (mainProcessName != null && exit.processName.isNotEmpty() && exit.processName != mainProcessName) {
                        SystemKillDecision.SkipSecondaryProcess
                    } else {
                        classifySystemKill(
                            reasonCode = exit.reasonCode,
                            importance = exit.importance,
                            telemetry = telemetry,
                            exitTimestampMillis = exit.timestampMillis,
                            status = exit.status,
                            description = exit.description,
                            startup = startup,
                        )
                    }
                SystemKillPlanItem(exit, decision, SystemKillAction.Skip, telemetry, systemKillBudget(exit, telemetry))
            }
    val limits =
        mapOf(
            SystemKillBudget.Visible to SystemKillBudgetLimit(perLaunchCap, dailyCap, recentReportMillis),
            SystemKillBudget.Review to review,
            SystemKillBudget.Background to background,
        )
    val actions = HashMap<SystemKillExitKey, SystemKillAction>()
    for (budget in SystemKillBudget.entries) {
        val limit = limits.getValue(budget)
        var sentToday = limit.recentReportMillis.count { nowMillis - it < DayMillis }
        var sentNow = 0
        for (item in classified) {
            if (item.budget != budget || !item.decision.report) continue
            actions[item.key] =
                when {
                    sentToday < limit.perDay && sentNow < limit.perLaunch -> {
                        sentToday += 1
                        sentNow += 1
                        SystemKillAction.Submit
                    }
                    budget == SystemKillBudget.Background -> SystemKillAction.RateLimited
                    else -> SystemKillAction.Deferred
                }
        }
    }
    return classified.map { it.copy(action = actions[it.key] ?: SystemKillAction.Skip) }
}

/**
 * The order one launch handles [plan] in: every skip, drop and deferral first, then the uploads by
 * budget (visible, review, background), each in time order. A retryable upload error stops the
 * pass, so one on a lower budget's report can never delay a higher one's.
 */
internal fun systemKillProcessingOrder(plan: List<SystemKillPlanItem>): List<SystemKillPlanItem> =
    plan.sortedBy { if (it.action == SystemKillAction.Submit) it.budget.ordinal else -1 }

/**
 * The single watermark after handling [handled] (items of [plan], handled out of time order, plus
 * those kept from earlier launches). The next plan takes only exits newer than the watermark, so it
 * passes a timestamp only once every exit of [plan] at that timestamp is handled (two exits can
 * share a millisecond), and stops at the first timestamp that is not. Handled exits above it are
 * kept, by pid and timestamp, so the next plan does not send them again.
 */
internal fun advanceSystemKillWatermark(
    watermarkMillis: Long,
    plan: List<SystemKillPlanItem>,
    handled: Set<SystemKillExitKey>,
): Pair<Long, Set<SystemKillExitKey>> {
    var watermark = watermarkMillis
    for ((timestamp, items) in plan.groupBy { it.exit.timestampMillis }.toSortedMap()) {
        if (items.any { it.key !in handled }) break
        watermark = maxOf(watermark, timestamp)
    }
    return watermark to handled.filterTo(mutableSetOf()) { it.timestampMillis > watermark }
}

/**
 * A captured report waiting to be sent, fully built when its exit was first read (message and
 * metadata with the evidence already resolved), so it is sent as classified even after the exit
 * left the system's exit list or its telemetry left the snapshot ring.
 *
 * Hands answers a resend with the same `crash_exit_event_id` with the first attempt's ticket and
 * stores nothing from the resend (botiverse/hands#955), so every attempt must carry what the first
 * one did: [attempted] and [sentBatch] (the drop counts bound to this report, none when null) are
 * committed before the first upload, and every later attempt sends [extras] plus [sentBatch] as is.
 */
internal data class QueuedSystemKillReport(
    val key: SystemKillExitKey,
    val budget: SystemKillBudget,
    val versionCode: Long,
    val message: String,
    /** JSON-safe values only: strings, numbers, booleans, null. */
    val extras: Map<String, Any?>,
    /** True once an upload was started; entries stored before this field existed load as false. */
    val attempted: Boolean = false,
    /** The drop counts bound to this report at its first attempt; only this report ever sends them. */
    val sentBatch: BackgroundDropBatch? = null,
    /**
     * For a native crash ([SystemKillDecision.ReportNativeCrash]): the exit's identity and how long
     * its decision has waited ([decideNativeExit]); null for every other report and once decided.
     */
    val nativeExit: NativeExitWait? = null,
    /**
     * The `submission_id` every attempt is sent under (a native crash on the merge path,
     * [NativeExitVerdict.Merge]), frozen with the first attempt like [extras]; null: none.
     */
    val submissionId: String? = null,
    /**
     * True when the last send under [submissionId] was answered with an error status, so Hands
     * stored nothing from it; false when it may have been stored (no answer: a network error, or
     * the process died mid-upload). Decides what a later 409 means ([runSystemKillReports]).
     */
    val submissionNotStored: Boolean = false,
) {
    /** What every attempt of this report uploads. */
    fun uploadExtras(): Map<String, Any?> = extras + sentBatch?.extras().orEmpty()

    fun toJson(): JSONObject =
        JSONObject()
            .put("pid", key.pid)
            .put("timestamp", key.timestampMillis)
            .put("budget", budget.name)
            .put("version_code", versionCode)
            .put("message", message)
            .put("extras", JSONObject().apply { extras.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
            .put("attempted", attempted)
            .apply { sentBatch?.let { put("sent_batch_id", it.id).put("sent_batch", it.allToJson()) } }
            .apply {
                nativeExit?.let { wait ->
                    put(
                        "native_exit",
                        JSONObject()
                            .put("pid", wait.identity.pid)
                            .put("exit_timestamp", wait.identity.exitTimestampMillis)
                            .put("process_start", wait.identity.processStartMillis ?: JSONObject.NULL)
                            .put("submission_id", wait.identity.submissionId ?: JSONObject.NULL)
                            .put("first_seen", wait.firstSeenMillis)
                            .put("launches_waited", wait.launchesWaited),
                    )
                }
            }
            .apply {
                submissionId?.let { put("submission_id", it).put("submission_not_stored", submissionNotStored) }
            }

    companion object {
        fun fromJson(json: JSONObject): QueuedSystemKillReport? {
            val budget = SystemKillBudget.entries.firstOrNull { it.name == json.optString("budget") } ?: return null
            val extrasJson = json.optJSONObject("extras") ?: return null
            val extras = extrasJson.keys().asSequence().associateWith { key -> extrasJson.get(key).takeUnless { it == JSONObject.NULL } }
            return QueuedSystemKillReport(
                key = SystemKillExitKey(json.optInt("pid"), json.optLong("timestamp")),
                budget = budget,
                versionCode = json.optLong("version_code", -1L),
                message = json.optString("message"),
                extras = extras,
                attempted = json.optBoolean("attempted", false),
                sentBatch =
                    json.optString("sent_batch_id", "").takeIf(String::isNotEmpty)?.let {
                        BackgroundDropBatch(it, BackgroundDropLedger.entries(json.optJSONArray("sent_batch")))
                    },
                nativeExit =
                    json.optJSONObject("native_exit")?.let { wait ->
                        NativeExitWait(
                            identity =
                                NativeExitIdentity(
                                    pid = wait.optInt("pid"),
                                    exitTimestampMillis = wait.optLong("exit_timestamp"),
                                    processStartMillis = if (wait.isNull("process_start")) null else wait.optLong("process_start"),
                                    submissionId = if (wait.isNull("submission_id")) null else wait.optString("submission_id").ifEmpty { null },
                                ),
                            firstSeenMillis = wait.optLong("first_seen"),
                            launchesWaited = wait.optInt("launches_waited"),
                        )
                    },
                submissionId = if (json.isNull("submission_id")) null else json.optString("submission_id").ifEmpty { null },
                submissionNotStored = json.optBoolean("submission_not_stored", false),
            )
        }

        fun encodeAll(queue: List<QueuedSystemKillReport>): String =
            JSONArray().apply { queue.forEach { put(it.toJson()) } }.toString()

        fun decodeAll(encoded: String?): List<QueuedSystemKillReport> {
            if (encoded.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(encoded)
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
            }.getOrDefault(emptyList())
        }
    }
}

/** At most this many captured reports wait on the device ([SystemKillProgress.enqueue]). */
internal const val MaxQueuedSystemKillReports = 20

/**
 * Everything the reporter persists, written in one commit per step ([SystemKillProgressStore]):
 * where it is (watermark and handled exits above it), the never-sent counts, the deferred reports
 * and the reports sent per budget in the last 24 hours.
 */
internal data class SystemKillProgress(
    val watermarkMillis: Long = 0L,
    val handledAboveWatermark: Set<SystemKillExitKey> = emptySet(),
    val ledger: BackgroundDropLedger = BackgroundDropLedger(),
    val queue: List<QueuedSystemKillReport> = emptyList(),
    val recentReportMillis: Map<SystemKillBudget, List<Long>> = emptyMap(),
) {
    fun recent(budget: SystemKillBudget, nowMillis: Long): List<Long> =
        recentReportMillis[budget].orEmpty().filter { nowMillis - it < DayMillis }

    fun withReport(budget: SystemKillBudget, nowMillis: Long): SystemKillProgress =
        copy(recentReportMillis = recentReportMillis + (budget to recent(budget, nowMillis) + nowMillis))

    /**
     * After [item] of [plan] is handled: a background drop is counted, a deferred report is queued
     * ([queued]), and the item is marked handled. One value, so a count or a queued report never
     * lands without its exit's handled marker, and a relaunch never handles the same exit twice.
     */
    fun afterHandled(
        item: SystemKillPlanItem,
        plan: List<SystemKillPlanItem>,
        queued: QueuedSystemKillReport? = null,
        queueCap: Int = MaxQueuedSystemKillReports,
    ): SystemKillProgress {
        var next = this
        if (item.action == SystemKillAction.RateLimited && item.budget == SystemKillBudget.Background) {
            next = next.copy(ledger = next.ledger.record(systemKillExitVersionCode(item.exit, item.telemetry), item.exit.timestampMillis))
        }
        if (queued != null) next = next.enqueue(queued, queueCap)
        val (watermark, above) = advanceSystemKillWatermark(watermarkMillis, plan, handledAboveWatermark + item.key)
        return next.copy(watermarkMillis = watermark, handledAboveWatermark = above)
    }

    /**
     * Adds [report]; over [cap] the oldest background report is evicted first, then the oldest
     * review one, the oldest visible one only when neither is left. Each eviction is counted: a
     * background one with the background drops, a visible / review one in its own category. An
     * evicted report's bound batch goes back to the ledger ([BackgroundDropLedger.restore]).
     */
    fun enqueue(report: QueuedSystemKillReport, cap: Int = MaxQueuedSystemKillReports): SystemKillProgress {
        var nextQueue = queue + report
        var nextLedger = ledger
        while (nextQueue.size > cap) {
            val victim =
                nextQueue.filter { it.budget == SystemKillBudget.Background }.minByOrNull { it.key.timestampMillis }
                    ?: nextQueue.filter { it.budget == SystemKillBudget.Review }.minByOrNull { it.key.timestampMillis }
                    ?: nextQueue.minBy { it.key.timestampMillis }
            nextQueue = nextQueue - victim
            victim.sentBatch?.let { nextLedger = nextLedger.restore(it, keepId = true) }
            val category =
                if (victim.budget == SystemKillBudget.Background) BackgroundDropEntry.EvictedBackground else victim.budget.label
            nextLedger = nextLedger.record(victim.versionCode, victim.key.timestampMillis, category = category)
        }
        return copy(queue = nextQueue, ledger = nextLedger)
    }
}

/** Where the reporter keeps [SystemKillProgress]; each [commit] is one atomic write. */
internal interface SystemKillProgressStore {
    fun load(): SystemKillProgress

    /** True only when [progress] is on disk (`SharedPreferences.Editor.commit`); false: not saved. */
    fun commit(progress: SystemKillProgress): Boolean
}

/**
 * One launch, in two phases, every step one commit:
 *
 * 1. Capture. The new exits [readPlan] returns are classified and each reportable one is built
 *    ([buildReport]) and queued ([SystemKillProgress.enqueue], cap [queueCap]) in the same commit
 *    that marks it handled. Visible and review reports are always captured; a background one only
 *    while the background day budget (sent today plus already queued) has room, else it is dropped
 *    and counted. Nothing here uploads, so a failing upload can never stop capture.
 * 2. Send. The whole queue, old and new together, visible first, then review, then background,
 *    oldest first in each, within each budget's per-launch and per-24-hours [limits]. A retryable
 *    upload error stops sending for this launch and keeps everything queued; Hands rejecting a
 *    ticket for good ([isPermanentHandsRejection]) removes it.
 *
 * A report's first upload carries the never-sent counts ([BackgroundDropBatch.extras]), frozen
 * under their id and bound to that report ([QueuedSystemKillReport.sentBatch]) in a commit before
 * the upload. Every resend sends exactly that (no counts included), never counts recorded since:
 * Hands answers a resend of a stored report with its first ticket and keeps nothing new from it.
 * A batch is bound to one report only, so no other report sends it; it leaves the device with
 * that report's accepted upload and returns to the ledger if the report is rejected or evicted.
 *
 * A native crash ([QueuedSystemKillReport.nativeExit]) is decided in the send phase before its
 * first attempt ([decideNativeExit]): dropped on a [nativeConfirmations] match; with a known
 * process event id sent at once under it as `submission_id` ([NativeExitVerdict.Merge]); else
 * kept queued (no budget used) while a [nativePending] record may be its, within the wait bounds,
 * else sent standalone. Each carries `crash_native_correlation`.
 *
 * A report sent under a `submission_id` ([QueuedSystemKillReport.submissionId]):
 * - merged into the ticket already holding the id: accepted, like any ticket;
 * - 503 `submission_merge_conflict`: the same content again after [mergeRetryDelayMillis], at most
 *   [NativeMergeConflictRetries] times this launch, then kept queued for the next;
 * - 409: Hands will not take it under this id. Hands answers an identical resend of a stored
 *   report with 200, so a 409 never proves an earlier unanswered send was stored (it can mean a
 *   different reporter, conflicting content or the merge cap). It is re-frozen without the id
 *   (`crash_native_correlation=merge_rejected`) and sent once more, standalone; from then on it
 *   is an ordinary report. A rare duplicate ticket is preferred over losing the exit.
 */
internal suspend fun runSystemKillReports(
    store: SystemKillProgressStore,
    nowMillis: Long,
    limits: Map<SystemKillBudget, SystemKillBudgetLimit>,
    readPlan: (progress: SystemKillProgress, capture: Map<SystemKillBudget, SystemKillBudgetLimit>) -> List<SystemKillPlanItem>,
    buildReport: (item: SystemKillPlanItem) -> Pair<String, Map<String, Any?>>,
    newBatchId: () -> String,
    submit: suspend (message: String, extras: Map<String, Any?>, submissionId: String?) -> SystemKillSubmission,
    log: (item: SystemKillPlanItem?, queued: QueuedSystemKillReport?, outcome: String) -> Unit = { _, _, _ -> },
    onRetryableFailure: (timestampMillis: Long, error: Throwable) -> Unit = { _, _ -> },
    queueCap: Int = MaxQueuedSystemKillReports,
    /** Native tickets Hands confirmed ([HandsNativeTicketConfirmation.None]: the SDK reports none to the app). */
    nativeConfirmations: List<HandsNativeTicketConfirmation> = HandsNativeTicketConfirmation.None,
    /** Native-crash records the SDK still holds this launch. */
    nativePending: List<HandsNativePendingRecord> = emptyList(),
    onNativeVerdict: (queued: QueuedSystemKillReport, verdict: NativeExitVerdict) -> Unit = { _, _ -> },
    mergeRetryDelayMillis: Long = NativeMergeConflictRetryDelayMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    var progress = store.load()

    // 1. Capture.
    val background = limits.getValue(SystemKillBudget.Background)
    val unlimited = SystemKillBudgetLimit(Int.MAX_VALUE, Int.MAX_VALUE)
    val capture =
        mapOf(
            SystemKillBudget.Visible to unlimited,
            SystemKillBudget.Review to unlimited,
            SystemKillBudget.Background to
                SystemKillBudgetLimit(
                    perLaunch = Int.MAX_VALUE,
                    perDay = background.perDay,
                    // Already queued background reports count against today's background budget.
                    recentReportMillis =
                        progress.recent(SystemKillBudget.Background, nowMillis) +
                            progress.queue.filter { it.budget == SystemKillBudget.Background }.map { nowMillis },
                ),
        )
    val plan = readPlan(progress, capture)
    for (item in plan) {
        val queued =
            if (item.action == SystemKillAction.Submit || item.action == SystemKillAction.Deferred) {
                val (message, extras) = buildReport(item)
                QueuedSystemKillReport(
                    item.key,
                    item.budget,
                    systemKillExitVersionCode(item.exit, item.telemetry),
                    message,
                    extras,
                    nativeExit =
                        if (item.decision == SystemKillDecision.ReportNativeCrash) {
                            NativeExitWait(
                                NativeExitIdentity(
                                    item.exit.pid,
                                    item.exit.timestampMillis,
                                    item.telemetry?.processStartMillis,
                                    submissionId = nativeExitSubmissionId(item.telemetry),
                                ),
                                firstSeenMillis = nowMillis,
                            )
                        } else {
                            null
                        },
                )
            } else {
                null
            }
        val next = progress.afterHandled(item, plan, queued, queueCap)
        // Not on disk: stop the launch; the next one starts again from what was saved.
        if (!store.commit(next)) return
        progress = next
        log(item, queued, if (queued != null) "queued" else "none")
    }

    // 2. Send.
    val sentNow = SystemKillBudget.entries.associateWith { 0 }.toMutableMap()
    fun hasRoom(budget: SystemKillBudget): Boolean {
        val limit = limits.getValue(budget)
        return sentNow.getValue(budget) < limit.perLaunch && progress.recent(budget, nowMillis).size < limit.perDay
    }
    for (candidate in progress.queue.sortedWith(compareBy<QueuedSystemKillReport>({ it.budget.ordinal }, { it.key.timestampMillis }))) {
        var queued = progress.queue.firstOrNull { it.key == candidate.key } ?: continue
        // A native crash not yet attempted is decided first, without budget: a confirmed Hands
        // ticket drops it, a pending SDK record holds it. Once attempted it is only ever resent.
        var nativeCorrelation: String? = null
        var nativeSubmissionId: String? = null
        val nativeWait = queued.nativeExit
        if (nativeWait != null && !queued.attempted) {
            val verdict = decideNativeExit(nativeWait, nativeConfirmations, nativePending, nowMillis)
            when (verdict) {
                is NativeExitVerdict.SkipConfirmed -> {
                    val next = progress.copy(queue = progress.queue.filterNot { it.key == queued.key })
                    if (!store.commit(next)) return
                    progress = next
                    onNativeVerdict(queued, verdict)
                    continue
                }
                NativeExitVerdict.Wait -> {
                    val waited = queued.copy(nativeExit = nativeWait.copy(launchesWaited = nativeWait.launchesWaited + 1))
                    val next = progress.copy(queue = progress.queue.map { if (it.key == waited.key) waited else it })
                    if (!store.commit(next)) return
                    progress = next
                    onNativeVerdict(waited, verdict)
                    continue
                }
                is NativeExitVerdict.Report -> {
                    onNativeVerdict(queued, verdict)
                    nativeCorrelation = verdict.correlation
                }
                is NativeExitVerdict.Merge -> {
                    onNativeVerdict(queued, verdict)
                    nativeCorrelation = NativeCorrelationSubmissionId
                    nativeSubmissionId = verdict.submissionId
                }
            }
        }
        if (!hasRoom(queued.budget)) continue
        if (!queued.attempted) {
            val (ledger, dropped) = progress.ledger.attach(newBatchId)
            // The native decision is frozen with the first attempt: every resend carries it as is.
            val decidedExtras =
                nativeCorrelation?.let { queued.extras + ("crash_native_correlation" to it) } ?: queued.extras
            val bound =
                queued.copy(
                    attempted = true,
                    sentBatch = dropped,
                    extras = decidedExtras,
                    nativeExit = null,
                    submissionId = nativeSubmissionId,
                )
            val frozen = progress.copy(ledger = ledger, queue = progress.queue.map { if (it.key == bound.key) bound else it })
            // What the first attempt sends must be on disk before it is sent: every resend sends the same.
            if (!store.commit(frozen)) return
            progress = frozen
            queued = bound
        }
        var result = runCatching { submit(queued.message, queued.uploadExtras(), queued.submissionId) }
        var retries = 0
        while (queued.submissionId != null && retries < NativeMergeConflictRetries &&
            result.exceptionOrNull()?.let(::nativeMergeFailure) == NativeMergeFailure.RetrySameContent
        ) {
            retries += 1
            wait(mergeRetryDelayMillis)
            result = runCatching { submit(queued.message, queued.uploadExtras(), queued.submissionId) }
        }
        if (queued.submissionId != null && result.exceptionOrNull()?.let(::nativeMergeFailure) == NativeMergeFailure.Refused) {
            run {
                // A 409 never proves an earlier unanswered send was stored (an identical resend
                // of a stored report gets 200): re-frozen without the id and sent once more,
                // standalone. Committed first, so every later attempt sends the same.
                val standalone =
                    queued.copy(
                        extras = queued.extras + ("crash_native_correlation" to NativeCorrelationMergeRejected),
                        submissionId = null,
                        submissionNotStored = false,
                    )
                val refrozen = progress.copy(queue = progress.queue.map { if (it.key == standalone.key) standalone else it })
                if (!store.commit(refrozen)) return
                progress = refrozen
                log(null, queued, "merge_refused_standalone")
                queued = standalone
                result = runCatching { submit(queued.message, queued.uploadExtras(), null) }
            }
        }
        val error = result.exceptionOrNull()
        if (error != null && !isPermanentHandsRejection(error)) {
            // Network, server, auth or configuration: everything stays queued, no budget is used.
            if (queued.submissionId != null) {
                // Whether Hands answered (stored nothing) or not (may have stored it).
                val notStored = error is HandsFeedbackException && error.code !in 200..299
                if (notStored != queued.submissionNotStored) {
                    val marked = queued.copy(submissionNotStored = notStored)
                    val next = progress.copy(queue = progress.queue.map { if (it.key == marked.key) marked else it })
                    if (store.commit(next)) progress = next
                }
            }
            onRetryableFailure(queued.key.timestampMillis, error)
            return
        }
        val next =
            if (error == null) {
                progress.copy(queue = progress.queue.filterNot { it.key == queued.key })
                    .withReport(queued.budget, nowMillis)
            } else {
                // Rejected for good (malformed): given up, counted with its status, no budget used;
                // its counts never reached Hands, so a later report carries them.
                progress.copy(
                    ledger =
                        progress.ledger.restore(queued.sentBatch, keepId = false).record(
                            queued.versionCode,
                            queued.key.timestampMillis,
                            category = BackgroundDropEntry.Rejected,
                            httpStatus = (error as? HandsFeedbackException)?.code ?: 0,
                        ),
                    queue = progress.queue.filterNot { it.key == queued.key },
                )
            }
        if (!store.commit(next)) return
        progress = next
        if (error == null) sentNow[queued.budget] = sentNow.getValue(queued.budget) + 1
        val submission = result.getOrNull()
        log(
            null,
            queued,
            when {
                submission == null -> "rejected"
                submission.merged -> "merged:${submission.ticketId?.take(8) ?: "anonymous"}"
                else -> submission.ticketId?.take(8) ?: "none"
            },
        )
    }
}

/**
 * Reports of one exit build and UTC exit day that never reached Hands: [category] `background`
 * (over the background budget, or evicted), `visible` / `review` (evicted from a full pending
 * queue) or `rejected` (Hands rejected it for good, with its [httpStatus]).
 */
internal data class BackgroundDropEntry(
    val versionCode: Long,
    val exitDayUtc: String,
    val count: Int,
    val category: String = BackgroundDropEntry.Background,
    /** The HTTP status Hands answered, for [Rejected]; 0 otherwise. */
    val httpStatus: Int = 0,
) {
    companion object {
        const val Background = "background"
        /** A report Hands rejected for good ([isPermanentHandsRejection]). */
        const val Rejected = "rejected"
        /** A background report evicted from a full queue; sent in `crash_pending_evicted` as `background`. */
        const val EvictedBackground = "evicted_background"
    }
}

/** Entries sent together under one random [id], so Hands can dedupe a resent batch. */
internal data class BackgroundDropBatch(val id: String, val entries: List<BackgroundDropEntry>) {
    /** `crash_background_dropped`: `[{version_code, exit_day_utc, count}]`, background budget drops only. */
    fun toJson(): JSONArray = json(entries.filter { it.category == BackgroundDropEntry.Background }, withCategory = false)

    /** `crash_pending_evicted`: `[{version_code, exit_day_utc, category, count}]`, queue evictions (visible / review / background). */
    fun evictedToJson(): JSONArray =
        json(
            entries.filter { it.category != BackgroundDropEntry.Background && it.category != BackgroundDropEntry.Rejected },
            withCategory = true,
        )

    /** `crash_rejected`: `[{version_code, exit_day_utc, http_status, count}]`, permanent rejections only. */
    fun rejectedToJson(): JSONArray =
        JSONArray().apply {
            entries.filter { it.category == BackgroundDropEntry.Rejected }.forEach {
                put(
                    JSONObject().put("version_code", it.versionCode).put("exit_day_utc", it.exitDayUtc)
                        .put("http_status", it.httpStatus).put("count", it.count),
                )
            }
        }

    /** The ticket fields for this batch (none when empty): both lists share [id]. */
    fun extras(): Map<String, Any?> {
        // Checked outside buildMap: inside it, `entries` is the map being built.
        if (entries.isEmpty()) return emptyMap()
        val dropped = toJson()
        val evicted = evictedToJson()
        val rejected = rejectedToJson()
        return buildMap {
            put("crash_background_dropped_id", id)
            if (dropped.length() > 0) put("crash_background_dropped", dropped)
            if (evicted.length() > 0) put("crash_pending_evicted", evicted)
            if (rejected.length() > 0) put("crash_rejected", rejected)
        }
    }

    /** Every entry with its category, for the device's own store. */
    fun allToJson(): JSONArray =
        JSONArray().apply {
            entries.forEach {
                put(
                    JSONObject().put("version_code", it.versionCode).put("exit_day_utc", it.exitDayUtc)
                        .put("category", it.category).put("http_status", it.httpStatus).put("count", it.count),
                )
            }
        }

    private fun json(list: List<BackgroundDropEntry>, withCategory: Boolean): JSONArray =
        JSONArray().apply {
            list.forEach {
                val entry = JSONObject().put("version_code", it.versionCode).put("exit_day_utc", it.exitDayUtc)
                if (withCategory) {
                    entry.put("category", if (it.category == BackgroundDropEntry.EvictedBackground) "background" else it.category)
                    if (it.httpStatus != 0) entry.put("http_status", it.httpStatus)
                }
                put(entry.put("count", it.count))
            }
        }
}

/**
 * Background reports dropped over their budget, kept on the device until a later system-kill
 * report carries them (best effort; they stay here if no later report is ever sent).
 *
 * New drops go to [accumulating]. A report's first upload takes them, frozen under a fresh id
 * ([attach]), and keeps them as its own ([QueuedSystemKillReport.sentBatch]) until it is accepted.
 * [pending] holds batches no report owns, oldest first: one written by an earlier version of
 * this reporter, or ones handed back by evicted reports ([restore]); each later first upload
 * takes the oldest one first, under its own id.
 *
 * Limits (best effort):
 * - A restored batch whose first attempt was stored is sent again (same id) with another report;
 *   any total that does not dedupe by batch id may count it twice ([restore]). Hands only stores
 *   the lists today and does not total them.
 * - More than [MaxPending] pending batches: the oldest is merged into [accumulating] and later
 *   goes out under a new id, which a future dedupe by the original id cannot catch either.
 * - More than [MaxEntries] distinct (version, day, category, status) entries in [accumulating]:
 *   the entries of the oldest days are dropped with their counts, uncounted anywhere: a known
 *   undercount ([bounded]).
 * Counts no later report carries (no further system-kill report is ever sent) never reach Hands.
 */
internal data class BackgroundDropLedger(
    val pending: List<BackgroundDropBatch> = emptyList(),
    val accumulating: List<BackgroundDropEntry> = emptyList(),
) {
    fun record(
        versionCode: Long,
        exitTimestampMillis: Long,
        category: String = BackgroundDropEntry.Background,
        httpStatus: Int = 0,
    ): BackgroundDropLedger {
        val day = utcDay(exitTimestampMillis)
        val existing =
            accumulating.firstOrNull {
                it.versionCode == versionCode && it.exitDayUtc == day && it.category == category && it.httpStatus == httpStatus
            }
        val next =
            if (existing != null) {
                accumulating.map { if (it === existing) it.copy(count = it.count + 1) else it }
            } else {
                accumulating + BackgroundDropEntry(versionCode, day, 1, category, httpStatus)
            }
        return copy(accumulating = bounded(next))
    }

    /**
     * The batch a report's first upload carries (null when nothing was dropped), and the ledger
     * without it: the batch now belongs to that report alone. The oldest [pending] batch goes
     * first; [accumulating] only once none is pending.
     */
    fun attach(newId: () -> String): Pair<BackgroundDropLedger, BackgroundDropBatch?> =
        when {
            pending.isNotEmpty() -> copy(pending = pending.drop(1)) to pending.first()
            accumulating.isEmpty() -> this to null
            else -> BackgroundDropBatch(newId(), accumulating).let { copy(accumulating = emptyList()) to it }
        }

    /**
     * Takes back [batch] of a report that will never be accepted. With [keepId] (evicted: its
     * first attempt may have reached Hands, so a repeat must keep the id Hands can dedupe on) it
     * joins the end of [pending] under its id; without (rejected: Hands never stored it) its counts
     * are added to [accumulating] and go out with the next batch.
     *
     * A restored batch can reach Hands twice even without overflow: when the evicted report's first
     * attempt was accepted but its response was lost, the same batch goes out again with another
     * report. Keeping its id lets a batch-level dedupe on the Hands side catch that; Hands does
     * not dedupe by batch yet, so a total built from the stored lists may count it twice.
     *
     * Over [MaxPending], the oldest pending batch is merged into [accumulating] and later goes
     * out under a new id. That adds a second risk: once its id has changed, even a future dedupe
     * by the original id cannot catch it. It needs more than [MaxPending] attempted reports
     * evicted before any later report's first upload.
     */
    fun restore(batch: BackgroundDropBatch?, keepId: Boolean): BackgroundDropLedger =
        when {
            batch == null || batch.entries.isEmpty() -> this
            keepId -> {
                val next = pending + batch
                val overflow = next.size - MaxPending
                if (overflow <= 0) {
                    copy(pending = next)
                } else {
                    next.take(overflow).fold(copy(pending = next.drop(overflow))) { ledger, oldest -> ledger.merge(oldest.entries) }
                }
            }
            else -> merge(batch.entries)
        }

    private fun merge(entries: List<BackgroundDropEntry>): BackgroundDropLedger {
        val merged =
            (accumulating + entries)
                .groupBy { listOf(it.versionCode, it.exitDayUtc, it.category, it.httpStatus) }
                .values.map { same -> same.first().copy(count = same.sumOf { it.count }) }
        return copy(accumulating = bounded(merged))
    }

    /**
     * Keeps the [MaxEntries] newest entries (by exit day, then category, status, version). The
     * rest are dropped with their counts and reported nowhere: a known undercount, accepted
     * because it needs more than [MaxEntries] distinct entries waiting for one report.
     */
    private fun bounded(entries: List<BackgroundDropEntry>): List<BackgroundDropEntry> =
        entries.sortedWith(
            compareBy<BackgroundDropEntry>({ it.exitDayUtc }, { it.category }, { it.httpStatus }, { it.versionCode }),
        ).takeLast(MaxEntries)

    fun encode(): String =
        JSONObject().apply {
            if (pending.isNotEmpty()) {
                put(
                    "pending_batches",
                    JSONArray().apply { pending.forEach { put(JSONObject().put("id", it.id).put("entries", it.allToJson())) } },
                )
            }
            put("accumulating", BackgroundDropBatch("", accumulating).allToJson())
        }.toString()

    companion object {
        const val MaxEntries = 32

        /** Pending batches kept under their own id, as many as [MaxQueuedSystemKillReports]. */
        const val MaxPending = MaxQueuedSystemKillReports

        fun decode(encoded: String?): BackgroundDropLedger {
            if (encoded.isNullOrBlank()) return BackgroundDropLedger()
            return runCatching {
                val json = JSONObject(encoded)
                val batches = json.optJSONArray("pending_batches")
                val pending =
                    if (batches != null) {
                        (0 until batches.length()).mapNotNull { index ->
                            val batch = batches.optJSONObject(index) ?: return@mapNotNull null
                            val id = batch.optString("id", "")
                            if (id.isEmpty()) null else BackgroundDropBatch(id, entries(batch.optJSONArray("entries")))
                        }
                    } else {
                        // Written before pending became a list: one batch as `pending_id` / `pending`.
                        val pendingId = json.optString("pending_id", "")
                        if (pendingId.isNotEmpty()) listOf(BackgroundDropBatch(pendingId, entries(json.optJSONArray("pending")))) else emptyList()
                    }
                BackgroundDropLedger(
                    pending = pending,
                    accumulating = entries(json.optJSONArray("accumulating")),
                )
            }.getOrDefault(BackgroundDropLedger())
        }

        internal fun entries(array: JSONArray?): List<BackgroundDropEntry> =
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val entry = array?.optJSONObject(index) ?: return@mapNotNull null
                BackgroundDropEntry(
                    entry.optLong("version_code", -1L),
                    entry.optString("exit_day_utc"),
                    entry.optInt("count"),
                    entry.optString("category", BackgroundDropEntry.Background),
                    entry.optInt("http_status", 0),
                )
            }

        fun utcDay(timestampMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestampMillis))
    }
}

/**
 * `crash_exit_event_id`: a stable id of one exit on one install, so a resend after a lost response
 * carries the same id. SHA-256 of the per-install id, pid, exit timestamp, reason and process
 * name; the install id keeps two devices with the same pid, millisecond and reason apart.
 */
internal fun systemKillExitEventId(installId: String, exit: SystemKillExit): String {
    val identity = listOf(installId, exit.pid, exit.timestampMillis, exit.reasonCode, exit.processName).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)
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

/**
 * The build that writes the report, read once per launch: its BuildConfig version and
 * `PackageInfo.lastUpdateTime` (null when the package manager gave none), with the time it was read.
 */
internal data class SystemKillReporterBuild(
    val versionName: String,
    val versionCode: Long,
    val packageUpdatedMillis: Long?,
    val nowMillis: Long,
)

/**
 * Whether the installed build ([reporter]) can be inferred as the host app's version at [exit]:
 * the package was last updated strictly before the exit (an update kills the app's processes and
 * moves `lastUpdateTime`, so an older build should not have exited after it). This is an inference
 * from wall-clock times, never a record of what the process ran: a device clock changed after the
 * exit can make it wrong. A missing, zero or negative update time, an update at or after the exit,
 * and an update time or exit in the future all leave it uninferred.
 */
internal fun installedBuildInferredForExit(exit: SystemKillExit, reporter: SystemKillReporterBuild): Boolean {
    val updated = reporter.packageUpdatedMillis ?: return false
    val exitAt = exit.timestampMillis
    return updated > 0L && exitAt > 0L && updated <= reporter.nowMillis && exitAt <= reporter.nowMillis && updated < exitAt
}

/**
 * How far the exited process got through startup ([RaftStartupPhaseMarker]) and how long after the
 * last package update it exited. `crash_startup_marker` is always set (matched / none);
 * the rest only when known.
 */
internal fun startupPhaseExtras(
    exit: SystemKillExit,
    startup: StartupPhaseRecord?,
    reporter: SystemKillReporterBuild?,
): Map<String, Any?> =
    buildMap {
        val at = exit.timestampMillis
        put("crash_startup_marker", if (startup == null) "none" else "matched")
        if (startup != null) {
            put("crash_startup_phase", startup.phase.label)
            put("crash_ms_from_process_start", (at - startup.processStartMillis).coerceAtLeast(0L))
            put("crash_startup_phase_age_ms", at - startup.phaseAtMillis)
        }
        val updated = reporter?.packageUpdatedMillis?.takeIf { it > 0L }
        if (updated != null) {
            put("crash_package_updated_at", updated)
            // Negative when the update came after the exit (that exit predates this install).
            put("crash_update_to_exit_ms", at - updated)
        }
    }

/**
 * How the app restarted after a system kill and how long it stayed without UI recovery (#proj-mobile:#220).
 */
internal data class SystemKillRestartInfo(
    /** `"sticky_service"`, `"background_process"`, `"user_launch"`, or `"unknown"`. */
    val restartKind: String,
    /** True when the system restarted the process (`"sticky_service"` or `"background_process"`). */
    val restartedBySystem: Boolean,
    /** Wall-clock time of the first main-process start after the exit; null when unknown. */
    val restartAtMillis: Long?,
    /** Elapsed millis from `exit.timestampMillis` to `restartAtMillis`; null when unknown or clock rolled back. */
    val restartDelayMs: Long?,
    /**
     * Elapsed millis from `exit.timestampMillis` until UI recovery (`firstActivityAtMillis`, or
     * `restartAtMillis` for direct `user_launch`); null when unknown or clock rolled back.
     */
    val outageMs: Long?,
) {
    companion object {
        const val KindStickyService = "sticky_service"
        const val KindBackgroundProcess = "background_process"
        const val KindUserLaunch = "user_launch"
        const val KindUnknown = "unknown"
    }
}

/**
 * Maximum gap between process start and first Activity creation for a non-sticky process to still
 * count as a direct user launch; a longer gap means the process started in the background (FCM,
 * WorkManager, ContentProvider) and an Activity was opened later in that same process.
 */
private const val DirectUserLaunchActivityWindowMillis = 60_000L

/** Upper bound on plausible restart/outage intervals; larger values indicate a wall-clock jump (e.g. NTP sync). */
internal const val MaxPlausibleRestartDurationMillis: Long = 30L * 24 * 60 * 60 * 1000

private data class RestartCandidateProcess(
    val startMillis: Long,
    val firstActivityAtMillis: Long,
    val firstActivityAfterStartMs: Long,
    val stickyServiceRestart: Boolean,
    val reachedActivity: Boolean,
)

/**
 * Resolves how the app restarted after [exit] and the outage duration until UI recovery, using the
 * persisted [startupRecords] from [RaftStartupPhaseMarker] plus the current process's live state.
 */
internal fun resolveSystemKillRestart(
    exit: SystemKillExit,
    startupRecords: List<StartupPhaseRecord>,
    currentProcessStartMillis: Long?,
    currentFirstActivityAtMillis: Long = -1L,
    currentFirstActivityAfterStartMs: Long = -1L,
    currentStickyRestart: Boolean = false,
): SystemKillRestartInfo {
    val exitAt = exit.timestampMillis
    val candidatesByStart = LinkedHashMap<Long, RestartCandidateProcess>()

    for (record in startupRecords) {
        if (exit.processName.isNotEmpty() && record.processName.isNotEmpty() && record.processName != exit.processName) {
            continue
        }
        // Never treat the exited process itself as its own restart, even if wall clock stepped
        // backward between process start and exit (so record.processStartMillis > exitAt).
        if (record.pid == exit.pid) continue
        if (record.processStartMillis < exitAt) continue
        val reachedActivity =
            record.firstActivityAtMillis >= 0L ||
                record.firstActivityAfterStartMs >= 0L ||
                record.phase.ordinal >= RaftStartupPhaseMarker.Phase.ActivityCreated.ordinal
        val effectiveFirstActivity =
            when {
                record.firstActivityAtMillis >= 0L -> record.firstActivityAtMillis
                record.phase == RaftStartupPhaseMarker.Phase.ActivityCreated -> record.phaseAtMillis
                else -> -1L
            }
        val candidate =
            RestartCandidateProcess(
                startMillis = record.processStartMillis,
                firstActivityAtMillis = effectiveFirstActivity,
                firstActivityAfterStartMs = record.firstActivityAfterStartMs,
                stickyServiceRestart = record.stickyServiceRestart,
                reachedActivity = reachedActivity,
            )
        val existing = candidatesByStart[record.processStartMillis]
        candidatesByStart[record.processStartMillis] =
            if (existing == null) {
                candidate
            } else {
                existing.copy(
                    firstActivityAtMillis =
                        when {
                            existing.firstActivityAtMillis >= 0L && candidate.firstActivityAtMillis >= 0L ->
                                minOf(existing.firstActivityAtMillis, candidate.firstActivityAtMillis)
                            candidate.firstActivityAtMillis >= 0L -> candidate.firstActivityAtMillis
                            else -> existing.firstActivityAtMillis
                        },
                    firstActivityAfterStartMs =
                        when {
                            existing.firstActivityAfterStartMs >= 0L && candidate.firstActivityAfterStartMs >= 0L ->
                                minOf(existing.firstActivityAfterStartMs, candidate.firstActivityAfterStartMs)
                            candidate.firstActivityAfterStartMs >= 0L -> candidate.firstActivityAfterStartMs
                            else -> existing.firstActivityAfterStartMs
                        },
                    stickyServiceRestart = existing.stickyServiceRestart || candidate.stickyServiceRestart,
                    reachedActivity = existing.reachedActivity || candidate.reachedActivity,
                )
            }
    }

    if (currentProcessStartMillis != null && currentProcessStartMillis >= exitAt) {
        val currentReachedActivity = currentFirstActivityAtMillis >= 0L || currentFirstActivityAfterStartMs >= 0L
        val existing = candidatesByStart[currentProcessStartMillis]
        candidatesByStart[currentProcessStartMillis] =
            if (existing == null) {
                RestartCandidateProcess(
                    startMillis = currentProcessStartMillis,
                    firstActivityAtMillis = currentFirstActivityAtMillis,
                    firstActivityAfterStartMs = currentFirstActivityAfterStartMs,
                    stickyServiceRestart = currentStickyRestart,
                    reachedActivity = currentReachedActivity,
                )
            } else {
                existing.copy(
                    firstActivityAtMillis =
                        if (currentFirstActivityAtMillis >= 0L) currentFirstActivityAtMillis else existing.firstActivityAtMillis,
                    firstActivityAfterStartMs =
                        if (currentFirstActivityAfterStartMs >= 0L) {
                            currentFirstActivityAfterStartMs
                        } else {
                            existing.firstActivityAfterStartMs
                        },
                    stickyServiceRestart = existing.stickyServiceRestart || currentStickyRestart,
                    reachedActivity = existing.reachedActivity || currentReachedActivity,
                )
            }
    }

    val timeline = candidatesByStart.values.sortedBy { it.startMillis }
    val first = timeline.firstOrNull()
        ?: return SystemKillRestartInfo(
            restartKind = SystemKillRestartInfo.KindUnknown,
            restartedBySystem = false,
            restartAtMillis = null,
            restartDelayMs = null,
            outageMs = null,
        )

    val restartAt = first.startMillis
    val restartDelayMs = (restartAt - exitAt).takeIf { it in 0L..MaxPlausibleRestartDurationMillis }

    val isDirectUserLaunch =
        !first.stickyServiceRestart &&
            first.reachedActivity &&
            when {
                // Prefer monotonic process-start -> first-activity offset so a backward NTP step
                // during cold start never misclassifies a direct launch as background_process.
                first.firstActivityAfterStartMs >= 0L ->
                    first.firstActivityAfterStartMs <= DirectUserLaunchActivityWindowMillis
                first.firstActivityAtMillis >= 0L ->
                    (first.firstActivityAtMillis - first.startMillis) in 0L..DirectUserLaunchActivityWindowMillis
                else -> true
            }

    val restartKind =
        when {
            first.stickyServiceRestart -> SystemKillRestartInfo.KindStickyService
            isDirectUserLaunch -> SystemKillRestartInfo.KindUserLaunch
            else -> SystemKillRestartInfo.KindBackgroundProcess
        }
    val restartedBySystem =
        restartKind == SystemKillRestartInfo.KindStickyService ||
            restartKind == SystemKillRestartInfo.KindBackgroundProcess

    val outageMs: Long? =
        if (restartKind == SystemKillRestartInfo.KindUserLaunch) {
            restartDelayMs
        } else {
            val recoveryAt =
                timeline.firstNotNullOfOrNull { candidate ->
                    when {
                        candidate.firstActivityAtMillis >= candidate.startMillis && candidate.firstActivityAtMillis >= exitAt ->
                            candidate.firstActivityAtMillis
                        candidate.firstActivityAfterStartMs >= 0L && candidate.startMillis >= exitAt ->
                            candidate.startMillis + candidate.firstActivityAfterStartMs
                        candidate.firstActivityAtMillis < 0L && candidate.reachedActivity && candidate.startMillis >= exitAt ->
                            candidate.startMillis
                        else -> null
                    }
                }
            recoveryAt?.let { (it - exitAt).takeIf { delta -> delta in 0L..MaxPlausibleRestartDurationMillis } }
        }

    return SystemKillRestartInfo(
        restartKind = restartKind,
        restartedBySystem = restartedBySystem,
        restartAtMillis = restartAt,
        restartDelayMs = restartDelayMs,
        outageMs = outageMs,
    )
}

internal fun systemKillRestartExtras(restart: SystemKillRestartInfo?): Map<String, Any?> {
    if (restart == null) return emptyMap()
    return buildMap {
        put("crash_restart_kind", restart.restartKind)
        put("crash_restarted_by_system", restart.restartedBySystem)
        restart.restartAtMillis?.let { put("crash_restart_at", it) }
        restart.restartDelayMs?.let { put("crash_restart_delay_ms", it) }
        restart.outageMs?.let { put("crash_outage_ms", it) }
    }
}

/** Ticket metadata (merged into Hands `metadata_json`), in the `crash_*` vocabulary ANR tickets use. */
internal fun systemKillExtras(
    exit: SystemKillExit,
    telemetry: MemoryTelemetrySnapshot?,
    droppedBatch: BackgroundDropBatch? = null,
    /** The per-install id ([build.hands.update.HandsDeviceId]) for `crash_exit_event_id`; none when null. */
    installId: String? = null,
    /**
     * The build writing the report: always sent as `reporter_version_*` (never `version_*`), and
     * the last version source when the exit carries none of its own; none when null.
     */
    reporter: SystemKillReporterBuild? = null,
    /** The exited process's startup progress ([RaftStartupPhaseMarker]); null when none was found. */
    startup: StartupPhaseRecord? = null,
    /** How the app restarted and outage duration until UI recovery ([resolveSystemKillRestart]); omitted when null. */
    restart: SystemKillRestartInfo? = null,
): Map<String, Any?> =
    buildMap {
        installId?.let { put("crash_exit_event_id", systemKillExitEventId(it, exit)) }
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
            // Neither: the host app's installed version, inferred when the package has not been
            // updated since before the exit. Not recorded by the process, and wall-clock based (a
            // clock changed after the exit can defeat it); crash_version_inferred says so.
            // Only when the defining uid is known to be ours: per ApplicationExitInfo.getDefiningUid
            // a foreign one means the process hosted an external service provider's code, so our
            // installed version says nothing about it; a missing or invalid uid can't rule that out.
            // Our own isolated or app-zygote processes (getRealUid differs from getPackageUid,
            // getDefiningUid does not) run our APK, so the inference still applies to them.
            reporter != null && exit.definingUidForeign == false && installedBuildInferredForExit(exit, reporter) -> {
                put("fallback_version_code", reporter.versionCode)
                put("fallback_version_name", reporter.versionName)
                put("crash_version_source", "installed_before_exit")
            }
            else -> put("crash_version_source", "unknown")
        }
        put("crash_version_inferred", get("crash_version_source") == "installed_before_exit")
        reporter?.let {
            put("reporter_version_name", it.versionName)
            put("reporter_version_code", it.versionCode)
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
        // The uid facts say what kind of process exited (isolated, or hosting another package's
        // code), not why it was killed.
        exit.isolatedProcess?.let { put("crash_process_isolated", it) }
        exit.definingUidForeign?.let { put("crash_defining_uid_foreign", it) }
        exit.description?.trim()?.takeIf(String::isNotEmpty)?.let {
            put("crash_exit_description", it.take(MaxExitDescriptionChars))
        }
        put("crash_memory_sample", if (telemetry == null) "none" else "matched")
        exit.summaryState?.let { put("crash_exit_summary", it.label) }
        putAll(startupPhaseExtras(exit, startup, reporter))
        putAll(systemKillRestartExtras(restart))
        // The app's recent foreground evidence (see classifySystemKill), next to the raw fields:
        // crash_exit_importance, crash_foreground (the exit importance) and crash_trim are never
        // replaced by an inference. Names proposed in botiverse/hands#955.
        val recent = recentForeground(telemetry, exit.timestampMillis)
        recent.value?.let { put("crash_recent_foreground", it) }
        put("crash_recent_foreground_source", recent.source)
        recent.ageSeconds?.let { put("crash_recent_foreground_age_s", it) }
        // Dropped background reports this one carries (best effort; see BackgroundDropLedger).
        droppedBatch?.let { putAll(it.extras()) }
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
internal fun systemKillMessage(
    exit: SystemKillExit,
    telemetry: MemoryTelemetrySnapshot?,
    startup: StartupPhaseRecord? = null,
    restart: SystemKillRestartInfo? = null,
): String =
    buildString {
        val at = exit.timestampMillis
        append("System kill: ").append(historicalProcessExitReasonLabel(exit.reasonCode))
        append(" (reasonCode=").append(exit.reasonCode)
        append(" status=").append(exit.status)
        append(" importance=").append(exit.importance).append(')')
        if (startup != null) {
            append("\nstartup reached ").append(startup.phase.label)
            append(" (").append(beforeExit(at, startup.phaseAtMillis)).append("), ")
            append((at - startup.processStartMillis).coerceAtLeast(0L)).append(" ms after process start")
        }
        if (restart != null) {
            append("\nrestart ").append(restart.restartKind)
            append(" (by_system=").append(restart.restartedBySystem)
            restart.restartDelayMs?.let { append(", delay=").append(it / 1000L).append('s') }
            restart.outageMs?.let { append(", outage=").append(it / 1000L).append('s') }
            append(')')
        }
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
 * See [runSystemKillReports]: queued reports first, then new exits by budget; all state in one
 * preferences file, one commit per step.
 */
internal object AndroidSystemKillReporter {
    private const val Tag = "SlockAndroidProcessExit"
    private const val PreferencesName = "raft-process-exit-diagnostics"
    private const val WatermarkKey = "system-kill-reported-timestamp-millis"
    /** Visible reports (and every report sent before the budgets were split). */
    private const val RecentReportsKey = "system-kill-recent-reports"
    private const val RecentReviewReportsKey = "system-kill-recent-review-reports"
    private const val RecentBackgroundReportsKey = "system-kill-recent-background-reports"
    private const val HandledAboveWatermarkKey = "system-kill-handled-above-watermark"
    private const val DroppedLedgerKey = "system-kill-background-dropped"
    private const val QueueKey = "system-kill-pending-reports"
    private const val MaxExitsQueried = 16
    internal const val MaxReportsPerLaunch = 3
    internal const val MaxReportsPerDay = 5
    internal const val MaxReviewReportsPerLaunch = 3
    internal const val MaxReviewReportsPerDay = 5
    internal const val MaxBackgroundReportsPerLaunch = 3
    internal const val MaxBackgroundReportsPerDay = 5
    private val started = AtomicBoolean(false)
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs in the background; the Hands client is only built when there is something to send.
     * `RAFT_PRIVACY_MODE`: nothing is looked at or sent until every signed-in server answered
     * privacy mode off ([RaftServicesPrivacyGate]); on or unknown, the exits stay unreported (the
     * watermark does not move) for the next launch to decide again.
     */
    fun start(context: Context, baseUrl: String, appSlug: String, channel: String?, clientKey: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        uploadScope.launch {
            val privacyMode = RaftServicesPrivacyGate.awaitLaunchDecision()
            if (!RaftServicesPrivacyGate.allowsAutomatic(privacyMode)) {
                // Keep enough startup records for a later launch to resolve intermediate sticky restarts.
                runCatching { RaftStartupPhaseMarker.prune(appContext.filesDir, keep = MaxExitsQueried) }
                RaftAndroidLocalDiagnostics.recordLog(
                    "info",
                    Tag,
                    "system_kill_report_skipped reason=privacy_mode mode=${privacyMode.name.lowercase()} kept_for=next_launch",
                )
                return@launch
            }
            try {
                runCatching {
                    val feedback by lazy {
                        // Version comes from each exit's extras (stamp, its process's record, or the
                        // installed build when it predates the exit), never set here for this install.
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
                    reportPending(appContext) { message, extras, submissionId ->
                        val submission =
                            feedback.submitReport(message = message, kind = "crash", extras = extras, submissionId = submissionId)
                        SystemKillSubmission(ticketId = submission.ticketId, merged = submission.merged)
                    }
                }.onFailure { error ->
                    RaftAndroidLocalDiagnostics.recordLog("warn", Tag, "system_kill_report_failed class=${error.javaClass.name}")
                }
            } finally {
                // Prune only after reportPending has read startup records so intermediate sticky restarts stay visible.
                runCatching { RaftStartupPhaseMarker.prune(appContext.filesDir) }
            }
        }
    }

    private suspend fun reportPending(
        context: Context,
        submit: suspend (message: String, extras: Map<String, Any?>, submissionId: String?) -> SystemKillSubmission,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val snapshots = RaftMemoryTelemetry.previousSnapshots.await()
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // The id Hands already keeps per install; without it crash_exit_event_id is omitted, never shared.
        val installId = runCatching { HandsDeviceId.get(context) }.getOrNull()?.takeIf { it.isNotBlank() }
        val mainProcessName = context.applicationInfo.processName ?: context.packageName
        val packageUpdatedMillis =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrNull()
        val reporter =
            SystemKillReporterBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), packageUpdatedMillis, now)
        // Startup progress of earlier processes (RaftStartupPhaseMarker), joined to exits by pid.
        val startupRecords = runCatching { RaftStartupPhaseMarker.readAll(context.filesDir) }.getOrDefault(emptyList())
        val fallbackCurrentProcessStartMillis =
            now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        // The SDK's own native-crash records still on disk (read-only; see HandsNativeExitCorrelation.kt).
        val nativeScan =
            runCatching { scanHandsNativePendingRecords(handsNativeCrashDir(context.filesDir)) }
                .getOrElse { HandsNativePendingScan(emptyList(), 0) }
        if (nativeScan.records.isNotEmpty() || nativeScan.unreadable > 0) {
            RaftAndroidLocalDiagnostics.recordLog(
                "info",
                Tag,
                "hands_native_records pending=${nativeScan.records.size} unreadable=${nativeScan.unreadable}",
            )
        }
        runSystemKillReports(
            store = PreferencesProgressStore(preferences),
            nowMillis = now,
            limits =
                mapOf(
                    SystemKillBudget.Visible to SystemKillBudgetLimit(MaxReportsPerLaunch, MaxReportsPerDay),
                    SystemKillBudget.Review to SystemKillBudgetLimit(MaxReviewReportsPerLaunch, MaxReviewReportsPerDay),
                    SystemKillBudget.Background to SystemKillBudgetLimit(MaxBackgroundReportsPerLaunch, MaxBackgroundReportsPerDay),
                ),
            readPlan = { progress, remaining ->
                val visible = remaining.getValue(SystemKillBudget.Visible)
                planSystemKillReports(
                    exits =
                        activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MaxExitsQueried)
                            .map { it.toSystemKillExit() },
                    snapshots = snapshots,
                    // Before the first launch with this reporter nothing was decided; do not look back.
                    cutoffMillis = firstSeenMillis(preferences, packageUpdatedMillis, now),
                    watermarkMillis = progress.watermarkMillis,
                    recentReportMillis = visible.recentReportMillis,
                    nowMillis = now,
                    perLaunchCap = visible.perLaunch,
                    dailyCap = visible.perDay,
                    mainProcessName = mainProcessName,
                    review = remaining.getValue(SystemKillBudget.Review),
                    background = remaining.getValue(SystemKillBudget.Background),
                    handledAboveWatermark = progress.handledAboveWatermark,
                    startupRecords = startupRecords,
                )
            },
            buildReport = { item ->
                val startup = startupRecordForExit(startupRecords, item.exit.pid, item.exit.timestampMillis)
                val current = RaftStartupPhaseMarker.current()
                val restart =
                    resolveSystemKillRestart(
                        exit = item.exit,
                        startupRecords = startupRecords,
                        currentProcessStartMillis = current?.processStartMillis ?: fallbackCurrentProcessStartMillis,
                        currentFirstActivityAtMillis = current?.firstActivityAtMillis ?: -1L,
                        currentFirstActivityAfterStartMs = current?.firstActivityAfterStartMs ?: -1L,
                        currentStickyRestart = current?.stickyServiceRestart ?: false,
                    )
                systemKillMessage(item.exit, item.telemetry, startup, restart) to
                    systemKillExtras(
                        item.exit,
                        item.telemetry,
                        installId = installId,
                        reporter = reporter,
                        startup = startup,
                        restart = restart,
                    )
            },
            newBatchId = { UUID.randomUUID().toString() },
            submit = submit,
            log = ::logOutcome,
            onRetryableFailure = { timestampMillis, error ->
                RaftAndroidLocalDiagnostics.recordLog(
                    "warn",
                    Tag,
                    "system_kill_report_upload_failed timestampMillis=$timestampMillis " +
                        "class=${error.javaClass.name} retry=next_launch",
                )
            },
            // HandsCrash.install uploads native records itself and drops the results: no native
            // ticket id reaches the app, nothing is ever confirmed. Exits with a process event id
            // are merged by Hands instead (NativeExitVerdict.Merge).
            nativeConfirmations = HandsNativeTicketConfirmation.None,
            nativePending = nativeScan.records,
            onNativeVerdict = { queued, verdict ->
                val wait = queued.nativeExit
                RaftAndroidLocalDiagnostics.recordLog(
                    "info",
                    Tag,
                    "native_exit_correlation verdict=${verdict.label} timestampMillis=${queued.key.timestampMillis} " +
                        "processStart=${if (wait?.identity?.processStartMillis != null) "known" else "unknown"} " +
                        "launchesWaited=${wait?.launchesWaited ?: 0}" +
                        when (verdict) {
                            is NativeExitVerdict.Report -> " correlation=${verdict.correlation}"
                            is NativeExitVerdict.SkipConfirmed -> " ticket=${verdict.ticketId.take(8)}"
                            is NativeExitVerdict.Merge -> " correlation=$NativeCorrelationSubmissionId"
                            NativeExitVerdict.Wait -> ""
                        },
                )
            },
        )
    }

    /** All reporter state in one preferences file; every [commit] is a single editor commit. */
    private class PreferencesProgressStore(private val preferences: SharedPreferences) : SystemKillProgressStore {
        private val recentKeys =
            mapOf(
                SystemKillBudget.Visible to RecentReportsKey,
                SystemKillBudget.Review to RecentReviewReportsKey,
                SystemKillBudget.Background to RecentBackgroundReportsKey,
            )

        override fun load(): SystemKillProgress =
            SystemKillProgress(
                watermarkMillis = preferences.getLong(WatermarkKey, 0L),
                handledAboveWatermark = SystemKillExitKey.decodeAll(preferences.getString(HandledAboveWatermarkKey, null)),
                ledger = BackgroundDropLedger.decode(preferences.getString(DroppedLedgerKey, null)),
                queue = QueuedSystemKillReport.decodeAll(preferences.getString(QueueKey, null)),
                recentReportMillis =
                    recentKeys.mapValues { (_, key) ->
                        preferences.getString(key, null).orEmpty().split(',').mapNotNull(String::toLongOrNull)
                    },
            )

        override fun commit(progress: SystemKillProgress): Boolean {
            val editor =
                preferences.edit()
                    .putLong(WatermarkKey, progress.watermarkMillis)
                    .putString(HandledAboveWatermarkKey, SystemKillExitKey.encodeAll(progress.handledAboveWatermark))
                    .putString(DroppedLedgerKey, progress.ledger.encode())
                    .putString(QueueKey, QueuedSystemKillReport.encodeAll(progress.queue))
            recentKeys.forEach { (budget, key) ->
                editor.putString(key, progress.recentReportMillis[budget].orEmpty().joinToString(","))
            }
            return editor.commit()
        }
    }

    private fun logOutcome(item: SystemKillPlanItem?, queued: QueuedSystemKillReport?, outcome: String) {
        if (item == null) {
            RaftAndroidLocalDiagnostics.recordLog(
                "warn",
                Tag,
                "system_kill_report action=queued_report budget=${queued?.budget?.label} " +
                    "timestampMillis=${queued?.key?.timestampMillis} ticket=$outcome",
            )
            return
        }
        log(item, outcome)
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

    private fun firstSeenMillis(preferences: SharedPreferences, packageUpdatedMillis: Long?, now: Long): Long {
        val key = "system-kill-first-seen-millis"
        return resolveSystemKillBaseline(
            storedMillis = if (preferences.contains(key)) preferences.getLong(key, now) else null,
            packageUpdatedMillis = packageUpdatedMillis,
            nowMillis = now,
            persist = { preferences.edit().putLong(key, it).commit() },
            onPersistFailed = {
                RaftAndroidLocalDiagnostics.recordLog("warn", Tag, "system_kill_report_baseline_commit_failed retry=next_launch")
            },
        )
    }
}

@RequiresApi(Build.VERSION_CODES.R)
internal fun ApplicationExitInfo.toSystemKillExit(): SystemKillExit =
    SystemKillExit(
        pid = pid,
        timestampMillis = timestamp,
        reasonCode = reason,
        status = status,
        importance = importance,
        pssKb = pss,
        rssKb = rss,
        build = RaftBuildStamp.decodeStamp(processStateSummary),
        summaryState = ExitSummaryState.of(processStateSummary),
        processName = processName.orEmpty(),
        description = description,
        // realUid/packageUid/definingUid are API 30, like the reporter (start() returns below R).
        isolatedProcess = realUid != packageUid,
        packageUid = packageUid,
        definingUid = definingUid,
    )

/**
 * HTTP statuses that are not the report's fault and may pass once fixed or later: 401 (Hands
 * answers it for a wrong or missing client key), 403, 404 (Hands answers it for an unknown app
 * slug), 408 (timeout) and 429 (rate limit).
 */
internal val RetryableHandsStatuses = setOf(401, 403, 404, 408, 429)

/**
 * A ticket Hands will never accept as sent (malformed: 400, 413, 422 and the other 4xx not in
 * [RetryableHandsStatuses]). Everything else, network errors and 5xx included, is retried.
 */
internal fun isPermanentHandsRejection(error: Throwable): Boolean {
    val code = (error as? HandsFeedbackException)?.code ?: return false
    return code in 400..499 && code !in RetryableHandsStatuses
}
