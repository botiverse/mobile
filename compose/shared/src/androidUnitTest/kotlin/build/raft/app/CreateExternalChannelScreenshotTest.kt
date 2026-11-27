package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.CreateChannelResult
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.InviteConnectableServer
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import build.raft.app.resources.Res
import build.raft.app.resources.channel_external_invite_title
import build.raft.app.resources.common_close
import build.raft.app.resources.stringResource
import build.raft.app.settings.SharedCreateChannelVisibility
import build.raft.app.settings.SharedCreateChannelVisibilityControl
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of Create Channel's External choice (`channel.create.external*`): step 1's
 * visibility with External chosen (its hint) and at the Free plan's limit (its line and Upgrade for
 * more), and the review after a create whose invites weren't sent. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CreateExternalChannelScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun visibilityFreeLimitDark() =
        capture("channel.create.external.free-limit.dark", RaftAppAppearance.Dark) {
            SharedCreateChannelVisibilityControl(
                selected = SharedCreateChannelVisibility.External,
                enabled = true,
                onSelected = {},
                externalOffered = true,
                freeLimitServerName = "Acme",
            )
        }

    @Test
    fun reviewAfterTheCreateLight() =
        capture("channel.create.external.review-created", RaftAppAppearance.Light, sheet = true) {
            SharedCreateExternalChannelReviewBody(
                state =
                    CreateExternalChannelState(
                        availability = ExternalChannelCreateAvailability.Open,
                        invite =
                            ExternalInviteDialogState(
                                text = "lena@contoso.com",
                                preview = ExternalInvitePreview(valid = listOf("lena@contoso.com"), invalid = listOf("not-an-email")),
                                failure = ExternalInviteFailure(ExternalInviteError.DailyCap),
                                servers = listOf(InviteConnectableServer(id = "s2", name = "Botiverse", canConnectDirectly = true)),
                                serverIds = setOf("s2"),
                            ),
                        created = CreateChannelResult(HomeChannelRow(threadId = ThreadId("c1"), channelId = ChannelId("c1"), name = "vendors")),
                    ),
                serverName = "Acme",
                onUpgrade = {},
            )
        }

    private fun capture(name: String, appearance: RaftAppAppearance, sheet: Boolean = false, content: @Composable () -> Unit) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                if (sheet) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        RaftDialogSheetSurface(
                            title = stringResource(Res.string.channel_external_invite_title, "Acme"),
                            commitLabel = "Invite",
                            commitEnabled = true,
                            onCommit = {},
                            onClose = {},
                            modifier = Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction).wrapContentHeight(Alignment.Bottom),
                            closeLabel = stringResource(Res.string.common_close),
                        ) {
                            content()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(20.dp)) { content() }
                }
            }
        }
    }
}
