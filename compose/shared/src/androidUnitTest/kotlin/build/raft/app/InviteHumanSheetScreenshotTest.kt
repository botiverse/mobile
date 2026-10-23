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
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the Invite Human phone sheet (`members.invite-human.*`): the sheet's surface
 * (no window) at 90% of a phone's height on the email tab (Send Invites in the head) and the link
 * tab (no commit button), light and dark. Reports land in `build/reports/home-screenshots/` (CI
 * artifact `shared-home-screenshots`); verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class InviteHumanSheetScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val invitees =
        listOf(
            InviteHumanInvitee(id = 1, email = "maria@acme.com"),
            InviteHumanInvitee(id = 2, email = "", role = InviteHumanRoleGuest),
        )

    @Test
    fun emailLight() = capture("members.invite-human.email", RaftAppAppearance.Light, InviteHumanTab.Email)

    @Test
    fun emailDark() = capture("members.invite-human.email.dark", RaftAppAppearance.Dark, InviteHumanTab.Email)

    @Test
    fun linkLight() = capture("members.invite-human.link", RaftAppAppearance.Light, InviteHumanTab.Link)

    @Test
    fun linkDark() = capture("members.invite-human.link.dark", RaftAppAppearance.Dark, InviteHumanTab.Link)

    private fun capture(name: String, appearance: RaftAppAppearance, tab: InviteHumanTab) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        InviteHumanSheetSurfaceForFixture(
                            tab = tab,
                            invitees = invitees,
                            joinUrl = "https://raft.build/join/acme-7f3k2",
                            modifier = Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction),
                        )
                    }
                }
            }
        }
    }
}
