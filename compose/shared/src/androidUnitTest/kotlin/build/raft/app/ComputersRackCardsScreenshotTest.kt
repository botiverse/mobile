package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
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
 * JVM screenshots of the Computers list page (`settings.computers`), light and dark: web
 * ComputersRack cards for an online computer, one with an upgrade, one below the version floor,
 * one with low disk and more agents than one line holds, and an offline one (dimmed agents).
 * Agents carry no avatar image, so the capture draws initials without a fetch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1240dp-xxhdpi")
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

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftAppThemeState.apply(appearance)
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager =
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            }
        activity.setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { ComputersListVisualContent(appearance) }
        }
        settle()
        val composeView = findComposeRoot(activity.window.decorView)
        assertNotNull(composeView, "no compose root")
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        RaftRobolectricScreenshot.verifyOrRecord(name, bitmap, File("build/reports/home-screenshots"))
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
}

@Composable
private fun ComputersListVisualContent(appearance: RaftAppAppearance) {
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
                        diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 310 * gib, totalBytes = 994 * gib),
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
                    ),
                ),
            canAddComputer = true,
            latestComputerVersion = "1.0.53",
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
            RaftComputersTheme {
                RaftSettingsComputersContent(
                    model = raftComputersListModel(ScreenLoadState.Content(snapshot)),
                    onRetry = {},
                    onAddComputer = {},
                    onOpenComputer = {},
                    agentsByMachine = agentsByMachine,
                )
            }
        }
    }
}
