package build.raft.app

import build.raft.app.data.HomeChannelRow
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
    fun channelSettingsIsAuthenticatedStandaloneRoute() {
        assertTrue(CHANNEL_SETTINGS_PAGE in authenticatedProductRoutePageNames)
        assertEquals(CHANNEL_SETTINGS_PAGE, AuthenticatedProductRoute.ChannelSettings.pageName)
    }

    @Test
    fun standalonePageRequiresNewDesignBetaPreference() {
        assertFalse(channelSettingsUsesStandalonePage(newDesignSettingsEnabled = false))
        assertTrue(channelSettingsUsesStandalonePage(newDesignSettingsEnabled = true))
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
        )

    private fun actions(channel: HomeChannelRow, canManage: Boolean = true, stopAgents: Boolean = false) =
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
            )
        )

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
}
