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

/**
 * The DM settings drawer (web ChannelOverflowMenu for a DM): the name with its "Direct message"
 * tag, then Preferences (Move to section, Mute for a DM with an App bot, Collapse long messages).
 * On a phone it fills the window: Friday on Pinned in light and dark, and a DM with a person at
 * home in Direct Messages, which has no Mute. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class DirectMessageSettingsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun dmSettingsPinnedLight() = captureDmSettings(activities, "dm.settings.pinned", RaftAppAppearance.Light, pinned = true)

    @Test
    fun dmSettingsPinnedDark() = captureDmSettings(activities, "dm.settings.pinned.dark", RaftAppAppearance.Dark, pinned = true)

    @Test
    fun dmSettingsPersonAtHomeLight() =
        captureDmSettings(activities, "dm.settings.home", RaftAppAppearance.Light, pinned = false, mutable = false)
}

/** The same drawer on a wide window: a panel beside the page, the head still name and tag. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h1000dp-mdpi")
class DirectMessageSettingsWideScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun dmSettingsWideLight() = captureDmSettings(activities, "dm.settings.wide", RaftAppAppearance.Light, pinned = true)

    @Test
    fun dmSettingsWideDark() = captureDmSettings(activities, "dm.settings.wide.dark", RaftAppAppearance.Dark, pinned = true)
}

private fun captureDmSettings(
    activities: RobolectricComposeActivityRule,
    name: String,
    appearance: RaftAppAppearance,
    pinned: Boolean,
    mutable: Boolean = true,
) = RaftRobolectricScreenshot.capture(
    name,
    appearance,
    reportDir = "build/reports/home-screenshots",
    activities = activities,
    root = { channelSettingsDrawerWindow() },
) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
        RaftDesignSystemTheme { DirectMessageSettingsPanelVisualScreen(pinned = pinned, mutable = mutable) }
    }
}
