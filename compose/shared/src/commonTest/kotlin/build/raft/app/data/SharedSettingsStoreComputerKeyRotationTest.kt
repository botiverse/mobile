package build.raft.app.data

import build.raft.app.data.SharedSettingsStoreTest.FakeSettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rotate API key on the native Computer detail page: the store shows the full new key once, keeps
 * it out of every printed form, drops it when the dialog closes, and keeps the page on failure.
 */
class SharedSettingsStoreComputerKeyRotationTest {
    @Test
    fun rotationPatchesThePrefixAndShowsTheFullKeyUntilTheDialogCloses() = runTest {
        val repository = repository(rotateResult = DataResult.Content(rotation("m-1")))
        val detail = store(repository)
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))

        detail.accept(SharedSettingsIntent.RotateComputerKey("m-1"))

        // Only the rotation: the new prefix is patched into the loaded detail, no reload.
        assertEquals(listOf("computerDetail:m-1", "rotateComputerKey:m-1"), repository.calls)
        val state = detail.state.value
        assertEquals(SettingsComputerRotatedKey("m-1", SettingsComputerApiKey(FakeKey)), state.rotatedComputerKey)
        val computer = assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(state.computerDetail).data.computer
        assertEquals(FakeKey.take(20), computer.apiKeyPrefix)
        assertEquals("Studio", computer.name)
        assertEquals(SharedSettingsActionState(COMPUTER_ACTION_ROTATE_KEY, "m-1", inProgress = false), state.actionState)

        detail.accept(SharedSettingsIntent.DismissRotatedComputerKey)

        assertNull(detail.state.value.rotatedComputerKey)
        // The prefix stays; only the one-time key is gone.
        assertEquals(
            FakeKey.take(20),
            assertIs<ScreenLoadState.Content<SettingsComputerDetailSnapshot>>(detail.state.value.computerDetail).data.computer.apiKeyPrefix
        )
    }

    @Test
    fun theKeyNeverReachesPrintedStateIntentsTransitionsOrDiagnostics() = runTest {
        val diagnostics = mutableListOf<String>()
        val transitions = mutableListOf<String>()
        val repository = repository(rotateResult = DataResult.Content(rotation("m-1")))
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), settingsRepository = repository),
                scope = backgroundScope,
                diagnosticsLogger = { diagnostics += it },
                standaloneNavigation = true
            )
        val contract = SharedSettingsStoreContract(store, backgroundScope) { transitions += it.toString() }

        contract.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))
        runCurrent()
        contract.dispatch(SharedSettingsIntent.RotateComputerKey("m-1"))
        runCurrent()

        val shown = assertNotNull(store.state.value.rotatedComputerKey, "the rotation must reach the state")
        assertEquals(FakeKey, shown.apiKey.value)
        val printed =
            listOf(
                store.state.value.toString(),
                contract.state.value.toString(),
                shown.toString(),
                rotation("m-1").toString(),
                SharedSettingsIntent.RotateComputerKey("m-1").toString(),
                SharedSettingsIntent.DismissRotatedComputerKey.toString(),
            ) + transitions + diagnostics
        assertTrue(transitions.isNotEmpty(), "the contract logs its transitions")
        printed.forEach { line -> assertTrue(FakeKey !in line, "the key leaked into: $line") }
        assertTrue("<redacted>" in store.state.value.toString(), "the state prints a redaction marker instead")
    }

    @Test
    fun failureKeepsThePageAndAttachesTheActionError() = runTest {
        val repository =
            repository(rotateResult = DataResult.Error(ScreenError("server_forbidden", retryable = false)))
        val detail = store(repository)
        detail.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))
        val before = detail.state.value.computerDetail

        detail.accept(SharedSettingsIntent.RotateComputerKey("m-1"))

        assertEquals(before, detail.state.value.computerDetail)
        assertNull(detail.state.value.rotatedComputerKey)
        val action = assertNotNull(detail.state.value.actionState)
        assertEquals(COMPUTER_ACTION_ROTATE_KEY, action.action)
        assertEquals("m-1", action.itemId)
        assertEquals(false, action.inProgress)
        assertEquals("server_forbidden", action.screenError?.code)
    }

    @Test
    fun leavingTheMachineOrTheAccountDropsTheKey() = runTest {
        data class Case(val label: String, val leave: suspend (SharedSettingsStore, EventingAuthStore) -> Unit)
        val cases =
            listOf(
                Case("another machine") { store, _ ->
                    store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-2"))
                },
                Case("sign-out") { _, auth -> auth.emit(AuthSessionEvent.Cleared(1, AuthSessionClearReason.Logout)) },
                Case("another account") { _, auth -> auth.emit(AuthSessionEvent.Authenticated(1)) },
                Case("store cleared") { store, _ -> store.clear() },
            )
        cases.forEach { case ->
            val auth = EventingAuthStore(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()))
            val store =
                SharedSettingsStore(
                    dataSource =
                        SharedSettingsDataSource(
                            auth,
                            settingsRepository = repository(rotateResult = DataResult.Content(rotation("m-1")))
                        ),
                    scope = backgroundScope,
                    standaloneNavigation = true
                )
            runCurrent() // the store subscribes to the auth events
            store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))
            store.accept(SharedSettingsIntent.RotateComputerKey("m-1"))
            assertNotNull(store.state.value.rotatedComputerKey, "${case.label}: shown first")

            case.leave(store, auth)
            runCurrent()

            assertNull(store.state.value.rotatedComputerKey, "${case.label}: the key must be dropped")
        }
    }

    @Test
    fun reloadingTheSameMachineOrATokenRefreshKeepsTheDialog() = runTest {
        val auth = EventingAuthStore(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()))
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(auth, settingsRepository = repository(rotateResult = DataResult.Content(rotation("m-1")))),
                scope = backgroundScope,
                standaloneNavigation = true
            )
        runCurrent()
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))
        store.accept(SharedSettingsIntent.RotateComputerKey("m-1"))

        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, "m-1"))
        store.accept(SharedSettingsIntent.Refresh)
        auth.emit(AuthSessionEvent.TokensRotated(1))
        runCurrent()

        assertEquals(FakeKey, store.state.value.rotatedComputerKey?.apiKey?.value)
    }

    private fun TestScope.store(repository: FakeSettingsRepository): SharedSettingsStore =
        SharedSettingsStore(
            dataSource = SharedSettingsDataSource(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), settingsRepository = repository),
            scope = backgroundScope,
            standaloneNavigation = true
        )

    private fun repository(rotateResult: DataResult<SettingsComputerKeyRotationResult>) =
        FakeSettingsRepository(
            computerDetailResult =
                DataResult.Content(
                    SettingsComputerDetailSnapshot(
                        SettingsComputerSnapshot(id = "m-1", name = "Studio", daemonVersion = "0.51.1", online = true, apiKeyPrefix = "sk_machine_old00000"),
                        emptyList(),
                        emptyList(),
                        emptyList()
                    )
                ),
            rotateComputerKeyResult = rotateResult
        )

    private fun rotation(machineId: String) =
        SettingsComputerKeyRotationResult(
            machineId = machineId,
            apiKeyPrefix = FakeKey.take(20),
            apiKey = SettingsComputerApiKey(FakeKey)
        )

    class EventingAuthStore(delegate: InMemoryRaftAuthSessionStore) : RaftAuthSessionStore by delegate, RaftAuthSessionEventSource {
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 4)
        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        suspend fun emit(event: AuthSessionEvent) = events.emit(event)
    }

    private companion object {
        /** `sk_machine_` + 64 hex, the server's key shape; a fake. */
        val FakeKey = "sk_machine_" + "fedcba9876543210".repeat(4)
    }
}
