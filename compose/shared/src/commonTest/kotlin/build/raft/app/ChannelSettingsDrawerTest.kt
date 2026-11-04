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

    @Test
    fun ownerGetsInfoAndEveryAction() {
        val row = channel()
        val panel = ThreadChannelSettingsPanel(channel = row, permissions = channelSettingsPermissions(row, canManage = true))
        assertTrue(panel.showsInfoForm())
        assertTrue(panel.nameEditable())
        assertTrue(panel.allowsChannelAction("visibility"))
        assertTrue(panel.allowsChannelAction("archive"))
        assertTrue(panel.allowsChannelAction("delete"))
    }

    @Test
    fun memberWithoutCapabilitiesGetsNoInfoFormAndNoActions() {
        val row = channel()
        val panel = ThreadChannelSettingsPanel(channel = row, permissions = channelSettingsPermissions(row, canManage = false))
        assertFalse(panel.showsInfoForm())
        assertFalse(panel.allowsChannelAction("visibility"))
        assertFalse(panel.allowsChannelAction("archive"))
        assertFalse(panel.allowsChannelAction("delete"))
    }

    @Test
    fun allChannelKeepsItsNameAndOffersNoLifecycleActions() {
        val row = channel(name = "all")
        val panel = ThreadChannelSettingsPanel(channel = row, permissions = channelSettingsPermissions(row, canManage = true))
        assertTrue(panel.showsInfoForm())
        assertFalse(panel.nameEditable())
        assertFalse(panel.allowsChannelAction("visibility"))
        assertFalse(panel.allowsChannelAction("archive"))
        assertFalse(panel.allowsChannelAction("delete"))
    }

    @Test
    fun archivedAndJointChannelsHideWhatTheWebHides() {
        val archived = channel(archived = true)
        val archivedPanel = ThreadChannelSettingsPanel(channel = archived, permissions = channelSettingsPermissions(archived, canManage = true))
        assertFalse(archivedPanel.showsInfoForm())
        assertFalse(archivedPanel.allowsChannelAction("archive"))
        assertFalse(archivedPanel.allowsChannelAction("delete"))

        val joint = channel(type = "joint", kind = ConversationKind.Joint)
        val jointPanel = ThreadChannelSettingsPanel(channel = joint, permissions = channelSettingsPermissions(joint, canManage = true))
        assertFalse(jointPanel.allowsChannelAction("visibility"))
        assertFalse(jointPanel.allowsChannelAction("delete"))
        assertTrue(jointPanel.allowsChannelAction("archive"))
    }

    @Test
    fun unknownChannelKeepsWhatTheConversationOffers() {
        val panel = ThreadChannelSettingsPanel()
        assertTrue(panel.showsInfoForm())
        assertTrue(panel.nameEditable())
        assertTrue(panel.allowsChannelAction("archive"))
        assertFalse(panel.allowsChannelAction("leave"))
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
