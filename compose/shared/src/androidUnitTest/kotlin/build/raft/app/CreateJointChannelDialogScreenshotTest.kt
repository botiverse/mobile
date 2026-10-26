package build.raft.app

import build.raft.app.data.JointSameAdminContext
import build.raft.app.data.JointSameAdminServer
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * JVM screenshots of the create-joint-channel dialog (`joint.create.*`), light and dark: its
 * current-server member picker with an empty search and two members selected. A tall viewport
 * so the whole scrolling body, picker included, is on screen. `joint.create.sameAdmin`: a draft
 * where the inviter invites themselves on a server they administer (web SameAdminJointNotice). Verify / record as
 * [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xxhdpi")
class CreateJointChannelDialogScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val state =
        CreateJointChannelState(
            name = "launch-room",
            selectedAgentIds = setOf("agent-cindy"),
            selectedUserIds = setOf("user-designer"),
        )

    private val sameAdminState =
        CreateJointChannelState(
            name = "launch-room",
            drafts = listOf(CreateJointInviteDraft(id = 1, targetServerSlug = "partner-co", invitedPeopleText = "@me, bo@example.com")),
            sameAdminContext =
                JointSameAdminContext(
                    currentServerSlug = "mobile",
                    currentServerName = "Mobile",
                    userName = "me",
                    userEmail = "me@example.com",
                    servers = listOf(JointSameAdminServer("mobile", "Mobile", "owner"), JointSameAdminServer("partner-co", "Partner Co", "admin")),
                ),
        )

    @Test
    fun sameAdminLight() = capture("joint.create.sameAdmin", RaftAppAppearance.Light, sameAdminState)

    @Test
    fun sameAdminDark() = capture("joint.create.sameAdmin.dark", RaftAppAppearance.Dark, sameAdminState)

    @Test
    fun membersLight() = capture("joint.create.members", RaftAppAppearance.Light)

    @Test
    fun membersDark() = capture("joint.create.members.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance, state: CreateJointChannelState = this.state) {
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            CreateJointChannelDialog(
                state = state,
                freePlan = false,
                currentServerSlug = "mobile",
                selectableMembers = MemberPickerTestFixtures.roster,
                membersLoading = false,
                onIntent = {},
                onDismiss = {},
                onOpenBilling = {},
                fixtureStatusFallbackEnabled = true,
            )
        }
    }
}
