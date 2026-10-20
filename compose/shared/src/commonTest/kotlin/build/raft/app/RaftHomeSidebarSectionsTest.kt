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
class RaftHomeSidebarSectionsTest {

    private fun channel(id: String, name: String, lastMessageAt: String? = null): RaftHomeConversationItem =
        RaftHomeConversationItem(id = id, name = name, kind = "channel", lastMessageAt = lastMessageAt)

    private fun agentDm(id: String, name: String, memberId: String): RaftHomeConversationItem =
        RaftHomeConversationItem(id = id, name = name, kind = "dm", peerType = "agent", presenceMemberId = memberId)

    private fun section(id: String, name: String, sortMode: String = "manual", emoji: String? = null) =
        SettingsServerSidebarCustomSection(id = id, name = name, emoji = emoji, sortMode = sortMode)

    private fun placement(kind: String, id: String, sectionId: String, position: Int) =
        SettingsServerSidebarSectionPlacement(kind = kind, id = id, sectionId = sectionId, position = position)

    private fun sectionKeys(sections: List<RaftHomeSidebarSection>): List<String> =
        sections.map {
            when (it) {
                RaftHomeSidebarSection.Pinned -> "system:pinned"
                RaftHomeSidebarSection.JointChannels -> "system:joint"
                RaftHomeSidebarSection.Channels -> "system:channels"
                RaftHomeSidebarSection.DirectMessages -> "system:dms"
                is RaftHomeSidebarSection.Custom -> it.rows.section.id
            }
        }

    private fun rowsFor(vararg sections: SettingsServerSidebarCustomSection): List<RaftHomeCustomSectionRows> =
        sections.map { RaftHomeCustomSectionRows(section = it, items = listOf(channel("c-${it.id}", it.name))) }

    @Test
    fun sidebarSectionsInterleaveSystemAndCustomSectionsInWebSectionOrder() {
        // Hands a8fcdfaa: web order puts the custom "Mobile" section between
        // pinned and the system channel blocks; mobile used to render every
        // custom section after DMs.
        val mobile = section("sec-mobile", "Mobile")
        val other = section("sec-other", "Other")
        val order =
            homeSidebarSectionOrder(
                sectionOrder = listOf("system:pinned", "sec-mobile", "system:dms", "system:joint", "sec-other", "system:channels"),
                customSections = listOf(other, mobile),
                customSectionRows = rowsFor(other, mobile),
                hasPinned = true,
                hasJointChannels = true
            )
        assertEquals(
            listOf("system:pinned", "sec-mobile", "system:dms", "system:joint", "sec-other", "system:channels"),
            sectionKeys(order)
        )
    }

    @Test
    fun sidebarSectionsFallBackLikeWebForEmptyOrPartialOrder() {
        val a = section("sec-a", "A")
        val b = section("sec-b", "B")
        // Empty order: system sections in canonical order, then custom sections
        // in declaration order (web normalizeSidebarSectionOrder appends both).
        assertEquals(
            listOf("system:pinned", "system:joint", "system:channels", "system:dms", "sec-a", "sec-b"),
            sectionKeys(
                homeSidebarSectionOrder(
                    sectionOrder = emptyList(),
                    customSections = listOf(a, b),
                    customSectionRows = rowsFor(a, b),
                    hasPinned = true,
                    hasJointChannels = true
                )
            )
        )
        // Partial order with unknown / duplicate ids: listed ids first (deduped,
        // unknowns dropped), then missing system ids, then missing custom ids.
        assertEquals(
            listOf("sec-b", "system:dms", "system:pinned", "system:joint", "system:channels", "sec-a"),
            sectionKeys(
                homeSidebarSectionOrder(
                    sectionOrder = listOf("sec-b", "bogus", "system:dms", "sec-b"),
                    customSections = listOf(a, b),
                    customSectionRows = rowsFor(a, b),
                    hasPinned = true,
                    hasJointChannels = true
                )
            )
        )
    }

    @Test
    fun sidebarSectionsHideEmptyPinnedJointAndCustomSectionsButKeepChannelsAndDms() {
        val a = section("sec-a", "A")
        val empty = section("sec-empty", "Empty")
        assertEquals(
            listOf("sec-a", "system:channels", "system:dms"),
            sectionKeys(
                homeSidebarSectionOrder(
                    sectionOrder = listOf("sec-empty", "system:pinned", "sec-a", "system:joint", "system:channels", "system:dms"),
                    customSections = listOf(a, empty),
                    // sidebarCustomSectionRows omits empty sections.
                    customSectionRows = rowsFor(a),
                    hasPinned = false,
                    hasJointChannels = false
                )
            )
        )
    }

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
        val items = raftHomeSectionMenuItems()
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
            raftHomeMoveToSectionMenuItems(sections, currentSectionId = null).map { it.id }
        )
        assertEquals(
            listOf("section:", "section:sec-1", "section:sec-2"),
            raftHomeMoveToSectionMenuItems(sections, currentSectionId = "sec-2").map { it.id }
        )
    }

    @Test
    fun conversationMenuAddsMoveToSectionOnlyWhenCustomSectionsExist() {
        val conversation = channel("c-1", "one")
        val withoutSections =
            raftHomeConversationMenuItems(
                conversation = conversation,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false,
                hasCustomSections = false
            )
        assertTrue(withoutSections.none { it.id == "move-to-section" })
        val withSections =
            raftHomeConversationMenuItems(
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
