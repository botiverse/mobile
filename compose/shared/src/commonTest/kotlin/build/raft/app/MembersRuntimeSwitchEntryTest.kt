package build.raft.app

import build.raft.app.data.AgentResetMode
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.SharedCreateAgentRuntimeCatalog
import build.raft.app.data.SharedCreateAgentRuntimeOption
import build.raft.app.data.buildSharedAgentProfileUpdateBody
import build.raft.app.data.runtimeform.v2.BUILTIN_V2_FORM_JSON
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.members.MembersRuntimeFormV2EditLoad
import build.raft.app.members.loadMembersRuntimeFormV2Edit
import build.raft.app.members.membersRuntimeFormV2EditRuntimeSelection
import build.raft.app.members.membersRuntimeFormV2EditRuntimeSwitched
import build.raft.app.members.membersRuntimeFormV2EditSaveEnabled
import build.raft.app.members.membersRuntimeFormV2SaveNeedsSwitchConfirmation
import build.raft.app.members.membersRuntimeFormV2UpdateRequest
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #8 (retire runtime form v1, artin / HaoHao #wg-runtime-form f03a0c5a): switching an
 * existing agent's runtime in the v2 edit dialog loads the new runtime's v2 create form and
 * saves it as a confirmed runtime switch; a failed load stays in v2 with Retry. Mirrors web
 * botiverse/slock #11754 / #11758.
 */
class MembersRuntimeSwitchEntryTest {
    @Test
    fun v2EditRuntimeOptionsAreTheComputerRuntimesWithNotInstalledDisabled() {
        val items =
            runtimePickerRows(
                build.raft.app.data.BundledRuntimeCatalog,
                machineRuntimeIds = listOf("builtin", "codex"),
                selectedRuntimeId = "builtin",
                showAll = true,
                currentRuntimeId = "builtin"
            ).rows
        val enabled = items.filter { it.enabled }.map { it.id }.toSet()
        assertTrue("builtin" in enabled && "codex" in enabled, "items=$items")
        assertEquals(RuntimePickerGroup.Current, items.first().group)
        val claude = items.single { it.id == "claude" }
        assertEquals(false, claude.enabled)
        assertEquals(RuntimePickerSuffix.NotInstalled, claude.suffix)
    }

    @Test
    fun pickingAnotherRuntimeMovesTheSelectAndCountsAsASwitch() {
        assertEquals("builtin", membersRuntimeFormV2EditRuntimeSelection(currentRuntimeId = "codex", picked = "builtin"))
        assertTrue(membersRuntimeFormV2EditRuntimeSwitched(agentRuntimeId = "codex", selectedRuntimeId = "builtin"))
        // Back to the agent's own runtime: its edit form again, not a switch.
        assertEquals("codex", membersRuntimeFormV2EditRuntimeSelection(currentRuntimeId = "builtin", picked = "codex"))
        assertFalse(membersRuntimeFormV2EditRuntimeSwitched(agentRuntimeId = "codex", selectedRuntimeId = "codex"))
    }

    @Test
    fun aSwitchedRuntimeLoadsThatRuntimesCreateFormWithoutTheAgent() = runBlocking {
        val computers = FormComputersApi()
        val agents = FormAgentsApi(editCode = 200)
        val load = loadMembersRuntimeFormV2Edit(access(computers, agents), "m-1", AGENT, "codex", "builtin", catalogWith("builtin" to true))

        val ready = assertIs<MembersRuntimeFormV2EditLoad.Ready>(load)
        assertEquals("builtin", ready.loaded.form.runtimeId)
        assertEquals(listOf("builtin"), computers.formRequests, "GET …/machines/m-1/runtime-forms/v2/builtin")
        assertEquals(emptyList(), agents.editRequests, "the agent's own edit form is not requested")
    }

    @Test
    fun theAgentsOwnRuntimeLoadsItsEditForm() = runBlocking {
        val computers = FormComputersApi()
        val agents = FormAgentsApi(editCode = 503)
        loadMembersRuntimeFormV2Edit(access(computers, agents), "m-1", AGENT, "codex", "codex", catalogWith())

        assertEquals(listOf(AGENT), agents.editRequests)
        assertEquals(emptyList(), computers.formRequests)
    }

    @Test
    fun anEditLoadFailureStaysInV2AsAFailureWithItsCode() = runBlocking {
        val load = loadMembersRuntimeFormV2Edit(access(FormComputersApi(), FormAgentsApi(editCode = 503)), "m-1", AGENT, "codex", "codex", catalogWith())
        assertEquals(MembersRuntimeFormV2EditLoad.Failed("runtime_form_v2_http_503"), load)
    }

    @Test
    fun saveIsDisabledUntilTheFormHasLoaded() {
        assertFalse(save(formReady = false, runtimeSwitched = true, valuesChanged = true))
        assertFalse(save(formReady = false, runtimeSwitched = false, valuesChanged = true))
    }

    @Test
    fun aStoppedAgentsSwitchSavesWithASessionResetAndTheNewRuntimesFormRef() {
        val submit = RuntimeFormV2SubmitBody(runtimeId = "claude", formValues = buildJsonObject { put("model", JsonPrimitive("opus")) })
        for (active in listOf(true, false)) {
            val request = membersRuntimeFormV2UpdateRequest(AGENT, "Agent", null, submit, runtimeSwitched = true, activeAgent = active)
            val body = Json.parseToJsonElement(buildSharedAgentProfileUpdateBody(request)).jsonObject
            if (active) {
                // botiverse/slock #13372: a running agent switches when it is next idle, not now.
                assertNull(request.runtimeResetMode)
                assertFalse("restartMode" in body, "a running agent's switch carries no restart")
            } else {
                assertEquals(AgentResetMode.Session, request.runtimeResetMode)
                assertEquals("session", body.getValue("restartMode").jsonPrimitive.content)
            }
            assertEquals(
                buildJsonObject {
                    put("protocolVersion", JsonPrimitive(2))
                    put("runtimeId", JsonPrimitive("claude"))
                },
                body.getValue("formDefinitionRef")
            )
            assertEquals(buildJsonObject { put("model", JsonPrimitive("opus")) }, body.getValue("formValues"))
            assertFalse("runtimeConfig" in body, "the server assembles the config from the form")
        }
    }

    @Test
    fun aSameRuntimeSaveNeverRestarts() {
        // botiverse/slock #13372: a running agent's change applies when it is next idle.
        val submit = RuntimeFormV2SubmitBody(runtimeId = "codex", formValues = buildJsonObject {})
        for (active in listOf(true, false)) {
            val request = membersRuntimeFormV2UpdateRequest(AGENT, "Agent", null, submit, runtimeSwitched = false, activeAgent = active)
            assertNull(request.runtimeResetMode, "activeAgent=$active")
            assertFalse("restartMode" in Json.parseToJsonElement(buildSharedAgentProfileUpdateBody(request)).jsonObject)
        }
    }

    @Test
    fun onlyAStoppedAgentsRuntimeSwitchIsConfirmed() {
        assertTrue(membersRuntimeFormV2SaveNeedsSwitchConfirmation(runtimeSwitched = true, activeAgent = false))
        assertFalse(membersRuntimeFormV2SaveNeedsSwitchConfirmation(runtimeSwitched = true, activeAgent = true))
        assertFalse(membersRuntimeFormV2SaveNeedsSwitchConfirmation(runtimeSwitched = false, activeAgent = false))
        assertFalse(membersRuntimeFormV2SaveNeedsSwitchConfirmation(runtimeSwitched = false, activeAgent = true))
    }

    private fun save(
        formReady: Boolean,
        runtimeSwitched: Boolean,
        valuesChanged: Boolean,
        hasErrors: Boolean = false,
        saving: Boolean = false
    ) = membersRuntimeFormV2EditSaveEnabled(saving, formReady, runtimeSwitched, valuesChanged, hasErrors)

    private fun catalogWith(vararg rows: Pair<String, Boolean>): suspend (String) -> SharedCreateAgentRuntimeCatalog? = { machineId ->
        SharedCreateAgentRuntimeCatalog(
            machineId = machineId,
            options =
                rows.map { (runtimeId, marker) ->
                    SharedCreateAgentRuntimeOption(
                        runtimeId = runtimeId,
                        capabilityStatus = "available",
                        admissionStatus = "available_for_new",
                        admissionReason = null,
                        current = false,
                        availableForNew = true,
                        manageableForCurrentAgent = false,
                        canSelectInThisContext = true,
                        runtimeFormV2 = marker
                    )
                }
        )
    }

    private fun access(computers: RaftComputersApi, agents: RaftAgentsApi) =
        RuntimeFormV2Access(RuntimeFormV2Loader(computers, agents), ServerId("server-1"))

    private companion object {
        val AGENT = MemberId("agent-1")
    }
}

private class FormComputersApi(private val formCode: Int = 200) : RaftComputersApi {
    val formRequests = mutableListOf<String>()

    override suspend fun runtimeFormV2(serverId: ServerId, machineId: String, runtimeId: String): HomeNetworkResponse {
        formRequests += runtimeId
        return HomeNetworkResponse(code = formCode, bodyText = if (formCode == 200) BUILTIN_V2_FORM_JSON else "{}")
    }

    override suspend fun runtimeFormV2OptionSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String,
        refresh: Boolean,
        formRevision: String?
    ): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                """{"sourceId":"$sourceId","kind":"select","pointer":"/$sourceId",""" +
                    """"options":[{"value":"deepseek","label":"DeepSeek"}]}"""
        )

    override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
    override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
    override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
        error("unused")
}

private class FormAgentsApi(private val editCode: Int) : RaftAgentsApi {
    val editRequests = mutableListOf<MemberId>()

    override suspend fun agentRuntimeFormV2(agentId: MemberId): HomeNetworkResponse {
        editRequests += agentId
        return HomeNetworkResponse(code = editCode, bodyText = if (editCode == 200) BUILTIN_V2_FORM_JSON else "{}")
    }

    override suspend fun agents(): HomeNetworkResponse = error("unused")
    override suspend fun agent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun createAgent(body: String): HomeNetworkResponse = error("unused")
    override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun deleteAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse = error("unused")
    override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse = error("unused")
    override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse = error("unused")
    override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
    override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
}
