package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Add Computer's "The terminal shows a code? Enter it here." end to end through the settings
 * store, the real repository and a scripted network (web botiverse/slock 329e1700f7,
 * `tests/computerAddByCode.behavior.test.tsx`, case for case). Bodies are the server's, staging
 * 23ae82825 `packages/server/src/routes/deviceAuth.ts`: describe's 200, approve's
 * `{ ok, action }` and its refusals (`answerApproval`, `userCodeFailureStatus`), and the 403
 * `second_factor_step_up_required` of routes/secondFactor.ts `refuseUnverifiedApprover`.
 */
class SharedSettingsAddComputerCodeTest {
    private val describePath = DEVICE_LOGIN_DESCRIBE_PATH
    private val approvePath = DEVICE_LOGIN_APPROVE_PATH
    private val approved = """{"ok":true,"action":"approved"}"""
    private val trace = mapOf("X-Trace-Id" to listOf("4bf92f3577b34da6a3ce929d0e0e4736"))

    @Test
    fun aCodeForThisServerIsApprovedHereAndTheSheetKeepsWaiting() = runTest {
        val network =
            scripted {
                respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT))
                respond("POST", approvePath, 200, approved)
            }
        val store = openCodeField(network)
        assertTrue(store.code.open)

        store.enterCode(" vswa-7m58 ")

        // Normalized as the device page does, in the body only.
        val describe = network.posts(describePath).single()
        assertEquals(DataRequestPriority.Foreground, describe.priority)
        assertEquals("VSWA-7M58", describe.json().getValue("userCode").jsonPrimitive.content)
        val review = assertIs<AddComputerCodeStep.Confirm>(store.code.step)
        // This sheet's server, as describe lists it: nothing to pick, Beta Labs never offered.
        assertEquals(DeviceLoginServer("server-1", "slock", "Slock Community"), review.target)
        assertEquals("build-box", review.request.grant?.machineName)

        store.accept(SharedSettingsIntent.ApproveAddComputerCode)

        val approve = network.posts(approvePath).single().json()
        assertEquals("VSWA-7M58", approve.getValue("userCode").jsonPrimitive.content)
        assertTrue(approve.getValue("approve").jsonPrimitive.boolean)
        assertEquals("ticket-1", approve.getValue("approvalTicket").jsonPrimitive.content)
        assertEquals("server-1", approve.getValue("serverId").jsonPrimitive.content)
        val done = assertIs<AddComputerCodeStep.Approved>(store.code.step)
        assertEquals(AddComputerCodeOutcome(migrate = false, machineName = "build-box", serverName = "Slock Community"), done.outcome)
        // Still the command step, waiting for the computer to connect.
        assertEquals(AddComputerStep.Waiting, store.state.value.addComputerDialog?.step)
    }

    @Test
    fun aCodeThatCantBeApprovedSaysWhyInTheFieldBeforeAnyApproval() = runTest {
        listOf(
            Triple(410, "expired", AddComputerCodeProblem.CodeExpired),
            Triple(404, "user_code_invalid", AddComputerCodeProblem.CodeInvalid),
            Triple(409, "already_resolved", AddComputerCodeProblem.CodeAlreadyUsed),
        ).forEach { (status, code, problem) ->
            val network =
                scripted {
                    respond("POST", describePath, status, """{"error":"Device authorization can't be approved","code":"$code"}""", trace)
                }
            val store = openCodeField(network)
            store.enterCode()

            val field = store.code
            assertEquals(AddComputerCodeStep.Enter, field.step, code)
            assertEquals(problem, field.error?.problem, code)
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", field.error?.traceId, code)
            assertFalse(field.error?.offersDevicePage == true, code)
            assertTrue(field.open, code)
            assertEquals(listOf(describePath), network.posts().map { it.path }, code)
        }
    }

    @Test
    fun aCodeForAnotherServerOrForAnythingButAComputerOffersTheDeviceLoginPageWithoutTheCode() = runTest {
        listOf(
            "another server" to DEVICE_CODE_OTHER_SERVER_GRANT,
            "a sign-in" to null,
            "an agent key" to DEVICE_CODE_AGENT_KEY_GRANT,
        ).forEach { (label, grant) ->
            val network = scripted { respond("POST", describePath, 200, deviceCodeDescribeBody(grant = grant)) }
            val store = openCodeField(network)
            store.enterCode()

            assertNull(assertIs<AddComputerCodeStep.Confirm>(store.code.step, label).target, label)
            // Nothing to approve or deny here.
            store.accept(SharedSettingsIntent.ApproveAddComputerCode)
            store.accept(SharedSettingsIntent.DenyAddComputerCode)
            assertEquals(listOf(describePath), network.posts().map { it.path }, label)

            store.accept(SharedSettingsIntent.OpenAddComputerDeviceLoginPage)
            assertNull(store.state.value.addComputerDialog, "$label: the sheet closes, as the web window does")
            val page = assertIs<SharedSettingsEffect.OpenDeviceLoginPage>(store.state.value.effect, label)
            assertEquals("https://app.raft.build/login/device", page.url, label)
            assertFalse("VSWA" in page.url, label)
        }
    }

    @Test
    fun aGrantKindTheAppDoesntKnowIsRefusedWithTheDeviceLoginPageOffered() = runTest {
        val network = scripted { respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_UNKNOWN_GRANT)) }
        val store = openCodeField(network)
        store.enterCode()

        assertEquals(AddComputerCodeStep.Enter, store.code.step)
        assertEquals(AddComputerCodeProblem.KindUnsupported, store.code.error?.problem)
        assertTrue(store.code.error?.offersDevicePage == true)
    }

    @Test
    fun twoStepVerificationAsksForTheSecondStepThenApprovesTheSameRequest() = runTest {
        val network =
            scripted {
                respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT))
                respond("POST", approvePath, 403, STEP_UP_REFUSAL)
                respond("POST", approvePath, 200, approved)
                respond("POST", "auth/second-factor/step-up", 200, """{"verified":true}""")
            }
        val store = openCodeField(network)
        store.enterCode()

        launch { store.accept(SharedSettingsIntent.ApproveAddComputerCode) }
        runCurrent()

        val dialog = assertNotNull(store.state.value.secondFactorStepUp, "Verify it's you opens over the sheet")
        assertTrue(SecondFactorStepUpMethod.Totp in dialog.methods)
        // The confirm screen waits under it, approving, with no server sentence of its own.
        assertIs<AddComputerCodeStep.Confirm>(store.code.step)
        assertEquals(AddComputerCodeSubmitting.Approve, store.code.submitting)
        assertNull(store.code.error)

        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.Totp)))
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.InputChanged("123456")))
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Submit))
        runCurrent()

        assertNull(store.state.value.secondFactorStepUp)
        assertIs<AddComputerCodeStep.Approved>(store.code.step)
        assertEquals(listOf(describePath, approvePath, "auth/second-factor/step-up", approvePath), network.posts().map { it.path })
        val retried = network.posts(approvePath).last().json()
        assertEquals("ticket-1", retried.getValue("approvalTicket").jsonPrimitive.content)
        assertEquals("server-1", retried.getValue("serverId").jsonPrimitive.content)
    }

    @Test
    fun closingVerifyItsYouLeavesTheConfirmScreenAsItWas() = runTest {
        val network =
            scripted {
                respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT))
                respond("POST", approvePath, 403, STEP_UP_REFUSAL)
            }
        val store = openCodeField(network)
        store.enterCode()
        launch { store.accept(SharedSettingsIntent.ApproveAddComputerCode) }
        runCurrent()
        assertNotNull(store.state.value.secondFactorStepUp)

        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Cancel))
        runCurrent()

        assertNull(store.state.value.secondFactorStepUp)
        assertIs<AddComputerCodeStep.Confirm>(store.code.step)
        assertNull(store.code.submitting)
        assertNull(store.code.error)
        assertEquals(1, network.posts(approvePath).size, "nothing approved")
    }

    @Test
    fun denySendsNoTicketAndNoServerAndNothingIsAdded() = runTest {
        val network =
            scripted {
                respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT))
                respond("POST", approvePath, 200, """{"ok":true,"action":"denied"}""")
            }
        val store = openCodeField(network)
        store.enterCode()
        store.accept(SharedSettingsIntent.DenyAddComputerCode)

        val deny = network.posts(approvePath).single().json()
        assertEquals(setOf("userCode", "approve"), deny.keys)
        assertFalse(deny.getValue("approve").jsonPrimitive.boolean)
        assertEquals(AddComputerCodeStep.Denied("Slock Community"), store.code.step)
    }

    @Test
    fun aDifferentCodeAfterAConfirmScreenApprovesWithTheNewCodesOwnTicket() = runTest {
        val network =
            scripted {
                respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT))
                respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT, userCode = "WXYZ-2345", ticket = "ticket-2"))
                respond("POST", approvePath, 200, approved)
            }
        val store = openCodeField(network)
        store.enterCode()
        store.accept(SharedSettingsIntent.ResetAddComputerCode)

        // Back to an empty, open field, the old request gone.
        assertEquals(AddComputerCodeStep.Enter, store.code.step)
        assertEquals("", store.code.code)
        assertTrue(store.code.open)

        store.enterCode("wxyz-2345")
        store.accept(SharedSettingsIntent.ApproveAddComputerCode)

        assertEquals(listOf(describePath, describePath, approvePath), network.posts().map { it.path })
        val approve = network.posts(approvePath).single().json()
        assertEquals("WXYZ-2345", approve.getValue("userCode").jsonPrimitive.content)
        assertEquals("ticket-2", approve.getValue("approvalTicket").jsonPrimitive.content)
    }

    @Test
    fun rightsGoneBetweenDescribeAndApproveKeepTheConfirmScreenAndOfferTheDeviceLoginPage() = runTest {
        val network =
            scripted {
                respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT))
                respond("POST", approvePath, 403, """{"error":"You can't approve this for that choice.","code":"grant_selection_not_allowed"}""", trace)
            }
        val store = openCodeField(network)
        store.enterCode()
        store.accept(SharedSettingsIntent.ApproveAddComputerCode)

        assertIs<AddComputerCodeStep.Confirm>(store.code.step)
        val error = assertNotNull(store.code.error)
        assertEquals(AddComputerCodeProblem.ServerSelectionNotAllowed, error.problem)
        assertTrue(error.offersDevicePage)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", error.traceId)
    }

    @Test
    fun anAnswerForAClosedSheetNeverLandsOnTheNextOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        val network = scripted { respond("POST", describePath, 200, deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT)) }
        network.hold("POST", describePath, gate)
        val store = openCodeField(network)
        store.accept(SharedSettingsIntent.SetAddComputerCode("vswa-7m58"))
        launch { store.accept(SharedSettingsIntent.SubmitAddComputerCode) }
        runCurrent()
        assertEquals(AddComputerCodeSubmitting.Describe, store.code.submitting)

        store.accept(SharedSettingsIntent.DismissAddComputerDialog)
        store.accept(SharedSettingsIntent.ShowAddComputerDialog)
        gate.complete(Unit)
        runCurrent()

        assertEquals(AddComputerCodeState(), store.code, "the new sheet's field is untouched")
    }

    private fun scripted(script: ScriptedAgentControlsNetworkClient.() -> Unit): ScriptedAgentControlsNetworkClient =
        ScriptedAgentControlsNetworkClient().apply {
            respond("GET", "servers/server-1/machines", 200, """{"machines":[]}""")
            script()
        }

    /** The Computers page with Add Computer on its command, the code field open. */
    private suspend fun TestScope.openCodeField(network: RaftNetworkClient): SharedSettingsStore {
        val sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession())
        val store =
            SharedSettingsStore(
                dataSource = SharedSettingsDataSource(sessionStore = sessionStore, settingsRepository = RealSettingsRepository(sessionStore, network)),
                scope = backgroundScope,
            )
        store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
        store.accept(SharedSettingsIntent.ShowAddComputerDialog)
        store.accept(SharedSettingsIntent.SetAddComputerStep(AddComputerStep.Waiting))
        store.accept(SharedSettingsIntent.ToggleAddComputerCode)
        runCurrent()
        return store
    }

    private suspend fun SharedSettingsStore.enterCode(code: String = " vswa-7m58 ") {
        accept(SharedSettingsIntent.SetAddComputerCode(code))
        accept(SharedSettingsIntent.SubmitAddComputerCode)
    }

    private val SharedSettingsStore.code: AddComputerCodeState
        get() = assertNotNull(state.value.addComputerDialog, "Add Computer is open").code

    private fun ScriptedAgentControlsNetworkClient.posts(path: String? = null): List<AgentControlsRecordedRequest> =
        requests.filter { it.method == "POST" && (path == null || it.path == path) }

    private fun AgentControlsRecordedRequest.json(): JsonObject = Json.parseToJsonElement(assertNotNull(bodyText)).jsonObject

    private companion object {
        /** routes/secondFactor.ts refuseUnverifiedApprover's default error, with the person's methods. */
        const val STEP_UP_REFUSAL =
            """{"error":"Confirm it's you with your second step before approving this sign-in.","code":"second_factor_step_up_required","methods":["totp","backup_code"]}"""
    }
}
