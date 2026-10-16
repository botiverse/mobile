package build.raft.app

import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.data.SettingsServerSidebarSectionPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task #512: pure helpers behind the home sidebar's custom sections (web
 * channel-sections port). Covers placement resolution (channel + agent kinds),
 * sectionOrder ordering, empty-section hiding, pinned exclusion, per-section
 * sort modes, and the section / move-to-section menu item builders.
 */
class SlockHomeSidebarSectionsTest {

    private fun channel(id: String, name: String, lastMessageAt: String? = null): SlockHomeConversationItem =
        SlockHomeConversationItem(id = id, name = name, kind = "channel", lastMessageAt = lastMessageAt)

    private fun agentDm(id: String, name: String, memberId: String): SlockHomeConversationItem =
        SlockHomeConversationItem(id = id, name = name, kind = "dm", peerType = "agent", presenceMemberId = memberId)

    private fun section(id: String, name: String, sortMode: String = "manual", emoji: String? = null) =
        SettingsServerSidebarCustomSection(id = id, name = name, emoji = emoji, sortMode = sortMode)

    private fun placement(kind: String, id: String, sectionId: String, position: Int) =
        SettingsServerSidebarSectionPlacement(kind = kind, id = id, sectionId = sectionId, position = position)

    @Test
    fun customSectionRowsFollowSectionOrderAndHideEmptySections() {
        val sections = listOf(section("sec-b", "B"), section("sec-a", "A"), section("sec-empty", "Empty"))
        val rows =
            sidebarCustomSectionRows(
                channels = listOf(channel("c-1", "one"), channel("c-2", "two")),
                directMessages = emptyList(),
                customSections = sections,
                // sec-a before sec-b even though declared later; system ids interleave.
                sectionOrder = listOf("system:pinned", "sec-a", "system:channels", "sec-b", "sec-empty"),
                sectionPlacements =
                    listOf(
                        placement("channel", "c-1", "sec-b", 0),
                        placement("channel", "c-2", "sec-a", 0)
                    ),
                pinnedConversationIds = emptySet()
            )
        assertEquals(listOf("sec-a", "sec-b"), rows.map { it.section.id })
        assertEquals(listOf("c-2"), rows[0].items.map { it.id })
        assertEquals(listOf("c-1"), rows[1].items.map { it.id })
    }

    @Test
    fun customSectionRowsSkipUnknownAndPinnedConversations() {
        val rows =
            sidebarCustomSectionRows(
                channels = listOf(channel("c-1", "one"), channel("c-2", "two")),
                directMessages = emptyList(),
                customSections = listOf(section("sec-1", "Project")),
                sectionOrder = listOf("sec-1"),
                sectionPlacements =
                    listOf(
                        placement("channel", "c-1", "sec-1", 0),
                        placement("channel", "c-2", "sec-1", 1),
                        placement("channel", "ghost", "sec-1", 2)
                    ),
                pinnedConversationIds = setOf("c-2")
            )
        assertEquals(listOf("c-1"), rows.single().items.map { it.id })
    }

    @Test
    fun customSectionRowsResolveAgentPlacementsToDirectMessages() {
        val rows =
            sidebarCustomSectionRows(
                channels = emptyList(),
                directMessages = listOf(agentDm("dm-1", "Agent One", "member-1")),
                customSections = listOf(section("sec-1", "Agents")),
                sectionOrder = listOf("sec-1"),
                sectionPlacements = listOf(placement("agent", "member-1", "sec-1", 0)),
                pinnedConversationIds = emptySet()
            )
        assertEquals(listOf("dm-1"), rows.single().items.map { it.id })
    }

    @Test
    fun customSectionRowsApplyTheSectionSortMode() {
        val placements =
            listOf(
                placement("channel", "c-1", "sec-az", 0),
                placement("channel", "c-2", "sec-az", 1),
                placement("channel", "c-3", "sec-recent", 0),
                placement("channel", "c-4", "sec-recent", 1)
            )
        val channels =
            listOf(
                channel("c-1", "zeta", lastMessageAt = "2026-10-01T00:00:00Z"),
                channel("c-2", "alpha", lastMessageAt = "2026-10-02T00:00:00Z"),
                channel("c-3", "old", lastMessageAt = "2026-10-01T00:00:00Z"),
                channel("c-4", "new", lastMessageAt = "2026-10-03T00:00:00Z")
            )
        val rows =
            sidebarCustomSectionRows(
                channels = channels,
                directMessages = emptyList(),
                customSections = listOf(section("sec-az", "Az", sortMode = "az"), section("sec-recent", "Recent", sortMode = "recent")),
                sectionOrder = listOf("sec-az", "sec-recent"),
                sectionPlacements = placements,
                pinnedConversationIds = emptySet()
            )
        assertEquals(listOf("alpha", "zeta"), rows[0].items.map { it.name })
        assertEquals(listOf("new", "old"), rows[1].items.map { it.name })
    }

    @Test
    fun customPlacedConversationIdsCoverChannelAndAgentKinds() {
        val ids =
            sidebarCustomPlacedConversationIds(
                sectionPlacements =
                    listOf(
                        placement("channel", "c-1", "sec-1", 0),
                        placement("agent", "member-1", "sec-1", 1)
                    ),
                directMessages = listOf(agentDm("dm-1", "Agent One", "member-1"))
            )
        assertEquals(setOf("c-1", "dm-1"), ids)
    }

    @Test
    fun sectionHeaderLabelPrefixesTheEmoji() {
        assertEquals("🚀 My Project", section("sec-1", "My Project", emoji = "🚀").sidebarSectionHeaderLabel())
        assertEquals("Plain", section("sec-2", "Plain").sidebarSectionHeaderLabel())
    }

    @Test
    fun sectionMenuItemsListSortRadiosThenActions() {
        val items = slockHomeSectionMenuItems()
        assertEquals(
            listOf("sort:manual", "sort:recent", "sort:az", "rename", "create", "delete"),
            items.map { it.id }
        )
    }

    @Test
    fun moveToSectionMenuItemsOfferRemovalOnlyWhenPlaced() {
        val sections = listOf(section("sec-1", "One"), section("sec-2", "Two"))
        assertEquals(
            listOf("section:sec-1", "section:sec-2"),
            slockHomeMoveToSectionMenuItems(sections, currentSectionId = null).map { it.id }
        )
        assertEquals(
            listOf("section:", "section:sec-1", "section:sec-2"),
            slockHomeMoveToSectionMenuItems(sections, currentSectionId = "sec-2").map { it.id }
        )
    }

    @Test
    fun conversationMenuAddsMoveToSectionOnlyWhenCustomSectionsExist() {
        val conversation = channel("c-1", "one")
        val withoutSections =
            slockHomeConversationMenuItems(
                conversation = conversation,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false,
                hasCustomSections = false
            )
        assertTrue(withoutSections.none { it.id == "move-to-section" })
        val withSections =
            slockHomeConversationMenuItems(
                conversation = conversation,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false,
                hasCustomSections = true
            )
        assertTrue(withSections.any { it.id == "move-to-section" })
    }
}
