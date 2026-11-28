package build.raft.app

import build.raft.app.data.ActivityUnreadSummaryFreshness
import build.raft.app.data.ActivityUnreadSummaryProjection
import build.raft.app.data.AppBadgeCount
import build.raft.app.data.AppBadgeSource
import build.raft.app.data.HomeServerActivityUnreadProjection
import build.raft.app.data.RaftNotificationLevel
import build.raft.app.data.RaftServerNotificationLevels
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.model.ServerId
import build.raft.app.settings.AppShellBadgeEmission
import build.raft.app.settings.appShellBadgeEmissionFlow
import build.raft.app.settings.appShellForegroundReconcileEpochFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsHomeShellBadgeEffectsTest {
    @Test
    fun warmForegroundReturnReEmitsZeroBadgeEvenWhenKotlinCountStaysZero() = runTest {
        val owner = ReadReceiptOwner(environmentId = "https://api.raft.build/api", accountId = "user-1", serverId = "server-1")
        val zeroHomeBadge =
            AppBadgeCount(
                count = 0,
                source = AppBadgeSource.SERVER_UNREAD_SUMMARY,
                selectedServerId = ServerId("server-1"),
                lastUpdated = 1_000L,
                isStale = false,
            ) to HomeServerActivityUnreadProjection(requestOwner = owner, countsByServer = mapOf("server-1" to 0L))
        val homeBadgeFlow = MutableSharedFlow<Pair<AppBadgeCount, HomeServerActivityUnreadProjection>>(replay = 1)
        homeBadgeFlow.tryEmit(zeroHomeBadge)
        val activitySummaryFlow =
            MutableStateFlow(
                ActivityUnreadSummaryProjection(
                    serverId = "server-1",
                    totalUnreadCount = 0,
                    totalCount = 0,
                    hasValue = true,
                    freshness = ActivityUnreadSummaryFreshness.Fresh,
                    revision = 1L,
                )
            )
        val notificationLevelsFlow = MutableStateFlow<Map<String, RaftServerNotificationLevels>>(emptyMap())
        val appForeground = MutableStateFlow(true)
        val foregroundSyncSettled = MutableStateFlow(0L)

        val emissions = mutableListOf<AppShellBadgeEmission>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            appShellBadgeEmissionFlow(
                homeBadgeFlow = homeBadgeFlow,
                activityUnreadSummaryFlow = activitySummaryFlow,
                notificationLevelsFlow = notificationLevelsFlow,
                foregroundReconcileEpochFlow =
                    appShellForegroundReconcileEpochFlow(
                        appForeground = appForeground,
                        foregroundSyncSettled = foregroundSyncSettled,
                    ),
                useActivityProjection = true,
                readyServerId = "server-1",
                serverActivityExpectedOwner = owner,
            ).collect { emissions += it }
        }
        runCurrent()

        val expectedZero =
            AppShellBadgeEmission(
                count = 0,
                source = AppBadgeSource.ACTIVITY_UNREAD_SUMMARY.wire,
                isStale = false,
            )
        assertEquals(listOf(expectedZero), emissions, "initial cold-start emission")

        // Unrelated duplicate home emission is still suppressed by distinctUntilChanged.
        homeBadgeFlow.tryEmit(zeroHomeBadge)
        runCurrent()
        assertEquals(1, emissions.size, "duplicate home state must not spam bridge.appBadge")

        // Going to background emits nothing.
        appForeground.value = false
        runCurrent()
        assertEquals(1, emissions.size, "background transition must not emit")

        // Warm foreground return re-emits 0 so a stale OS SpringBoard / launcher badge >= 1 is cleared.
        appForeground.value = true
        runCurrent()
        assertEquals(listOf(expectedZero, expectedZero), emissions, "warm foreground return must re-emit 0")

        // Settling foreground sync with 0 -> 0 also re-emits the reconciled badge.
        foregroundSyncSettled.value = 1L
        runCurrent()
        assertEquals(
            listOf(expectedZero, expectedZero, expectedZero),
            emissions,
            "foreground sync settle must re-emit even when count stayed 0 -> 0",
        )
    }

    @Test
    fun multiServerBadgeCombinesCurrentActivityWithOtherServersOnForegroundReconcile() = runTest {
        val owner = ReadReceiptOwner(environmentId = "https://api.raft.build/api", accountId = "user-1", serverId = "server-1")
        val homeBadgeFlow =
            MutableStateFlow(
                AppBadgeCount(
                    count = 0,
                    source = AppBadgeSource.SERVER_UNREAD_SUMMARY,
                    selectedServerId = ServerId("server-1"),
                    lastUpdated = 1_000L,
                    isStale = false,
                ) to
                    HomeServerActivityUnreadProjection(
                        requestOwner = owner,
                        countsByServer = mapOf("server-1" to 0L, "server-2" to 3L, "server-muted" to 5L),
                    )
            )
        val activitySummaryFlow =
            MutableStateFlow(
                ActivityUnreadSummaryProjection(
                    serverId = "server-1",
                    totalUnreadCount = 2,
                    totalCount = 2,
                    hasValue = true,
                    freshness = ActivityUnreadSummaryFreshness.Fresh,
                    revision = 1L,
                )
            )
        val notificationLevelsFlow =
            MutableStateFlow(
                mapOf(
                    "server-muted" to
                        RaftServerNotificationLevels(
                            serverId = "server-muted",
                            server = RaftNotificationLevel.None,
                            serverLevelKnown = true,
                        )
                )
            )
        val appForeground = MutableStateFlow(true)
        val foregroundSyncSettled = MutableStateFlow(0L)

        val emissions = mutableListOf<AppShellBadgeEmission>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            appShellBadgeEmissionFlow(
                homeBadgeFlow = homeBadgeFlow,
                activityUnreadSummaryFlow = activitySummaryFlow,
                notificationLevelsFlow = notificationLevelsFlow,
                foregroundReconcileEpochFlow =
                    appShellForegroundReconcileEpochFlow(
                        appForeground = appForeground,
                        foregroundSyncSettled = foregroundSyncSettled,
                    ),
                useActivityProjection = true,
                readyServerId = "server-1",
                serverActivityExpectedOwner = owner,
            ).collect { emissions += it }
        }
        runCurrent()
        assertEquals(listOf(5), emissions.map { it.count }, "current(2) + server-2(3), excluding none server-muted(5)")

        appForeground.value = false
        runCurrent()
        appForeground.value = true
        runCurrent()
        assertEquals(listOf(5, 5), emissions.map { it.count }, "re-emits cross-server total on warm foreground return")
    }
}
