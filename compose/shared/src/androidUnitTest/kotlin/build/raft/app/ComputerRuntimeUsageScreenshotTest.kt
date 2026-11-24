package build.raft.app

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardSurface
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

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
    fun chipsDark() = capture("settings.computer.runtime_usage.chips.dark", RaftAppAppearance.Dark) { RuntimeUsageChipsContent(it) }

    // A finger's tap on Claude Code's chip on a phone: the usage opens as a bottom sheet (web: a
    // Drawer on a phone), never as the hover card; the sheet's window is captured.
    @Test
    fun chipTapSheetLight() =
        RaftRobolectricScreenshot.capture(
            "settings.computer.runtime_usage.chip_tap_sheet",
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { activity ->
                tapChip(activity, "Claude")
                ShadowDialog.getLatestDialog()?.window?.decorView?.let(::fittedToItsWindow)
            },
        ) { CapturePage(RaftAppAppearance.Light) { RuntimeUsageChipsContent(it) } }

    // Web parity (slock #15482, #15483, #15557, #15395, #15787): a titled plan with resets and credits on
    // one line, the account's "…" menu and the on-credits callout; some model groups used up; the
    // provider wanting a sign-in again (Sign in under the account, no meters); why an account is unsupported.
    @Test
    @Config(sdk = [34], qualifiers = "w520dp-h1500dp-xxhdpi")
    fun previewCardStatesLight() = capture("settings.computer.runtime_usage.preview_card.states", RaftAppAppearance.Light) { RuntimeUsagePreviewCardStates() }

    // The agent profile's usage card says what is used, like the sheet above (web #11855).
    @Test
    fun memberCardLight() = capture("members.runtime_usage.card", RaftAppAppearance.Light) { RuntimeUsageMemberCardContent() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable (RaftAppAppearance) -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CapturePage(appearance, content)
        }
}

@Composable
private fun CapturePage(appearance: RaftAppAppearance, content: @Composable (RaftAppAppearance) -> Unit) {
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

/** A finger's tap (down, then up 60ms later) on the centre of the runtime chip whose text starts with [text]; then the page settles. */
private fun tapChip(activity: ComponentActivity, text: String) {
    val root = findCompose(activity.window.decorView)
    assertNotNull(root, "no compose root")
    val chip =
        (root as ViewRootForTest).semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).first { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) == COMPUTER_RUNTIME_CHIP_TAG &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(text) } == true
        }
    val at = chip.boundsInRoot.center
    val down = SystemClock.uptimeMillis()
    MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, at.x, at.y, 0).also { root.dispatchTouchEvent(it) }.recycle()
    MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, at.x, at.y, 0).also { root.dispatchTouchEvent(it) }.recycle()
    repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
}

/** The dialog window with its compose content measured to the window (RaftUpgradeDialogScreenshotTest). */
private fun fittedToItsWindow(decor: View): View {
    val content = findCompose(decor) ?: return decor
    val parent = content.parent as? View ?: return decor
    val exactly = View.MeasureSpec.EXACTLY
    content.measure(View.MeasureSpec.makeMeasureSpec(parent.width, exactly), View.MeasureSpec.makeMeasureSpec(parent.height, exactly))
    content.layout(0, 0, parent.width, parent.height)
    return decor
}

private fun findCompose(view: View): View? {
    if (view is ViewRootForTest) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) findCompose(view.getChildAt(index))?.let { return it }
    }
    return null
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

@Composable
private fun RuntimeUsageMemberCardContent() {
    val read = remember { Json.parseToJsonElement(CLAUDE_USAGE).toRuntimeAccountUsageRead() as RuntimeAccountUsageRead.Available }
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
        MembersRuntimeUsageAccountCard(account = read.snapshot.accounts.single(), stale = false, timeContext = usageTimeContext)
    }
}

/** The usage card as a pinned preview card shows it, under the chip it belongs to. */
@Composable
private fun RuntimeUsagePreviewCardContent(usage: String, label: String = "Claude Code") {
    val read = remember(usage) { Json.parseToJsonElement(usage).toRuntimeAccountUsageRead()!! }
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftBadge(text = label, appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
        RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
            ComputerRuntimeUsageCard(
                runtimeLabel = label,
                state =
                    ComputerRuntimeUsageSheetState(
                        load = RuntimeAccountUsageLoad.Loaded(read),
                        loading = false,
                        checkDue = true,
                        runtimeVersion = "2.1.304 (Claude Code)".takeIf { label == "Claude Code" },
                    ),
                timeContext = usageTimeContext,
                onRefresh = {},
                onClose = {},
            )
        }
    }
}

private val statesAuthView =
    RuntimeAuthView(
        canAct = true,
        availability = RuntimeAuthAvailability.Ok,
        status =
            mapOf(
                RuntimeAuthRuntime.Codex to RuntimeAuthStatus.LoggedIn(),
                RuntimeAuthRuntime.Claude to RuntimeAuthStatus.LoggedIn(),
                RuntimeAuthRuntime.Antigravity to RuntimeAuthStatus.LoggedIn(),
            ),
        runtimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Antigravity),
        logoutRuntimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Antigravity),
    )

// A Codex team plan past its weekly window on credits, with resets left (slock #15374, #15482, #15557).
private const val CODEX_TEAM_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-t","maskedLabel":"te***@example.com","planLabel":"team","health":"ok","limitResetsAvailable":2,"credits":{"available":true,"balance":"30784"},"windows":[{"id":"w5","label":"Codex · 5 hours","status":"ok","usedRatio":0.42,"resetsAt":"2026-10-22T05:10:00.000Z"},{"id":"wk","label":"Codex · 1 week","status":"limit_reached","usedRatio":1,"resetsAt":"2026-10-24T04:10:00.000Z"}]}]}}"""

// Antigravity with one of its two model groups used up (slock #15483).
private const val ANTIGRAVITY_GROUPS_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"antigravity","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-g","maskedLabel":"ag***@example.com","planLabel":"Google AI Pro","health":"ok","windows":[{"id":"g","label":"Gemini Models","status":"limit_reached","usedRatio":1,"resetsAt":"2026-10-22T06:10:00.000Z","scope":{"kind":"models","name":"Gemini Models"}},{"id":"c","label":"Claude and GPT models","status":"ok","usedRatio":0.35,"resetsAt":"2026-10-22T06:10:00.000Z","scope":{"kind":"models","name":"Claude and GPT models"}}]}]}}"""

// Claude's provider wants the Computer's own account signed in again (slock #15787).
private const val CLAUDE_REAUTH_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-r","maskedLabel":"ar***@example.com","planLabel":"max","health":"reauth_required","windows":[{"id":"s","label":"Current session","status":"ok","usedRatio":0.62}]}]}}"""

// Kimi reads no usage for this sign-in method (slock #15395).
private const val KIMI_UNSUPPORTED_USAGE =
    """{"state":"stale","snapshot":{"protocolVersion":2,"provider":"kimi","collectedAt":"2026-10-22T01:40:00.000Z","accounts":[{"accountKey":"acct-k","health":"unsupported","unsupportedReason":"unsupported_auth_mode","windows":[]}]}}"""

@Composable
private fun RuntimeUsagePreviewCardStates() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatesCard("Codex CLI", CODEX_TEAM_USAGE, "codex", RuntimeAuthRuntime.Codex, limitReset = ComputerRuntimeLimitResetOffer(primary = true, onCredits = true))
        StatesCard("Antigravity", ANTIGRAVITY_GROUPS_USAGE, "antigravity", RuntimeAuthRuntime.Antigravity)
        StatesCard("Claude Code", CLAUDE_REAUTH_USAGE, "claude", RuntimeAuthRuntime.Claude)
        StatesCard("Kimi Code", KIMI_UNSUPPORTED_USAGE, null, null)
    }
}

@Composable
private fun StatesCard(
    label: String,
    usage: String,
    raftRuntimeId: String?,
    runtime: RuntimeAuthRuntime?,
    limitReset: ComputerRuntimeLimitResetOffer? = null,
) {
    val read = remember(usage) { Json.parseToJsonElement(usage).toRuntimeAccountUsageRead()!! }
    val auth = if (raftRuntimeId != null && runtime != null) ComputerRuntimeAuthCardState(raftRuntimeId, runtime, statesAuthView) else null
    RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
        ComputerRuntimeUsageCard(
            runtimeLabel = label,
            state =
                ComputerRuntimeUsageSheetState(
                    load = RuntimeAccountUsageLoad.Loaded(read),
                    loading = false,
                    checkDue = true,
                    runtimeVersion = "0.98.0",
                    limitReset = limitReset,
                ),
            timeContext = usageTimeContext,
            onRefresh = {},
            onClose = {},
            auth = auth,
            authRuntimeName = label,
        )
    }
}
