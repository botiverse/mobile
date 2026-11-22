package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.compat.json.JSONObject
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelSettingsPageTest {
    @Test
    fun missingMemberSliceRendersLoadingInsteadOfZero() {
        val missing =
            SharedServerChannelMembersSlice(
                serverId = "server-1",
                channelId = "channel-1",
                status = SharedServerMembersLoadStatus.InFlight,
            )

        assertEquals("Loading", channelSettingsMemberCountLabel(missing, fallbackCount = null, loadingLabel = "Loading"))
    }

    @Test
    fun deleteAndDisconnectAlwaysLeaveTheConversation() {
        listOf(ChannelSettingsAction.Delete, ChannelSettingsAction.Disconnect).forEach { action ->
            listOf(landingRow("channel"), landingRow("private"), null).forEach { channel ->
                assertEquals(
                    ChannelSettingsCloseLanding.LeaveConversation,
                    channelSettingsCloseLanding(action, channel),
                    "$action ${channel?.channelType}",
                )
            }
        }
    }

    @Test
    fun leavingAPublicChannelStaysNotJoinedAndAPrivateOrJointOneGoes() {
        assertEquals(
            ChannelSettingsCloseLanding.StayNotJoined,
            channelSettingsCloseLanding(ChannelSettingsAction.Leave, landingRow("channel")),
        )
        assertEquals(
            ChannelSettingsCloseLanding.LeaveConversation,
            channelSettingsCloseLanding(ChannelSettingsAction.Leave, landingRow("private")),
        )
        assertEquals(
            ChannelSettingsCloseLanding.LeaveConversation,
            channelSettingsCloseLanding(ChannelSettingsAction.Leave, landingRow("joint")),
        )
        assertEquals(
            ChannelSettingsCloseLanding.LeaveConversation,
            channelSettingsCloseLanding(ChannelSettingsAction.Leave, landingRow("channel").copy(kind = ConversationKind.Joint)),
        )
        // Not known to be public: no stale page.
        assertEquals(
            ChannelSettingsCloseLanding.LeaveConversation,
            channelSettingsCloseLanding(ChannelSettingsAction.Leave, null),
        )
    }

    @Test
    fun theLandingTellsHomeWhetherTheRowStays() {
        val id = ChannelId("channel-9")
        assertNull(ChannelSettingsCloseLanding.StayInChannel.homeIntent(id))
        assertEquals(
            SharedHomeIntent.ChannelMembershipEnded(id, keepListed = true),
            ChannelSettingsCloseLanding.StayNotJoined.homeIntent(id),
        )
        assertEquals(
            SharedHomeIntent.ChannelMembershipEnded(id, keepListed = false),
            ChannelSettingsCloseLanding.LeaveConversation.homeIntent(id),
        )
    }

    private fun landingRow(channelType: String) =
        HomeChannelRow(
            threadId = ThreadId("channel-9"),
            channelId = ChannelId("channel-9"),
            channelType = channelType,
            name = "nine",
        )

    @Test
    fun jointChannelUsesBranchIdentityAndPrivateManagementSemantics() {
        val joint =
            HomeChannelRow(
                threadId = ThreadId("joint-1"),
                channelId = ChannelId("joint-1"),
                channelType = "joint",
                kind = ConversationKind.Joint,
                name = "raft-mobile",
            )

        assertEquals(
            RaftLucideIconName.GitBranch,
            joint
                .toThreadLaunchConversationIdentity()
                .threadRouteChannelKind()
                .threadChannelHeaderIcon(),
        )
        assertTrue(channelSettingsIsPrivate(joint))
        assertFalse(channelSettingsCanMakePrivate(joint))
        assertFalse(channelSettingsCanDelete(joint))
        assertEquals(
            listOf("archive"),
            channelSettingsManagementActions(showMakePrivate = false, showDelete = false).map { it.id },
        )
    }

    private fun row(
        name: String = "triage",
        type: String = "channel",
        archived: Boolean = false,
        joined: Boolean = true,
        capabilities: ChannelCapabilities? = null,
        jointRole: String? = null,
    ): HomeChannelRow =
        HomeChannelRow(
            threadId = ThreadId("c"),
            channelId = ChannelId("c"),
            channelType = type,
            kind = if (type == "joint") ConversationKind.Joint else ConversationKind.Channel,
            name = name,
            archived = archived,
            joined = joined,
            channelCapabilities = capabilities,
            jointRole = jointRole,
        )

    private fun actions(
        channel: HomeChannelRow,
        canManage: Boolean = true,
        stopAgents: Boolean = false,
    ) =
        channelSettingsActionRows(
            ChannelSettingsUiModel(
                channel = channel,
                memberSlice = SharedServerChannelMembersSlice(channelId = "c"),
                pinned = false,
                muted = false,
                permissions = channelSettingsPermissions(channel, canManage),
                writeActionsEnabled = true,
                canStopAllAgents = stopAgents,
            )
        )

    @Test
    fun privateMemberConnectNeedsAPrivateChannelTheViewerIsInAndAServerToConnect() {
        // Web EditChannelDialog showPrivateConnectSection: private, not archived, memberConnect.available.
        val account = memberTestAccount()
        val private = row(name = "partners", type = "private")
        assertTrue(channelSettingsOffersPrivateMemberConnect(private, account = account))
        // Only a private channel: not a public one, not a joint one (its own Connected servers section).
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners"), account = account))
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners", type = "joint"), account = account))
        // Never archived, never #all, only for a viewer in the channel.
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners", type = "private", archived = true), account = account))
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "all", type = "private"), account = account))
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners", type = "private", joined = false), account = account))
        // A server to connect: none known yet, or none the viewer owns or administers.
        assertFalse(channelSettingsOffersPrivateMemberConnect(private, account = null))
        val memberOnly = account.copy(servers = account.servers.filter { it.role != "owner" && it.role != "admin" || it.slug == "community" || it.id == account.currentServerId })
        assertFalse(channelSettingsOffersPrivateMemberConnect(private, account = memberOnly))
    }

    @Test
    fun actionsFollowTheWebOrderAndDropWhatTheChannelCannotDo() {
        assertEquals(
            listOf(
                ChannelSettingsActionRow.MakePrivate,
                ChannelSettingsActionRow.Archive,
                ChannelSettingsActionRow.Leave,
                ChannelSettingsActionRow.StopAllAgents,
                ChannelSettingsActionRow.Delete,
            ),
            actions(row(), stopAgents = true),
        )
        assertEquals(
            listOf(ChannelSettingsActionRow.MakePublic, ChannelSettingsActionRow.Archive, ChannelSettingsActionRow.Leave, ChannelSettingsActionRow.Delete),
            actions(row(type = "private")),
        )
        // Archived: only Unarchive (web shows no Leave, Stop or Delete on an archived channel).
        assertEquals(listOf(ChannelSettingsActionRow.Unarchive), actions(row(archived = true)))
        // Joint: no visibility; Disconnect takes Delete's place.
        assertEquals(
            listOf(ChannelSettingsActionRow.Archive, ChannelSettingsActionRow.Leave, ChannelSettingsActionRow.Disconnect),
            actions(row(type = "joint")),
        )
        // #all: nothing but Stop All Agents.
        assertEquals(listOf(ChannelSettingsActionRow.StopAllAgents), actions(row(name = "all"), stopAgents = true))
        // A plain member: Leave only.
        assertEquals(listOf(ChannelSettingsActionRow.Leave), actions(row(), canManage = false))
        assertEquals(emptyList(), actions(row(joined = false), canManage = false))
    }

    @Test
    fun channelCapabilitiesOverrideTheServerRole() {
        val archiveOnly = ChannelCapabilities(archiveChannels = true, editChannelMetadata = false)
        assertEquals(
            listOf(ChannelSettingsActionRow.Archive, ChannelSettingsActionRow.Leave),
            actions(row(capabilities = archiveOnly), canManage = false),
        )
        val permissions = channelSettingsPermissions(row(capabilities = archiveOnly), canManage = true)
        assertFalse(permissions.editMetadata)
        assertTrue(permissions.canEditChannel)
        assertFalse(channelSettingsPermissions(row(capabilities = ChannelCapabilities()), canManage = true).canEditChannel)
    }

    @Test
    fun visitorVisibleRequiresExplicitManageVisitorAccessAndRespectsChannelGuards() {
        val withCap = ChannelCapabilities(manageVisitorAccess = true)
        val withoutCap = ChannelCapabilities(editChannelMetadata = true, changeChannelVisibility = true, manageVisitorAccess = false)

        // Absent channelCapabilities never infers manageVisitorAccess from canManage.
        assertFalse(channelSettingsPermissions(row(capabilities = null), canManage = true).manageVisitorAccess)
        assertFalse(channelSettingsPermissions(row(capabilities = withoutCap), canManage = true).manageVisitorAccess)
        val granted = channelSettingsPermissions(row(capabilities = withCap), canManage = false)
        assertTrue(granted.manageVisitorAccess)
        assertFalse(granted.canEditChannel)

        val publicChannel = row(capabilities = withCap)
        assertTrue(channelSettingsOffersVisitorVisible(publicChannel, granted, serverPublicPageLive = true))
        // Unknown serverPublicPageLive leaves the row up so the server decides.
        assertTrue(channelSettingsOffersVisitorVisible(publicChannel, granted, serverPublicPageLive = null))

        // Off and cannot turn on: hidden; on: shown so it can be turned off.
        val cannotTurnOn =
            listOf(
                row(type = "private", capabilities = withCap) to true,
                row(name = "all", capabilities = withCap) to true,
                row(capabilities = withCap) to false,
                row(capabilities = withCap).copy(hasExternalRelation = true) to true,
            )
        cannotTurnOn.forEach { (offChannel, publicPageLive) ->
            assertFalse(channelSettingsOffersVisitorVisible(offChannel, granted, publicPageLive))
            assertTrue(channelSettingsOffersVisitorVisible(offChannel.copy(guestVisible = true), granted, publicPageLive))
        }

        // Joint and archived channels never show the row, even when on.
        assertFalse(channelSettingsOffersVisitorVisible(row(type = "joint", capabilities = withCap).copy(guestVisible = true), granted, true))
        assertFalse(channelSettingsOffersVisitorVisible(row(archived = true, capabilities = withCap).copy(guestVisible = true), granted, true))

        // Channel settings section shows when Visible to visitors is the only row (e.g. #all while on).
        val allOn = row(name = "all", capabilities = withCap).copy(guestVisible = true)
        assertTrue(
            channelDetailsHasChannelSettings(
                ChannelSettingsUiModel(
                    channel = allOn,
                    memberSlice = SharedServerChannelMembersSlice(channelId = "c"),
                    pinned = false,
                    muted = false,
                    permissions = granted,
                    writeActionsEnabled = true,
                    canStopAllAgents = false,
                    serverPublicPageLive = true,
                )
            )
        )
    }

    @Test
    fun visitorVisibleRefusalCodesMapToDedicatedCopyAndFallback() {
        assertEquals(Res.string.channel_settings_visitor_visible_forbidden, channelSettingsVisitorVisibleErrorRes("visitor_visibility_forbidden"))
        assertEquals(
            Res.string.channel_settings_visitor_visible_requires_public_server,
            channelSettingsVisitorVisibleErrorRes("visitor_visibility_requires_public_server"),
        )
        assertEquals(
            Res.string.channel_settings_visitor_visible_requires_public_channel,
            channelSettingsVisitorVisibleErrorRes("visitor_visibility_requires_public_channel"),
        )
        assertEquals(
            Res.string.channel_settings_visitor_visible_unsupported_channel,
            channelSettingsVisitorVisibleErrorRes("visitor_visibility_unsupported_channel"),
        )
        assertEquals(Res.string.channel_settings_visitor_visible_save_failed, channelSettingsVisitorVisibleErrorRes("visitor_visible_invalid"))
        assertEquals(Res.string.channel_settings_visitor_visible_save_failed, channelSettingsVisitorVisibleErrorRes("visitor_visible_conflict"))
        assertEquals(Res.string.channel_settings_visitor_visible_save_failed, channelSettingsVisitorVisibleErrorRes("server_unavailable"))
    }
}
