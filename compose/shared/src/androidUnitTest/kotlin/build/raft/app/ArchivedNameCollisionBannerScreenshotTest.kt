package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.ChannelId
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** The banner alone: a short phone viewport. */
private const val BANNER_QUALIFIERS = "w390dp-h320dp-xxhdpi"

/**
 * JVM screenshots of the archived-name warning (web ArchivedNameCollisionBanner, slock #12614),
 * light and dark: Create Channel's banner for someone who may unarchive (Unarchive, Open Channel,
 * Change Name) and for someone who may not (Open Channel, Change Name), and Create Joint
 * Channel's dialog with the warning (no hint line, as on the web). Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xxhdpi")
class ArchivedNameCollisionBannerScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(qualifiers = BANNER_QUALIFIERS)
    fun canUnarchiveLight() = captureBanner("create-channel.archived-name", RaftAppAppearance.Light, canUnarchive = true)

    @Test
    @Config(qualifiers = BANNER_QUALIFIERS)
    fun canUnarchiveDark() = captureBanner("create-channel.archived-name.dark", RaftAppAppearance.Dark, canUnarchive = true)

    @Test
    @Config(qualifiers = BANNER_QUALIFIERS)
    fun cannotUnarchiveLight() =
        captureBanner("create-channel.archived-name.no-unarchive", RaftAppAppearance.Light, canUnarchive = false)

    @Test
    @Config(qualifiers = BANNER_QUALIFIERS)
    fun cannotUnarchiveDark() =
        captureBanner("create-channel.archived-name.no-unarchive.dark", RaftAppAppearance.Dark, canUnarchive = false)

    @Test
    fun jointLight() = captureJoint("joint.create.archived-name", RaftAppAppearance.Light)

    @Test
    fun jointDark() = captureJoint("joint.create.archived-name.dark", RaftAppAppearance.Dark)

    private fun captureBanner(name: String, appearance: RaftAppAppearance, canUnarchive: Boolean) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(24.dp)) {
                    ArchivedNameCollisionBanner(
                        archivedChannelName = "launch-room",
                        canUnarchive = canUnarchive,
                        unarchiving = false,
                        onUnarchive = {},
                        onOpen = {},
                        onChangeName = {},
                    )
                }
            }
        }
    }

    private fun captureJoint(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            CreateJointChannelDialog(
                state =
                    CreateJointChannelState(
                        name = "launch-room",
                        collision = CreateJointArchivedCollision(ChannelId("ch-old"), "launch-room", canUnarchive = true),
                    ),
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
