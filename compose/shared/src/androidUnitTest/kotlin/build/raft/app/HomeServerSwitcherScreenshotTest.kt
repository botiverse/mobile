package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Home server switcher (web ServerSwitcherMenu) open on a phone, light and dark: eight
 * servers, so the list scrolls after six rows above the footer. Each row is the server tile, the
 * name over `/slug`, the current one bold with the check and no count, another server's Activity
 * unread as the pink count (99+ capped), and a server whose notifications are muted as the gray
 * mono count.
 *
 * A 390dp-wide phone, the top 640dp. Goldens: `src/androidUnitTest/screenshots/<name>.png`;
 * record with `:shared:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class HomeServerSwitcherScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun light() = capture("home.server-switcher", RaftAppAppearance.Light)

    @Test fun dark() = capture("home.server-switcher.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = ReportDir, activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.Frame).padding(top = 8.dp)) {
                    ServerSwitcherPanel(
                        servers = Servers,
                        currentServerId = "raft",
                        pendingServerId = null,
                        prefersChineseCommunity = false,
                        labels = RaftHomeShellLabels(),
                        onServerSelected = {},
                        onServerOrderChange = {},
                        onCreateServerClick = {},
                        onInviteHumanClick = {},
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }

    private companion object {
        const val ReportDir = "build/reports/home-screenshots"

        val Servers =
            listOf(
                RaftServerSwitcherItem(id = "botiverse", name = "Botiverse", slug = "botiverse", activityUnreadCount = 16L),
                // The current server: its own count is not shown (you are already there).
                RaftServerSwitcherItem(id = "raft", name = "Raft", slug = "raft", activityUnreadCount = 27L),
                RaftServerSwitcherItem(id = "raft-mobile", name = "Raft Mobile", slug = "raft-mobile", activityUnreadCount = 123L),
                RaftServerSwitcherItem(id = "friends", name = "artin and his friends", slug = "friends", activityUnreadCount = 0L),
                // Notifications muted: the gray mono count, not the pink one.
                RaftServerSwitcherItem(id = "artin", name = "artin", slug = "artin", activityUnreadCount = 16L, serverPushMuted = true),
                RaftServerSwitcherItem(id = "community", name = "Raft Community", slug = "community", activityUnreadCount = 22L, publicPageLive = true),
                RaftServerSwitcherItem(id = "oar", name = "Open Agent Runtime", slug = "open-agent-runtime", activityUnreadCount = 474L),
                RaftServerSwitcherItem(id = "vscordis", name = "vscordis", slug = "vscordis", activityUnreadCount = 0L),
            )
    }
}
