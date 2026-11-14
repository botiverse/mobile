package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import androidx.compose.ui.unit.dp
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun archiveAndUnarchiveStayInTheChannel() {
        listOf(ChannelSettingsAction.Archive, ChannelSettingsAction.Unarchive, null).forEach { action ->
            assertEquals(
                ChannelSettingsCloseLanding.StayInChannel,
                channelSettingsCloseLanding(action, landingRow("private")),
                "$action",
            )
        }
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
    fun routeCarriesTheOpeningConversationsLinkWhenGiven() {
        val pageData = channelSettingsPageData(ServerId("server-1"), ChannelId("channel-9"), "thread-settings:a")

        assertEquals("thread-settings:a", pageData.optString(THREAD_SETTINGS_LINK_KEY))
        assertEquals(
            ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-9")),
            pageData.toChannelSettingsRouteOrNull(),
        )
        assertEquals("", channelSettingsPageData(ServerId("server-1"), ChannelId("channel-9")).optString(THREAD_SETTINGS_LINK_KEY))
    }

    @Test
    fun routeCarriesIdentityOnly() {
        val pageData = channelSettingsPageData(ServerId("server-1"), ChannelId("channel-9"))

        assertEquals(CHANNEL_SETTINGS_PAGE, pageData.optString("pageName"))
        assertEquals("server-1", pageData.optString("serverId"))
        assertEquals("channel-9", pageData.optString("channelId"))
        assertEquals(
            ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-9")),
            pageData.toChannelSettingsRouteOrNull()
        )
        val serialized = pageData.toString()
        assertFalse(serialized.contains("channelName"))
        assertFalse(serialized.contains("description"))
        assertFalse(serialized.contains("members"))
        assertFalse(serialized.contains("permissions"))
        assertFalse(serialized.contains("activityMuted"))
        assertFalse(serialized.contains("pinned"))
    }

    @Test
    fun routeRejectsMissingIdentity() {
        assertNull(JSONObject().toChannelSettingsRouteOrNull())
        assertNull(JSONObject().apply { put("serverId", "server-1") }.toChannelSettingsRouteOrNull())
        assertNull(JSONObject().apply { put("channelId", "channel-9") }.toChannelSettingsRouteOrNull())
    }

    @Test
    fun pageSectionsDoNotReintroduceRemovedToolsCard() {
        assertEquals(
            listOf("identity", "members", "preferences"),
            channelSettingsSectionKeys(canManage = false)
        )
        assertEquals(
            listOf("identity", "members", "preferences", "management"),
            channelSettingsSectionKeys(canManage = true)
        )
        val keys = channelSettingsSectionKeys(canManage = true)
        assertFalse("tools" in keys)
        assertFalse("search" in keys)
        assertFalse("tasks" in keys)
        assertFalse("files" in keys)
    }

    @Test
    fun managementKeepsOriginalThreadSettingsActionOrder() {
        assertEquals(
            listOf("visibility", "archive", "delete"),
            channelSettingsManagementActions(showMakePrivate = true).map { it.id }
        )
        assertEquals(
            listOf("archive", "delete"),
            channelSettingsManagementActions(showMakePrivate = false).map { it.id }
        )
    }

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

    @Test
    fun archiveAndDeleteUseTheSameSingleConfirmationContract() {
        assertEquals(
            ThreadSettingsDangerConfirmationMode.SingleConfirm,
            threadSettingsDangerConfirmationMode("archive")
        )
        assertEquals(
            ThreadSettingsDangerConfirmationMode.SingleConfirm,
            threadSettingsDangerConfirmationMode("delete")
        )
        assertEquals(
            ThreadSettingsDangerConfirmationMode.Explanation,
            threadSettingsDangerConfirmationMode("visibility")
        )
        assertEquals(
            ThreadSettingsDangerConfirmationMode.Unsupported,
            threadSettingsDangerConfirmationMode("unknown")
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
        externalChannels: Boolean = false,
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
                showGuestAccess = false,
                externalChannelsEnabled = externalChannels,
            )
        )

    @Test
    fun privateMemberConnectNeedsTheFlagAPrivateChannelTheViewerIsInAndAServerToConnect() {
        // Web EditChannelDialog showPrivateConnectSection: private, not archived, memberConnect.available.
        val account = memberTestAccount()
        val private = row(name = "partners", type = "private")
        assertTrue(channelSettingsOffersPrivateMemberConnect(private, externalChannelsEnabled = true, account = account))
        // The flag gates it: off, the page is as before.
        assertFalse(channelSettingsOffersPrivateMemberConnect(private, externalChannelsEnabled = false, account = account))
        // Only a private channel: not a public one, not a joint one (its own Connected servers section).
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners"), externalChannelsEnabled = true, account = account))
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners", type = "joint"), externalChannelsEnabled = true, account = account))
        // Never archived, never #all, only for a viewer in the channel.
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners", type = "private", archived = true), externalChannelsEnabled = true, account = account))
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "all", type = "private"), externalChannelsEnabled = true, account = account))
        assertFalse(channelSettingsOffersPrivateMemberConnect(row(name = "partners", type = "private", joined = false), externalChannelsEnabled = true, account = account))
        // A server to connect: none known yet, or none the viewer owns or administers.
        assertFalse(channelSettingsOffersPrivateMemberConnect(private, externalChannelsEnabled = true, account = null))
        val memberOnly = account.copy(servers = account.servers.filter { it.role != "owner" && it.role != "admin" || it.slug == "community" || it.id == account.currentServerId })
        assertFalse(channelSettingsOffersPrivateMemberConnect(private, externalChannelsEnabled = true, account = memberOnly))
    }

    @Test
    fun hostsJointCopyOffersArchiveOnlyWithExternalChannels() {
        val archiveLeave = listOf(ChannelSettingsActionRow.Archive, ChannelSettingsActionRow.Leave)
        val withDisconnect = archiveLeave + ChannelSettingsActionRow.Disconnect
        // Web hostCannotDisconnect: the server's jointRole, and the flag; off, as today.
        assertEquals(archiveLeave, actions(row(type = "joint", jointRole = "host"), externalChannels = true))
        assertEquals(withDisconnect, actions(row(type = "joint", jointRole = "host"), externalChannels = false))
        assertEquals(withDisconnect, actions(row(type = "joint", jointRole = "participant"), externalChannels = true))
        assertEquals(withDisconnect, actions(row(type = "joint"), externalChannels = true))
        assertTrue(channelSettingsHostCannotDisconnect(row(type = "joint", jointRole = "host"), externalChannelsEnabled = true))
        assertFalse(channelSettingsHostCannotDisconnect(row(type = "joint", jointRole = "host"), externalChannelsEnabled = false))
        assertFalse(channelSettingsHostCannotDisconnect(row(jointRole = "host"), externalChannelsEnabled = true))
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
    fun guestPolicyAppliesToPublicChannelsAndHiddenAll() {
        assertTrue(channelSettingsGuestPolicyApplies(row()))
        assertFalse(channelSettingsGuestPolicyApplies(row(type = "private")))
        assertTrue(channelSettingsGuestPolicyApplies(row(name = "all", type = "private")))
        assertFalse(channelSettingsGuestPolicyApplies(row(type = "joint")))
    }

    @Test
    fun membersStripKeepsTheAddSlotAndAPlusTileWithinThreeRows() {
        // 8 columns x 3 rows = 24 slots.
        assertEquals(8, channelSettingsMemberStripColumns(350.dp))
        assertEquals(23, channelSettingsMemberStripVisibleCount(23, 8, withAddSlot = true))
        assertEquals(22, channelSettingsMemberStripVisibleCount(24, 8, withAddSlot = true))
        assertEquals(24, channelSettingsMemberStripVisibleCount(24, 8, withAddSlot = false))
        assertEquals(23, channelSettingsMemberStripVisibleCount(30, 8, withAddSlot = false))
    }

    @Test
    fun addTileOpensMembersInAddModeThroughAnIntentKeyOnly() {
        val route = ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-9"))
        val add = channelSettingsMembersPageData(route, openAddMember = true)
        assertEquals("add", add.optString(THREAD_MEMBERS_INITIAL_VIEW_KEY))
        assertEquals(true, add.toThreadMembersPageRouteOrNull()?.openAddMember)
        assertEquals(false, channelSettingsMembersPageData(route).toThreadMembersPageRouteOrNull()?.openAddMember)
    }

    @Test
    fun archiveTakesTheChannelOffHomeWhileStayingOnThePage() {
        val channelId = ChannelId("channel-1")
        val landing = channelSettingsCloseLanding(ChannelSettingsAction.Archive, null)

        assertEquals(ChannelSettingsCloseLanding.StayInChannel, landing)
        assertEquals(
            SharedHomeIntent.ChannelMembershipEnded(channelId, keepListed = false),
            channelSettingsCloseHomeIntent(ChannelSettingsAction.Archive, landing, channelId),
        )
    }

    @Test
    fun aProfileEditLeavesHomeAlone() {
        val landing = channelSettingsCloseLanding(ChannelSettingsAction.Profile, null)

        assertEquals(null, channelSettingsCloseHomeIntent(ChannelSettingsAction.Profile, landing, ChannelId("channel-1")))
    }

    @Test
    fun theLinkUnderTheNewestPageAnswersAgainWhenTheNewestGoes() {
        val key = "thread-settings:link-stack-test"
        val under = ThreadSettingsPageLink()
        val over = ThreadSettingsPageLink()

        ThreadSettingsPageLinks.register(key, under)
        ThreadSettingsPageLinks.register(key, over)
        assertSame(over, ThreadSettingsPageLinks.get(key))

        ThreadSettingsPageLinks.unregister(key, over)
        assertSame(under, ThreadSettingsPageLinks.get(key))

        ThreadSettingsPageLinks.unregister(key, under)
        assertNull(ThreadSettingsPageLinks.get(key))
    }

    // Forms "A setting's row" (raft-design #1204): by the row's own width, not the window's.
    @Test
    fun aSelectGoesToTheRowEndOnlyFrom32remOfTheRowsOwnWidth() {
        assertTrue(channelDetailsSelectAtRowEnd(512.dp))
        assertTrue(channelDetailsSelectAtRowEnd(720.dp))
        assertFalse(channelDetailsSelectAtRowEnd(511.dp))
        // The 400 wide details column, inset: under the title on a wide window too.
        assertFalse(channelDetailsSelectAtRowEnd(366.dp))
    }

    // raft-design channel.md: Make private and Make public are the Public or Private row's button.
    @Test
    fun theVisibilityActionIsAChannelSettingsRowNotAnActionAtTheEnd() {
        assertTrue(channelSettingsIsVisibilityAction(ChannelSettingsActionRow.MakePrivate))
        assertTrue(channelSettingsIsVisibilityAction(ChannelSettingsActionRow.MakePublic))
        ChannelSettingsActionRow.entries
            .filterNot { it == ChannelSettingsActionRow.MakePrivate || it == ChannelSettingsActionRow.MakePublic }
            .forEach { assertFalse(channelSettingsIsVisibilityAction(it)) }
    }
}
