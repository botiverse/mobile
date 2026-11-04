package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
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

    private val pin =
        ThreadConversationPinUiState(
            conversationId = "channel-design",
            pinnedKind = "channel",
            pinnedId = "channel-design",
            pinned = false,
            available = true
        )

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
    fun pinnedWinsOverPlacementAndOrphanedPlacementShowsHome() {
        assertEquals(ChannelSectionValue.Pinned, placement(pinned = true, sectionId = "s-ops").current)
        assertEquals(ChannelSectionValue.Custom("s-ops"), placement(sectionId = "s-ops").current)
        assertEquals(ChannelSectionValue.Home, placement(sectionId = "s-deleted").current)
        assertEquals(ChannelSectionValue.Home, placement().current)
    }

    @Test
    fun newSectionGoesBeforeTheCurrentOne() {
        assertEquals("system:channels", placement().currentSectionKey)
        assertEquals("system:joint", placement().copy(joint = true).currentSectionKey)
        assertEquals("system:pinned", placement(pinned = true).currentSectionKey)
        assertEquals("s-launch", placement(sectionId = "s-launch").currentSectionKey)
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
    fun movesBecomeSidebarOrderIntents() {
        assertEquals(
            listOf(
                SharedHomeIntent.TogglePinned(conversationId = "channel-design", pinnedKind = "channel", pinnedId = "channel-design"),
                SharedHomeIntent.MoveConversationToSection("channel-design", null),
                SharedHomeIntent.MoveConversationToSection("channel-design", "s-ops"),
            ),
            channelSectionHomeIntents(
                pin,
                listOf(ChannelSectionMove.Unpin, ChannelSectionMove.ClearPlacement, ChannelSectionMove.MoveTo("s-ops"))
            )
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
                showGuestAccess = false,
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
    fun unknownChannelKeepsTheNameForm() {
        val panel = ThreadChannelSettingsPanel()
        assertTrue(panel.showsInfoForm())
        assertTrue(panel.nameEditable())
    }

    @Test
    fun membersStripHidesOnlyOnAllForAMemberWhenTheServerHidesPeople() {
        assertTrue(channelSettingsHidesMembersStrip(channel(name = "all"), serverRole = "member", hideHumansFromMembers = true))
        assertFalse(channelSettingsHidesMembersStrip(channel(name = "all"), serverRole = "admin", hideHumansFromMembers = true))
        assertFalse(channelSettingsHidesMembersStrip(channel(name = "all"), serverRole = "member", hideHumansFromMembers = false))
        assertFalse(channelSettingsHidesMembersStrip(channel(), serverRole = "member", hideHumansFromMembers = true))
        assertFalse(channelSettingsHidesMembersStrip(null, serverRole = "member", hideHumansFromMembers = true))
    }

    @Test
    fun addTileNeedsTheServerAnswerAndALiveChannel() {
        val slice = SharedServerChannelMembersSlice(channelId = "c-design", canAddMembers = true, canAddMembersKnown = true)
        assertTrue(channelSettingsCanAddMembers(slice, channel()))
        assertFalse(channelSettingsCanAddMembers(slice, channel(archived = true)))
        assertFalse(channelSettingsCanAddMembers(slice.copy(canAddMembersKnown = false), channel()))
        assertFalse(channelSettingsCanAddMembers(slice.copy(canAddMembers = false), channel()))
    }
}
