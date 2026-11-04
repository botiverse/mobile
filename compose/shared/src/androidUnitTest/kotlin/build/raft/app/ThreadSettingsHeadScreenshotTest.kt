package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftSideDrawerSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The channel settings drawer's head on a wide window, where the drawer is a panel beside the
 * page (web ChannelDrawerTitle from md up, slock #12627): "#name" then the channel's visibility
 * tag, Public, Private or Joint Channel; no tag where the kind is unknown. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h120dp-xxhdpi")
class ThreadSettingsHeadScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun wideHeadPublic() = capture("channel.settings.head.wide.public", RaftAppAppearance.Light, "design", "channel")

    @Test
    fun wideHeadPrivate() = capture("channel.settings.head.wide.private", RaftAppAppearance.Light, "roadmap", "private")

    @Test
    fun wideHeadPrivateDark() = capture("channel.settings.head.wide.private.dark", RaftAppAppearance.Dark, "roadmap", "private")

    @Test
    fun wideHeadJoint() = capture("channel.settings.head.wide.joint", RaftAppAppearance.Light, "partner-launch", "joint")

    @Test
    fun wideHeadUnknownKind() = capture("channel.settings.head.wide.no-tag", RaftAppAppearance.Light, "design", null)

    private fun capture(name: String, appearance: RaftAppAppearance, channelName: String, channelKind: String?) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                        RaftSideDrawerSurface(
                            title = "#$channelName",
                            titleTrailing = threadSettingsVisibilityTag(channelKind),
                            onClose = {},
                        ) {}
                    }
                }
            }
        }
}
