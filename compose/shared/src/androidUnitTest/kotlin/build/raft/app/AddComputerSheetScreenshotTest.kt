package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.AddComputerStep
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.designsystem.components.RaftDialogSheetDefaults
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the Add Computer phone sheet (`settings.add-computer.*`): the sheet's surface
 * (no window) at 90% of a phone's height for each step, light and dark. Reports land in
 * `build/reports/home-screenshots/` (CI artifact `shared-home-screenshots`); verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AddComputerSheetScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val type = AddComputerDialogState(step = AddComputerStep.Type, name = "my-computer")
    private val waiting =
        AddComputerDialogState(
            step = AddComputerStep.Waiting,
            machineId = "machine-new",
            registeredMachineId = "machine-new",
            commandContext = SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "production"),
        )
    private val connected =
        AddComputerDialogState(
            step = AddComputerStep.Connected,
            name = "builder.local",
            machineId = "computer-1",
            registeredMachineId = "machine-new",
            connectedDetail = "builder.local — darwin",
            connectedHostname = "builder.local",
            connectedRuntimes = listOf("claude", "codex"),
            requiresConfirmation = true,
        )

    @Test
    fun typeLight() = capture("settings.add-computer.type", RaftAppAppearance.Light, type)

    @Test
    fun typeDark() = capture("settings.add-computer.type.dark", RaftAppAppearance.Dark, type)

    @Test
    fun waitingLight() = capture("settings.add-computer.waiting", RaftAppAppearance.Light, waiting)

    @Test
    fun waitingDark() = capture("settings.add-computer.waiting.dark", RaftAppAppearance.Dark, waiting)

    @Test
    fun connectedLight() = capture("settings.add-computer.connected", RaftAppAppearance.Light, connected)

    @Test
    fun connectedDark() = capture("settings.add-computer.connected.dark", RaftAppAppearance.Dark, connected)

    private fun capture(name: String, appearance: RaftAppAppearance, state: AddComputerDialogState) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        AddComputerSheetSurfaceForFixture(state, Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction))
                    }
                }
            }
        }
    }
}
