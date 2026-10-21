package build.raft.app.data

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
    fun realtimeEventOfAnotherServerLeavesTheListAlone() = runTest {
        val repository = FakeSettingsRepository(computersResult = DataResult.Content(computers(machine("m-1", "Studio"))))
        val list = store(repository, standalone = true)
        list.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        repository.calls.clear()

        list.accept(SharedSettingsIntent.ApplyRealtimeEvent("server-1", machinesEvent("server-2", "m-1")))

        assertEquals(emptyList(), repository.calls)
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
    fun detailRefreshForcesTheComputersFetch() = runTest {
        val computersSource =
            FakeSettingsComputersSource(computerResult = DataResult.Content(machine("m-1", "Studio")))
        val detail =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession()),
                        settingsRepository = FakeSettingsRepository(),
                        computersSource = computersSource
                    ),
                scope = backgroundScope,
                standaloneNavigation = true
            )

        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))
        detail.accept(SharedSettingsIntent.Refresh)

        assertEquals(
            listOf("sharedComputer:server-1:m-1:false", "sharedComputer:server-1:m-1:true"),
            computersSource.calls
        )
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
    fun copyingACommandEmitsTheCopyEffect() = runTest {
        val detail = store(detailRepository(), standalone = true)

        detail.accept(SharedSettingsIntent.CopyComputerCommand(" raft-computer status "))

        assertEquals(SharedSettingsEffect.CopyText("raft-computer status"), detail.state.value.effect)
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
    ): SharedSettingsStore =
        SharedSettingsStore(
            dataSource =
                SharedSettingsDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession(role)),
                    settingsRepository = repository
                ),
            scope = backgroundScope,
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
