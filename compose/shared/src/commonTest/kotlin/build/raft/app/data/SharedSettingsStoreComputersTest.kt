package build.raft.app.data

import build.raft.app.data.SharedSettingsStoreTest.BlockingCall
import build.raft.app.data.SharedSettingsStoreTest.FakeSettingsComputersSource
import build.raft.app.data.SharedSettingsStoreTest.FakeSettingsRepository
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ComputerRestartDone
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Computers list / detail behavior of [SharedSettingsStore] as the standalone pages drive it. */
class SharedSettingsStoreComputersTest {
    @Test
    fun listStoreReflectsDeleteAndRenameMadeOnTheDetailPage() = runTest {
        val repository =
            FakeSettingsRepository(computersResult = DataResult.Content(computers(machine("m-1", "Studio"), machine("m-2", "Build"))))
        val list = store(repository, standalone = true)
        list.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        assertEquals(listOf("Studio", "Build"), listNames(list))

        // The detail page (its own store) renamed m-1 and deleted m-2. Returning to the list
        // refreshes it (resume), and a machine:updated event reloads it while it is visible.
        repository.computersResult = DataResult.Content(computers(machine("m-1", "Studio Mac")))
        list.accept(SharedSettingsIntent.RefreshComputers)
        assertEquals(listOf("Studio Mac"), listNames(list))

        repository.computersResult = DataResult.Content(computers(machine("m-1", "Studio Pro")))
        list.accept(SharedSettingsIntent.ApplyRealtimeEvent("server-1", machinesEvent("server-1", "m-1")))
        assertEquals(listOf("Studio Pro"), listNames(list))
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.Computers), list.state.value.route)
    }

    @Test
    fun refreshFailureKeepsTheListAndRecordsTheRouteError() = runTest {
        val repository = FakeSettingsRepository(computersResult = DataResult.Content(computers(machine("m-1", "Studio"))))
        val list = store(repository, standalone = true)
        list.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))

        repository.computersResult = DataResult.Error(ScreenError("server_unavailable", retryable = true))
        list.accept(SharedSettingsIntent.RefreshComputers)

        assertEquals(listOf("Studio"), listNames(list))
        assertEquals("server_unavailable", list.state.value.routeErrorCode)
    }

    @Test
    fun pullRefreshHoldsRefreshingRunsOnceAndKeepsThePageOnFailure() = runTest {
        data class Case(
            val label: String,
            val destination: SharedSettingsDestination,
            val itemId: String?,
            /** What the page's refresh button and pull-to-refresh dispatch. */
            val refresh: SharedSettingsIntent,
            val call: String,
        )
        val cases =
            listOf(
                Case("list", SharedSettingsDestination.Computers, null, SharedSettingsIntent.RefreshComputers, "computers"),
                Case("detail", SharedSettingsDestination.ComputerDetail, "m-1", SharedSettingsIntent.Refresh, "computerDetail:m-1"),
            )
        cases.forEach { case ->
            val repository = detailRepository().apply { computersResult = DataResult.Content(computers(machine("m-1", "Studio"))) }
            val store = store(repository, standalone = true)
            store.accept(SharedSettingsIntent.LoadPageContent(case.destination, case.itemId))
            val block = BlockingCall()
            repository.computersBlock = block
            repository.computerDetailBlock = block
            repository.computersResult = DataResult.Error(ScreenError("server_unavailable", retryable = true))
            repository.computerDetailResult = DataResult.Error(ScreenError("server_unavailable", retryable = true))
            repository.calls.clear()

            store.dispatch(case.refresh)
            runCurrent()
            assertTrue(store.state.value.isRefreshing(case.destination), "${case.label}: refreshing while the request runs")
            store.dispatch(case.refresh)
            runCurrent()
            assertEquals(listOf(case.call), repository.calls, "${case.label}: a refresh in flight is not restarted")

            block.release()
            runCurrent()
            assertEquals(false, store.state.value.isRefreshing(case.destination), case.label)
            assertEquals("server_unavailable", store.state.value.routeErrorCode, case.label)
            val shownName =
                when (case.destination) {
                    SharedSettingsDestination.Computers -> listNames(store).single()
                    else -> assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(store.state.value.computerDetail).data.computer.name
                }
            assertEquals("Studio", shownName, "${case.label}: the loaded page stays")
        }
    }

    @Test
    fun catchUpIsSilentWhileTheShownComputersAreFreshAndShowsTheIndicatorOnceTheyAreOld() = runTest {
        // #198 (raft-design freshness rule 3): a foreground / reconnect catch-up is the system's,
        // not the person's: no header spinner unless what the page shows is old.
        data class Case(val label: String, val destination: SharedSettingsDestination, val itemId: String?)
        val cases =
            listOf(
                Case("list", SharedSettingsDestination.Computers, null),
                Case("detail", SharedSettingsDestination.ComputerDetail, "m-1"),
            )
        cases.forEach { case ->
            var now = 1_000_000L
            val repository = detailRepository().apply { computersResult = DataResult.Content(computers(machine("m-1", "Studio"))) }
            val store = store(repository, standalone = true, nowMillis = { now })
            store.accept(SharedSettingsIntent.LoadPageContent(case.destination, case.itemId))
            val ages = listOf(
                "fresh" to (COMPUTERS_SHOWN_STALE_MILLIS - 1) to false,
                "old" to (COMPUTERS_SHOWN_STALE_MILLIS + 1) to true,
            )
            ages.forEach { (labelAndAge, expectIndicator) ->
                val (ageLabel, age) = labelAndAge
                now += age
                val block = BlockingCall()
                repository.computersBlock = block
                repository.computerDetailBlock = block
                store.dispatch(SharedSettingsIntent.CatchUpComputers)
                runCurrent()
                assertEquals(
                    expectIndicator,
                    store.state.value.isRefreshing(case.destination),
                    "${case.label} $ageLabel: indicator while the catch-up runs",
                )
                block.release()
                runCurrent()
                assertEquals(false, store.state.value.isRefreshing(case.destination), "${case.label} $ageLabel")
                repository.computersBlock = null
                repository.computerDetailBlock = null
            }
        }
    }

    @Test
    fun aFailedRefreshDoesNotMakeTheKeptListCountAsFresh() = runTest {
        var now = 1_000_000L
        val repository = FakeSettingsRepository(computersResult = DataResult.Content(computers(machine("m-1", "Studio"))))
        val store = store(repository, standalone = true, nowMillis = { now })
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))

        now += COMPUTERS_SHOWN_STALE_MILLIS + 1
        repository.computersResult = DataResult.Error(ScreenError("server_unavailable", retryable = true))
        store.accept(SharedSettingsIntent.RefreshComputers)
        assertEquals(listOf("Studio"), listNames(store), "the list stays")

        now += 1_000
        val block = BlockingCall()
        repository.computersBlock = block
        store.dispatch(SharedSettingsIntent.CatchUpComputers)
        runCurrent()
        assertTrue(store.state.value.isRefreshing(SharedSettingsDestination.Computers), "the list was not confirmed: indicator")
        block.release()
        runCurrent()
    }

    @Test
    fun renameAndDeleteFollowTheServerMachineGate() = runTest {
        data class Case(
            val label: String,
            val role: String,
            val creatorUserId: String? = null,
            val attachedByCurrentUser: Boolean = false,
            val expected: Boolean,
        )
        val cases =
            listOf(
                Case("owner", role = "owner", expected = true),
                Case("admin (editMachines/removeMachines)", role = "admin", expected = true),
                Case("member who created the machine", role = "member", creatorUserId = "user-1", expected = true),
                Case("member who attached the Computer", role = "member", attachedByCurrentUser = true, expected = true),
                Case("member", role = "member", creatorUserId = "user-2", expected = false),
                Case("guest", role = "guest", expected = false),
            )
        cases.forEach { case ->
            val computer =
                machine("m-1", "Studio").copy(
                    creatorUserId = case.creatorUserId,
                    attachedByCurrentUser = case.attachedByCurrentUser
                )
            val repository =
                FakeSettingsRepository(
                    computerDetailResult =
                        DataResult.Content(
                            SettingsComputerDetailSnapshot(computer, emptyList(), emptyList(), emptyList())
                        )
                )
            val detail = store(repository, standalone = true, role = case.role)
            detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

            val snapshot = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail).data
            assertEquals(case.expected, snapshot.canEditMachine, "${case.label}: edit")
            assertEquals(case.expected, snapshot.canRemoveMachine, "${case.label}: remove")
            assertEquals(case.expected, snapshot.canManageMachines, "${case.label}: manage")
        }
    }

    @Test
    fun permissionsNeverBorrowAnotherServersRole() = runTest {
        // The session's current server is not in its server list: no role, so no admin authority
        // from the first listed server.
        val session = settingsStoreSampleSession(role = "owner").copy(selectedServerId = "server-9")
        val repository =
            FakeSettingsRepository(
                computerDetailResult =
                    DataResult.Content(
                        SettingsComputerDetailSnapshot(machine("m-1", "Studio"), emptyList(), emptyList(), emptyList())
                    ),
                computersResult = DataResult.Content(computers(machine("m-1", "Studio")))
            )
        val detail =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(session), settingsRepository = repository),
                scope = backgroundScope,
                standaloneNavigation = true
            )
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

        val snapshot = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail).data
        assertEquals(false, snapshot.canEditMachine)
        assertEquals(false, snapshot.canRemoveMachine)
    }

    @Test
    fun addComputerInStandaloneModeOnlyEmitsOpenComputer() = runTest {
        val online = machine("machine-new", "my-computer")
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(computers(online)),
                computerDetailResult =
                    DataResult.Content(SettingsComputerDetailSnapshot(online, emptyList(), emptyList(), emptyList())),
                registerComputerResult =
                    DataResult.Content(
                        SettingsComputerRegistrationResult(
                            machine = online.copy(online = false),
                            apiKey = "test-key-full",
                            apiKeyPrefix = "test-key-ful"
                        )
                    )
            )
        val list = store(repository, standalone = true)
        list.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        list.accept(SharedSettingsIntent.ShowAddComputerDialog)
        list.accept(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest("my-computer")))
        repository.calls.clear()

        list.accept(SharedSettingsIntent.CompleteAddComputerDialog)
        runCurrent() // the store finishes the dialog on its own scope

        assertNull(list.state.value.addComputerDialog)
        assertEquals(SharedSettingsRoute(SharedSettingsDestination.Computers), list.state.value.route)
        assertEquals(SharedSettingsEffect.OpenComputer("machine-new"), list.state.value.effect)
        assertTrue("computerDetail:machine-new" !in repository.calls, "the list store must not load the detail: ${repository.calls}")
    }

    @Test
    fun restartShowsProgressUntilTheComputerReportsDoneThenClears() = runTest {
        val repository = detailRepository()
        repository.restartComputerResult = DataResult.Content(SettingsComputerRestartResult("m-1", requestId = "op-1"))
        val detail = store(repository, standalone = true)
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

        detail.accept(SharedSettingsIntent.RestartComputer("m-1"))
        assertEquals(SettingsComputerRestartProgress("m-1", requestId = "op-1"), detail.state.value.computerControl.restart)
        // A second tap while the restart runs sends nothing.
        detail.accept(SharedSettingsIntent.RestartComputer("m-1"))
        assertEquals(1, repository.calls.count { it == "restartComputer:m-1" })

        repository.calls.clear()
        detail.accept(SharedSettingsIntent.ApplyRealtimeEvent("server-1", ComputerRestartDone("m-1", "op-1", ok = true)))
        assertEquals(SettingsComputerRestartProgress("m-1", requestId = "op-1", done = true), detail.state.value.computerControl.restart)
        assertTrue("computerDetail:m-1" in repository.calls, "a finished restart reloads the machine: ${repository.calls}")

        advanceTimeBy(COMPUTER_RESTART_DONE_DISPLAY_MILLIS + 1)
        assertNull(detail.state.value.computerControl.restart)
    }

    @Test
    fun restartWithoutAnAnswerTimesOutAndARefusalFailsAtOnce() = runTest {
        val repository = detailRepository()
        repository.restartComputerResult = DataResult.Content(SettingsComputerRestartResult("m-1", requestId = "op-1"))
        val detail = store(repository, standalone = true)
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

        detail.accept(SharedSettingsIntent.RestartComputer("m-1"))
        advanceTimeBy(COMPUTER_RESTART_TIMEOUT_MILLIS + 1)
        assertEquals(COMPUTER_RESTART_TIMED_OUT, detail.state.value.computerControl.restart?.errorCode)
        advanceTimeBy(COMPUTER_RESTART_ERROR_DISPLAY_MILLIS + 1)
        assertNull(detail.state.value.computerControl.restart)

        repository.restartComputerResult = DataResult.Error(ScreenError("computer_offline", retryable = false))
        detail.accept(SharedSettingsIntent.RestartComputer("m-1"))
        val refused = detail.state.value.computerControl.restart
        assertEquals("computer_offline", refused?.errorCode)
        assertEquals(true, refused?.failed)
        // A done frame for another request leaves it alone.
        detail.accept(SharedSettingsIntent.ApplyRealtimeEvent("server-1", ComputerRestartDone("m-2", "op-9", ok = true)))
        assertEquals(refused, detail.state.value.computerControl.restart)
    }

    @Test
    fun upgradeKeepsTheAcceptedRequestOrTheRefusalWithItsAvailabilityKey() = runTest {
        val repository = detailRepository()
        val pending =
            SettingsComputerUpgradeRequestSnapshot(id = "req-2", targetVersion = "1.0.50", requestedAt = "2026-10-21T01:00:00.000Z", state = "pending")
        repository.upgradeComputerResult = DataResult.Content(pending)
        val detail = store(repository, standalone = true)
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

        detail.accept(SharedSettingsIntent.UpgradeComputer("m-1", "1.0.50"))
        assertEquals(pending, detail.state.value.computerControl.upgradeAccepted?.request)
        assertNull(detail.state.value.computerControl.upgradeSubmitting)
        assertTrue("upgradeComputer:m-1:1.0.50" in repository.calls)

        repository.upgradeComputerResult = DataResult.Error(ScreenError("remote_upgrade_disabled", retryable = false))
        detail.accept(SharedSettingsIntent.UpgradeComputer("m-1", "1.0.50"))
        val refusal = detail.state.value.computerControl.upgradeRefusal
        assertEquals("remote_upgrade_disabled", refusal?.code)
        assertEquals(settingsComputerUpgradeAvailabilityKey(machine("m-1", "Studio")), refusal?.availabilityKey)
    }

    @Test
    fun catchUpComputersArrivingWhileLoadIsInFlightLatchesTrailingFollowUp() = runTest {
        val repository =
            FakeSettingsRepository(
                computersResult = DataResult.Content(computers(machine("m-1", "Studio").copy(online = false)))
            )
        val store = store(repository, standalone = true)
        val block = BlockingCall()
        repository.computersBlock = block

        store.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        runCurrent()
        // A CatchUpComputers arrives while the initial load is still in flight; it must latch
        // instead of dropping silently.
        store.dispatch(SharedSettingsIntent.CatchUpComputers)
        runCurrent()

        repository.computersBlock = null
        repository.computersResult = DataResult.Content(computers(machine("m-1", "Studio").copy(online = true)))
        block.release()
        runCurrent()

        val loaded = assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data
        assertTrue(loaded.computers.single().online, "trailing catch-up ran after in-flight load and updated status")
        assertEquals(2, repository.calls.count { it == "computers" })
    }

    @Test
    fun workerRuntimesToggleUpdatesOptimisticallySavesWholeListAndAppliesServerMachine() = runTest {
        val initialComputer =
            machine("m-1", "Studio").copy(
                runtimes = listOf("claude", "codex"),
                workerRuntimesTurnedOn = emptyList(),
                canChangeWorkerRuntimes = true,
                workerRuntimesChangeBy = "any_member",
            )
        val repository =
            FakeSettingsRepository(
                computerDetailResult =
                    DataResult.Content(SettingsComputerDetailSnapshot(initialComputer, emptyList(), emptyList(), emptyList()))
            )
        val block = BlockingCall()
        repository.setComputerWorkerRuntimesBlock = block
        repository.setComputerWorkerRuntimesResult =
            DataResult.Content(
                SettingsComputerWorkerRuntimesResult(
                    machineId = "m-1",
                    machine = initialComputer.copy(workerRuntimesTurnedOn = listOf("codex")),
                )
            )
        val detail = store(repository, standalone = true)
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

        detail.dispatch(SharedSettingsIntent.SetComputerWorkerRuntimes("m-1", "codex", enabled = true))
        runCurrent()

        // Applied optimistically at once and switch is marked saving; duplicate click while in flight is ignored.
        val optimistic = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail).data
        assertEquals(listOf("codex"), optimistic.computer.workerRuntimesTurnedOn)
        assertTrue(detail.state.value.computerControl.workerRuntimes.isSaving("m-1", "codex"))
        detail.dispatch(SharedSettingsIntent.SetComputerWorkerRuntimes("m-1", "codex", enabled = false))
        runCurrent()
        assertEquals(1, repository.setComputerWorkerRuntimesRequests.size)
        assertEquals(listOf("codex"), repository.setComputerWorkerRuntimesRequests.single().runtimes)

        block.release()
        runCurrent()

        val saved = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail).data
        assertEquals(listOf("codex"), saved.computer.workerRuntimesTurnedOn)
        assertEquals(false, detail.state.value.computerControl.workerRuntimes.isSaving("m-1", "codex"))
        assertNull(detail.state.value.computerControl.workerRuntimes.errorFor("m-1", "codex"))
    }

    @Test
    fun workerRuntimesRefusalRollsBackAndRecordsInlineErrorUntilNextChange() = runTest {
        val initialComputer =
            machine("m-1", "Studio").copy(
                runtimes = listOf("claude", "codex"),
                workerRuntimesTurnedOn = emptyList(),
                canChangeWorkerRuntimes = true,
                workerRuntimesChangeBy = "any_member",
            )
        val repository =
            FakeSettingsRepository(
                computerDetailResult =
                    DataResult.Content(SettingsComputerDetailSnapshot(initialComputer, emptyList(), emptyList(), emptyList()))
            )
        val refusalMessage =
            "Only an owner or admin of this server can change which runtimes Workers may use on this Computer."
        val block = BlockingCall()
        repository.setComputerWorkerRuntimesBlock = block
        repository.setComputerWorkerRuntimesResult =
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "worker_runtimes_forbidden",
                    requiredCapability = "manage_computer_worker_runtimes",
                    serverMessage = refusalMessage,
                    serverCode = "worker_runtimes_forbidden",
                    traceId = "trace-wr-1",
                )
            )
        val detail = store(repository, standalone = true)
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

        detail.dispatch(SharedSettingsIntent.SetComputerWorkerRuntimes("m-1", "codex", enabled = true))
        runCurrent()
        assertEquals(
            listOf("codex"),
            assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail)
                .data.computer.workerRuntimesTurnedOn
        )

        block.release()
        runCurrent()

        val rolledBack = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail).data
        assertEquals(emptyList(), rolledBack.computer.workerRuntimesTurnedOn)
        assertEquals(false, detail.state.value.computerControl.workerRuntimes.isSaving("m-1", "codex"))
        val rowError = detail.state.value.computerControl.workerRuntimes.errorFor("m-1", "codex")
        assertEquals("worker_runtimes_forbidden", rowError?.code)
        assertEquals(refusalMessage, rowError?.serverMessage)
        assertEquals("trace-wr-1", rowError?.traceId)

        // Next change clears the inline error and commits on success.
        repository.setComputerWorkerRuntimesBlock = null
        repository.setComputerWorkerRuntimesResult =
            DataResult.Content(
                SettingsComputerWorkerRuntimesResult(
                    machineId = "m-1",
                    machine = initialComputer.copy(workerRuntimesTurnedOn = listOf("codex")),
                )
            )
        detail.accept(SharedSettingsIntent.SetComputerWorkerRuntimes("m-1", "codex", enabled = true))
        assertNull(detail.state.value.computerControl.workerRuntimes.errorFor("m-1", "codex"))
        assertEquals(
            listOf("codex"),
            assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail)
                .data.computer.workerRuntimesTurnedOn
        )
    }

    private fun detailRepository() =
        FakeSettingsRepository(
            computerDetailResult =
                DataResult.Content(SettingsComputerDetailSnapshot(machine("m-1", "Studio"), emptyList(), emptyList(), emptyList()))
        )

    private fun kotlinx.coroutines.test.TestScope.store(
        repository: FakeSettingsRepository,
        standalone: Boolean,
        role: String = "owner",
        nowMillis: () -> Long = ::defaultNowMillis,
    ): SharedSettingsStore =
        SharedSettingsStore(
            dataSource =
                SharedSettingsDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession(role)),
                    settingsRepository = repository
                ),
            scope = backgroundScope,
            nowMillis = nowMillis,
            standaloneNavigation = standalone
        )

    private fun listNames(store: SharedSettingsStore): List<String> =
        assertIs<ScreenLoadState.Content<SettingsComputersSnapshot>>(store.state.value.computers).data.computers.map { it.name }

    private fun machinesEvent(serverId: String, machineId: String) =
        RealtimeRefreshRequired(
            scope = RealtimeRefreshScope.Machines,
            reason = "machine:updated",
            serverId = ServerId(serverId),
            subjectId = machineId
        )

    private fun computers(vararg machines: SettingsComputerSnapshot) = SettingsComputersSnapshot(computers = machines.toList())

    private fun machine(id: String, name: String) =
        SettingsComputerSnapshot(id = id, name = name, daemonVersion = "0.51.1", online = true)
}
