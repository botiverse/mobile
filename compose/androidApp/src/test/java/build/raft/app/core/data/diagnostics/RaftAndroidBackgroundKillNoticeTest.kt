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

    /** A new process: a new controller over [store], at [at]. */
    private fun launch(
        store: InMemoryNoticeStore,
        at: Long,
        persistAsync: (() -> Unit) -> Unit = { it() },
    ) = RaftAndroidBackgroundKillNoticeController(
        store = store,
        nowMillis = { at },
        persistAsync = persistAsync,
    )

    /** [RaftAndroidBackgroundKillNoticeController.evaluate] as a Huawei phone without Play services runs it. */
    private fun RaftAndroidBackgroundKillNoticeController.eval(exits: List<SystemKillExit>) =
        evaluate(
            exits = exits,
            snapshots = emptyList(),
            packageUpdatedMillis = installedAt,
            mainProcessName = mainProcess,
            vendor = AndroidOemVendor.Huawei,
            noPlayServicesFallbackApplies = true,
            keepAlivePreferenceEnabled = true,
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
        val controller =
            RaftAndroidBackgroundKillNoticeController(
                store = store,
                nowMillis = { now },
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

        // User opens in-app Notification settings -> 24-hour cooldown and clears notice.
        val openedAt = now
        controller.markSettingsOpened()
        assertNull(controller.notice.value)
        assertEquals(exit3.timestampMillis, store.state.lastAcknowledgedExitTimestampMillis)
        assertEquals(openedAt, store.state.lastOpenedSettingsAtMillis)

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
    fun suppressedForeverAndTwoAppearancesSelectNoNotice() {
        val killAt = installedAt + hour
        val kill = exit(pid = 501, at = killAt, importance = IMPORTANCE_SERVICE)

        fun select(state: BackgroundKillNoticePersistenceState) =
            selectBackgroundKillForNotice(
                exits = listOf(kill),
                snapshots = emptyList(),
                state = state,
                baselineMillis = installedAt,
                nowMillis = killAt + 10_000L,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Huawei,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
            )

        assertEquals(kill, select(BackgroundKillNoticePersistenceState()))
        // "Don't show again", with no appearance, cooldown or acknowledged exit in the way.
        assertNull(select(BackgroundKillNoticePersistenceState(suppressedForever = true)))
        // At most two appearances per install.
        assertEquals(kill, select(BackgroundKillNoticePersistenceState(shownCount = 1)))
        assertNull(select(BackgroundKillNoticePersistenceState(shownCount = 2)))
    }

    @Test
    fun evaluateAloneNeverCountsAnAppearance() {
        val store = InMemoryNoticeStore()
        val kill = exit(pid = 601, at = installedAt + hour, importance = IMPORTANCE_SERVICE)

        // Three headless starts (a push, a widget, the keep-alive service): each selects the notice
        // and none shows it, so none counts, not even when evaluated twice.
        for (start in 1..3) {
            val headless = launch(store, at = kill.timestampMillis + start * hour)
            assertNotNull(headless.eval(listOf(kill)))
            assertNotNull(headless.eval(listOf(kill)))
            assertEquals(0, store.state.shownCount)
        }

        // The user opens Raft: the notice is still there, and counts once it is on screen.
        val opened = launch(store, at = kill.timestampMillis + 4 * hour)
        assertNotNull(opened.eval(listOf(kill)))
        assertEquals(0, store.state.shownCount)
        opened.markShown()
        assertEquals(1, store.state.shownCount)
    }

    @Test
    fun markShownCountsOncePerProcessAndTwoProcessesThatShowedItEndTheNotice() {
        val store = InMemoryNoticeStore()
        val kill = exit(pid = 611, at = installedAt + hour, importance = IMPORTANCE_SERVICE)
        val newerKill = exit(pid = 612, at = installedAt + 2 * hour, importance = IMPORTANCE_SERVICE)

        // 1st process: nothing on screen yet, so nothing to count. Then it is shown, recomposed, a
        // newer exit is swapped in and a second window shows it: one appearance.
        val first = launch(store, at = newerKill.timestampMillis + hour)
        first.markShown()
        assertEquals(0, store.state.shownCount)
        assertNotNull(first.eval(listOf(kill)))
        first.markShown()
        first.markShown()
        assertEquals(newerKill.timestampMillis, first.eval(listOf(kill, newerKill))?.exitTimestampMillis)
        first.markShown()
        assertEquals(1, store.state.shownCount)

        // 2nd process: shown again; the user leaves it there.
        val second = launch(store, at = newerKill.timestampMillis + 2 * hour)
        val secondNotice = second.eval(listOf(kill, newerKill))
        assertNotNull(secondNotice)
        second.markShown()
        assertEquals(2, store.state.shownCount)

        // Evaluated again in that process, past the cap: the notice stays on screen, not counted again.
        assertEquals(secondNotice, second.eval(listOf(kill, newerKill)))
        assertEquals(secondNotice, second.notice.value)
        second.markShown()
        assertEquals(2, store.state.shownCount)

        // 3rd process: no notice, not even for a newer kill.
        val newestKill = exit(pid = 613, at = newerKill.timestampMillis + 3 * hour, importance = IMPORTANCE_SERVICE)
        val third = launch(store, at = newestKill.timestampMillis + hour)
        assertNull(third.eval(listOf(kill, newerKill, newestKill)))
        assertNull(third.notice.value)
        assertEquals(2, store.state.shownCount)
    }

    @Test
    fun dismissForeverPersistsHidesTheNoticeAndSurvivesAReload() {
        val store = InMemoryNoticeStore()
        val kill = exit(pid = 701, at = installedAt + hour, importance = IMPORTANCE_SERVICE)
        val deferredSaves = mutableListOf<() -> Unit>()
        val shown = launch(store, at = kill.timestampMillis + 60_000L, persistAsync = { task -> deferredSaves += task })
        assertNotNull(shown.eval(listOf(kill)))
        shown.markShown()

        shown.dismissForever()
        assertNull(shown.notice.value)
        // Both saves run off the caller thread: no synchronous store.load() or store.save().
        assertEquals(1, store.loadCalls)
        assertEquals(0, store.saveCalls)
        assertEquals(2, deferredSaves.size)
        // Even when the appearance's save runs last, it writes the state after Don't show again,
        // not the older one it was queued with.
        deferredSaves.asReversed().forEach { save -> save() }
        assertTrue(store.state.suppressedForever)
        assertEquals(kill.timestampMillis, store.state.lastAcknowledgedExitTimestampMillis)
        assertEquals(1, store.state.shownCount)
        // Not a Dismiss: no dismiss cooldown starts.
        assertEquals(0, store.state.dismissCount)

        // Next process: a newer qualifying kill, one appearance so far and no cooldown, yet nothing shows.
        val newerKill = exit(pid = 702, at = kill.timestampMillis + 2 * day, importance = IMPORTANCE_SERVICE)
        val reloaded = launch(store, at = newerKill.timestampMillis + 60_000L)
        assertNull(reloaded.eval(listOf(kill, newerKill)))
        assertNull(reloaded.notice.value)
        assertTrue(store.state.suppressedForever)
    }

    @Test
    fun nonInteractiveOemPrestartExitsNeverTriggerWhitelistNotice() {
        val killAt = installedAt + hour
        val spegExit =
            exit(
                pid = 801,
                at = killAt,
                reason = ApplicationExitInfo.REASON_OTHER,
                importance = IMPORTANCE_CACHED,
                description = "stop build.raft.app due to SPEG",
            )
        val prestartExit =
            exit(
                pid = 802,
                at = killAt + 1_000L,
                reason = ApplicationExitInfo.REASON_OTHER,
                importance = IMPORTANCE_CACHED,
                description = "PreStartCachedKill",
            )
        val appCreated =
            StartupPhaseRecord(
                pid = 802,
                processStartMillis = killAt + 950L,
                processName = mainProcess,
                phase = RaftStartupPhaseMarker.Phase.ApplicationCreated,
                phaseAtMillis = killAt + 975L,
            )
        assertNull(
            selectBackgroundKillForNotice(
                exits = listOf(spegExit, prestartExit),
                snapshots = emptyList(),
                state = BackgroundKillNoticePersistenceState(baselineMillis = installedAt),
                baselineMillis = installedAt,
                nowMillis = killAt + 60_000L,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Xiaomi,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
                startupRecords = listOf(appCreated),
            ),
        )
        // A newer OEM prestart exit must not mask an older qualifying background kill.
        val olderRealBackgroundKill =
            exit(
                pid = 800,
                at = killAt - 5_000L,
                reason = ApplicationExitInfo.REASON_SIGNALED,
                importance = IMPORTANCE_SERVICE,
            )
        assertEquals(
            olderRealBackgroundKill,
            selectBackgroundKillForNotice(
                exits = listOf(olderRealBackgroundKill, spegExit, prestartExit),
                snapshots = emptyList(),
                state = BackgroundKillNoticePersistenceState(baselineMillis = installedAt),
                baselineMillis = installedAt,
                nowMillis = killAt + 60_000L,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Xiaomi,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
                startupRecords = listOf(appCreated),
            ),
        )
        // Once the process actually reached ActivityCreated, a later background kill still qualifies.
        val activityCreated =
            appCreated.copy(
                phase = RaftStartupPhaseMarker.Phase.ActivityCreated,
                firstActivityAtMillis = killAt + 980L,
                firstActivityAfterStartMs = 30L,
            )
        assertEquals(
            prestartExit,
            selectBackgroundKillForNotice(
                exits = listOf(prestartExit),
                snapshots = emptyList(),
                state = BackgroundKillNoticePersistenceState(baselineMillis = installedAt),
                baselineMillis = installedAt,
                nowMillis = killAt + 60_000L,
                mainProcessName = mainProcess,
                vendor = AndroidOemVendor.Xiaomi,
                noPlayServicesFallbackApplies = true,
                keepAlivePreferenceEnabled = true,
                startupRecords = listOf(activityCreated),
            ),
        )
    }
}
