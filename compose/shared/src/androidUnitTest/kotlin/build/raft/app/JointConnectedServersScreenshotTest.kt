package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.ChannelId
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelRole
import build.raft.app.data.JointChannelServer
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Connected servers of a joint channel, seen by the host's owner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h420dp-xxhdpi")
class JointConnectedServersScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun hostLight() = capture("joint.connected-servers.host", RaftAppAppearance.Light)

    @Test
    fun hostDark() = capture("joint.connected-servers.host.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    JointConnectionsContent(
                        state = connectedServersHostState(),
                        slug = "",
                        people = "",
                        sameAdminConfirmed = false,
                        statusMessage = null,
                        onSlugChange = {},
                        onPeopleChange = {},
                        onSameAdminConfirmedChange = {},
                        onSendInvite = {},
                        onResend = {},
                        bottomSafeArea = 0.dp,
                    )
                }
            }
        }
}

/** artin's #wg-deck: Botiverse hosts it (this server, its owner looking), Raft connected; one more server to connect. */
internal fun connectedServersHostState(): JointChannelConnectionsState =
    JointChannelConnectionsState(
        status = JointChannelConnectionsLoadStatus.Content,
        connections =
            JointChannelConnections(
                channelId = ChannelId("ch-deck"),
                channelName = "wg-deck",
                isJoint = true,
                archived = false,
                servers =
                    listOf(
                        JointChannelServer("srv-botiverse", "Botiverse", "botiverse", active = true, isCurrentServer = true, plan = null, role = JointChannelRole.Host),
                        JointChannelServer("srv-raft", "Raft", "raft", active = true, isCurrentServer = false, plan = null, role = JointChannelRole.Participant),
                    ),
                hasCurrentServerPendingInvites = false,
                jointRole = JointChannelRole.Host,
                federateChannels = true,
                joined = true,
            ),
        canFederate = true,
        externalChannelsEnabled = true,
        memberConnectAccount =
            MemberConnectAccount(
                currentServerId = "srv-botiverse",
                currentServerName = "Botiverse",
                currentServerSlug = "botiverse",
                currentServerRole = "owner",
                servers =
                    listOf(
                        MemberConnectServer("srv-botiverse", "Botiverse", "botiverse", null, "owner"),
                        MemberConnectServer("srv-lab", "Lab", "lab", null, "owner"),
                    ),
            ),
    )
