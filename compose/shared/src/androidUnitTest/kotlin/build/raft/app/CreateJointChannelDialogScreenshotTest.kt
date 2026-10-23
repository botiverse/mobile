package build.raft.app

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
 * so the whole scrolling body, picker included, is on screen. Verify / record as
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

    @Test
    fun membersLight() = capture("joint.create.members", RaftAppAppearance.Light)

    @Test
    fun membersDark() = capture("joint.create.members.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
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
