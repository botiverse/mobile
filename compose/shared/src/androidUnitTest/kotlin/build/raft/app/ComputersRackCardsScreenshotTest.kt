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
import build.raft.app.data.SettingsComputerSnapshot
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
 * JVM screenshots of the Computers list page (`settings.computers`), light and dark: an online
 * computer, an offline one, one with an upgrade, one with low disk and one with many agents.
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
                        latestComputerVersion = "1.0.53",
                    ),
                    SettingsComputerSnapshot(
                        id = "m-gpu",
                        name = "gpu-rig",
                        daemonVersion = "",
                        computerVersion = "1.0.53",
                        isComputer = true,
                        online = true,
                        hostname = "gpu-rig",
                        platform = "linux x64",
                        latestComputerVersion = "1.0.53",
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
                        latestComputerVersion = "1.0.53",
                    ),
                ),
            canAddComputer = true,
            latestComputerVersion = "1.0.53",
        )
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
            RaftComputersTheme {
                RaftSettingsComputersContent(
                    model = raftComputersListModel(ScreenLoadState.Content(snapshot)),
                    onRetry = {},
                    onAddComputer = {},
                    onOpenComputer = {},
                )
            }
        }
    }
}
