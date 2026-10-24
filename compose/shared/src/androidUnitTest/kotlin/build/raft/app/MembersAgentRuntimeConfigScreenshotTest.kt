package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.ServerId
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent profile's Runtime Config rows with runtime account usage
 * (`members.agent-runtime-config.usage`): Claude Code's account is rate limited (orange dot,
 * attention) above, Codex is healthy (lime dot) below. The usage state is only the dot's
 * accessible name, as on web `RuntimeAccountUsageChip`; artin saw "Usage needs attention"
 * drawn over the chip when it was a sibling Text of the chip inside the value Box.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class MembersAgentRuntimeConfigScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun usageLight() = capture("members.agent-runtime-config.usage", RaftAppAppearance.Light)

    @Test
    fun usageDark() = capture("members.agent-runtime-config.usage.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { RuntimeConfigUsageContent() }
            }
        }
}

private val runtimeConfigUsageOwner =
    SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

// slock packages/shared/src/runtimeAccountUsage.ts (protocol 2) read envelopes.
private const val RATE_LIMITED_CLAUDE_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-1","planLabel":"Max","health":"rate_limited","windows":[{"id":"seven_day","label":"Weekly limit","status":"limit_reached","usedRatio":1,"resetsAt":"2026-10-27T19:00:00.000Z"}]}]}}"""

private const val HEALTHY_CODEX_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-2","planLabel":"Pro","health":"ok","windows":[{"id":"primary","label":"5-hour limit","status":"ok","usedRatio":0.2,"resetsAt":"2026-10-22T04:10:00.000Z"}]}]}}"""

private class RuntimeConfigUsageApi : RaftComputersApi {
    override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                when (provider) {
                    "claude" -> RATE_LIMITED_CLAUDE_USAGE
                    "codex" -> HEALTHY_CODEX_USAGE
                    else -> """{"state":"missing","snapshot":null}"""
                },
        )

    override suspend fun refreshRuntimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: String,
        body: String,
        priority: DataRequestPriority,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 202, bodyText = """{"accepted":false,"state":"computer_offline"}""")

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

private fun runtimeConfigFixture(runtime: String, model: String): MembersAgentRuntimeConfigResolution =
    MembersAgentRuntimeConfigResolution.Content(
        RuntimeConfig(
            runtime = runtime,
            provider = null,
            model = membersRuntimeModelConfig(runtime, model, "preset"),
            mode = RuntimeModeConfig.Default,
            reasoningEffort = null,
            envVars = null,
        )
    )

@Composable
private fun RuntimeConfigUsageContent() {
    val access =
        remember {
            MembersRuntimeAccountUsageAccess(
                store =
                    SharedRuntimeAccountUsageStore(
                        client = RaftComputersApiClient(RuntimeConfigUsageApi()),
                        currentIdentity = { runtimeConfigUsageOwner },
                        clock = { 1_792_635_000_000L },
                    ),
                owner = runtimeConfigUsageOwner,
                serverId = "server-1",
            )
        }
    CompositionLocalProvider(LocalMembersRuntimeAccountUsageAccess provides access) {
        Column(
            modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            MembersAgentRuntimeConfigRows(
                resolution = remember { runtimeConfigFixture("claude", "claude-opus-4-1") },
                runtimeModelsCatalog = null,
                machineId = "m-1",
            )
            MembersAgentRuntimeConfigRows(
                resolution = remember { runtimeConfigFixture("codex", "gpt-5-codex") },
                runtimeModelsCatalog = null,
                machineId = "m-1",
            )
        }
    }
}
