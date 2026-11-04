package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The DM settings (web ChannelPreferencesSection for a DM, slock #12622): Move to section on
 * Pinned and at home in Direct Messages, then Mute and Collapse long messages, on a phone in
 * light and dark. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class DirectMessageSettingsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun dmSettingsPinnedLight() = capture("dm.settings.pinned", RaftAppAppearance.Light, pinned = true)

    @Test
    fun dmSettingsPinnedDark() = capture("dm.settings.pinned.dark", RaftAppAppearance.Dark, pinned = true)

    @Test
    fun dmSettingsHomeLight() = capture("dm.settings.home", RaftAppAppearance.Light, pinned = false)

    private fun capture(name: String, appearance: RaftAppAppearance, pinned: Boolean) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { activity -> dialogWindow() ?: activity.window.decorView },
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { DirectMessageSettingsPanelVisualScreen(pinned = pinned) }
            }
        }

    /** A dialog in its own window: that window, its content measured to it (as [channelSettingsDrawerWindow]). */
    private fun dialogWindow(): android.view.View? =
        if (ShadowDialog.getLatestDialog() == null) null else channelSettingsDrawerWindow()
}
