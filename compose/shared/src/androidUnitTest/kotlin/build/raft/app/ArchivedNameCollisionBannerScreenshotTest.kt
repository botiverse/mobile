package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ARCHIVED_NAME_COLLISION
import build.raft.app.data.ArchivedNameCollision
import build.raft.app.data.HomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import build.raft.app.thread.ArchivedNameCollisionBanner
import build.raft.app.thread.ChannelProfileUpdateStatus
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The banner alone: a short phone viewport. */
private const val BANNER_QUALIFIERS = "w390dp-h320dp-xxhdpi"

/** Channel settings' Info form with the rename's warning above the name. */
private const val RENAME_QUALIFIERS = "w390dp-h560dp-xxhdpi"

/**
 * JVM screenshots of the archived-name warning (web ArchivedNameCollisionBanner, slock #12614),
 * light and dark: Create Channel's banner for someone who may unarchive (Unarchive, Open Channel,
 * Change Name) and for someone who may not (Open Channel, Change Name); and channel settings' Info
 * form after a rename onto an archived channel's name (Unarchive and Open Channel, no Change
 * Name), also after that Unarchive failed. Verify / record as
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
    @Config(qualifiers = RENAME_QUALIFIERS)
    fun renameUnarchiveFailedLight() =
        captureRename("channel.settings.rename.archived-name.unarchive-failed", RaftAppAppearance.Light, unarchiveFailed = true)

    private fun captureRename(
        name: String,
        appearance: RaftAppAppearance,
        unarchiveFailed: Boolean = false,
        withWarning: Boolean = true,
    ) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(20.dp)) {
                    ChannelInfoForm(
                        channel =
                            HomeChannelRow(
                                threadId = ThreadId("ch-design"),
                                channelId = ChannelId("ch-design"),
                                name = "#launch-room",
                                subtitle = "Product design reviews",
                            ),
                        nameEditable = true,
                        enabled = true,
                        status = ChannelProfileUpdateStatus.Failed,
                        errorCode = if (unarchiveFailed) CHANNEL_SETTINGS_ARCHIVED_UNARCHIVE_FAILED else ARCHIVED_NAME_COLLISION,
                        slackBridge = null,
                        onSave = { _, _, _ -> },
                        onClearStatus = {},
                        archivedCollision =
                            if (!withWarning) null else ChannelInfoArchivedCollision(
                                collision =
                                    ArchivedNameCollision(
                                        archivedChannelId = ChannelId("ch-old"),
                                        archivedChannelName = "launch-room",
                                        archivedChannelType = "channel",
                                        canUnarchive = true,
                                    ),
                                unarchiving = false,
                                onUnarchive = {},
                                onOpen = {},
                                onClear = {},
                            ),
                        errorMessage = null,
                    )
                }
            }
        }
    }

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
}
