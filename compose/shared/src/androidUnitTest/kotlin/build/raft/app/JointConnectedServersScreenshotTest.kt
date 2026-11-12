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
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.RaftLongMessageCollapseConversationPreference
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.ThreadId
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

/**
 * Connected servers of a joint channel, seen by the host's owner (web EditChannelDialog
 * `serversSection`, slock c8849c8b3cfd), light and dark:
 * - `joint.connected-servers.host`: the section alone on the details' 20dp inset: the heading
 *   (server icon, the name, ⓘ, ⋮), Botiverse with its Host badge beside the name, "botiverse ·
 *   This server", and Raft with its Disconnect;
 * - `channel.settings.joint`: where it sits, after the members (no announcement here) and
 *   before Channel settings, in #wg-deck's details.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h260dp-xxhdpi")
class JointConnectedServersScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun hostLight() = section("joint.connected-servers.host", RaftAppAppearance.Light)

    @Test
    fun hostDark() = section("joint.connected-servers.host.dark", RaftAppAppearance.Dark)

    @Test
    @Config(qualifiers = "w390dp-h1500dp-xxhdpi")
    fun detailsLight() = details("channel.settings.joint", RaftAppAppearance.Light)

    @Test
    @Config(qualifiers = "w390dp-h1500dp-xxhdpi")
    fun detailsDark() = details("channel.settings.joint.dark", RaftAppAppearance.Dark)

    private fun section(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(20.dp)) {
                    hostSection()
                }
            }
        }

    private fun details(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                ChannelSettingsScaffold(refreshing = false, onBack = {}) {
                    ChannelSettingsContent(
                        model =
                            ChannelSettingsUiModel(
                                channel = wgDeck,
                                memberSlice = SharedServerChannelMembersSlice(channelId = "ch-deck", status = SharedServerMembersLoadStatus.Loaded),
                                pinned = false,
                                muted = false,
                                permissions = channelSettingsPermissions(wgDeck, canManage = true),
                                writeActionsEnabled = true,
                                canStopAllAgents = false,
                                showGuestAccess = false,
                                externalChannelsEnabled = true,
                                section = ChannelSectionPlacement(customSections = emptyList(), pinned = false, placementSectionId = null),
                                serverName = "Botiverse",
                            ),
                        longMessageCollapseSetting =
                            RaftLongMessageCollapseConversationSetting(
                                preference = RaftLongMessageCollapseConversationPreference(globalEnabled = false, channelOverride = null),
                                available = true,
                                onSetEnabled = {},
                            ),
                        servers = { hostSection() },
                        callbacks = ChannelSettingsCallbacks(),
                    )
                }
            }
        }
}

@Composable
private fun hostSection() =
    JointConnectedServersSection(
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
    )

private val wgDeck =
    HomeChannelRow(
        threadId = ThreadId("ch-deck"),
        channelId = ChannelId("ch-deck"),
        channelType = "joint",
        name = "wg-deck",
        subtitle = "Deck working group",
        memberCount = 2,
        jointRole = "host",
    )

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
