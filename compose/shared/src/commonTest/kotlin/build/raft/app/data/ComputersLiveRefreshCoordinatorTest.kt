package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * End-to-end single-source refresh tests for Computers (#wg-mobile-feedback:#273):
 *
 * 1. Offline computer -> background -> computer comes online -> return to foreground: incremental
 *    sync (`applySyncedMachineRows`) updates [SharedComputersStore], and both open Computers List
 *    and Computer Detail pages observe [SharedComputersStore] directly and flip to Online without
 *    manual refresh or extra full-table `/machines` rereads.
 * 2. App returns to foreground while offline -> network recovers later (`NetworkRestored`):
 *    failed foreground refresh never consumes the 10 s freshness window (and a `NetworkRestored`
 *    arriving while a failing foreground refresh is still in flight is latched), so both
 *    incremental-sync-on (even when `sync/changes` returns 0 machine liveness rows) and
 *    incremental-sync-off servers recover automatically to Online.
 * 3. Pre-background `/machines` refreshes cached for 25 s never suppress a post-background
 *    foreground return with `notStartedBefore = returnBoundary`, while a companion reconnect still
 *    coalesces.
 * 4. Provider-level realtime `RealtimeRefreshScope.Machines` refreshes loaded/observed servers
 *    even while the page is hidden, coalesces bursts, schedules a post-event follow-up when a push
 *    arrives mid-flight, and skips unloaded/unobserved servers.
 * 5. Server switch cleanly moves `SharedSettingsStore` live observation to the new server, and
 *    detail loads overlay live `SharedComputersStore` flips arriving while detail fetch suspends.
 */
class ComputersLiveRefreshCoordinatorTest {
    @Test
    fun backgroundRestartAndForegroundReturnUpdatesOpenListAndDetailViaCanonicalStoreWithoutExtraMachinesReread() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            val sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession())
            val detailRepo =
                SharedSettingsStoreTest.FakeSettingsRepository(
                    computerDetailResult =
                        DataResult.Content(
                            SettingsComputerDetailSnapshot(
                                computer = offlineComputerSnapshot(),
                                workspaces = listOf(SettingsComputerWorkspaceSnapshot("ws-1", "repo", "/tmp/repo")),
                                agents = emptyList(),
                                sections = emptyList(),
                            )
                        ),
                )
            val listStore =
                SharedSettingsStore(
                    dataSource = SharedSettingsDataSource(sessionStore, settingsRepository = detailRepo, computersSource = computersStore),
                    scope = backgroundScope,
                    standaloneNavigation = true,
                )
            val detailStore =
                SharedSettingsStore(
                    dataSource = SharedSettingsDataSource(sessionStore, settingsRepository = detailRepo, computersSource = computersStore),
                    scope = backgroundScope,
                    standaloneNavigation = true,
                )

            listStore.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
            detailStore.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "machine-1"))
            runCurrent()

            assertFalse(listComputer(listStore).online, "list starts Offline")
            assertFalse(detailComputer(detailStore).online, "detail starts Offline")
            val callsBeforeReturn = api.calls.size

            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 8)
            var extraMachinesRefreshes = 0
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { _, _, _, _ ->
                        extraMachinesRefreshes += 1
                        true
                    },
                    syncChangesPullAllowed = { true },
                    pullSyncChanges = { _, _ -> true },
                )
            val freshnessJob = coordinator.startFreshness(freshness)
            runCurrent()

            // Return from background with incremental sync enabled: coordinator must not add an
            // extra /machines reread on Foreground; SharedForegroundReconciler drives Foreground
            // and applies incoming machine rows to SharedComputersStore.
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            runCurrent()
            assertEquals(0, extraMachinesRefreshes, "sync-on foreground must not add an extra /machines reread")
            assertEquals(callsBeforeReturn, api.calls.size)

            val onlineRow = parseSyncedComputerRow(syncedMachineJson("machine-1", "Mac Studio", "online"))!!
            assertTrue(computersStore.applySyncedMachineRows("server-1", listOf(onlineRow), deletes = emptySet()))
            runCurrent()

            assertTrue(listComputer(listStore).online, "open Computers list flips to Online automatically")
            assertTrue(detailComputer(detailStore).online, "open Computer detail flips to Online automatically")
            val detailContent = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detailStore.state.value.computerDetail).data
            assertEquals(listOf("ws-1"), detailContent.workspaces.map { it.id }, "detail keeps existing workspaces without flicker")

            freshnessJob.cancel()
            listStore.clear()
            detailStore.clear()
        }

    @Test
    fun foregroundBeforeNetworkRecoveryRecoversOnlineStatusWhenSyncChangesHasZeroLivenessRows() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            val sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession())
            val listStore =
                SharedSettingsStore(
                    dataSource = SharedSettingsDataSource(sessionStore, computersSource = computersStore),
                    scope = backgroundScope,
                    standaloneNavigation = true,
                )
            listStore.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
            runCurrent()
            assertFalse(listComputer(listStore).online)

            var clock = 1_000_000L
            val timeSource = TestTimeSource()
            val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = timeSource)
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 8)
            val syncTriggers = mutableListOf<String>()
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { serverId, reason, window, boundary ->
                        coalescer.runOrCoalesceWithResult(
                            serverId = serverId,
                            reason = reason,
                            coalesceFreshWithin = window,
                            notStartedBefore = boundary,
                        ) {
                            computersStore.reloadMachinesForSyncSucceeded(serverId)
                        }.succeeded
                    },
                    syncChangesPullAllowed = { true },
                    pullSyncChanges = { serverId, trigger ->
                        // Realistic current backend: sync/changes returns 200 with 0 machine liveness rows.
                        syncTriggers += "$serverId:$trigger"
                        true
                    },
                    nowMillis = { clock },
                    timeSource = timeSource,
                )
            val freshnessJob = coordinator.startFreshness(freshness)
            runCurrent()

            // Case A: foreground refresh started while offline and failed before NetworkRestored arrived.
            val tokenA = coordinator.onExternalForegroundRefreshStarted("server-1")
            clock += 400
            timeSource += 400.milliseconds
            coordinator.onExternalForegroundRefreshCompleted("server-1", tokenA, succeeded = false)

            // 2 s later (< 10 s coalesce window), network recovers and computer is Online.
            api.statusByServer["server-1"] = "online"
            clock += 2_000
            timeSource += 2_000.milliseconds
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()

            assertEquals(listOf("server-1:freshness_network"), syncTriggers)
            assertTrue(listComputer(listStore).online, "list recovers to Online even when sync/changes carries 0 liveness rows")

            // A redundant NetworkRestored 1 s after the successful recovery is coalesced.
            clock += 1_000
            timeSource += 1_000.milliseconds
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()
            assertEquals(1, syncTriggers.size)

            // Case B: NetworkRestored arrives WHILE a hanging foreground refresh is still in flight, then fails.
            clock += RAFT_FRESHNESS_COALESCE_MILLIS + 1
            timeSource += (RAFT_FRESHNESS_COALESCE_MILLIS + 1).milliseconds
            val tokenB = coordinator.onExternalForegroundRefreshStarted("server-1")
            clock += 1_500
            timeSource += 1_500.milliseconds
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()
            assertEquals(1, syncTriggers.size, "waits for in-flight foreground refresh")

            clock += 500
            timeSource += 500.milliseconds
            coordinator.onExternalForegroundRefreshCompleted("server-1", tokenB, succeeded = false)
            runCurrent()
            assertEquals(
                listOf("server-1:freshness_network", "server-1:freshness_network"),
                syncTriggers,
                "latched NetworkRestored fires as soon as the failing foreground refresh finishes",
            )

            freshnessJob.cancel()
            listStore.clear()
        }

    @Test
    fun foregroundBeforeNetworkRecoveryRecoversAutomaticallyWhenIncrementalSyncIsOff() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            val sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession())
            val listStore =
                SharedSettingsStore(
                    dataSource = SharedSettingsDataSource(sessionStore, computersSource = computersStore),
                    scope = backgroundScope,
                    standaloneNavigation = true,
                )
            listStore.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
            runCurrent()
            assertFalse(listComputer(listStore).online)

            // Return to foreground while device has no network: /machines fails with 503.
            api.codeByServer["server-1"] = 503
            var clock = 1_000_000L
            val timeSource = TestTimeSource()
            val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = timeSource)
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 8)
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { serverId, reason, window, boundary ->
                        coalescer.runOrCoalesceWithResult(
                            serverId = serverId,
                            reason = reason,
                            coalesceFreshWithin = window,
                            notStartedBefore = boundary,
                        ) {
                            computersStore.reloadMachinesForSyncSucceeded(serverId)
                        }.succeeded
                    },
                    syncChangesPullAllowed = { false },
                    nowMillis = { clock },
                    timeSource = timeSource,
                )
            val freshnessJob = coordinator.startFreshness(freshness)
            runCurrent()

            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            runCurrent()
            assertFalse(listComputer(listStore).online, "still Offline after failed foreground read")

            // 2 s later network recovers and computer is now Online.
            api.codeByServer.remove("server-1")
            api.statusByServer["server-1"] = "online"
            clock += 2_000
            timeSource += 2_000.milliseconds
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()

            assertTrue(listComputer(listStore).online, "NetworkRestored retries and updates open Computers list to Online")

            freshnessJob.cancel()
            listStore.clear()
        }

    @Test
    fun preBackgroundRefreshDoesNotSuppressPostBackgroundForegroundReturnWithin25Seconds() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            computersStore.ensureComputerPickerForServer("server-1")

            val timeSource = TestTimeSource()
            val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = timeSource)
            // t=0 s: pre-background realtime machine:status caches a 25 s fresh entry.
            val preBg =
                coalescer.runOrCoalesceWithResult(
                    serverId = "server-1",
                    reason = "realtime_machine:status",
                    coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                ) {
                    computersStore.reloadMachinesForSyncSucceeded("server-1")
                }
            assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, preBg.outcome)

            // t=16 s (< 25 s): user returns from background after computer rebooted Online.
            // Foreground reconcile passes `notStartedBefore = returnBoundary`, so pre-background
            // cache cannot suppress this return.
            timeSource += 16_000.milliseconds
            api.statusByServer["server-1"] = "online"
            val returnBoundary = timeSource.markNow()
            val foregroundReturn =
                coalescer.runOrCoalesceWithResult(
                    serverId = "server-1",
                    reason = "foreground_reconcile",
                    coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                    notStartedBefore = returnBoundary,
                ) {
                    computersStore.reloadMachinesForSyncSucceeded("server-1")
                }
            assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, foregroundReturn.outcome)
            assertEquals(
                ComputerStatus.Online,
                computersStore.state.value.selectComputerServerSlice("server-1").machines.single().status,
            )

            // Companion reconnect refetch 4 s later with connectedAt <= returnBoundary still coalesces.
            timeSource += 4_000.milliseconds
            val companionReconnect =
                coalescer.runOrCoalesceWithResult(
                    serverId = "server-1",
                    reason = PresenceTransportRosterRefetch.REASON,
                    coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                    notStartedBefore = returnBoundary,
                ) {
                    error("companion reconnect must coalesce with the fresh foreground return")
                }
            assertEquals(RaftRecentServerRefreshCoalescer.Outcome.FreshCoalesced, companionReconnect.outcome)
            assertTrue(companionReconnect.succeeded)
        }

    @Test
    fun preBackgroundRefreshWithin10SecondsDoesNotSwallowPostBackgroundNetworkRestored() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            val sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession())
            val listStore =
                SharedSettingsStore(
                    dataSource = SharedSettingsDataSource(sessionStore, computersSource = computersStore),
                    scope = backgroundScope,
                    standaloneNavigation = true,
                )
            listStore.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
            runCurrent()
            assertFalse(listComputer(listStore).online)

            var clock = 1_000_000L
            val timeSource = TestTimeSource()
            val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = timeSource)
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 8)
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { serverId, reason, window, boundary ->
                        coalescer.runOrCoalesceWithResult(
                            serverId = serverId,
                            reason = reason,
                            coalesceFreshWithin = window,
                            notStartedBefore = boundary,
                        ) {
                            computersStore.reloadMachinesForSyncSucceeded(serverId)
                        }.succeeded
                    },
                    syncChangesPullAllowed = { true },
                    pullSyncChanges = { _, _ -> true },
                    nowMillis = { clock },
                    timeSource = timeSource,
                )
            val freshnessJob = coordinator.startFreshness(freshness)
            runCurrent()

            // t=0 s (before background): realtime machine:status (offline) populates coalescer cache.
            val preBg =
                coalescer.runOrCoalesceWithResult(
                    serverId = "server-1",
                    reason = "realtime_machine:status",
                    coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                ) {
                    computersStore.reloadMachinesForSyncSucceeded("server-1")
                }
            assertTrue(preBg.succeeded)

            // t=5 s: app returns to foreground while offline; foreground refresh fails (503) and
            // retires the stale pre-background completed cache entry.
            clock += 5_000
            timeSource += 5_000.milliseconds
            api.codeByServer["server-1"] = 503
            val returnBoundary = timeSource.markNow()
            val fgToken = coordinator.onExternalForegroundRefreshStarted("server-1", returnBoundary)
            val fgMachines =
                coalescer.runOrCoalesceWithResult(
                    serverId = "server-1",
                    reason = "foreground_reconcile",
                    coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
                    notStartedBefore = returnBoundary,
                ) {
                    computersStore.reloadMachinesForSyncSucceeded("server-1")
                }
            assertFalse(fgMachines.succeeded)
            coordinator.onExternalForegroundRefreshCompleted("server-1", fgToken, succeeded = false)

            // t=8 s (< 10 s from t=0 s!): network recovers and computer is Online.
            // NetworkRestored MUST hit /machines (never FreshCoalesced against t=0 s) and flip Online.
            clock += 3_000
            timeSource += 3_000.milliseconds
            api.codeByServer.remove("server-1")
            api.statusByServer["server-1"] = "online"
            val callsBeforeNetwork = api.calls.count { it == "server-1" }
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
            runCurrent()

            assertEquals(callsBeforeNetwork + 1, api.calls.count { it == "server-1" })
            assertTrue(
                listComputer(listStore).online,
                "NetworkRestored within 10 s of a pre-background refresh must still fetch /machines and flip Online",
            )

            freshnessJob.cancel()
            listStore.clear()
        }

    @Test
    fun realtimeMachinesSinkUpdatesLoadedStoreWhenHiddenSkipsUnloadedAndFollowsUpMidFlightPush() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            // server-1 was loaded earlier (page is currently closed/hidden -> 0 active observers, hasSnapshot = true).
            computersStore.ensureComputerPickerForServer("server-1")
            assertFalse(computersStore.hasActiveObservers("server-1"))
            assertTrue(computersStore.hasLoadedOrObservedServer("server-1"))
            assertFalse(computersStore.hasLoadedOrObservedServer("server-2"))

            val timeSource = TestTimeSource()
            val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = timeSource)
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { serverId, reason, window, boundary ->
                        coalescer.runOrCoalesceWithResult(
                            serverId = serverId,
                            reason = reason,
                            coalesceFreshWithin = window,
                            notStartedBefore = boundary,
                        ) {
                            computersStore.reloadMachinesForSyncSucceeded(serverId)
                        }.succeeded
                    },
                    windowMillis = 250L,
                    minRefreshIntervalMillis = 0L,
                    timeSource = timeSource,
                )

            // Unloaded/unobserved server-2 is ignored completely.
            coordinator.onMachinesRealtimeEvent("server-2", "machine:status")
            advanceTimeBy(300L)
            runCurrent()
            assertEquals(0, api.calls.count { it == "server-2" })

            // Burst of realtime pushes on loaded server-1 while hidden folds into one refresh.
            api.statusByServer["server-1"] = "online"
            val callsBeforeBurst = api.calls.count { it == "server-1" }
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(100L)
            timeSource += 100.milliseconds
            coordinator.onMachinesRealtimeEvent("server-1", "machine:updated")
            advanceTimeBy(250L)
            timeSource += 250.milliseconds
            runCurrent()

            assertEquals(callsBeforeBurst + 1, api.calls.count { it == "server-1" })
            assertEquals(
                ComputerStatus.Online,
                computersStore.state.value.selectComputerServerSlice("server-1").machines.single().status,
                "canonical store updates to Online even while Computers page is hidden",
            )

            // Mid-flight push: while Flight 1 (returning offline) is suspended, a newer machine:status
            // (online) arrives -> schedules one post-event follow-up that does NOT join Flight 1.
            val flight1Gate = CompletableDeferred<Unit>()
            api.statusByServer["server-1"] = "offline"
            api.nextGateByServer["server-1"] = flight1Gate
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(260L)
            timeSource += 260.milliseconds
            runCurrent()

            // Flight 1 is now in flight; computer immediately comes back Online.
            api.statusByServer["server-1"] = "online"
            timeSource += 50.milliseconds
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(260L)
            timeSource += 260.milliseconds
            runCurrent()
            flight1Gate.complete(Unit)
            runCurrent()

            assertEquals(
                ComputerStatus.Online,
                computersStore.state.value.selectComputerServerSlice("server-1").machines.single().status,
                "mid-flight push triggers a post-boundary refresh and wins over the earlier flight",
            )

            // Manual pull-to-refresh bypasses the 25 s coalescer and hits the server directly.
            val beforeManual = api.calls.count { it == "server-1" }
            computersStore.loadSettingsComputersForServer("server-1", force = true)
            assertEquals(beforeManual + 1, api.calls.count { it == "server-1" })
        }

    @Test
    fun serverSwitchMovesLiveObservationAndDetailLoadOverlaysMidFlightCanonicalFlip() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            api.statusByServer["server-2"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            val baseSession = settingsStoreSampleSession()
            val sessionWithTwoServers =
                baseSession.copy(
                    servers =
                        baseSession.servers +
                            StoredAuthServer(
                                id = "server-2",
                                name = "Server Two",
                                slug = "server-2",
                                role = "owner",
                            ),
                )
            val sessionStore = SwitchingAuthSessionStore(sessionWithTwoServers)
            val detailBlock = SharedSettingsStoreTest.BlockingCall()
            val detailRepo =
                SharedSettingsStoreTest.FakeSettingsRepository(
                    computerDetailResult =
                        DataResult.Content(
                            SettingsComputerDetailSnapshot(
                                computer = offlineComputerSnapshot(),
                                workspaces = emptyList(),
                                agents = emptyList(),
                                sections = emptyList(),
                            )
                        ),
                ).apply {
                    computerDetailBlock = detailBlock
                }

            // 1. Detail load overlays a fresher SharedComputersStore flip arriving while agents fetch suspends.
            val detailStore =
                SharedSettingsStore(
                    dataSource = SharedSettingsDataSource(sessionStore, settingsRepository = detailRepo, computersSource = computersStore),
                    scope = backgroundScope,
                    standaloneNavigation = true,
                )
            detailStore.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "machine-1"))
            runCurrent()

            // While getComputerDetailForComputer is suspended, SharedComputersStore updates to Online.
            val onlineRow = parseSyncedComputerRow(syncedMachineJson("machine-1", "Mac Studio", "online"))!!
            assertTrue(computersStore.applySyncedMachineRows("server-1", listOf(onlineRow), deletes = emptySet()))
            detailBlock.release()
            runCurrent()
            assertTrue(
                detailComputer(detailStore).online,
                "detail load must overlay the fresher Online snapshot from SharedComputersStore",
            )
            detailStore.clear()

            // 2. Failed or starting refresh in SharedComputersStore must not mark the list fresh.
            var clock = 1_000_000L
            val listStore =
                SharedSettingsStore(
                    dataSource = SharedSettingsDataSource(sessionStore, computersSource = computersStore),
                    scope = backgroundScope,
                    nowMillis = { clock },
                    standaloneNavigation = true,
                )
            listStore.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
            runCurrent()
            assertTrue(computersStore.hasActiveObservers("server-1"))
            assertFalse(computersStore.hasActiveObservers("server-2"))

            clock += COMPUTERS_SHOWN_STALE_MILLIS + 1
            api.codeByServer["server-1"] = 503
            assertFalse(computersStore.reloadMachinesForSyncSucceeded("server-1"))
            runCurrent()

            // Starting a CatchUpComputers after a failed refresh must still show the stale indicator
            // because the failed refresh did not stamp computersValidatedAtMillis.
            api.codeByServer.remove("server-1")
            val catchUpGate = CompletableDeferred<Unit>()
            api.nextGateByServer["server-1"] = catchUpGate
            clock += 1_000
            listStore.dispatch(SharedSettingsIntent.CatchUpComputers)
            runCurrent()
            assertTrue(
                listStore.state.value.isRefreshing(SharedSettingsDestination.Computers),
                "failed refresh must not stamp computersValidatedAtMillis",
            )
            catchUpGate.complete(Unit)
            runCurrent()

            // 3. Server switch detaches server-1 observer and attaches live observation to server-2.
            sessionStore.switchServer("server-2")
            runCurrent()
            assertFalse(computersStore.hasActiveObservers("server-1"), "old server observer must detach on switch")
            assertTrue(computersStore.hasActiveObservers("server-2"), "new server observer must attach on switch")
            assertFalse(listComputer(listStore).online, "server-2 starts Offline")

            // Live update on server-2 flows immediately into the switched listStore.
            assertTrue(computersStore.applySyncedMachineRows("server-2", listOf(onlineRow), deletes = emptySet()))
            runCurrent()
            assertTrue(listComputer(listStore).online, "switched list observes server-2 live updates")
            listStore.clear()
        }

    @Test
    fun machineStatusStormRespectsMinRefreshIntervalAndCoalescesTrailingRecovery() =
        runTest {
            val api = ScriptedMachinesApi()
            api.statusByServer["server-1"] = "offline"
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            computersStore.ensureComputerPickerForServer("server-1")

            val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = testScheduler.timeSource)
            val startedTimes = mutableListOf<Long>()
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { serverId, reason, window, boundary ->
                        startedTimes += testScheduler.currentTime
                        coalescer.runOrCoalesceWithResult(
                            serverId = serverId,
                            reason = reason,
                            coalesceFreshWithin = window,
                            notStartedBefore = boundary,
                        ) {
                            computersStore.reloadMachinesForSyncSucceeded(serverId)
                        }.succeeded
                    },
                    windowMillis = 1_500L,
                    minRefreshIntervalMillis = 15_000L,
                    timeSource = testScheduler.timeSource,
                )

            val callsBefore = api.calls.count { it == "server-1" }
            // First machine:status after idle runs promptly in 1.5s.
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(1_500L)
            runCurrent()
            assertEquals(callsBefore + 1, api.calls.count { it == "server-1" })
            assertEquals(listOf(1_500L), startedTimes)

            // Repeated high-frequency events every 3s for 30s coalesce behind the 15s cooldown.
            val reasons = listOf("machine:status", "daemon:status", "machine:updated", "machine:capabilities")
            for (step in 1..10) {
                advanceTimeBy(3_000L)
                if (step == 10) api.statusByServer["server-1"] = "online"
                coordinator.onMachinesRealtimeEvent("server-1", reasons[step % reasons.size])
                runCurrent()
            }
            advanceTimeBy(20_000L)
            runCurrent()

            val totalDuringStorm = api.calls.count { it == "server-1" } - callsBefore
            assertTrue(totalDuringStorm <= 3, "expected <= 3 /machines fetches during 30s storm, got $totalDuringStorm")
            startedTimes.zipWithNext().forEach { (prev, next) ->
                assertTrue(next - prev >= 15_000L, "cooldown spacing violated: $startedTimes")
            }
            assertEquals(
                ComputerStatus.Online,
                computersStore.state.value.selectComputerServerSlice("server-1").machines.single().status,
                "trailing coalesced refresh must capture final Online state",
            )
        }

    @Test
    fun interactiveUpgradeEventsBypassAndPromoteMachineStatusCooldown() =
        runTest {
            val api = ScriptedMachinesApi()
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            computersStore.ensureComputerPickerForServer("server-1")

            val refreshLog = mutableListOf<Pair<Long, String>>()
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { _, reason, _, _ ->
                        refreshLog += testScheduler.currentTime to reason
                        true
                    },
                    windowMillis = 1_500L,
                    minRefreshIntervalMillis = 15_000L,
                    timeSource = testScheduler.timeSource,
                )

            // 1. Initial machine:status runs at t=1500 and starts 15s cooldown.
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(1_500L)
            runCurrent()
            assertEquals(listOf(1_500L to "realtime_machine:status"), refreshLog)

            // 2. Another machine:status at t=2500 schedules a throttled trailing job (~14s away).
            advanceTimeBy(1_000L)
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(1, refreshLog.size, "second machine:status waits out cooldown")

            // 3. Urgent computer:upgrade:done at t=4500 promotes the waiting throttled job to run in 1.5s.
            coordinator.onMachinesRealtimeEvent("server-1", "computer:upgrade:done")
            advanceTimeBy(1_500L)
            runCurrent()
            assertEquals(2, refreshLog.size)
            assertEquals(6_000L to "realtime_computer:upgrade:done", refreshLog.last())

            // 4. Urgent machine:upgrade-request immediately after also runs promptly in 1.5s.
            advanceTimeBy(500L)
            coordinator.onMachinesRealtimeEvent("server-1", "machine:upgrade-request")
            advanceTimeBy(1_500L)
            runCurrent()
            assertEquals(3, refreshLog.size)
            assertEquals(8_000L to "realtime_machine:upgrade-request", refreshLog.last())
        }

    @Test
    fun cancelAndCancelAllResetRealtimeCooldownWhileFreshnessRemainsUnaffected() =
        runTest {
            val api = ScriptedMachinesApi()
            val computersStore =
                SharedComputersStore(
                    computersApiClient = RaftComputersApiClient(api),
                    scope = backgroundScope,
                )
            computersStore.ensureComputerPickerForServer("server-1")

            var clock = 1_000_000L
            val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 8)
            val refreshReasons = mutableListOf<String>()
            val coordinator =
                ComputersLiveRefreshCoordinator(
                    scope = backgroundScope,
                    loadedOrObservedServerIds = { computersStore.loadedOrObservedServerIds() },
                    hasLoadedOrObservedServer = computersStore::hasLoadedOrObservedServer,
                    hasActiveObservers = computersStore::hasActiveObservers,
                    refreshMachines = { _, reason, _, _ ->
                        refreshReasons += reason
                        true
                    },
                    syncChangesPullAllowed = { false },
                    windowMillis = 1_500L,
                    minRefreshIntervalMillis = 15_000L,
                    nowMillis = { clock },
                    timeSource = testScheduler.timeSource,
                )
            val freshnessJob = coordinator.startFreshness(freshness)
            runCurrent()

            // Initial machine:status starts cooldown.
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(1_500L)
            clock += 1_500L
            runCurrent()
            assertEquals(listOf("realtime_machine:status"), refreshReasons)

            // Foreground freshness trigger during cooldown runs immediately.
            freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
            runCurrent()
            assertEquals(listOf("realtime_machine:status", "freshness_foreground"), refreshReasons)

            // cancel(server-1) clears realtime cooldown so next machine:status runs promptly in 1.5s.
            coordinator.cancel("server-1")
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(1_500L)
            clock += 1_500L
            runCurrent()
            assertEquals(
                listOf("realtime_machine:status", "freshness_foreground", "realtime_machine:status"),
                refreshReasons,
            )

            // cancelAll() also clears realtime cooldown.
            coordinator.cancelAll()
            coordinator.onMachinesRealtimeEvent("server-1", "machine:status")
            advanceTimeBy(1_500L)
            runCurrent()
            assertEquals(4, refreshReasons.size)

            freshnessJob.cancel()
        }

    private class SwitchingAuthSessionStore(
        initial: StoredAuthSession,
        private val inner: InMemoryRaftAuthSessionStore = InMemoryRaftAuthSessionStore(initial),
    ) : RaftAuthSessionStore by inner, RaftAuthSessionEventSource {
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
        private var generation = 0L
        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        suspend fun switchServer(serverId: String) {
            inner.updateSelectedServer(serverId, null)
            events.emit(AuthSessionEvent.BindingMetadataChanged(++generation))
        }
    }

    private fun listComputer(store: SharedSettingsStore): SettingsComputerSnapshot =
        assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data.computers.single()

    private fun detailComputer(store: SharedSettingsStore): SettingsComputerSnapshot =
        assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail).data.computer

    private fun offlineComputerSnapshot(): SettingsComputerSnapshot =
        SettingsComputerSnapshot(
            id = "machine-1",
            name = "Mac Studio",
            online = false,
            daemonVersion = "0.53.0",
            runtimes = listOf("codex"),
        )

    private fun syncedMachineJson(id: String, name: String, status: String): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"$id","name":"$name","status":"$status","runtimes":["codex"],"agentCount":1}"""
        ) as kotlinx.serialization.json.JsonObject

    private class ScriptedMachinesApi : RaftComputersApi {
        val calls = mutableListOf<String>()
        val statusByServer = mutableMapOf<String, String>()
        val codeByServer = mutableMapOf<String, Int>()
        val nextGateByServer = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            val id = serverId.value
            calls += id
            nextGateByServer.remove(id)?.await()
            codeByServer[id]?.let { code ->
                return HomeNetworkResponse(code = code, bodyText = """{"error":"offline"}""")
            }
            val status = statusByServer[id] ?: "online"
            return HomeNetworkResponse(
                code = 200,
                bodyText = """{"machines":[{"id":"machine-1","name":"Mac Studio","status":"$status","runtimes":["codex"]}]}""",
            )
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }
}
