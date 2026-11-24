package build.raft.app

import build.raft.app.data.ErrorPresentationKey
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
        // Web EditChannelDialog showPrivateConnectRow (slock #16395/#16454): private, not archived, not converting, memberConnect.available.
        val account = memberTestAccount()
        val private = row(name = "partners", type = "private")
        assertTrue(channelSettingsOffersPrivateMemberConnect(private, account = account))
        // Hidden while a conversion to an external channel is under way.
        assertFalse(channelSettingsOffersPrivateMemberConnect(private, account = account, conversionInProgress = true))
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

        // Channel settings section shows when Connect your servers is the only row (e.g. plain member of a private channel).
        assertTrue(
            channelDetailsHasChannelSettings(
                ChannelSettingsUiModel(
                    channel = private,
                    memberSlice = SharedServerChannelMembersSlice(channelId = "c"),
                    pinned = false,
                    muted = false,
                    permissions = channelSettingsPermissions(private, canManage = false),
                    writeActionsEnabled = true,
                    canStopAllAgents = false,
                    privateMemberConnect = true,
                )
            )
        )
    }

    @Test
    fun guestMemberConnectRequiresAGuestWhoCannotAddMembersAndNoConversion() {
        val guestAccount = memberTestAccount().copy(currentServerRole = "guest", servers = listOf(MemberConnectServer("srv-cur", "Current Co", "current-co", null, "guest")))
        val private = row(name = "partners", type = "private")
        val hostJoint = row(name = "partners", type = "joint", jointRole = "host")
        val participantJoint = row(name = "partners", type = "joint", jointRole = "participant")

        assertTrue(channelSettingsOffersGuestMemberConnect(private, canAddMembers = false, account = guestAccount))
        assertTrue(channelSettingsOffersGuestMemberConnect(hostJoint, canAddMembers = false, account = guestAccount))
        // Participant copy, public channel, #all, archived, unjoined, non-guest, canAddMembers, or converting -> hidden.
        assertFalse(channelSettingsOffersGuestMemberConnect(participantJoint, canAddMembers = false, account = guestAccount))
        assertFalse(channelSettingsOffersGuestMemberConnect(row(name = "partners"), canAddMembers = false, account = guestAccount))
        assertFalse(channelSettingsOffersGuestMemberConnect(row(name = "all", type = "private"), canAddMembers = false, account = guestAccount))
        assertFalse(channelSettingsOffersGuestMemberConnect(row(name = "partners", type = "private", archived = true), canAddMembers = false, account = guestAccount))
        assertFalse(channelSettingsOffersGuestMemberConnect(row(name = "partners", type = "private", joined = false), canAddMembers = false, account = guestAccount))
        assertFalse(channelSettingsOffersGuestMemberConnect(private, canAddMembers = true, account = guestAccount))
        assertFalse(channelSettingsOffersGuestMemberConnect(private, canAddMembers = false, account = guestAccount, conversionInProgress = true))
        assertFalse(channelSettingsOffersGuestMemberConnect(private, canAddMembers = false, account = memberTestAccount()))
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

    @Test
    fun channelSettingsFailureMessageSurfacesServerReasonForAllActionsAndScrubsInfra() {
        val strings = ErrorPresentationStrings(ErrorPresentationKey.entries.associateWith { it.name })
        val labels =
            ChannelSettingsFailureLabels(
                update = "Update failed",
                visibility = "Visibility failed",
                archive = "Archive failed",
                unarchive = "Unarchive failed",
                delete = "Delete failed",
                disconnect = "Disconnect failed",
            )

        assertEquals(
            "Only owners can archive announcement channels.",
            channelSettingsFailureMessage(
                ChannelSettingsEffect.Failure(
                    action = ChannelSettingsAction.Archive,
                    code = "http_403",
                    serverMessage = "Only owners can archive announcement channels.",
                    serverCode = "archive_forbidden",
                ),
                labels,
                strings,
            ),
        )
        assertEquals(
            "Cannot delete the default #all channel.",
            channelSettingsFailureMessage(
                ChannelSettingsEffect.Failure(
                    action = ChannelSettingsAction.Delete,
                    code = "http_409",
                    serverMessage = "Cannot delete the default #all channel.",
                    serverCode = "default_channel_protected",
                ),
                labels,
                strings,
            ),
        )
        assertEquals(
            "The host server cannot disconnect from a shared channel.",
            channelSettingsFailureMessage(
                ChannelSettingsEffect.Failure(
                    action = ChannelSettingsAction.Disconnect,
                    code = "host_cannot_disconnect",
                    serverMessage = "The host server cannot disconnect from a shared channel.",
                    serverCode = "host_cannot_disconnect",
                ),
                labels,
                strings,
            ),
        )
        assertEquals(
            "Delete failed",
            channelSettingsFailureMessage(
                ChannelSettingsEffect.Failure(
                    action = ChannelSettingsAction.Delete,
                    code = "http_400",
                    serverMessage = null,
                ),
                labels,
                strings,
            ),
        )
        assertEquals(
            ErrorPresentationKey.ServerUnavailable.name,
            channelSettingsFailureMessage(
                ChannelSettingsEffect.Failure(
                    action = ChannelSettingsAction.Archive,
                    code = "http_500",
                    serverMessage = "<html>500</html>",
                    serverCode = "internal_error",
                ),
                labels,
                strings,
            ),
        )
        assertEquals(
            ErrorPresentationKey.EdgeBlocked.name,
            channelSettingsFailureMessage(
                ChannelSettingsEffect.Failure(
                    action = ChannelSettingsAction.Delete,
                    code = "http_403",
                    serverMessage = "edge_blocked",
                    serverCode = "edge_blocked",
                ),
                labels,
                strings,
            ),
        )
    }

    @Test
    fun profileSlackAndVisitorVisibleFailureMessagesPreserveTiersAndScrubInfra() {
        val strings = ErrorPresentationStrings(ErrorPresentationKey.entries.associateWith { it.name })

        assertEquals(
            "Saved channel, Slack failed. That Slack channel is already paired.",
            channelSettingsProfileFailureMessage(
                errorCode = CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE,
                errorServerCode = "slack_channel_already_paired",
                errorMessage = "That Slack channel is already paired.",
                strings = strings,
                partialSlackLabel = "Saved channel, Slack failed.",
                unarchiveFallback = "Unarchive failed",
                updateFallback = "Update failed",
            ),
        )
        assertEquals(
            "Saved channel, Slack failed.",
            channelSettingsProfileFailureMessage(
                errorCode = CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE,
                errorServerCode = "internal_error",
                errorMessage = "SQL crash",
                strings = strings,
                partialSlackLabel = "Saved channel, Slack failed.",
                unarchiveFallback = "Unarchive failed",
                updateFallback = "Update failed",
            ),
        )
        assertEquals(
            "That channel name is reserved.",
            channelSettingsProfileFailureMessage(
                errorCode = "http_409",
                errorServerCode = "channel_name_reserved",
                errorMessage = "That channel name is reserved.",
                strings = strings,
                partialSlackLabel = "Saved channel, Slack failed.",
                unarchiveFallback = "Unarchive failed",
                updateFallback = "Update failed",
            ),
        )
        assertEquals(
            "Slack Bridge is disabled for this server.",
            channelSettingsSlackDialogFailureMessage(
                errorCode = CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE,
                errorServerCode = "slack_bridge_disabled",
                errorMessage = "Slack Bridge is disabled for this server.",
                strings = strings,
                fallback = "Slack failed",
            ),
        )
        assertEquals(
            "Slack failed",
            channelSettingsSlackDialogFailureMessage(
                errorCode = CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE,
                errorServerCode = "internal_error",
                errorMessage = "SQL crash",
                strings = strings,
                fallback = "Slack failed",
            ),
        )
        assertEquals(
            "KNOWN:visitor_visibility_forbidden",
            channelSettingsVisitorVisibleFailureMessage(
                errorCode = "http_403",
                errorServerCode = "visitor_visibility_forbidden",
                errorMessage = "English server text",
                strings = strings,
                knownCopy = { "KNOWN:visitor_visibility_forbidden" },
                fallback = "Save failed",
            ),
        )
        assertEquals(
            "Channels with external guests cannot be made visible to visitors.",
            channelSettingsVisitorVisibleFailureMessage(
                errorCode = "http_409",
                errorServerCode = "visitor_visible_external_conflict",
                errorMessage = "Channels with external guests cannot be made visible to visitors.",
                strings = strings,
                knownCopy = { "KNOWN" },
                fallback = "Save failed",
            ),
        )
        assertEquals(
            ErrorPresentationKey.EdgeBlocked.name,
            channelSettingsVisitorVisibleFailureMessage(
                errorCode = "http_403",
                errorServerCode = "edge_blocked",
                errorMessage = "edge_blocked",
                strings = strings,
                knownCopy = { "KNOWN" },
                fallback = "Save failed",
            ),
        )
        assertEquals(
            ErrorPresentationKey.ServerUnavailable.name,
            channelSettingsVisitorVisibleFailureMessage(
                errorCode = "http_500",
                errorServerCode = "internal_error",
                errorMessage = "panic",
                strings = strings,
                knownCopy = { "KNOWN" },
                fallback = "Save failed",
            ),
        )
    }
}
