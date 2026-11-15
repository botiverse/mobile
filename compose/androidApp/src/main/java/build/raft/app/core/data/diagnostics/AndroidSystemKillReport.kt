package build.raft.app.core.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import build.hands.update.HandsFeedback
import build.hands.update.HandsDeviceId
import build.hands.update.HandsFeedbackException
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
            exit.reasonCode !in HandsActionableSystemKillReasonCodes -> SystemKillBudget.Background
        else -> SystemKillBudget.Review
    }
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
): List<SystemKillPlanItem> {
    val floor = maxOf(cutoffMillis, watermarkMillis)
    val classified =
        exits
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
 * A visible or review report deferred for budget, fully built when it was deferred (message and
 * metadata with the evidence already resolved), so it is sent as classified even after the exit
 * left the system's exit list or its telemetry left the snapshot ring.
 */
internal data class QueuedSystemKillReport(
    val key: SystemKillExitKey,
    val budget: SystemKillBudget,
    val versionCode: Long,
    val message: String,
    /** JSON-safe values only: strings, numbers, booleans, null. */
    val extras: Map<String, Any?>,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("pid", key.pid)
            .put("timestamp", key.timestampMillis)
            .put("budget", budget.name)
            .put("version_code", versionCode)
            .put("message", message)
            .put("extras", JSONObject().apply { extras.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })

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

/** At most this many deferred reports wait on the device ([SystemKillProgress.enqueue]). */
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
     * Adds [report]; over [cap] the oldest review report is evicted first, the oldest visible one
     * only when no review report is left. Each eviction is counted (category visible / review).
     */
    fun enqueue(report: QueuedSystemKillReport, cap: Int = MaxQueuedSystemKillReports): SystemKillProgress {
        var nextQueue = queue + report
        var nextLedger = ledger
        while (nextQueue.size > cap) {
            val victim =
                nextQueue.filter { it.budget == SystemKillBudget.Review }.minByOrNull { it.key.timestampMillis }
                    ?: nextQueue.minBy { it.key.timestampMillis }
            nextQueue = nextQueue - victim
            nextLedger = nextLedger.record(victim.versionCode, victim.key.timestampMillis, category = victim.budget.label)
        }
        return copy(queue = nextQueue, ledger = nextLedger)
    }
}

/** Where the reporter keeps [SystemKillProgress]; each [commit] is one atomic write. */
internal interface SystemKillProgressStore {
    fun load(): SystemKillProgress

    fun commit(progress: SystemKillProgress)
}

/**
 * One launch: first the queued reports (visible, then review, oldest first) within budget, then
 * the new exits [readPlan] plans with what is left of each budget. Every step is one commit.
 *
 * - An upload carries the never-sent counts ([BackgroundDropBatch.extras]): frozen under their id
 *   and committed before the upload, so a retry resends the same id and entries; only an accepted
 *   upload clears them.
 * - A retryable upload error stops the launch (that report is retried next launch); Hands
 *   rejecting a ticket for good ([isPermanentHandsRejection]) handles it.
 * - A new visible or review exit over budget is built now and queued ([SystemKillProgress.enqueue])
 *   in the same commit that marks it handled; a background one is counted.
 */
internal suspend fun runSystemKillReports(
    store: SystemKillProgressStore,
    nowMillis: Long,
    limits: Map<SystemKillBudget, SystemKillBudgetLimit>,
    readPlan: (progress: SystemKillProgress, remaining: Map<SystemKillBudget, SystemKillBudgetLimit>) -> List<SystemKillPlanItem>,
    buildReport: (item: SystemKillPlanItem) -> Pair<String, Map<String, Any?>>,
    newBatchId: () -> String,
    submit: suspend (message: String, extras: Map<String, Any?>) -> String,
    log: (item: SystemKillPlanItem?, queued: QueuedSystemKillReport?, outcome: String) -> Unit = { _, _, _ -> },
    onRetryableFailure: (timestampMillis: Long, error: Throwable) -> Unit = { _, _ -> },
    queueCap: Int = MaxQueuedSystemKillReports,
) {
    var progress = store.load()
    val sentNow = SystemKillBudget.entries.associateWith { 0 }.toMutableMap()
    fun hasRoom(budget: SystemKillBudget): Boolean {
        val limit = limits.getValue(budget)
        return sentNow.getValue(budget) < limit.perLaunch && progress.recent(budget, nowMillis).size < limit.perDay
    }

    /** Uploads; null on a retryable error (progress holds the frozen batch), else progress after it. */
    suspend fun upload(timestampMillis: Long, message: String, extras: Map<String, Any?>): Pair<SystemKillProgress, String>? {
        val (ledger, dropped) = progress.ledger.attach(newBatchId)
        if (ledger != progress.ledger) {
            progress = progress.copy(ledger = ledger)
            store.commit(progress)
        }
        val result = runCatching { submit(message, extras + dropped?.extras().orEmpty()) }
        val error = result.exceptionOrNull()
        if (error != null && !isPermanentHandsRejection(error)) {
            onRetryableFailure(timestampMillis, error)
            return null
        }
        val after = progress.copy(ledger = if (result.isSuccess) ledger.uploaded(dropped?.id) else ledger)
        return after to (result.getOrNull()?.take(8) ?: "rejected")
    }

    for (queued in progress.queue.sortedWith(compareBy<QueuedSystemKillReport>({ it.budget.ordinal }, { it.key.timestampMillis }))) {
        if (!hasRoom(queued.budget)) continue
        val (after, ticket) = upload(queued.key.timestampMillis, queued.message, queued.extras) ?: return
        progress = after.copy(queue = after.queue - queued).withReport(queued.budget, nowMillis)
        sentNow[queued.budget] = sentNow.getValue(queued.budget) + 1
        store.commit(progress)
        log(null, queued, ticket)
    }

    val remaining =
        limits.mapValues { (budget, limit) ->
            SystemKillBudgetLimit(
                perLaunch = (limit.perLaunch - sentNow.getValue(budget)).coerceAtLeast(0),
                perDay = limit.perDay,
                recentReportMillis = progress.recent(budget, nowMillis),
            )
        }
    val plan = readPlan(progress, remaining)
    for (item in systemKillProcessingOrder(plan)) {
        when (item.action) {
            SystemKillAction.Submit -> {
                val (message, extras) = buildReport(item)
                val (after, ticket) = upload(item.exit.timestampMillis, message, extras) ?: return
                progress = after.afterHandled(item, plan).withReport(item.budget, nowMillis)
                store.commit(progress)
                log(item, null, ticket)
            }
            SystemKillAction.Deferred -> {
                val (message, extras) = buildReport(item)
                val queued =
                    QueuedSystemKillReport(item.key, item.budget, systemKillExitVersionCode(item.exit, item.telemetry), message, extras)
                progress = progress.afterHandled(item, plan, queued, queueCap)
                store.commit(progress)
                log(item, queued, "queued")
            }
            SystemKillAction.Skip, SystemKillAction.RateLimited -> {
                progress = progress.afterHandled(item, plan)
                store.commit(progress)
                log(item, null, "none")
            }
        }
    }
}

/**
 * Reports of one exit build and UTC exit day that were never sent: [category] `background` (over
 * the background budget) or `visible` / `review` (evicted from a full pending queue).
 */
internal data class BackgroundDropEntry(
    val versionCode: Long,
    val exitDayUtc: String,
    val count: Int,
    val category: String = BackgroundDropEntry.Background,
) {
    companion object {
        const val Background = "background"
    }
}

/** Entries sent together under one random [id], so Hands can dedupe a resent batch. */
internal data class BackgroundDropBatch(val id: String, val entries: List<BackgroundDropEntry>) {
    /** `crash_background_dropped`: `[{version_code, exit_day_utc, count}]`, background budget drops only. */
    fun toJson(): JSONArray = json(entries.filter { it.category == BackgroundDropEntry.Background }, withCategory = false)

    /** `crash_pending_evicted`: `[{version_code, exit_day_utc, category, count}]`, queue evictions only. */
    fun evictedToJson(): JSONArray = json(entries.filter { it.category != BackgroundDropEntry.Background }, withCategory = true)

    /** The ticket fields for this batch (none when empty): both lists share [id]. */
    fun extras(): Map<String, Any?> =
        buildMap<String, Any?> {
            if (entries.isEmpty()) return@buildMap
            put("crash_background_dropped_id", id)
            toJson().takeIf { it.length() > 0 }?.let { put("crash_background_dropped", it) }
            evictedToJson().takeIf { it.length() > 0 }?.let { put("crash_pending_evicted", it) }
        }

    /** Every entry with its category, for the device's own store. */
    fun allToJson(): JSONArray = json(entries, withCategory = true)

    private fun json(list: List<BackgroundDropEntry>, withCategory: Boolean): JSONArray =
        JSONArray().apply {
            list.forEach {
                val entry = JSONObject().put("version_code", it.versionCode).put("exit_day_utc", it.exitDayUtc)
                if (withCategory) entry.put("category", it.category)
                put(entry.put("count", it.count))
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
    fun record(
        versionCode: Long,
        exitTimestampMillis: Long,
        category: String = BackgroundDropEntry.Background,
    ): BackgroundDropLedger {
        val day = utcDay(exitTimestampMillis)
        val existing =
            accumulating.firstOrNull { it.versionCode == versionCode && it.exitDayUtc == day && it.category == category }
        val next =
            if (existing != null) {
                accumulating.map { if (it === existing) it.copy(count = it.count + 1) else it }
            } else {
                accumulating + BackgroundDropEntry(versionCode, day, 1, category)
            }
        return copy(
            accumulating = next.sortedWith(compareBy<BackgroundDropEntry>({ it.exitDayUtc }, { it.category }, { it.versionCode })).takeLast(MaxEntries),
        )
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
            pending?.let { put("pending_id", it.id).put("pending", it.allToJson()) }
            put("accumulating", BackgroundDropBatch("", accumulating).allToJson())
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
                BackgroundDropEntry(
                    entry.optLong("version_code", -1L),
                    entry.optString("exit_day_utc"),
                    entry.optInt("count"),
                    entry.optString("category", BackgroundDropEntry.Background),
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

/** Ticket metadata (merged into Hands `metadata_json`), in the `crash_*` vocabulary ANR tickets use. */
internal fun systemKillExtras(
    exit: SystemKillExit,
    telemetry: MemoryTelemetrySnapshot?,
    droppedBatch: BackgroundDropBatch? = null,
    /** The per-install id ([build.hands.update.HandsDeviceId]) for `crash_exit_event_id`; none when null. */
    installId: String? = null,
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
        // The id Hands already keeps per install; without it crash_exit_event_id is omitted, never shared.
        val installId = runCatching { HandsDeviceId.get(context) }.getOrNull()?.takeIf { it.isNotBlank() }
        val mainProcessName = context.applicationInfo.processName ?: context.packageName
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
                    cutoffMillis = firstSeenMillis(preferences, now),
                    watermarkMillis = progress.watermarkMillis,
                    recentReportMillis = visible.recentReportMillis,
                    nowMillis = now,
                    perLaunchCap = visible.perLaunch,
                    dailyCap = visible.perDay,
                    mainProcessName = mainProcessName,
                    review = remaining.getValue(SystemKillBudget.Review),
                    background = remaining.getValue(SystemKillBudget.Background),
                    handledAboveWatermark = progress.handledAboveWatermark,
                )
            },
            buildReport = { item ->
                systemKillMessage(item.exit, item.telemetry) to systemKillExtras(item.exit, item.telemetry, installId = installId)
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

        override fun commit(progress: SystemKillProgress) {
            val editor =
                preferences.edit()
                    .putLong(WatermarkKey, progress.watermarkMillis)
                    .putString(HandledAboveWatermarkKey, SystemKillExitKey.encodeAll(progress.handledAboveWatermark))
                    .putString(DroppedLedgerKey, progress.ledger.encode())
                    .putString(QueueKey, QueuedSystemKillReport.encodeAll(progress.queue))
            recentKeys.forEach { (budget, key) ->
                editor.putString(key, progress.recentReportMillis[budget].orEmpty().joinToString(","))
            }
            editor.commit()
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

    private fun firstSeenMillis(preferences: SharedPreferences, now: Long): Long {
        val key = "system-kill-first-seen-millis"
        if (!preferences.contains(key)) preferences.edit().putLong(key, now).commit()
        return preferences.getLong(key, now)
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
