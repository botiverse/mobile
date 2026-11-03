package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The channel settings drawer opened from a conversation (web ChannelOverflowMenu settings
 * drawer), the #design fixture of `components.channel.settings.panel`: the members strip, Info
 * with its own Save, Preferences (Move to section, Notifications, Collapse long messages) and
 * Actions, on a phone in light and dark. Tall enough to show the whole body. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1280dp-xxhdpi")
class ChannelSettingsDrawerScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelSettingsDrawerLight() = capture("channel.settings.drawer", RaftAppAppearance.Light)

    @Test
    fun channelSettingsDrawerDark() = capture("channel.settings.drawer.dark", RaftAppAppearance.Dark)

    @Test
    fun channelSettingsDrawerPinnedLight() = capture("channel.settings.drawer.pinned", RaftAppAppearance.Light, pinned = true)

    private fun capture(name: String, appearance: RaftAppAppearance, pinned: Boolean = false) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities, root = { channelSettingsDrawerWindow() }) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { ChannelSettingsPanelVisualScreen(pinned = pinned) }
            }
        }
}

/**
 * The same drawer on a wide window, where it is a panel beside the page with the one-line
 * "#design" head (web ChannelSettingsSheet from md up).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h1000dp-mdpi")
class ChannelSettingsDrawerWideScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelSettingsDrawerWideLight() = capture("channel.settings.drawer.wide", RaftAppAppearance.Light)

    @Test
    fun channelSettingsDrawerWideDark() = capture("channel.settings.drawer.wide.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities, root = { channelSettingsDrawerWindow() }) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { ChannelSettingsPanelVisualScreen() }
            }
        }
}

/**
 * The drawer is its own window (Modal): capture that window, its content measured to it (as
 * [RaftHomeSectionDialogScreenshotTest]).
 */
internal fun channelSettingsDrawerWindow(): android.view.View? {
    val decor = ShadowDialog.getLatestDialog()?.window?.decorView ?: return null
    val content = channelSettingsDrawerCompose(decor) ?: return decor
    val parent = content.parent as? android.view.View ?: return decor
    val exactly = android.view.View.MeasureSpec.EXACTLY
    content.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(parent.width, exactly),
        android.view.View.MeasureSpec.makeMeasureSpec(parent.height, exactly),
    )
    content.layout(0, 0, parent.width, parent.height)
    return decor
}

private fun channelSettingsDrawerCompose(view: android.view.View): android.view.View? {
    if (view is androidx.compose.ui.platform.ViewRootForTest) return view
    if (view is android.view.ViewGroup) {
        for (index in 0 until view.childCount) channelSettingsDrawerCompose(view.getChildAt(index))?.let { return it }
    }
    return null
}
