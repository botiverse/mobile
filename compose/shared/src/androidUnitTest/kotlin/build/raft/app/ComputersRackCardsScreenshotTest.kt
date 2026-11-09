package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.ComputersRackView
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerRestartProgress
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import build.raft.app.resources.*
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerVersionGateSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the Computers page, light and dark, with its header and the List / Cards
 * toggle. Cards (`settings.computers`): web ComputersRack cards for an online computer, one with
 * an upgrade, one below the version floor, one with low disk and more agents than one line holds,
 * and an offline one (dimmed agents, no meters). The online cards carry Disk / Memory meters
 * (normal, low-disk warning, "—" when not reported) and runtime usage lines: normal, warning from
 * 80%, used up, a stale read (dimmed), "—" with nothing read, "—" with an account needing
 * attention, and Other runtimes. Each card ends with its next step: Studio restarting, build-box
 * "Upgrade to v1.0.53", old-node "Upgrading…", the laptop's restart command with its copy button;
 * gpu-rig (up to date, and the viewer may not manage it) has none.
 * List (`settings.computers.list`): the same computers as phone list rows (tile with its light,
 * name with the agent count and Low disk, facts, description), no meters, no next step.
 * Agents carry no avatar image, so the capture draws initials without a fetch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-xxhdpi")
class ComputersRackCardsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val previousAppearance = RaftAppThemeState.appearance

    @After
    fun restoreAppearance() {
        RaftAppThemeState.apply(previousAppearance)
    }

    @Test
    fun computersLight() = capture("settings.computers", RaftAppAppearance.Light)

    @Test
    fun computersDark() = capture("settings.computers.dark", RaftAppAppearance.Dark)

    @Test
    fun computersListLight() = capture("settings.computers.list", RaftAppAppearance.Light, ComputersRackView.List)

    @Test
    fun computersListDark() = capture("settings.computers.list.dark", RaftAppAppearance.Dark, ComputersRackView.List)

    private fun capture(name: String, appearance: RaftAppAppearance, view: ComputersRackView = ComputersRackView.Cards) =
        captureComputersRack(activities, name, appearance, view, wide = false)
}

/**
 * The wide Computers page (Compose desktop's content area beside the rail, artin 11-01): the web's
 * desktop head (title with the counts, List / Cards, Add Computer) and the cards in a grid of
 * cards at least 250dp wide (`repeat(auto-fill, minmax(250px, 1fr))`): four columns at 1280dp,
 * the short second row keeping the card width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h1100dp-mdpi")
class ComputersRackWideCardsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val previousAppearance = RaftAppThemeState.appearance

    @After
    fun restoreAppearance() {
        RaftAppThemeState.apply(previousAppearance)
    }

    @Test
    fun computersWideCardsLight() =
        captureComputersRack(activities, "settings.computers.wide", RaftAppAppearance.Light, ComputersRackView.Cards, wide = true)
}

private fun captureComputersRack(
    activities: RobolectricComposeActivityRule,
    name: String,
    appearance: RaftAppAppearance,
    view: ComputersRackView,
    wide: Boolean,
) {
    run {
        RaftAppThemeState.apply(appearance)
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager =
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            }
        activity.setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftSettingsWideDetail provides wide) {
                ComputersListVisualContent(appearance, view, wide)
            }
        }
        settle()
        val composeView = findComposeRoot(activity.window.decorView)
        assertNotNull(composeView, "no compose root")
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        RaftRobolectricScreenshot.verifyOrRecord(name, bitmap, File("build/reports/home-screenshots"))
    }

}

private fun settle() {
    repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
}

private fun findComposeRoot(view: View): View? {
    if (view is ViewRootForTest) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
    }
    return null
}

@Composable
private fun ComputersListVisualContent(appearance: RaftAppAppearance, view: ComputersRackView, wide: Boolean = false) {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 22),
            nowEpochMillis = 1_792_635_000_000L,
        )
    val gib = 1024.0 * 1024.0 * 1024.0
    val snapshot =
        SettingsComputersSnapshot(
            computers =
                listOf(
                    SettingsComputerSnapshot(
                        id = "m-studio",
                        name = "Studio Mac",
                        daemonVersion = "",
                        computerVersion = "1.0.53",
                        isComputer = true,
                        online = true,
                        hostname = "studio.local",
                        platform = "darwin arm64",
                        latestComputerVersion = "1.0.53",
                        runtimes = listOf("claude", "codex", "cursor", "gemini"),
                        diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 310 * gib, totalBytes = 994 * gib),
                        memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 18 * gib, totalBytes = 64 * gib),
                        canViewRuntimeUsage = true,
                        canManage = true,
                    ),
                    SettingsComputerSnapshot(
                        id = "m-build",
                        name = "build-box",
                        daemonVersion = "",
                        computerVersion = "1.0.49",
                        isComputer = true,
                        online = true,
                        upgradeAvailable = true,
                        hostname = "build-01",
                        platform = "linux x64",
                        osRelease = "Ubuntu 24.04.3 LTS",
                        latestComputerVersion = "1.0.53",
                        runtimes = listOf("claude", "kimi-sdk", "opencode"),
                        memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 2 * gib, totalBytes = 32 * gib),
                        remoteUpgradeSupported = true,
                        broadcastPolicy = SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = "1.0.53"),
                        description = "CI runners",
                        canViewRuntimeUsage = true,
                        canManage = true,
                    ),
                    SettingsComputerSnapshot(
                        id = "m-old",
                        name = "old-node",
                        daemonVersion = "",
                        computerVersion = "1.0.46",
                        isComputer = true,
                        online = true,
                        platform = "linux arm64",
                        osRelease = "Debian GNU/Linux 12",
                        versionGate = SettingsComputerVersionGateSnapshot(reason = "computer_version_unsupported", minVersion = "1.0.50"),
                        latestComputerVersion = "1.0.53",
                        runtimes = listOf("claude", "grok"),
                        diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 40 * gib, totalBytes = 256 * gib),
                        memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 6 * gib, totalBytes = 16 * gib),
                        upgradeRequest =
                            SettingsComputerUpgradeRequestSnapshot(
                                id = "u-old",
                                targetVersion = "1.0.53",
                                requestedAt = "2026-10-22T02:08:00Z",
                                state = "pending",
                            ),
                        canViewRuntimeUsage = true,
                        canManage = true,
                    ),
                    SettingsComputerSnapshot(
                        id = "m-gpu",
                        name = "gpu-rig-with-a-rather-long-hostname",
                        daemonVersion = "",
                        computerVersion = "1.0.53",
                        isComputer = true,
                        online = true,
                        platform = "linux x64",
                        osRelease = "Ubuntu 22.04",
                        latestComputerVersion = "1.0.53",
                        diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 6 * gib, totalBytes = 500 * gib),
                        memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 100 * gib, totalBytes = 128 * gib),
                        runtimes = listOf("codex", "qwen", "pi", "copilot"),
                        canViewRuntimeUsage = true,
                    ),
                    SettingsComputerSnapshot(
                        id = "m-laptop",
                        name = "Travel laptop",
                        daemonVersion = "",
                        computerVersion = "1.0.50",
                        isComputer = true,
                        online = false,
                        platform = "darwin arm64",
                        lastHeartbeat = "2026-10-20T12:00:00Z",
                        latestComputerVersion = "1.0.50",
                        runtimes = listOf("claude", "codex"),
                        diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 6 * gib, totalBytes = 500 * gib),
                        canViewRuntimeUsage = true,
                        canManage = true,
                    ),
                ),
            canAddComputer = true,
            latestComputerVersion = "1.0.53",
            commandContext = SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "production"),
        )
    val names = listOf("Wug", "Jony", "Lagoon", "Tenny", "Artea", "Kirby", "Mochi", "Pixel", "Nova", "Orbit", "Quill", "Rune", "Sable", "Tide", "Umber")
    fun agents(machine: String, count: Int, offset: Int = 0) =
        List(count) { index -> RaftComputerCardAgent(id = "$machine-a$index", name = names[(index + offset) % names.size], avatarWireValue = null) }
    val agentsByMachine =
        mapOf(
            "m-studio" to agents("m-studio", 3),
            "m-build" to agents("m-build", 2, offset = 3),
            "m-old" to agents("m-old", 1, offset = 5),
            "m-gpu" to agents("m-gpu", 14, offset = 1),
            "m-laptop" to agents("m-laptop", 2, offset = 7),
        )
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
            SettingsDetailShell(
                title = stringResource(Res.string.settings_computers_title),
                subtitle = "",
                applyTopSafeArea = false,
                onBack = {},
                titleContent =
                    if (wide) {
                        {
                            RaftComputersWideTitle(
                                stringResource(Res.string.settings_computers_title),
                                raftComputersCountsLine(raftComputersCounts(raftComputersListModel(ScreenLoadState.Content(snapshot)).let { (it as RaftComputersListModel.Content).rows }, agentsByMachine)),
                            )
                        }
                    } else {
                        null
                    },
                trailingContent = {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        RaftComputersRackViewToggle(view) {}
                        if (wide) RaftComputersAddButton {}
                    }
                },
            ) {
                RaftComputersTheme {
                    RaftSettingsComputersContent(
                        model = raftComputersListModel(ScreenLoadState.Content(snapshot)),
                        onRetry = {},
                        onAddComputer = {},
                        onOpenComputer = {},
                        agentsByMachine = agentsByMachine,
                        usageLoads = rackUsageFixture(),
                        rackView = view,
                        summaryInHeader = wide,
                        computerControl = SettingsComputerControlState(restart = SettingsComputerRestartProgress(machineId = "m-studio")),
                    )
                }
            }
        }
    }
}

/**
 * The cards' usage reads: Studio normal + warning; build-box used up + stale; old-node nothing
 * read ("—") + an account needing attention ("—" with the pixel); gpu-rig normal. The offline
 * laptop has a read that must not show.
 */
private fun rackUsageFixture(): Map<String, Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>> {
    fun window(ratio: Double?, status: RuntimeAccountUsageWindowStatus = RuntimeAccountUsageWindowStatus.Ok) =
        RuntimeAccountUsageWindow(id = "five_hour", label = "5h", status = status, usedRatio = ratio, resetsAt = null)
    fun account(vararg windows: RuntimeAccountUsageWindow, health: RuntimeAccountUsageHealth = RuntimeAccountUsageHealth.Ok) =
        RuntimeAccountUsageAccount(accountKey = "acct", planLabel = "Max", identityLabel = null, health = health, windows = windows.toList())
    fun loaded(provider: String, account: RuntimeAccountUsageAccount, stale: Boolean = false) =
        RuntimeAccountUsageLoad.Loaded(
            RuntimeAccountUsageRead.Available(stale = stale, snapshot = RuntimeAccountUsageSnapshot(provider, null, listOf(account))),
        )
    return mapOf(
        "m-studio" to
            mapOf(
                RuntimeAccountUsageProvider.Claude to loaded("claude", account(window(0.42), window(0.18))),
                RuntimeAccountUsageProvider.Codex to loaded("codex", account(window(0.86))),
            ),
        "m-build" to
            mapOf(
                RuntimeAccountUsageProvider.Claude to loaded("claude", account(window(null, RuntimeAccountUsageWindowStatus.LimitReached))),
                RuntimeAccountUsageProvider.Kimi to loaded("kimi", account(window(0.3)), stale = true),
            ),
        "m-old" to
            mapOf(
                RuntimeAccountUsageProvider.Grok to loaded("grok", account(health = RuntimeAccountUsageHealth.ReauthRequired)),
            ),
        "m-gpu" to mapOf(RuntimeAccountUsageProvider.Codex to loaded("codex", account(window(0.12)))),
        "m-laptop" to mapOf(RuntimeAccountUsageProvider.Claude to loaded("claude", account(window(0.5)))),
    )
}
