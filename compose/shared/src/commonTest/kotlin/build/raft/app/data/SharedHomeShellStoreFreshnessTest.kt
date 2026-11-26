package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeShellStoreFreshnessTest {
    @Test
    fun failedHomeCatchUpDoesNotConsumeCoalesceWindowAndRetriesOnNextNetworkRestored() = runTest {
        var nowMs = 1_000L
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        val homeResults = ArrayDeque(listOf(false, true, true))
        var homeRefreshAttempts = 0
        val triggered = mutableListOf<Triple<RaftFreshnessTrigger, String, String>>()

        val store =
            SharedHomeShellStore(
                homeState = MutableStateFlow(SharedHomeState()),
                activityState = MutableStateFlow(SharedActivityState()),
                searchSavedState = MutableStateFlow(SharedHomeSearchSavedState()),
                dispatchHome = {},
                dispatchActivity = {},
                dispatchSearchSaved = {},
                scope = backgroundScope,
                freshness = freshness,
                freshnessNowMillis = { nowMs },
                freshnessLiveConnectionToken = { null },
                refreshHomeForFreshness = {
                    homeRefreshAttempts += 1
                    homeResults.removeFirst()
                },
            )
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("home"))
        store.setFreshnessScope(
            serverId = "server-1",
            visible = true,
            onTriggered = { trigger, serverId, tab, _ ->
                triggered += Triple(trigger, serverId, tab)
            },
        )
        runCurrent()

        // First catch-up fails: must NOT consume the 10 s coalesce window.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(1, homeRefreshAttempts)

        // Second NetworkRestored 1 s later retries immediately and succeeds.
        nowMs = 2_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(2, homeRefreshAttempts)

        // Third NetworkRestored 1 s after success is coalesced inside the 10 s window.
        nowMs = 3_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(2, homeRefreshAttempts)

        // A new socket connection (transportEpoch) bypasses the 10 s coalesce window.
        nowMs = 4_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 2L))
        runCurrent()
        assertEquals(3, homeRefreshAttempts)
        assertEquals(
            listOf(
                Triple(RaftFreshnessTrigger.NetworkRestored, "server-1", "home"),
                Triple(RaftFreshnessTrigger.NetworkRestored, "server-1", "home"),
                Triple(RaftFreshnessTrigger.Reconnect, "server-1", "home"),
            ),
            triggered,
        )
        store.clear()
    }

    @Test
    fun hiddenOrIneligibleTabHoldsReconnectAndDrainsWhenEligibleRouteShows() = runTest {
        var nowMs = 1_000L
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        var homeRefreshAttempts = 0
        var activityRefreshAttempts = 0
        val triggered = mutableListOf<Pair<RaftFreshnessTrigger, String>>()

        val store =
            SharedHomeShellStore(
                homeState = MutableStateFlow(SharedHomeState()),
                activityState = MutableStateFlow(SharedActivityState()),
                searchSavedState = MutableStateFlow(SharedHomeSearchSavedState()),
                dispatchHome = {},
                dispatchActivity = {},
                dispatchSearchSaved = {},
                scope = backgroundScope,
                freshness = freshness,
                freshnessNowMillis = { nowMs },
                freshnessLiveConnectionToken = { null },
                refreshHomeForFreshness = {
                    homeRefreshAttempts += 1
                    true
                },
                refreshActivityForFreshness = {
                    activityRefreshAttempts += 1
                    true
                },
            )
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("more"))
        store.setFreshnessScope(
            serverId = "server-1",
            visible = true,
            onTriggered = { trigger, _, tab, _ -> triggered += trigger to tab },
        )
        runCurrent()

        // Reconnect while on More is held, not dropped.
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = "server-1", transportEpoch = 1L))
        runCurrent()
        assertEquals(0, homeRefreshAttempts)
        assertEquals(0, activityRefreshAttempts)

        // Returning to Home drains the held reconnect.
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("home"))
        runCurrent()
        assertEquals(1, homeRefreshAttempts)
        assertEquals(listOf(RaftFreshnessTrigger.Reconnect to "home"), triggered)

        // Switching to Activity while hidden holds NetworkRestored until visible again.
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("activity"))
        store.setFreshnessScope(
            serverId = "server-1",
            visible = false,
            onTriggered = { trigger, _, tab, _ -> triggered += trigger to tab },
        )
        runCurrent()

        nowMs = 20_000L
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        runCurrent()
        assertEquals(0, activityRefreshAttempts)

        store.setFreshnessScope(
            serverId = "server-1",
            visible = true,
            onTriggered = { trigger, _, tab, _ -> triggered += trigger to tab },
        )
        runCurrent()
        assertEquals(1, activityRefreshAttempts)
        assertEquals(
            listOf(
                RaftFreshnessTrigger.Reconnect to "home",
                RaftFreshnessTrigger.NetworkRestored to "activity",
            ),
            triggered,
        )
        store.clear()
    }

    @Test
    fun foregroundSignalIsIgnoredByShellFreshnessGate() = runTest {
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        var homeRefreshAttempts = 0

        val store =
            SharedHomeShellStore(
                homeState = MutableStateFlow(SharedHomeState()),
                activityState = MutableStateFlow(SharedActivityState()),
                searchSavedState = MutableStateFlow(SharedHomeSearchSavedState()),
                dispatchHome = {},
                dispatchActivity = {},
                dispatchSearchSaved = {},
                scope = backgroundScope,
                freshness = freshness,
                freshnessNowMillis = { 1_000L },
                freshnessLiveConnectionToken = { null },
                refreshHomeForFreshness = {
                    homeRefreshAttempts += 1
                    true
                },
            )
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("home"))
        store.setFreshnessScope(serverId = "server-1", visible = true)
        runCurrent()

        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        runCurrent()
        assertEquals(0, homeRefreshAttempts)
        store.clear()
    }
}
