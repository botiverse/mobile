package build.raft.app.core.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import build.raft.app.data.RaftBackgroundKillNotice
import build.raft.app.data.RaftBackgroundKillNoticeHost
import build.raft.app.data.RaftBackgroundKillNoticeHostRegistry
import build.raft.app.shared.AndroidOemVendor
import build.raft.app.shared.AndroidRealtimeKeepAliveController
import build.raft.app.shared.RaftAndroidVendorWhitelist
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val BackgroundKillNoticeMaxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000
internal const val BackgroundKillNoticeFirstDismissCooldownMillis: Long = 7L * 24 * 60 * 60 * 1000
internal const val BackgroundKillNoticeRepeatDismissCooldownMillis: Long = 30L * 24 * 60 * 60 * 1000
internal const val BackgroundKillNoticeSettingsCooldownMillis: Long = 24L * 60 * 60 * 1000

/** The notice appears at most this many times on an install, then never again. */
internal const val BackgroundKillNoticeMaxAppearances: Int = 2

private val OemCleanerReasonCodes =
    setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

private val VendorCleanerDescriptionPattern =
    Regex(
        "(?:zuimemorycleaner|powergenie|iaware|cleaner|smartmanager|athena|millet|(?<![a-z])hans)",
        RegexOption.IGNORE_CASE,
    )

internal fun hasVendorCleanerDescription(description: String?): Boolean =
    !description.isNullOrBlank() && VendorCleanerDescriptionPattern.containsMatchIn(description)

/**
 * Persisted dedup and cooldown state for the local background-kill notice.
 *
 * [lastAcknowledgedExitTimestampMillis] is advanced ONLY on user action (`dismiss()`,
 * `dismissForever()` or `markSettingsOpened()`), NEVER during `Application.onCreate()`
 * evaluation, so a sticky-service restart never swallows a notice before the user opens the UI.
 *
 * [shownCount] counts the processes in which the banner was really on screen: `markShown()` adds
 * one, at most once per process, when the banner is composed. A headless start (a push, a widget,
 * the keep-alive service) only evaluates and never counts. At [BackgroundKillNoticeMaxAppearances]
 * the notice is never selected again. [suppressedForever] is the user's "Don't show again".
 */
internal data class BackgroundKillNoticePersistenceState(
    val baselineMillis: Long? = null,
    val lastAcknowledgedExitTimestampMillis: Long = 0L,
    val lastDismissedAtMillis: Long = 0L,
    val dismissCount: Int = 0,
    val lastOpenedSettingsAtMillis: Long = 0L,
    val shownCount: Int = 0,
    val suppressedForever: Boolean = false,
)

internal interface BackgroundKillNoticeStore {
    fun load(): BackgroundKillNoticePersistenceState

    fun save(state: BackgroundKillNoticePersistenceState)

    fun persistBaseline(baselineMillis: Long): Boolean
}

internal fun qualifiesForBackgroundKillNotice(
    exit: SystemKillExit,
    telemetry: MemoryTelemetrySnapshot?,
    vendor: AndroidOemVendor,
    noPlayServicesFallbackApplies: Boolean,
    keepAlivePreferenceEnabled: Boolean,
): Boolean {
    if (noPlayServicesFallbackApplies || keepAlivePreferenceEnabled) return true
    if (exit.importance in ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE until ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
        return true
    }
    if (hasVendorCleanerDescription(exit.description)) return true
    if (vendor != AndroidOemVendor.Generic &&
        exit.reasonCode in OemCleanerReasonCodes &&
        telemetry?.systemLowMemory == false
    ) {
        return true
    }
    return false
}

/**
 * Pure eligibility predicate for showing the local background-kill whitelist notice (#proj-mobile:#220).
 *
 * Never suppresses solely because `PowerManager.isIgnoringBatteryOptimizations()` is true: OEM
 * task/app-launch cleaners (Huawei PowerGenie, Lenovo ZuiMemoryCleaner, etc.) still kill apps
 * unless explicitly whitelisted in vendor settings.
 *
 * Selects nothing after "Don't show again" or once the banner has been on screen
 * [BackgroundKillNoticeMaxAppearances] times on this install. A notice already on screen stays
 * there all the same: the controller keeps it (see [RaftAndroidBackgroundKillNoticeController.evaluate]).
 */
internal fun selectBackgroundKillForNotice(
    exits: List<SystemKillExit>,
    snapshots: List<MemoryTelemetrySnapshot>,
    state: BackgroundKillNoticePersistenceState,
    baselineMillis: Long,
    nowMillis: Long,
    mainProcessName: String?,
    vendor: AndroidOemVendor,
    noPlayServicesFallbackApplies: Boolean,
    keepAlivePreferenceEnabled: Boolean,
    startupRecords: List<StartupPhaseRecord> = emptyList(),
): SystemKillExit? {
    if (state.suppressedForever || state.shownCount >= BackgroundKillNoticeMaxAppearances) return null
    if (state.dismissCount > 0 && state.lastDismissedAtMillis > 0L) {
        val cooldown =
            if (state.dismissCount >= 2) {
                BackgroundKillNoticeRepeatDismissCooldownMillis
            } else {
                BackgroundKillNoticeFirstDismissCooldownMillis
            }
        if (nowMillis < state.lastDismissedAtMillis + cooldown) return null
    }
    if (state.lastOpenedSettingsAtMillis > 0L &&
        nowMillis < state.lastOpenedSettingsAtMillis + BackgroundKillNoticeSettingsCooldownMillis
    ) {
        return null
    }
    val floorMillis =
        maxOf(
            baselineMillis,
            state.lastAcknowledgedExitTimestampMillis,
            state.lastDismissedAtMillis,
            state.lastOpenedSettingsAtMillis,
        )
    return exits
        .asSequence()
        .filter { exit ->
            exit.timestampMillis > floorMillis &&
                (nowMillis - exit.timestampMillis) in 0L..BackgroundKillNoticeMaxAgeMillis &&
                (mainProcessName == null || exit.processName.isEmpty() || exit.processName == mainProcessName)
        }
        .filter { exit ->
            val telemetry = MemoryTelemetryRing.match(snapshots, exit.pid, exit.timestampMillis)
            val startup = startupRecordForExit(startupRecords, exit.pid, exit.timestampMillis)
            val decision =
                classifySystemKill(
                    reasonCode = exit.reasonCode,
                    importance = exit.importance,
                    telemetry = telemetry,
                    exitTimestampMillis = exit.timestampMillis,
                    status = exit.status,
                    description = exit.description,
                    startup = startup,
                )
            decision == SystemKillDecision.ReportBackground &&
                qualifiesForBackgroundKillNotice(
                    exit = exit,
                    telemetry = telemetry,
                    vendor = vendor,
                    noPlayServicesFallbackApplies = noPlayServicesFallbackApplies,
                    keepAlivePreferenceEnabled = keepAlivePreferenceEnabled,
                )
        }
        .maxByOrNull { it.timestampMillis }
}

/** Testable controller backing [RaftAndroidBackgroundKillNoticeHost]. */
internal class RaftAndroidBackgroundKillNoticeController(
    private val store: BackgroundKillNoticeStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val persistAsync: (() -> Unit) -> Unit = { it() },
    private val mutableNotice: MutableStateFlow<RaftBackgroundKillNotice?> = MutableStateFlow(null),
) : RaftBackgroundKillNoticeHost {
    private val lock = Any()
    private var cachedState: BackgroundKillNoticePersistenceState? = null

    /** Whether this process has counted its appearance ([markShown]); guarded by [lock]. */
    private var appearanceCounted = false

    override val notice: StateFlow<RaftBackgroundKillNotice?> = mutableNotice.asStateFlow()

    /**
     * Picks this process's notice. It counts no appearance: only [markShown], once the banner is on
     * screen, does, so a headless start (a push, a widget, the keep-alive service) uses none. A
     * notice already on screen stays until the user acts on it: an evaluation that selects nothing
     * (past the cap, say) keeps it rather than taking it away.
     */
    fun evaluate(
        exits: List<SystemKillExit>,
        snapshots: List<MemoryTelemetrySnapshot>,
        packageUpdatedMillis: Long?,
        mainProcessName: String?,
        vendor: AndroidOemVendor,
        noPlayServicesFallbackApplies: Boolean,
        keepAlivePreferenceEnabled: Boolean,
        startupRecords: List<StartupPhaseRecord> = emptyList(),
    ): RaftBackgroundKillNotice? {
        val now = nowMillis()
        // Keep SharedPreferences / disk I/O strictly outside [lock] so main-thread clicks never
        // contend with background evaluation or baseline fsyncs.
        val loaded = store.load()
        val baseline =
            resolveSystemKillBaseline(
                storedMillis = loaded.baselineMillis,
                packageUpdatedMillis = packageUpdatedMillis,
                nowMillis = now,
                persist = store::persistBaseline,
            )
        val loadedWithBaseline =
            if (loaded.baselineMillis == null) loaded.copy(baselineMillis = baseline) else loaded
        val selected: SystemKillExit?
        val result: RaftBackgroundKillNotice?
        synchronized(lock) {
            val state = mergePersistenceState(cachedState, loadedWithBaseline)
            cachedState = state
            selected =
                selectBackgroundKillForNotice(
                    exits = exits,
                    snapshots = snapshots,
                    state = state,
                    baselineMillis = baseline,
                    nowMillis = now,
                    mainProcessName = mainProcessName,
                    vendor = vendor,
                    noPlayServicesFallbackApplies = noPlayServicesFallbackApplies,
                    keepAlivePreferenceEnabled = keepAlivePreferenceEnabled,
                    startupRecords = startupRecords,
                )
            // A notice already on screen stays when nothing is selected (see above).
            result =
                selected?.let { exit ->
                    RaftBackgroundKillNotice(
                        exitTimestampMillis = exit.timestampMillis,
                        guide = RaftAndroidVendorWhitelist.guideFor(vendor),
                    )
                } ?: mutableNotice.value
            mutableNotice.value = result
        }
        if (selected != null && result != null) {
            log(
                "background_kill_notice state=shown vendor=${vendor.id} guide=${result.guide.name} " +
                    "exitTimestampMillis=${selected.timestampMillis} reason=${historicalProcessExitReasonLabel(selected.reasonCode)} " +
                    "importance=${selected.importance}"
            )
        }
        return result
    }

    /**
     * The banner is on screen. The first call in this process while a notice is showing counts one
     * appearance towards [BackgroundKillNoticeMaxAppearances]; later calls in this process
     * (recomposition, a newer exit swapped in, a second window) change nothing.
     */
    override fun markShown() {
        val shownCount: Int
        val shownExitAt: Long
        synchronized(lock) {
            if (appearanceCounted) return
            val current = mutableNotice.value ?: return
            val state = cachedState ?: BackgroundKillNoticePersistenceState()
            appearanceCounted = true
            val counted = state.copy(shownCount = state.shownCount + 1)
            cachedState = counted
            shownCount = counted.shownCount
            shownExitAt = current.exitTimestampMillis
        }
        // Saves the state as it is when the save runs, not this snapshot: a click handled in the
        // meantime may have saved first, and must not be overwritten by an older state.
        persistAsync { runCatching { synchronized(lock) { cachedState }?.let(store::save) } }
        log("background_kill_notice state=on_screen shownCount=$shownCount exitTimestampMillis=$shownExitAt")
    }

    override fun dismiss() {
        val now = nowMillis()
        val dismissedExitAt: Long
        val updatedState: BackgroundKillNoticePersistenceState
        synchronized(lock) {
            val current = mutableNotice.value ?: return
            val state = cachedState ?: BackgroundKillNoticePersistenceState()
            dismissedExitAt = current.exitTimestampMillis
            updatedState =
                state.copy(
                    lastAcknowledgedExitTimestampMillis =
                        maxOf(state.lastAcknowledgedExitTimestampMillis, current.exitTimestampMillis),
                    lastDismissedAtMillis = now,
                    dismissCount = state.dismissCount + 1,
                )
            cachedState = updatedState
            mutableNotice.value = null
        }
        persistAsync { runCatching { store.save(updatedState) } }
        log("background_kill_notice action=dismiss dismissCount=${updatedState.dismissCount} exitTimestampMillis=$dismissedExitAt")
    }

    /** "Don't show again": acknowledges the current exit as [dismiss] does and suppresses the notice for good. */
    override fun dismissForever() {
        val dismissedExitAt: Long
        val updatedState: BackgroundKillNoticePersistenceState
        synchronized(lock) {
            val current = mutableNotice.value ?: return
            val state = cachedState ?: BackgroundKillNoticePersistenceState()
            dismissedExitAt = current.exitTimestampMillis
            updatedState =
                state.copy(
                    lastAcknowledgedExitTimestampMillis =
                        maxOf(state.lastAcknowledgedExitTimestampMillis, current.exitTimestampMillis),
                    suppressedForever = true,
                )
            cachedState = updatedState
            mutableNotice.value = null
        }
        persistAsync { runCatching { store.save(updatedState) } }
        log("background_kill_notice action=dismiss_forever shownCount=${updatedState.shownCount} exitTimestampMillis=$dismissedExitAt")
    }

    override fun markSettingsOpened() {
        val now = nowMillis()
        val acknowledgedExitAt: Long
        val updatedState: BackgroundKillNoticePersistenceState
        synchronized(lock) {
            val current = mutableNotice.value ?: return
            val state = cachedState ?: BackgroundKillNoticePersistenceState()
            acknowledgedExitAt = current.exitTimestampMillis
            updatedState =
                state.copy(
                    lastAcknowledgedExitTimestampMillis =
                        maxOf(state.lastAcknowledgedExitTimestampMillis, current.exitTimestampMillis),
                    lastOpenedSettingsAtMillis = now,
                )
            cachedState = updatedState
            mutableNotice.value = null
        }
        persistAsync { runCatching { store.save(updatedState) } }
        log("background_kill_notice action=open_in_app_settings exitTimestampMillis=$acknowledgedExitAt")
    }

    private fun mergePersistenceState(
        inMemory: BackgroundKillNoticePersistenceState?,
        disk: BackgroundKillNoticePersistenceState,
    ): BackgroundKillNoticePersistenceState {
        if (inMemory == null) return disk
        return BackgroundKillNoticePersistenceState(
            baselineMillis = inMemory.baselineMillis ?: disk.baselineMillis,
            lastAcknowledgedExitTimestampMillis =
                maxOf(inMemory.lastAcknowledgedExitTimestampMillis, disk.lastAcknowledgedExitTimestampMillis),
            lastDismissedAtMillis = maxOf(inMemory.lastDismissedAtMillis, disk.lastDismissedAtMillis),
            dismissCount = maxOf(inMemory.dismissCount, disk.dismissCount),
            lastOpenedSettingsAtMillis = maxOf(inMemory.lastOpenedSettingsAtMillis, disk.lastOpenedSettingsAtMillis),
            shownCount = maxOf(inMemory.shownCount, disk.shownCount),
            suppressedForever = inMemory.suppressedForever || disk.suppressedForever,
        )
    }
}

/**
 * Process-scoped Android host for the local background-kill whitelist guidance banner.
 *
 * Triggered 100% locally on the memory-telemetry thread right after historical exit logging
 * (never gated on [build.raft.app.data.RaftServicesPrivacyGate]). [install] performs zero
 * `SharedPreferences` or disk access on the main thread; storage is opened lazily off-main.
 */
internal object RaftAndroidBackgroundKillNoticeHost : RaftBackgroundKillNoticeHost {
    private const val Tag = "RaftBackgroundKillNotice"
    internal const val PreferencesName = "raft-background-kill-notice"
    private const val BaselineKey = "baseline-millis"
    private const val LastAcknowledgedExitKey = "last-acknowledged-exit-timestamp-millis"
    private const val LastDismissedAtKey = "last-dismissed-at-millis"
    private const val DismissCountKey = "dismiss-count"
    private const val LastOpenedSettingsAtKey = "last-opened-settings-at-millis"
    private const val ShownCountKey = "shown-count"
    private const val SuppressedForeverKey = "suppressed-forever"
    private const val MaxExitsQueried = 16

    private val hostNotice = MutableStateFlow<RaftBackgroundKillNotice?>(null)

    private val persistenceExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RaftBgKillNoticeStore").apply { isDaemon = true }
        }
    }

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var controller: RaftAndroidBackgroundKillNoticeController? = null

    override val notice: StateFlow<RaftBackgroundKillNotice?> = hostNotice.asStateFlow()

    fun install(context: Context) {
        appContextRef = context.applicationContext
        RaftBackgroundKillNoticeHostRegistry.host = this
    }

    fun evaluate(context: Context, previousTelemetry: List<MemoryTelemetrySnapshot>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        appContextRef = appContext
        runCatching {
            val activeController = ensureController(appContext)
            val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return
            val exits =
                activityManager.getHistoricalProcessExitReasons(appContext.packageName, 0, MaxExitsQueried)
                    .map { it.toSystemKillExit() }
            val packageUpdatedMillis =
                runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime }.getOrNull()
            val mainProcessName = appContext.applicationInfo.processName ?: appContext.packageName
            val vendor = RaftAndroidVendorWhitelist.resolveVendor()
            val noPlayServicesFallback =
                runCatching { AndroidRealtimeKeepAliveController.noPlayServicesFallbackApplies(appContext) }
                    .getOrDefault(false)
            val keepAliveEnabled =
                runCatching {
                    AndroidRealtimeKeepAliveController.isForegroundKeepAliveEnabled(appContext) ||
                        AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(appContext) ||
                        AndroidRealtimeKeepAliveController.isFcmAutoFallbackActive(appContext)
                }.getOrDefault(false)
            val startupRecords =
                runCatching { RaftStartupPhaseMarker.readAll(appContext.filesDir) }
                    .getOrDefault(emptyList())
            activeController.evaluate(
                exits = exits,
                snapshots = previousTelemetry,
                packageUpdatedMillis = packageUpdatedMillis,
                mainProcessName = mainProcessName,
                vendor = vendor,
                noPlayServicesFallbackApplies = noPlayServicesFallback,
                keepAlivePreferenceEnabled = keepAliveEnabled,
                startupRecords = startupRecords,
            )
        }.onFailure { error ->
            RaftAndroidLocalDiagnostics.recordLog(
                level = "warn",
                tag = Tag,
                message = "background_kill_notice_evaluate_failed class=${error.javaClass.name}",
            )
        }
    }

    override fun markSettingsOpened() {
        controller?.markSettingsOpened()
    }

    override fun dismiss() {
        controller?.dismiss()
    }

    override fun dismissForever() {
        controller?.dismissForever()
    }

    override fun markShown() {
        controller?.markShown()
    }

    internal fun resetForTests() {
        controller = null
        appContextRef = null
        hostNotice.value = null
    }

    @Synchronized
    private fun ensureController(appContext: Context): RaftAndroidBackgroundKillNoticeController {
        controller?.let { return it }
        val prefs = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val created =
            RaftAndroidBackgroundKillNoticeController(
                store = SharedPreferencesStore(prefs),
                log = { line -> RaftAndroidLocalDiagnostics.recordLog("info", Tag, line) },
                persistAsync = { task -> persistenceExecutor.execute(task) },
                mutableNotice = hostNotice,
            )
        controller = created
        return created
    }

    private class SharedPreferencesStore(
        private val preferences: SharedPreferences,
    ) : BackgroundKillNoticeStore {
        override fun load(): BackgroundKillNoticePersistenceState =
            BackgroundKillNoticePersistenceState(
                baselineMillis = if (preferences.contains(BaselineKey)) preferences.getLong(BaselineKey, 0L) else null,
                lastAcknowledgedExitTimestampMillis = preferences.getLong(LastAcknowledgedExitKey, 0L),
                lastDismissedAtMillis = preferences.getLong(LastDismissedAtKey, 0L),
                dismissCount = preferences.getInt(DismissCountKey, 0),
                lastOpenedSettingsAtMillis = preferences.getLong(LastOpenedSettingsAtKey, 0L),
                shownCount = preferences.getInt(ShownCountKey, 0),
                suppressedForever = preferences.getBoolean(SuppressedForeverKey, false),
            )

        override fun save(state: BackgroundKillNoticePersistenceState) {
            val editor =
                preferences.edit()
                    .putLong(LastAcknowledgedExitKey, state.lastAcknowledgedExitTimestampMillis)
                    .putLong(LastDismissedAtKey, state.lastDismissedAtMillis)
                    .putInt(DismissCountKey, state.dismissCount)
                    .putLong(LastOpenedSettingsAtKey, state.lastOpenedSettingsAtMillis)
                    .putInt(ShownCountKey, state.shownCount)
                    .putBoolean(SuppressedForeverKey, state.suppressedForever)
            state.baselineMillis?.let { editor.putLong(BaselineKey, it) }
            editor.apply()
        }

        override fun persistBaseline(baselineMillis: Long): Boolean =
            preferences.edit().putLong(BaselineKey, baselineMillis).commit()
    }
}
