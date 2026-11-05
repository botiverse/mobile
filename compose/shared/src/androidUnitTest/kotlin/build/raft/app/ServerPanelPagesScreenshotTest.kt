package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftMobileNavServerPopoverState
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's server panel (the server tab's popover) as the web's MobileServerTab draws it: the
 * servers, the switcher's actions and, at the bottom, the server's pages Members, Computers, Apps
 * and Settings (artin 2026-11-05, dm task #17: the phone Settings has no Computers or Apps row);
 * a guest's row has no Apps. A 390dp-wide phone; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class ServerPanelPagesScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun phoneServerPanel() = capture("shell.server-panel.pages", RaftAppAppearance.Light, guest = false)

    @Test
    fun phoneServerPanelDark() = capture("shell.server-panel.pages.dark", RaftAppAppearance.Dark, guest = false)

    @Test
    fun phoneServerPanelGuest() = capture("shell.server-panel.pages.guest", RaftAppAppearance.Light, guest = true)

    private fun capture(name: String, appearance: RaftAppAppearance, guest: Boolean) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val state = remember { RaftMobileNavServerPopoverState().apply { show() } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        RaftAppServerMenuOverlay(
                            model = serverPanelModel,
                            state = state,
                            anchor = RaftServerMenuAnchor.BottomBar,
                            onPick = {},
                            prefersChineseCommunity = false,
                            onCreateServerClick = {},
                            onInviteHumanClick = null,
                            screenHeight = 640.dp,
                            entries = raftServerMenuEntriesFor(serverPanelModel.entries, wide = false, guest = guest),
                        )
                    }
                }
            }
        }
}

private val serverPanelServers =
    listOf(
        RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
        RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 3L),
    )

private val serverPanelModel =
    RaftServerMenuModel(
        servers = serverPanelServers,
        currentServer = serverPanelServers.first(),
        attentionEntries = setOf(RaftServerMenuEntry.Computers),
    )
