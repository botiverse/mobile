package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Add computer with two-step verification on. Bodies are the server's, botiverse/slock staging
 * 2f7ce385caede57e0866ce50bd38f04a84c3c15b: routes/servers.ts `POST /:id/machines` calls
 * routes/secondFactor.ts `refuseWithoutRecentSecondFactor(..., "machine_key_register")` before
 * anything is created, answering 403 with services/secondFactor/stepUpGate.ts
 * `STEP_UP_ACTIONS.machine_key_register` and the person's methods.
 */
class SharedSettingsComputerStepUpTest {
    private val machinesPath = "servers/server-1/machines"
    private val registerRefusal =
        """{"error":"Confirm it's you with your second step before registering a machine.","code":"second_factor_step_up_required","methods":["totp","passkey","backup_code"]}"""
    private val registered =
        """{"machine":{"id":"machine-new","name":"my-computer","status":"offline","statusVersion":0,"apiKeyPrefix":"sk_machine_123456","runtimes":[],"daemonVersion":null,"hostname":null,"os":null,"lastHeartbeat":null,"createdAt":null},"apiKey":"sk_machine_123456789"}"""

    @Test
    fun theSharedHelperMapsOnlyAStepUpRefusal() {
        val error =
            assertIs<DataResult.Error>(
                HomeNetworkResponse(code = 403, bodyText = null, errorBodyText = registerRefusal).secondFactorStepUpErrorOrNull<Unit>()
            ).error
        assertEquals(SECOND_FACTOR_STEP_UP_REQUIRED_CODE, error.code)
        assertEquals(SECOND_FACTOR_STEP_UP_REQUIRED_CODE, error.serverCode)
        assertEquals(listOf("totp", "passkey", "backup_code"), error.secondFactorStepUp?.methods)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), error.secondFactorStepUp?.usableMethods)

        assertNull(
            HomeNetworkResponse(code = 403, bodyText = null, errorBodyText = """{"error":"Forbidden","code":"forbidden"}""")
                .secondFactorStepUpErrorOrNull<Unit>()
        )
        assertNull(HomeNetworkResponse(code = 400, bodyText = null, errorBodyText = registerRefusal).secondFactorStepUpErrorOrNull<Unit>())
    }

    @Test
    fun aRegisterRefusalIsAStepUpNotAPermissionBlocker() = runTest {
        val network = ScriptedAgentControlsNetworkClient().apply { respond("POST", machinesPath, 403, registerRefusal) }
        val client = RaftComputersApiClient(RaftComputersNetworkApi(network))

        val refused = assertIs<DataResult.Error>(client.registerMachine(ServerId("server-1"), "my-computer"))
        assertEquals(listOf("totp", "passkey", "backup_code"), refused.error.secondFactorStepUp?.methods)

        // Any other 403 of the computers routes is still the permission blocker it was.
        val forbidden = ScriptedAgentControlsNetworkClient().apply { respond("POST", machinesPath, 403, """{"error":"Only admins can add computers"}""") }
        val blocked = assertIs<DataResult.NoPermission>(RaftComputersApiClient(RaftComputersNetworkApi(forbidden)).registerMachine(ServerId("server-1"), "my-computer"))
        assertEquals("server_forbidden", blocked.blocker.code)
    }

    @Test
    fun addComputerAsksForTheSecondStepThenRegistersOnceMore() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", machinesPath, 200, """{"machines":[]}""")
                respond("POST", machinesPath, 403, registerRefusal)
                respond("POST", machinesPath, 201, registered)
                respond("POST", "auth/second-factor/step-up", 200, """{"verified":true}""")
            }
        val store = openAddComputer(network)

        launch { store.accept(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest("my-computer"))) }
        runCurrent()

        val dialog = assertNotNull(store.state.value.secondFactorStepUp, "Verify it's you opens on the refusal")
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), dialog.methods)
        assertEquals(SecondFactorStepUpMethod.Totp, dialog.method)
        val sheet = assertNotNull(store.state.value.addComputerDialog)
        assertTrue(sheet.registering, "the sheet keeps Setting up while the second step is asked")
        // Never the server's English sentence on the sheet.
        assertNull(sheet.screenError)
        assertNull(sheet.permissionBlocker)
        assertEquals(AddComputerStep.Type, sheet.step)

        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.InputChanged("123456")))
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Submit))
        runCurrent()

        assertNull(store.state.value.secondFactorStepUp)
        val waiting = assertNotNull(store.state.value.addComputerDialog)
        assertEquals(AddComputerStep.Waiting, waiting.step)
        assertEquals("machine-new", waiting.registeredMachineId)
        assertFalse(waiting.registering)
        assertNull(waiting.screenError)
        assertEquals(
            listOf("POST $machinesPath", "POST auth/second-factor/step-up", "POST $machinesPath"),
            network.requests.filter { it.method == "POST" }.map { "${it.method} ${it.path}" }
        )
    }

    @Test
    fun closingVerifyItsYouLeavesTheSheetAsItWas() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", machinesPath, 200, """{"machines":[]}""")
                respond("POST", machinesPath, 403, registerRefusal)
            }
        val store = openAddComputer(network)

        launch { store.accept(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest("my-computer"))) }
        runCurrent()
        assertNotNull(store.state.value.secondFactorStepUp)

        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Cancel))
        runCurrent()

        assertNull(store.state.value.secondFactorStepUp)
        val sheet = assertNotNull(store.state.value.addComputerDialog)
        assertEquals(AddComputerStep.Type, sheet.step)
        assertFalse(sheet.registering)
        assertNull(sheet.screenError)
        assertNull(sheet.permissionBlocker)
        assertNull(store.state.value.actionState?.errorCode)
        assertEquals(listOf("POST $machinesPath"), network.requests.filter { it.method == "POST" }.map { "${it.method} ${it.path}" })
    }

    private suspend fun TestScope.openAddComputer(network: RaftNetworkClient): SharedSettingsStore {
        val sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession())
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = sessionStore,
                        settingsRepository = RealSettingsRepository(sessionStore, network)
                    ),
                scope = backgroundScope
            )
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        store.accept(SharedSettingsIntent.ShowAddComputerDialog)
        runCurrent()
        return store
    }
}
