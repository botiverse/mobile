package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import build.raft.app.thread.ThreadConversationPinUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The channel settings drawer (web ChannelOverflowMenu + ChannelPreferencesSection): Move to section, gating. */
class ChannelSettingsDrawerTest {
    private val sections =
        listOf(
            SettingsServerSidebarCustomSection(id = "s-launch", name = "Launch", emoji = "🚀"),
            SettingsServerSidebarCustomSection(id = "s-ops", name = "Ops"),
        )

    private fun placement(pinned: Boolean = false, sectionId: String? = null, canPin: Boolean = true) =
        ChannelSectionPlacement(customSections = sections, pinned = pinned, placementSectionId = sectionId, canPin = canPin)

    private fun channel(
        name: String = "design",
        type: String = "channel",
        archived: Boolean = false,
        kind: ConversationKind = ConversationKind.Channel,
    ) = HomeChannelRow(
        threadId = ThreadId("t-$name"),
        channelId = ChannelId("c-$name"),
        channelType = type,
        kind = kind,
        name = name,
        archived = archived,
    )

    @Test
    fun optionsListHomeThenPinnedThenMySectionsInOrder() {
        assertEquals(
            listOf(
                ChannelSectionValue.Home,
                ChannelSectionValue.Pinned,
                ChannelSectionValue.Custom("s-launch"),
                ChannelSectionValue.Custom("s-ops"),
            ),
            placement().options
        )
        assertFalse(ChannelSectionValue.Pinned in placement(canPin = false).options)
    }

    @Test
    fun homeUnpinsAndClearsAnyPlacement() {
        assertEquals(listOf(ChannelSectionMove.Unpin), channelSectionMoves(placement(pinned = true), ChannelSectionValue.Home))
        assertEquals(
            listOf(ChannelSectionMove.ClearPlacement),
            channelSectionMoves(placement(sectionId = "s-ops"), ChannelSectionValue.Home)
        )
        // An orphaned placement shows as home; picking home is how it is cleared (web).
        assertEquals(
            listOf(ChannelSectionMove.ClearPlacement),
            channelSectionMoves(placement(sectionId = "s-deleted"), ChannelSectionValue.Home)
        )
        assertEquals(emptyList<ChannelSectionMove>(), channelSectionMoves(placement(), ChannelSectionValue.Home))
    }

    @Test
    fun pinnedAndCustomPicksWriteOneMove() {
        assertEquals(listOf(ChannelSectionMove.Pin), channelSectionMoves(placement(sectionId = "s-ops"), ChannelSectionValue.Pinned))
        assertEquals(
            listOf(ChannelSectionMove.MoveTo("s-launch")),
            channelSectionMoves(placement(pinned = true), ChannelSectionValue.Custom("s-launch"))
        )
        assertEquals(emptyList<ChannelSectionMove>(), channelSectionMoves(placement(pinned = true), ChannelSectionValue.Pinned))
        assertEquals(emptyList<ChannelSectionMove>(), channelSectionMoves(placement(sectionId = "s-ops"), ChannelSectionValue.Custom("s-ops")))
        assertEquals(emptyList<ChannelSectionMove>(), channelSectionMoves(placement(), ChannelSectionValue.Custom("s-gone")))
        assertEquals(emptyList<ChannelSectionMove>(), channelSectionMoves(placement(canPin = false), ChannelSectionValue.Pinned))
    }

    @Test
    fun dmMovesPinByItsPeer() {
        val dmPin =
            ThreadConversationPinUiState(
                conversationId = "dm-friday",
                pinnedKind = "agent",
                pinnedId = "agent-friday",
                pinned = false,
                available = true
            )
        assertEquals(
            listOf(
                SharedHomeIntent.TogglePinned(conversationId = "dm-friday", pinnedKind = "agent", pinnedId = "agent-friday"),
                SharedHomeIntent.MoveConversationToSection("dm-friday", "s-launch"),
            ),
            channelSectionHomeIntents(dmPin, listOf(ChannelSectionMove.Pin, ChannelSectionMove.MoveTo("s-launch")))
        )
    }

    private fun rows(row: HomeChannelRow, canManage: Boolean, canStopAllAgents: Boolean = false) =
        channelSettingsActionRows(
            ChannelSettingsUiModel(
                channel = row,
                memberSlice = SharedServerChannelMembersSlice(channelId = row.channelId.value),
                pinned = false,
                muted = false,
                permissions = channelSettingsPermissions(row, canManage),
                writeActionsEnabled = true,
                canStopAllAgents = canStopAllAgents,
            )
        )

    @Test
    fun ownerGetsInfoAndEveryActionInTheWebOrder() {
        val row = channel()
        val panel = ThreadChannelSettingsPanel(channel = row, permissions = channelSettingsPermissions(row, canManage = true))
        assertTrue(panel.showsInfoForm())
        assertTrue(panel.nameEditable())
        assertEquals(
            listOf(
                ChannelSettingsActionRow.MakePrivate,
                ChannelSettingsActionRow.Archive,
                ChannelSettingsActionRow.Leave,
                ChannelSettingsActionRow.StopAllAgents,
                ChannelSettingsActionRow.Delete,
            ),
            rows(row, canManage = true, canStopAllAgents = true)
        )
    }

    @Test
    fun memberWithoutCapabilitiesGetsNoInfoFormAndOnlyLeave() {
        val row = channel()
        val panel = ThreadChannelSettingsPanel(channel = row, permissions = channelSettingsPermissions(row, canManage = false))
        assertFalse(panel.showsInfoForm())
        assertEquals(listOf(ChannelSettingsActionRow.Leave), rows(row, canManage = false))
    }

    @Test
    fun allChannelKeepsItsNameAndOffersNoLifecycleActions() {
        val row = channel(name = "all")
        val panel = ThreadChannelSettingsPanel(channel = row, permissions = channelSettingsPermissions(row, canManage = true))
        assertTrue(panel.showsInfoForm())
        assertFalse(panel.nameEditable())
        assertEquals(emptyList<ChannelSettingsActionRow>(), rows(row, canManage = true))
    }

    @Test
    fun archivedChannelOffersOnlyUnarchiveAndJointOffersDisconnect() {
        val archived = channel(archived = true)
        val archivedPanel = ThreadChannelSettingsPanel(channel = archived, permissions = channelSettingsPermissions(archived, canManage = true))
        assertFalse(archivedPanel.showsInfoForm())
        assertEquals(listOf(ChannelSettingsActionRow.Unarchive), rows(archived, canManage = true))

        val joint = channel(type = "joint", kind = ConversationKind.Joint)
        assertEquals(
            listOf(ChannelSettingsActionRow.Archive, ChannelSettingsActionRow.Leave, ChannelSettingsActionRow.Disconnect),
            rows(joint, canManage = true)
        )
    }

    @Test
    fun membersStripHidesOnlyOnAllForAMemberWhenTheServerHidesPeople() {
        assertTrue(channelSettingsHidesMembersStrip(channel(name = "all"), serverRole = "member", hideHumansFromMembers = true))
        assertFalse(channelSettingsHidesMembersStrip(channel(name = "all"), serverRole = "admin", hideHumansFromMembers = true))
        assertFalse(channelSettingsHidesMembersStrip(channel(name = "all"), serverRole = "member", hideHumansFromMembers = false))
        assertFalse(channelSettingsHidesMembersStrip(channel(), serverRole = "member", hideHumansFromMembers = true))
        assertFalse(channelSettingsHidesMembersStrip(null, serverRole = "member", hideHumansFromMembers = true))
    }
}
