package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RuntimeAccountUsageViewer
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftChoicePickerSheetSurface
import com.tencent.tmm.kmmresource.compose.stringResource
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The create-agent computer picker with each computer's compact quota line for the chosen
 * runtime (Claude Code), by Jasper's picker rule for a field in a longer form: 4 computers are
 * radios; 7 are a Select whose bottom sheet lists them (captured as the trigger and the sheet
 * surface, no window). Quota lines come through the real usage store from a fake transport:
 * plenty left, a used-up 5-hour window with its reset, only a weekly window, no snapshot, an
 * offline computer (greyed out) and a computer the viewer may not see (no line, never read).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class CreateAgentComputerPickerScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun radiosLight() = capture("agent.create.computer_picker.radios", RaftAppAppearance.Light) { RadiosContent() }

    @Test
    fun radiosDark() = capture("agent.create.computer_picker.radios.dark", RaftAppAppearance.Dark) { RadiosContent() }

    @Test
    fun sheetLight() = capture("agent.create.computer_picker.sheet", RaftAppAppearance.Light) { SheetContent() }

    @Test
    fun sheetDark() = capture("agent.create.computer_picker.sheet.dark", RaftAppAppearance.Dark) { SheetContent() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(pickerTimeContext)) {
                        Column(
                            Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) { content() }
                    }
                }
            }
        }
}

private val pickerTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 22),
        // 2026-10-22T02:10:00Z
        nowEpochMillis = 1_792_635_000_000L
    )

private val pickerOwner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

private fun claudeUsage(vararg windows: String, plan: String = "max"): String =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct","planLabel":"$plan","health":"ok","windows":[${windows.joinToString(",")}]}]}}"""

// Labels as @botiverse/oar 0.69 writes them for Claude (runtimes/claude/account-usage.js); ids are opaque.
private val usageByMachine =
    mapOf(
        "m-1" to
            claudeUsage(
                """{"id":"w0_a","label":"Current session","status":"ok","usedRatio":0.28,"resetsAt":"2026-10-22T05:10:00.000Z"}""",
                """{"id":"w1_b","label":"Current week (all models)","status":"ok","usedRatio":0.6,"resetsAt":"2026-10-27T04:10:00.000Z"}""",
                """{"id":"w2_c","label":"Current week (Opus)","status":"ok","usedRatio":0.9,"resetsAt":"2026-10-27T04:10:00.000Z"}"""
            ),
        "m-2" to
            claudeUsage(
                """{"id":"w0_d","label":"Current session","status":"limit_reached","usedRatio":1,"resetsAt":"2026-10-22T05:10:00.000Z"}""",
                """{"id":"w1_e","label":"Current week (all models)","status":"ok","usedRatio":0.88,"resetsAt":"2026-10-27T04:10:00.000Z"}""",
                plan = "pro"
            ),
        "m-4" to claudeUsage("""{"id":"w1_f","label":"Current week (all models)","status":"ok","usedRatio":0.1,"resetsAt":"2026-10-27T04:10:00.000Z"}"""),
        "m-6" to
            claudeUsage(
                """{"id":"w0_g","label":"Current session","status":"ok","usedRatio":0.5,"resetsAt":"2026-10-22T05:10:00.000Z"}""",
                """{"id":"w1_h","label":"Current week (all models)","status":"ok","usedRatio":0.25,"resetsAt":"2026-10-27T04:10:00.000Z"}"""
            )
    )

private class PickerUsageApi : RaftComputersApi {
    override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = usageByMachine[machineId] ?: """{"state":"missing","snapshot":null}""")

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
private fun rememberPickerAccess(): MembersRuntimeAccountUsageAccess =
    remember {
        MembersRuntimeAccountUsageAccess(
            store =
                SharedRuntimeAccountUsageStore(
                    client = RaftComputersApiClient(PickerUsageApi()),
                    currentIdentity = { pickerOwner },
                    clock = { pickerTimeContext.nowEpochMillis ?: 0L }
                ),
            owner = pickerOwner,
            serverId = "server-1",
            // A member: sees usage only on the computers they attached or created.
            viewer = RuntimeAccountUsageViewer(currentUserId = "user-1", canEditMachines = false)
        )
    }

private fun computer(index: Int, name: String, attached: Boolean = true, online: Boolean = true) =
    SharedCreateAgentMachineOption(
        id = "m-$index",
        name = name,
        status = if (online) "online" else "offline",
        runtimes = listOf("claude", "codex"),
        attachedByCurrentUser = attached,
        usageFactsKnown = true
    )

private val fewMachines = listOf(computer(1, "studio-mac"), computer(2, "armbian-gx"), computer(3, "build-box"), computer(7, "old-laptop", online = false))

private val moreMachines =
    fewMachines + listOf(computer(4, "lab-linux"), computer(5, "team-server", attached = false), computer(6, "home-nas"))

@Composable
private fun PickerContent(machines: List<SharedCreateAgentMachineOption>, heading: String) {
    val access = rememberPickerAccess()
    Heading(heading)
    MachinePicker(
        machines = machines,
        machinesLoading = false,
        machinesErrorMessage = null,
        onRetry = {},
        selectedMachineId = "m-1",
        requiredComputer = null,
        suggestedComputer = null,
        enabled = true,
        runtimeId = "claude",
        usageAccess = access,
        onSelect = {}
    )
}

@Composable
private fun RadiosContent() = PickerContent(fewMachines, "4 computers: radios")

@Composable
private fun SheetContent() {
    PickerContent(moreMachines, "7 computers: a Select opening a sheet")
    val lines = rememberCreateAgentMachineQuotaLines(rememberPickerAccess(), moreMachines, "claude")
    RaftChoicePickerSheetSurface(
        value = "m-1",
        options =
            createAgentMachineChoiceOptions(
                createAgentMachineDropdownItems(moreMachines, requiredComputer = null, offlineSuffix = stringResource(MR.strings.agent_create_suffix_offline)),
                lines
            ),
        onPick = {},
        title = stringResource(MR.strings.agent_create_label_computer),
        emptyText = { "" },
        searchable = false,
        modifier = Modifier.height(560.dp)
    )
}

@Composable
private fun Heading(text: String) {
    RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
}
