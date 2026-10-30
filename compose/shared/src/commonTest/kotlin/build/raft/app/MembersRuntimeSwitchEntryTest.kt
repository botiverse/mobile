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
import build.raft.app.model.MemberId
import build.raft.app.model.getRuntimeDisplayName
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
    fun reselectingTheSelectedRuntimeOrABlankPickIsNoChange() {
        assertNull(membersRuntimeFormV2EditRuntimeSelection(currentRuntimeId = "builtin", picked = "builtin"))
        assertNull(membersRuntimeFormV2EditRuntimeSelection(currentRuntimeId = "builtin", picked = " "))
        assertFalse(membersRuntimeFormV2EditRuntimeSwitched(agentRuntimeId = "builtin", selectedRuntimeId = " "))
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
    fun aSwitchedRuntimesCreateFormFailureStaysInV2AsAFailure() = runBlocking {
        val load =
            loadMembersRuntimeFormV2Edit(
                access(FormComputersApi(formCode = 500), FormAgentsApi(editCode = 200)),
                "m-1",
                AGENT,
                "codex",
                "builtin",
                unknownCatalog
            )
        assertEquals(MembersRuntimeFormV2EditLoad.Failed("runtime_form_v2_http_500"), load)
    }

    @Test
    fun theV2EditorIsTheOnlyRuntimeEditor() {
        val authority =
            MembersAgentProfileEditAuthority(
                memberId = AGENT,
                environmentId = "prod",
                accountId = "account-1",
                serverId = ServerId("server-1"),
                profileGeneration = 1L
            )
        val live =
            MembersRuntimeEditorLiveFacts(
                authority = authority,
                canManage = true,
                deleted = false,
                runtimeStatusResolved = true,
                config = null,
                formV2Available = true
            )
        assertEquals(
            MembersRuntimeEditorDialog.FormV2,
            membersRuntimeEditorDialog(editing = true, opened = authority, openedFormV2 = true, live = live)
        )
        // Without v2 access (no loader or server) nothing opens; there is no v1 editor any more.
        assertEquals(
            MembersRuntimeEditorDialog.None,
            membersRuntimeEditorDialog(editing = true, opened = authority, openedFormV2 = false, live = live)
        )
    }

    @Test
    fun anAgentWithoutAComputerIsUnavailableAndRequestsNothing() = runBlocking {
        val computers = FormComputersApi()
        val agents = FormAgentsApi(editCode = 200)
        for (machine in listOf(null, " ")) {
            assertEquals(
                MembersRuntimeFormV2EditLoad.Unavailable,
                loadMembersRuntimeFormV2Edit(access(computers, agents), machine, AGENT, "codex", "codex", catalogWith())
            )
        }
        assertEquals(emptyList(), computers.formRequests)
        assertEquals(emptyList(), agents.editRequests)
    }

    @Test
    fun aSwitchToARuntimeWithoutTheV2MarkerIsUnavailableAndRequestsNoForm() = runBlocking {
        val computers = FormComputersApi()
        val agents = FormAgentsApi(editCode = 200)
        assertEquals(
            MembersRuntimeFormV2EditLoad.Unavailable,
            loadMembersRuntimeFormV2Edit(access(computers, agents), "m-1", AGENT, "codex", "gemini", catalogWith("codex" to true, "gemini" to false))
        )
        // No row at all (not admitted for new agents on this computer), while other rows are marked: same.
        assertEquals(
            MembersRuntimeFormV2EditLoad.Unavailable,
            loadMembersRuntimeFormV2Edit(access(computers, agents), "m-1", AGENT, "codex", "gemini", catalogWith("codex" to true))
        )
        assertEquals(emptyList(), computers.formRequests, "no endless retry against a form that does not exist")
        assertEquals(emptyList(), agents.editRequests)
    }

    @Test
    fun theAgentsOwnRuntimeWithoutTheV2MarkerIsUnavailableAndRequestsNoEditForm() = runBlocking {
        val computers = FormComputersApi()
        val agents = FormAgentsApi(editCode = 503)
        assertEquals(
            MembersRuntimeFormV2EditLoad.Unavailable,
            loadMembersRuntimeFormV2Edit(
                access(computers, agents),
                "m-1",
                AGENT,
                "gemini",
                "gemini",
                catalogWith("codex" to true, "gemini" to false)
            ),
            "not a Retry banner that can never succeed"
        )
        assertEquals(emptyList(), agents.editRequests)
        assertEquals(emptyList(), computers.formRequests)
    }

    @Test
    fun theAgentsOwnRuntimeLoadsItsEditFormWhenTheCatalogProvesNothing() = runBlocking {
        // Marked own runtime, failed catalog, or rows without any marker: the edit form load decides.
        for (catalog in listOf(catalogWith("codex" to true), unknownCatalog, catalogWith("codex" to false, "gemini" to false))) {
            val agents = FormAgentsApi(editCode = 503)
            assertEquals(
                MembersRuntimeFormV2EditLoad.Failed("runtime_form_v2_http_503"),
                loadMembersRuntimeFormV2Edit(access(FormComputersApi(), agents), "m-1", AGENT, "codex", "codex", catalog)
            )
            assertEquals(listOf(AGENT), agents.editRequests)
        }
    }

    @Test
    fun aSwitchTargetInACatalogWithNoMarkedRowLoadsItsCreateForm() = runBlocking {
        val computers = FormComputersApi()
        val load =
            loadMembersRuntimeFormV2Edit(
                access(computers, FormAgentsApi(editCode = 200)),
                "m-1",
                AGENT,
                "codex",
                "builtin",
                catalogWith("codex" to false, "builtin" to false)
            )
        assertIs<MembersRuntimeFormV2EditLoad.Ready>(load)
        assertEquals(listOf("builtin"), computers.formRequests)
    }

    @Test
    fun theSavingStateEndsOnEveryExit() = runBlocking {
        var saving = true
        membersRuntimeEditorSaving(setSaving = { saving = it }) { return@membersRuntimeEditorSaving }
        assertFalse(saving, "an early return (stale authority after the request) ends saving")

        saving = true
        runCatching { membersRuntimeEditorSaving(setSaving = { saving = it }) { error("boom") } }
        assertFalse(saving, "a failure ends saving")
    }

    @Test
    fun saveIsDisabledUntilTheFormHasLoaded() {
        assertFalse(save(formReady = false, runtimeSwitched = true, valuesChanged = true))
        assertFalse(save(formReady = false, runtimeSwitched = false, valuesChanged = true))
    }

    @Test
    fun aRuntimeSwitchIsAChangeByItself() {
        assertTrue(save(formReady = true, runtimeSwitched = true, valuesChanged = false))
        assertFalse(save(formReady = true, runtimeSwitched = false, valuesChanged = false))
        assertTrue(save(formReady = true, runtimeSwitched = false, valuesChanged = true))
        assertFalse(save(formReady = true, runtimeSwitched = true, valuesChanged = false, hasErrors = true))
        assertFalse(save(formReady = true, runtimeSwitched = true, valuesChanged = false, saving = true))
    }

    @Test
    fun aSwitchSavesWithASessionResetAndTheNewRuntimesFormRef() {
        val submit = RuntimeFormV2SubmitBody(runtimeId = "claude", formValues = buildJsonObject { put("model", JsonPrimitive("opus")) })
        for (active in listOf(true, false)) {
            val request = membersRuntimeFormV2UpdateRequest(AGENT, "Agent", null, submit, runtimeSwitched = true, activeAgent = active)
            assertEquals(AgentResetMode.Session, request.runtimeResetMode, "activeAgent=$active")
            val body = Json.parseToJsonElement(buildSharedAgentProfileUpdateBody(request)).jsonObject
            assertEquals("session", body.getValue("restartMode").jsonPrimitive.content)
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
    fun aSameRuntimeSaveRestartsOnlyAnActiveAgent() {
        val submit = RuntimeFormV2SubmitBody(runtimeId = "codex", formValues = buildJsonObject {})
        assertEquals(
            AgentResetMode.Restart,
            membersRuntimeFormV2UpdateRequest(AGENT, "Agent", null, submit, runtimeSwitched = false, activeAgent = true).runtimeResetMode
        )
        assertNull(membersRuntimeFormV2UpdateRequest(AGENT, "Agent", null, submit, runtimeSwitched = false, activeAgent = false).runtimeResetMode)
    }

    @Test
    fun theSwitchConfirmationUsesWebsFromToCopy() {
        val active = membersRuntimeFormV2SwitchConfirmation("codex", "claude", activeAgent = true)
        assertEquals(ResourceStringDesc(Res.string.member_action_switch_runtime_config), active.title)
        assertEquals(
            ResourceFormattedStringDesc(
                Res.string.member_runtime_switch_active_message,
                listOf(getRuntimeDisplayName("codex"), getRuntimeDisplayName("claude"))
            ),
            active.message
        )
        assertEquals(ResourceStringDesc(Res.string.member_action_reset_runtime_session), active.confirmLabel)

        val inactive = membersRuntimeFormV2SwitchConfirmation("codex", "claude", activeAgent = false)
        assertEquals(Res.string.member_runtime_switch_inactive_message, (inactive.message as ResourceFormattedStringDesc).stringRes)
        assertEquals(ResourceStringDesc(Res.string.member_action_save_runtime_change), inactive.confirmLabel)
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

    private val unknownCatalog: suspend (String) -> SharedCreateAgentRuntimeCatalog? = { null }

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
