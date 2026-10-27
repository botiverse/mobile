package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.tokens.RaftThemeId
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Appearance › "Theme on {server}" (web ServerThemeSection), light and dark: a Free
 * server wearing Space, so the swatch grid shows the selected card, Raft Yellow's "Default", the
 * Pro tag on Boring Gray and the decorations switch on. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1260dp-xxhdpi")
class SettingsServerThemeScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun light() = capture("settings.appearance.server-theme", RaftAppAppearance.Light)

    @Test
    fun dark() = capture("settings.appearance.server-theme.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                build.raft.app.components.RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                        SettingsServerThemeCard(
                            SettingsServerThemeBinding(
                                state =
                                    SettingsServerThemeState(
                                        serverId = "server-theme-screenshot",
                                        serverName = "Raft Team",
                                        plan = "free",
                                        choice = RaftThemeId.Space,
                                        decor = true,
                                    ),
                                onPick = {},
                                onDecorChange = {},
                            )
                        )
                    }
                }
            }
        }
}
