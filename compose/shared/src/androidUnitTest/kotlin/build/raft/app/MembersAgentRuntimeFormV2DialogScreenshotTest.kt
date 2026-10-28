package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.runtimeform.v2.GEMINI_V2_EDIT_JSON
import build.raft.app.data.runtimeform.v2.GEMINI_V2_SOURCE_JSON
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * JVM screenshots of the agent runtime-config edit dialog on runtime form v2
 * (`members.agent-runtime-form-v2`), light and dark: a Gemini agent's server-prefilled edit
 * form (slock fixtures gemini.edit / gemini.option-source), Cancel ghost and the primary lg
 * Save commit (disabled until a value changes), as web AgentDetailPanel's runtime editor.
 * Verify / record as [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MembersAgentRuntimeFormV2DialogScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun editLight() = capture("members.agent-runtime-form-v2", RaftAppAppearance.Light)

    @Test
    fun editDark() = capture("members.agent-runtime-form-v2.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val access =
                remember {
                    RuntimeFormV2Access(
                        loader = RuntimeFormV2Loader(RuntimeFormV2DialogComputersApi(), RuntimeFormV2DialogAgentsApi()),
                        serverId = ServerId("server-1"),
                    )
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    MembersAgentRuntimeFormV2Dialog(
                        access = access,
                        agentId = MemberId("agent-gemini"),
                        machineId = "m-1",
                        runtimeId = "gemini",
                        machineRuntimeIds = listOf("gemini", "claude", "codex"),
                        activeAgent = false,
                        saving = false,
                        error = null,
                        errorFieldKeys = emptySet(),
                        onDismiss = {},
                        onRuntimeSelected = {},
                        onSave = { _, _ -> },
                    )
                }
            }
        }
}

private class RuntimeFormV2DialogComputersApi : RaftComputersApi {
    override suspend fun runtimeFormV2OptionSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String,
        refresh: Boolean,
        formRevision: String?,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = GEMINI_V2_SOURCE_JSON)

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

private class RuntimeFormV2DialogAgentsApi : RaftAgentsApi {
    override suspend fun agentRuntimeFormV2(agentId: MemberId): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = GEMINI_V2_EDIT_JSON)

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
