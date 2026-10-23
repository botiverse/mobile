package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin task #18: the computer detail's runtime chips with account usage (web
 * `RuntimeAccountUsageGateChip`) and the phone sheet a tap opens. The chips read through the
 * real store from a fake transport: Claude Code has a used-up window (attention), Codex is ok,
 * Kimi's snapshot is stale (attention); Builtin reports no usage and stays a plain chip, and the
 * runtimes that are not installed keep their muted suffix. CI uploads the report folder as
 * `shared-home-screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ComputerRuntimeUsageScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun chipsLight() = capture("settings.computer.runtime_usage.chips", RaftAppAppearance.Light) { RuntimeUsageChipsContent(it) }

    @Test
    fun chipsDark() = capture("settings.computer.runtime_usage.chips.dark", RaftAppAppearance.Dark) { RuntimeUsageChipsContent(it) }

    @Test
    fun sheetLight() = capture("settings.computer.runtime_usage.sheet", RaftAppAppearance.Light) { RuntimeUsageSheetContent(it) }

    @Test
    fun sheetDark() = capture("settings.computer.runtime_usage.sheet.dark", RaftAppAppearance.Dark) { RuntimeUsageSheetContent(it) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable (RaftAppAppearance) -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(usageTimeContext)) {
                        RaftComputersTheme { content(appearance) }
                    }
                }
            }
        }
}

private val usageTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 22),
        // 2026-10-22T02:10:00Z
        nowEpochMillis = 1_792_635_000_000L,
    )

private val usageOwner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

// slock packages/shared/src/runtimeAccountUsage.ts (protocol 2) read envelopes.
private const val CLAUDE_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-1","maskedLabel":"ar***@example.com","planLabel":"Max","health":"rate_limited","windows":[{"id":"five_hour","label":"5-hour limit","status":"ok","usedRatio":0.62,"resetsAt":"2026-10-22T05:10:00.000Z"},{"id":"seven_day","label":"Weekly limit","status":"ok","usedRatio":0.88,"resetsAt":"2026-10-24T04:10:00.000Z"},{"id":"seven_day_opus","label":"Opus weekly","status":"limit_reached","usedRatio":1,"resetsAt":"2026-10-23T04:10:00.000Z"}]}]}}"""

private const val CODEX_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-2","planLabel":"Pro","health":"ok","windows":[{"id":"primary","label":"5-hour limit","status":"ok","usedRatio":0.2,"resetsAt":"2026-10-22T04:10:00.000Z"}]}]}}"""

private const val KIMI_USAGE =
    """{"state":"stale","snapshot":{"protocolVersion":2,"provider":"kimi","collectedAt":"2026-10-22T01:40:00.000Z","accounts":[{"accountKey":"acct-3","health":"ok","windows":[{"id":"weekly","label":"Weekly limit","status":"ok","usedRatio":0.3,"resetsAt":"2026-10-24T04:10:00.000Z"}]}]}}"""

private class FixtureUsageApi : RaftComputersApi {
    override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                when (provider) {
                    "claude" -> CLAUDE_USAGE
                    "codex" -> CODEX_USAGE
                    "kimi" -> KIMI_USAGE
                    else -> """{"state":"missing","snapshot":null}"""
                },
        )

    // The stale Kimi snapshot asks for a background refresh; the Computer is offline, so no polling.
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

@Composable
private fun RuntimeUsageChipsContent(@Suppress("UNUSED_PARAMETER") appearance: RaftAppAppearance) {
    val access =
        remember {
            MembersRuntimeAccountUsageAccess(
                store =
                    SharedRuntimeAccountUsageStore(
                        client = RaftComputersApiClient(FixtureUsageApi()),
                        currentIdentity = { usageOwner },
                        clock = { usageTimeContext.nowEpochMillis ?: 0L },
                    ),
                owner = usageOwner,
                serverId = "server-1",
            )
        }
    val snapshot =
        SettingsComputerDetailSnapshot(
            computer =
                SettingsComputerSnapshot(
                    id = "m-1",
                    name = "armbian-gx",
                    daemonVersion = "",
                    computerVersion = "1.0.51",
                    isComputer = true,
                    online = true,
                    hostname = "armbian-gx",
                    platform = "linux arm64",
                    runtimes = listOf("claude", "codex", "kimi-sdk", "builtin"),
                    createdAt = "2026-10-08T09:14:00Z",
                ),
            workspaces = emptyList(),
            agents = emptyList(),
            sections = emptyList(),
            canEditMachine = true,
        )
    val model = raftComputerDetailModel(ScreenLoadState.Content(snapshot), machineId = "m-1", targetServerId = null, currentServerId = "server-1")
    CompositionLocalProvider(LocalMembersRuntimeAccountUsageAccess provides access) {
        RaftSettingsComputerDetailContent(model = model, actionState = null, onRetry = {}, onIntent = {}, onOpenAgent = {})
    }
}

/** The sheet Claude Code's chip opens: plenty left, low (12% left) and used up. */
@Composable
private fun RuntimeUsageSheetContent(@Suppress("UNUSED_PARAMETER") appearance: RaftAppAppearance) {
    val read = remember { Json.parseToJsonElement(CLAUDE_USAGE).toRuntimeAccountUsageRead()!! }
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas), contentAlignment = Alignment.BottomCenter) {
        ComputerRuntimeUsageSheetSurface(
            runtimeLabel = "Claude Code",
            state =
                ComputerRuntimeUsageSheetState(
                    load = RuntimeAccountUsageLoad.Loaded(read),
                    loading = false,
                    refresh = ComputerRuntimeUsageRefreshStatus.Idle,
                    cooldownLeftMillis = 0L,
                ),
            timeContext = usageTimeContext,
        )
    }
}
