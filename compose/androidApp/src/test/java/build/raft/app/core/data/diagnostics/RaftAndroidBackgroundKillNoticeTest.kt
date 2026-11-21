package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import build.raft.app.data.RaftBackgroundKillNoticeHostRegistry
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.shared.AndroidOemVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAndroidBackgroundKillNoticeTest {
    private val hour = 60L * 60 * 1000
    private val day = 24L * hour
    private val mainProcess = "build.raft.app"
    private val installedAt = 100L * day

    private class InMemoryNoticeStore(
        var state: BackgroundKillNoticePersistenceState = BackgroundKillNoticePersistenceState(),
    ) : BackgroundKillNoticeStore {
        var loadCalls: Int = 0
        var saveCalls: Int = 0

        override fun load(): BackgroundKillNoticePersistenceState {
            loadCalls += 1
            return state
        }

        override fun save(state: BackgroundKillNoticePersistenceState) {
            saveCalls += 1
            this.state = state
        }

        override fun persistBaseline(baselineMillis: Long): Boolean {
            state = state.copy(baselineMillis = baselineMillis)
            return true
        }
    }

    private fun exit(
        pid: Int = 100,
        at: Long,
        reason: Int = ApplicationExitInfo.REASON_OTHER,
        importance: Int = IMPORTANCE_CACHED,
        description: String? = null,
        processName: String = mainProcess,
        status: Int = 0,
    ) = SystemKillExit(
        pid = pid,
        timestampMillis = at,
        reasonCode = reason,
        status = status,
        importance = importance,
        pssKb = 0L,
        rssKb = 0L,
        build = null,
        processName = processName,
        description = description,
    )

    private fun backgroundTelemetry(
        pid: Int = 100,
        at: Long,
        systemLowMemory: Boolean = false,
    ) = MemoryTelemetrySnapshot(
        pid = pid,
        processStartMillis = at - 3_600_000L,
        writtenAtMillis = at - 600_000L,
        foreground = false,
        foregroundChangedAtMillis = at - 600_000L,
        sampledAtMillis = at - 610_000L,
        lastInteractionAtMillis = at - 610_000L,
        systemLowMemory = systemLowMemory,
    )

    @Test
    fun huaweiNoGmsQualifiesAnyBackgroundKillAndUsesHuaweiGuide() {
        val killAt = installedAt + hour
        val now = killAt + 5 * 60_000L
        val store = InMemoryNoticeStore()
        val controller =
            RaftAndroidBackgroundKillNoticeController(
                store = store,
                nowMillis = { now },
                openWhitelist = { "huawei_startup_normal" },
            )

        val notice =
            controller.evaluate(
                exits = listOf(exit(at = killAt, reason = ApplicationExitInfo.REASON_LOW_MEMORY, importance = IMPORTANCE_CACHED)),
                snapshots = emptyList(),
                packageUpdatedMillis = installedAt,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Huawei,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = false,
            )

        assertNotNull(notice)
        assertEquals(killAt, notice!!.exitTimestampMillis)
        assertEquals(RaftBackgroundWhitelistGuide.HuaweiAppLaunch, notice.guide)
    }

    @Test
    fun lenovoPlayBuildGmsCatchesZuiCleanerAndNonCachedAndOemNonOomKills() {
        val killAt = installedAt + hour
        val now = killAt + 10_000L

        // Lenovo TB324ZC Play build case 2bb08937: ZuiMemoryCleaner description at IMPORTANCE_CACHED.
        val zuiCleanerExit =
            exit(
                pid = 201,
                at = killAt,
                reason = ApplicationExitInfo.REASON_SIGNALED,
                status = 9,
                importance = IMPORTANCE_CACHED,
                description = "kill by ZuiMemoryCleaner",
            )
        val zuiSelected =
            selectBackgroundKillForNotice(
                exits = listOf(zuiCleanerExit),
                snapshots = emptyList(),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.LenovoZui,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = false,
            )
        assertEquals(zuiCleanerExit, zuiSelected)

        // Lenovo TB324ZC Play build case 8991ff8f: non-cached importance (125 / 230 / 300).
        for (importance in listOf(IMPORTANCE_FOREGROUND_SERVICE, IMPORTANCE_PERCEPTIBLE, IMPORTANCE_SERVICE)) {
            val nonCached = exit(pid = 202, at = killAt, reason = ApplicationExitInfo.REASON_OTHER, importance = importance)
            val selected =
                selectBackgroundKillForNotice(
                    exits = listOf(nonCached),
                    snapshots = emptyList(),
                    state = BackgroundKillNoticePersistenceState(),
                    baselineMillis = installedAt,
                    nowMillis = now,
                    mainProcessName = mainProcess,
                    vendor = AndroidOemVendor.LenovoZui,
                    noPlayServicesFallbackApplies = false,
                    keepAlivePreferenceEnabled = false,
                )
            assertEquals("importance=$importance", nonCached, selected)
        }

        // Lenovo Play build OEM cleaner kill with no description but systemLowMemory == false.
        val oemNonOom = exit(pid = 203, at = killAt, reason = ApplicationExitInfo.REASON_OTHER, importance = IMPORTANCE_CACHED)
        val oemSelected =
            selectBackgroundKillForNotice(
                exits = listOf(oemNonOom),
                snapshots = listOf(backgroundTelemetry(pid = 203, at = killAt, systemLowMemory = false)),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.LenovoZui,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = false,
            )
        assertEquals(oemNonOom, oemSelected)

        // Verify controller sets LenovoZui guide.
        val controller =
            RaftAndroidBackgroundKillNoticeController(
                store = InMemoryNoticeStore(),
                nowMillis = { now },
                openWhitelist = { "zui_startup_list" },
            )
        val notice =
            controller.evaluate(
                exits = listOf(zuiCleanerExit),
                snapshots = emptyList(),
                packageUpdatedMillis = installedAt,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.LenovoZui,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = false,
            )
        assertEquals(RaftBackgroundWhitelistGuide.LenovoZui, notice?.guide)
    }

    @Test
    fun stockGmsSuppressesRoutineCachedLowMemoryButCatchesKnownCleanerDescriptions() {
        val killAt = installedAt + hour
        val now = killAt + 10_000L

        // Routine cached LOW_MEMORY on stock GMS phone: suppressed.
        val routineLowMem =
            exit(pid = 301, at = killAt, reason = ApplicationExitInfo.REASON_LOW_MEMORY, importance = IMPORTANCE_CACHED)
        assertNull(
            selectBackgroundKillForNotice(
                exits = listOf(routineLowMem),
                snapshots = listOf(backgroundTelemetry(pid = 301, at = killAt, systemLowMemory = true)),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Generic,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = false,
            )
        )

        // Generic vendor cached REASON_OTHER without cleaner marker: also suppressed.
        val genericOther =
            exit(pid = 302, at = killAt, reason = ApplicationExitInfo.REASON_OTHER, importance = IMPORTANCE_CACHED)
        assertNull(
            selectBackgroundKillForNotice(
                exits = listOf(genericOther),
                snapshots = listOf(backgroundTelemetry(pid = 302, at = killAt, systemLowMemory = false)),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Generic,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = false,
            )
        )

        // OEM cached LOW_MEMORY with genuine low memory: suppressed.
        assertNull(
            selectBackgroundKillForNotice(
                exits = listOf(routineLowMem),
                snapshots = listOf(backgroundTelemetry(pid = 301, at = killAt, systemLowMemory = true)),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Samsung,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = false,
            )
        )

        // Every documented vendor-cleaner marker qualifies even at IMPORTANCE_CACHED.
        for (marker in listOf("ZuiMemoryCleaner", "PowerGenie", "iaware", "cleaner", "SmartManager", "Hans", "Athena", "millet")) {
            val cleanerExit =
                exit(
                    pid = 303,
                    at = killAt,
                    reason = ApplicationExitInfo.REASON_OTHER,
                    importance = IMPORTANCE_CACHED,
                    description = "stopped by $marker",
                )
            assertEquals(
                marker,
                cleanerExit,
                selectBackgroundKillForNotice(
                    exits = listOf(cleanerExit),
                    snapshots = emptyList(),
                    state = BackgroundKillNoticePersistenceState(),
                    baselineMillis = installedAt,
                    nowMillis = now,
                    mainProcessName = mainProcess,
                    vendor = AndroidOemVendor.Generic,
                    noPlayServicesFallbackApplies = false,
                    keepAlivePreferenceEnabled = false,
                ),
            )
        }
    }

    @Test
    fun nonBackgroundAndSecondaryProcessExitsAreIgnored() {
        val killAt = installedAt + hour
        val now = killAt + 10_000L
        val nonQualifying =
            listOf(
                exit(pid = 1, at = killAt, importance = IMPORTANCE_FOREGROUND),
                exit(pid = 2, at = killAt, importance = IMPORTANCE_VISIBLE),
                exit(pid = 3, at = killAt, reason = ApplicationExitInfo.REASON_USER_REQUESTED, importance = IMPORTANCE_CACHED),
                exit(pid = 4, at = killAt, reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED, importance = IMPORTANCE_CACHED),
                exit(pid = 5, at = killAt, reason = ApplicationExitInfo.REASON_CRASH, importance = IMPORTANCE_CACHED),
                exit(pid = 6, at = killAt, reason = ApplicationExitInfo.REASON_CRASH_NATIVE, importance = IMPORTANCE_CACHED),
                exit(pid = 7, at = killAt, importance = IMPORTANCE_SERVICE, processName = "$mainProcess:keepalive"),
            )
        assertNull(
            selectBackgroundKillForNotice(
                exits = nonQualifying,
                snapshots = emptyList(),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Huawei,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
            )
        )
    }

    @Test
    fun stickyRestartEvaluationDoesNotConsumeNoticeBeforeUserOpensUi() {
        val killAt = installedAt + hour
        val stickyRestartAt = killAt + 5_000L
        val userLaunchAt = killAt + 3 * hour
        val store = InMemoryNoticeStore()
        val kill = exit(pid = 401, at = killAt, importance = IMPORTANCE_FOREGROUND_SERVICE)

        // Process 2 (sticky restart): evaluate runs in Application.onCreate, user never opens UI.
        val stickyProcessController =
            RaftAndroidBackgroundKillNoticeController(
                store = store,
                nowMillis = { stickyRestartAt },
                openWhitelist = { "huawei_startup_normal" },
            )
        val duringSticky =
            stickyProcessController.evaluate(
                exits = listOf(kill),
                snapshots = emptyList(),
                packageUpdatedMillis = installedAt,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Huawei,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
            )
        assertNotNull(duringSticky)
        assertEquals(0L, store.state.lastAcknowledgedExitTimestampMillis)

        // Process 3 (user opens UI hours later): the notice is still shown.
        val userLaunchController =
            RaftAndroidBackgroundKillNoticeController(
                store = store,
                nowMillis = { userLaunchAt },
                openWhitelist = { "huawei_startup_normal" },
            )
        val onUserLaunch =
            userLaunchController.evaluate(
                exits = listOf(kill),
                snapshots = emptyList(),
                packageUpdatedMillis = installedAt,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Huawei,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
            )
        assertNotNull(onUserLaunch)
        assertEquals(killAt, onUserLaunch!!.exitTimestampMillis)
    }

    @Test
    fun dismissAndOpenSettingsEnforceCooldownsAndRequireStrictlyNewerExit() {
        var now = installedAt + hour
        val store = InMemoryNoticeStore()
        var openedCount = 0
        val controller =
            RaftAndroidBackgroundKillNoticeController(
                store = store,
                nowMillis = { now },
                openWhitelist = {
                    openedCount += 1
                    "zui_startup_list"
                },
            )

        fun eval(exits: List<SystemKillExit>) =
            controller.evaluate(
                exits = exits,
                snapshots = emptyList(),
                packageUpdatedMillis = installedAt,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.LenovoZui,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = true,
            )

        val exit1 = exit(pid = 1, at = now - 1_000L, importance = IMPORTANCE_SERVICE)
        assertEquals(exit1.timestampMillis, eval(listOf(exit1))?.exitTimestampMillis)

        // 1st dismiss -> 7-day cooldown, clears notice, advances acknowledged timestamp.
        val firstDismissAt = now
        controller.dismiss()
        assertNull(controller.notice.value)
        assertEquals(1, store.state.dismissCount)
        assertEquals(exit1.timestampMillis, store.state.lastAcknowledgedExitTimestampMillis)

        // New exit during 7-day cooldown: suppressed.
        now = firstDismissAt + 6 * day
        val exitDuring7d = exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_SERVICE)
        assertNull(eval(listOf(exit1, exitDuring7d)))

        // After 7-day cooldown, old exit (<= firstDismissAt) still does not re-trigger.
        now = firstDismissAt + 7 * day + 1_000L
        assertNull(eval(listOf(exit1)))

        // Strictly newer exit after 7-day cooldown: shows again.
        val exit2 = exit(pid = 3, at = now - 500L, importance = IMPORTANCE_SERVICE)
        assertEquals(exit2.timestampMillis, eval(listOf(exit1, exit2))?.exitTimestampMillis)

        // 2nd dismiss -> 30-day cooldown.
        val secondDismissAt = now
        controller.dismiss()
        assertEquals(2, store.state.dismissCount)

        // New exit 29 days later: suppressed.
        now = secondDismissAt + 29 * day
        val exitDuring30d = exit(pid = 4, at = now - 1_000L, importance = IMPORTANCE_SERVICE)
        assertNull(eval(listOf(exitDuring30d)))

        // New exit after 30-day cooldown: shows again.
        now = secondDismissAt + 30 * day + 1_000L
        val exit3 = exit(pid = 5, at = now - 500L, importance = IMPORTANCE_SERVICE)
        assertEquals(exit3.timestampMillis, eval(listOf(exit3))?.exitTimestampMillis)

        // User opens vendor whitelist settings -> 24-hour cooldown and invokes opener.
        val openedAt = now
        controller.openVendorWhitelistSettings()
        assertEquals(1, openedCount)
        assertNull(controller.notice.value)
        assertEquals(exit3.timestampMillis, store.state.lastAcknowledgedExitTimestampMillis)

        // New exit 23 hours later: suppressed.
        now = openedAt + 23 * hour
        val exitDuring24h = exit(pid = 6, at = now - 1_000L, importance = IMPORTANCE_SERVICE)
        assertNull(eval(listOf(exitDuring24h)))

        // Old exit (<= openedAt) after 24 hours: suppressed (requires strictly newer exit).
        now = openedAt + 24 * hour + 1_000L
        assertNull(eval(listOf(exit3)))

        // Strictly newer exit after 24-hour cooldown: shows again.
        val exit4 = exit(pid = 7, at = now - 500L, importance = IMPORTANCE_SERVICE)
        assertEquals(exit4.timestampMillis, eval(listOf(exit4))?.exitTimestampMillis)
    }

    @Test
    fun baselineAndSevenDayMaxAgeFilterHistoricalExits() {
        val now = installedAt + 10 * day
        val beforeBaseline = exit(pid = 1, at = installedAt - 1L, importance = IMPORTANCE_SERVICE)
        val atBaseline = exit(pid = 2, at = installedAt, importance = IMPORTANCE_SERVICE)
        val tooOld = exit(pid = 3, at = now - 7 * day - 1L, importance = IMPORTANCE_SERVICE)
        val exactlySevenDays = exit(pid = 4, at = now - 7 * day, importance = IMPORTANCE_SERVICE)

        assertNull(
            selectBackgroundKillForNotice(
                exits = listOf(beforeBaseline, atBaseline, tooOld),
                snapshots = emptyList(),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Huawei,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
            )
        )

        assertEquals(
            exactlySevenDays,
            selectBackgroundKillForNotice(
                exits = listOf(beforeBaseline, tooOld, exactlySevenDays),
                snapshots = emptyList(),
                state = BackgroundKillNoticePersistenceState(),
                baselineMillis = installedAt,
                nowMillis = now,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Huawei,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
            ),
        )
    }

    @Test
    fun installPerformsZeroSharedPreferencesAccessAndClickHandlersNeverReadOrBlockCallerThread() {
        val noPrefsContext =
            object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                    throw AssertionError("getSharedPreferences must not be called during install()")
                }
            }
        val previousHost = RaftBackgroundKillNoticeHostRegistry.host
        try {
            RaftAndroidBackgroundKillNoticeHost.resetForTests()
            RaftAndroidBackgroundKillNoticeHost.install(noPrefsContext)
            assertTrue(RaftBackgroundKillNoticeHostRegistry.host === RaftAndroidBackgroundKillNoticeHost)
        } finally {
            RaftAndroidBackgroundKillNoticeHost.resetForTests()
            RaftBackgroundKillNoticeHostRegistry.host = previousHost
        }

        val now = installedAt + hour
        val store = InMemoryNoticeStore()
        val deferredSaves = mutableListOf<() -> Unit>()
        var openerResult: String? = null
        val controller =
            RaftAndroidBackgroundKillNoticeController(
                store = store,
                nowMillis = { now },
                openWhitelist = { openerResult },
                persistAsync = { task -> deferredSaves += task },
            )
        val kill = exit(pid = 900, at = now - 5_000L, importance = IMPORTANCE_SERVICE)
        assertNotNull(
            controller.evaluate(
                exits = listOf(kill),
                snapshots = emptyList(),
                packageUpdatedMillis = installedAt,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.LenovoZui,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = true,
            )
        )
        assertEquals(1, store.loadCalls)
        assertEquals(0, store.saveCalls)

        // When no whitelist activity can be opened, keep the notice visible without starting cooldown.
        controller.openVendorWhitelistSettings()
        assertNotNull(controller.notice.value)
        assertEquals(1, store.loadCalls)
        assertEquals(0, deferredSaves.size)

        // When opener succeeds, clear notice in memory with zero synchronous store.load() or store.save().
        openerResult = "zui_startup_list"
        controller.openVendorWhitelistSettings()
        assertNull(controller.notice.value)
        assertEquals(1, store.loadCalls)
        assertEquals(0, store.saveCalls)
        assertEquals(1, deferredSaves.size)
        deferredSaves.removeAt(0).invoke()
        assertEquals(1, store.saveCalls)
        assertEquals(kill.timestampMillis, store.state.lastAcknowledgedExitTimestampMillis)

        // Unknown importance == 0 on generic GMS does not spuriously qualify.
        assertEquals(
            false,
            qualifiesForBackgroundKillNotice(
                exit = exit(pid = 901, at = now - 1_000L, importance = 0),
                telemetry = null,
                vendor = AndroidOemVendor.Generic,
                noPlayServicesFallbackApplies = false,
                keepAlivePreferenceEnabled = false,
            ),
        )
    }
}
