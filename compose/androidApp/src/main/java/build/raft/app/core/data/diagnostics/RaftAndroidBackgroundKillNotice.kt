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
 * Persisted dedup and cooldown state for the local background-kill whitelist notice.
 *
 * [lastAcknowledgedExitTimestampMillis] is advanced ONLY on user action (`dismiss()`,
 * `dismissForever()` or `openVendorWhitelistSettings()`), NEVER during `Application.onCreate()`
 * evaluation, so a sticky-service restart never swallows a notice before the user opens the UI.
 *
 * [shownCount] counts the notice's appearances on this install: `evaluate()` adds one each time it
 * surfaces the notice in a process where it is not on screen yet. That includes a process that
 * never shows UI, such as a sticky-service restart: the cap ([BackgroundKillNoticeMaxAppearances])
 * counts appearances, not views. [suppressedForever] is the user's "Don't show again".
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
 * Selects nothing after "Don't show again" or once the notice has appeared
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
            val decision =
                classifySystemKill(
                    reasonCode = exit.reasonCode,
                    importance = exit.importance,
                    telemetry = telemetry,
                    exitTimestampMillis = exit.timestampMillis,
                    status = exit.status,
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
    private val openWhitelist: () -> String?,
    private val log: (String) -> Unit = {},
    private val persistAsync: (() -> Unit) -> Unit = { it() },
    private val mutableNotice: MutableStateFlow<RaftBackgroundKillNotice?> = MutableStateFlow(null),
) : RaftBackgroundKillNoticeHost {
    private val lock = Any()
    private var cachedState: BackgroundKillNoticePersistenceState? = null

    override val notice: StateFlow<RaftBackgroundKillNotice?> = mutableNotice.asStateFlow()

    /**
     * Picks this process's notice. Surfacing one while none is on screen in this process is a fresh
     * appearance and counts once towards [BackgroundKillNoticeMaxAppearances]. A notice already on
     * screen stays until the user acts on it: an evaluation that selects nothing (past the cap, say)
     * keeps it rather than taking it away.
     */
    fun evaluate(
        exits: List<SystemKillExit>,
        snapshots: List<MemoryTelemetrySnapshot>,
        packageUpdatedMillis: Long?,
        mainProcessName: String?,
        vendor: AndroidOemVendor,
        noPlayServicesFallbackApplies: Boolean,
        keepAlivePreferenceEnabled: Boolean,
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
        val freshAppearance: Boolean
        val shownCount: Int
        synchronized(lock) {
            val state = mergePersistenceState(cachedState, loadedWithBaseline)
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
                )
            val onScreen = mutableNotice.value
            freshAppearance = selected != null && onScreen == null
            val counted = if (freshAppearance) state.copy(shownCount = state.shownCount + 1) else state
            cachedState = counted
            shownCount = counted.shownCount
            result =
                selected?.let { exit ->
                    RaftBackgroundKillNotice(
                        exitTimestampMillis = exit.timestampMillis,
                        guide = RaftAndroidVendorWhitelist.guideFor(vendor),
                    )
                } ?: onScreen
            mutableNotice.value = result
        }
        if (freshAppearance) {
            // Saves the state as it is when the save runs, not this snapshot: a click handled in the
            // meantime may have saved first, and must not be overwritten by an older state.
            persistAsync { runCatching { synchronized(lock) { cachedState }?.let(store::save) } }
        }
        if (selected != null && result != null) {
            log(
                "background_kill_notice state=shown shownCount=$shownCount vendor=${vendor.id} guide=${result.guide.name} " +
                    "exitTimestampMillis=${selected.timestampMillis} reason=${historicalProcessExitReasonLabel(selected.reasonCode)} " +
                    "importance=${selected.importance}"
            )
        }
        return result
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

    override fun openVendorWhitelistSettings() {
        val target = runCatching { openWhitelist() }.getOrNull()
        val now = nowMillis()
        var acknowledgedExitAt: Long? = null
        var updatedState: BackgroundKillNoticePersistenceState? = null
        if (target != null) {
            synchronized(lock) {
                val current = mutableNotice.value
                acknowledgedExitAt = current?.exitTimestampMillis
                if (current != null) {
                    val state = cachedState ?: BackgroundKillNoticePersistenceState()
                    updatedState =
                        state.copy(
                            lastAcknowledgedExitTimestampMillis =
                                maxOf(state.lastAcknowledgedExitTimestampMillis, current.exitTimestampMillis),
                            lastOpenedSettingsAtMillis = now,
                        )
                    cachedState = updatedState
                    mutableNotice.value = null
                }
            }
            updatedState?.let { nextState ->
                persistAsync { runCatching { store.save(nextState) } }
            }
        }
        log(
            "background_kill_notice action=open_settings target=${target ?: "none"} " +
                "exitTimestampMillis=${acknowledgedExitAt ?: -1L}"
        )
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
                runCatching { AndroidRealtimeKeepAliveController.isForegroundKeepAliveEnabled(appContext) }
                    .getOrDefault(false)
            activeController.evaluate(
                exits = exits,
                snapshots = previousTelemetry,
                packageUpdatedMillis = packageUpdatedMillis,
                mainProcessName = mainProcessName,
                vendor = vendor,
                noPlayServicesFallbackApplies = noPlayServicesFallback,
                keepAlivePreferenceEnabled = keepAliveEnabled,
            )
        }.onFailure { error ->
            RaftAndroidLocalDiagnostics.recordLog(
                level = "warn",
                tag = Tag,
                message = "background_kill_notice_evaluate_failed class=${error.javaClass.name}",
            )
        }
    }

    override fun openVendorWhitelistSettings() {
        controller?.openVendorWhitelistSettings()
    }

    override fun dismiss() {
        controller?.dismiss()
    }

    override fun dismissForever() {
        controller?.dismissForever()
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
                openWhitelist = { RaftAndroidVendorWhitelist.open(appContext) },
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
