package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Leave Channel from the Home row menu (web botiverse/slock #11289 / #11292), light and dark:
 * - `home.leave-channel.menu`: a joined channel's row menu (Move to section / read / pin / mute,
 *   then Archive and Leave Channel under one separator). The real panel drawn in the page (in
 *   the app it is its own popup window, which the capture can't see);
 * - `home.leave-channel.dialog.{public,private,joint,last-member}`: the four confirmations —
 *   rejoin any time; a member's invite; an admin's invite; the last member's red "Leave and
 *   Delete" (the private channel and its messages are deleted).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class LeaveChannelScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(qualifiers = "w390dp-h440dp-xxhdpi")
    fun menuLight() = menu("home.leave-channel.menu", RaftAppAppearance.Light)

    @Test
    @Config(qualifiers = "w390dp-h440dp-xxhdpi")
    fun menuDark() = menu("home.leave-channel.menu.dark", RaftAppAppearance.Dark)

    @Test
    fun publicLight() = dialog("home.leave-channel.dialog.public", RaftAppAppearance.Light, LeaveChannelConfirmVariant.Public)

    @Test
    fun publicDark() = dialog("home.leave-channel.dialog.public.dark", RaftAppAppearance.Dark, LeaveChannelConfirmVariant.Public)

    @Test
    fun privateLight() = dialog("home.leave-channel.dialog.private", RaftAppAppearance.Light, LeaveChannelConfirmVariant.Private)

    @Test
    fun privateDark() = dialog("home.leave-channel.dialog.private.dark", RaftAppAppearance.Dark, LeaveChannelConfirmVariant.Private)

    @Test
    fun jointLight() = dialog("home.leave-channel.dialog.joint", RaftAppAppearance.Light, LeaveChannelConfirmVariant.Joint)

    @Test
    fun jointDark() = dialog("home.leave-channel.dialog.joint.dark", RaftAppAppearance.Dark, LeaveChannelConfirmVariant.Joint)

    @Test
    fun lastMemberLight() =
        dialog("home.leave-channel.dialog.last-member", RaftAppAppearance.Light, LeaveChannelConfirmVariant.LastMember)

    @Test
    fun lastMemberDark() =
        dialog("home.leave-channel.dialog.last-member.dark", RaftAppAppearance.Dark, LeaveChannelConfirmVariant.LastMember)

    private fun menu(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            Page {
                val items =
                    raftHomeConversationMenuItems(
                        conversation = RaftHomeConversationItem(id = "dev", name = "dev", activityMuteSupported = true),
                        labels = RaftHomeShellLabels.localized(),
                        pinTargetIsPinned = false,
                        sortableFromMenu = true,
                        activeIsDirectMessage = false,
                        activeCanArchive = true,
                        activeCanLeave = true,
                    )
                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(16.dp)) {
                    RaftLookBaseMenuPanel(
                        items = items,
                        selectedId = "",
                        width = raftLookBaseMenuWidth(items, fixedWidth = null, minWidth = 136.dp),
                        onSelect = {},
                    )
                }
            }
        }

    private fun dialog(name: String, appearance: RaftAppAppearance, variant: LeaveChannelConfirmVariant) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            Page {
                LeaveChannelConfirmDialog(
                    variant = variant,
                    channelName = "dev",
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }
}
