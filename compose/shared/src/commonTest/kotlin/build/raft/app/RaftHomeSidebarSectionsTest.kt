package build.raft.app

import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.data.SettingsServerSidebarSectionPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun hideEmptySectionsDropsEmptyPinnedJointAndDmsButKeepsChannelsAndCustomSections() {
        // Web sidebarEmptySectionVisibility.ts: the preference hides empty
        // Pinned / Joint / DMs; Channels and custom sections always render.
        val a = section("sec-a", "A")
        val empty = section("sec-empty", "Empty")
        val rows = listOf(rowsFor(a).single(), RaftHomeCustomSectionRows(section = empty, items = emptyList()))
        fun keys(hide: Boolean) =
            sectionKeys(
                homeSidebarSectionOrder(
                    sectionOrder = listOf("sec-empty", "system:pinned", "sec-a", "system:joint", "system:channels", "system:dms"),
                    customSections = listOf(a, empty),
                    customSectionRows = rows,
                    hasPinned = false,
                    hasJointChannels = false,
                    hasDirectMessages = false,
                    hideEmptySections = hide
                )
            )
        assertEquals(listOf("sec-empty", "sec-a", "system:channels"), keys(hide = true))
        assertEquals(
            listOf("sec-empty", "system:pinned", "sec-a", "system:joint", "system:channels", "system:dms"),
            keys(hide = false)
        )
    }

    @Test
    fun customSectionRowsFollowSectionOrderAndKeepEmptySections() {
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
        // Web renders an empty custom section with a "No conversations" hint.
        assertEquals(listOf("sec-a", "sec-b", "sec-empty"), rows.map { it.section.id })
        assertEquals(listOf("c-2"), rows[0].items.map { it.id })
        assertEquals(listOf("c-1"), rows[1].items.map { it.id })
        assertEquals(emptyList(), rows[2].items)
        // A brand-new section with no placements at all still shows.
        assertEquals(
            listOf("sec-new"),
            sidebarCustomSectionRows(
                channels = emptyList(),
                directMessages = emptyList(),
                customSections = listOf(section("sec-new", "New")),
                sectionOrder = emptyList(),
                sectionPlacements = emptyList(),
                pinnedConversationIds = emptySet()
            ).map { it.section.id }
        )
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
    fun customSectionMenuMirrorsWebSectionContextMenu() {
        val items = raftHomeSectionMenuItems(sortMode = RaftSidebarSortMode.Recent, hideEmptySections = true)
        assertEquals(
            listOf("sort:manual", "sort:recent", "sort:az", "toggle-hide-empty", "rename", "create", "delete"),
            items.map { it.id }
        )
        assertEquals(listOf("sort:recent", "toggle-hide-empty"), items.filter { it.checked }.map { it.id })
        assertEquals("Sort", items.first().groupLabel)
        assertEquals("Display", items.single { it.id == "toggle-hide-empty" }.groupLabel)
    }

    @Test
    fun systemSectionMenuOffersSortDisplayAndNewSectionButNoRenameOrDelete() {
        val channels =
            raftHomeSectionMenuItems(
                sortMode = RaftSidebarSortMode.Manual,
                hideEmptySections = false,
                custom = false,
                canCreateChannel = true
            )
        assertEquals(
            listOf("create-channel", "sort:manual", "sort:recent", "sort:az", "toggle-hide-empty", "create"),
            channels.map { it.id }
        )
        assertEquals(listOf("sort:manual"), channels.filter { it.checked }.map { it.id })
        val labels = RaftHomeShellLabels(sortMenuTitle = "排序", displaySection = "显示", hideEmptySections = "隐藏空的分区", newSectionMenu = "新建分区…")
        val joint = raftHomeSectionMenuItems(labels = labels, sortMode = RaftSidebarSortMode.Az, custom = false)
        assertEquals(listOf("排序", "显示"), joint.mapNotNull { it.groupLabel })
        assertEquals("新建分区…", joint.last().label)
        assertTrue(joint.none { it.id == "create-channel" })
    }

    @Test
    fun moveToSectionMenuMirrorsWebSubmenu() {
        val sections = listOf(section("sec-1", "One"), section("sec-2", "Two"))
        // web2.png: a channel in no section — Pinned, the sections, New Section….
        val plain = raftHomeMoveToSectionMenuItems(sections, currentSectionId = null)
        assertEquals(listOf("pinned", "section:sec-1", "section:sec-2", "create"), plain.map { it.id })
        assertTrue(plain.none { it.checked })
        assertTrue(plain.all { it.enabled })
        // web3.png: a pinned DM — Pinned is checked and disabled.
        val pinned = raftHomeMoveToSectionMenuItems(emptyList(), currentSectionId = null, pinned = true)
        assertEquals(listOf("pinned", "create"), pinned.map { it.id })
        assertTrue(pinned.first().checked)
        assertFalse(pinned.first().enabled)
        // Placed rows check their section and can be removed from it.
        val placed = raftHomeMoveToSectionMenuItems(sections, currentSectionId = "sec-2")
        assertEquals(listOf("pinned", "section:sec-1", "section:sec-2", "create", "section:"), placed.map { it.id })
        assertEquals(listOf("section:sec-2"), placed.filter { it.checked }.map { it.id })
    }

    /** Web SidebarReadToggleMenuItem (botiverse/slock d60367a1) and its behavior test. */
    @Test
    fun readToggleOffersMarkAllAsReadOnlyForJoinedChannelsWithBothGatesOn() {
        fun readItem(
            conversation: RaftHomeConversationItem,
            gatesOn: Boolean,
            directMessage: Boolean = false
        ) =
            raftHomeConversationMenuItems(
                conversation = conversation,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = directMessage,
                activeCanArchive = false,
                markAllReadEnabled = gatesOn
            ).single { it.id.startsWith("mark-") }.let { it.id to it.label }
        val threadOnly = RaftHomeConversationItem(id = "c-1", name = "one", followedThreadUnreadCount = 12)
        val unread = RaftHomeConversationItem(id = "c-1", name = "one", unreadCount = 2)
        // Thread-only unread reads the whole conversation ("全部已读" / "Mark All as Read").
        assertEquals("mark-all-read" to "Mark All as Read", readItem(threadOnly, gatesOn = true))
        assertEquals("mark-all-read" to "Mark All as Read", readItem(unread, gatesOn = true))
        // Gate off keeps the single-scope toggle; thread-only unread is not unread to it.
        assertEquals("mark-unread" to "Mark as Unread", readItem(threadOnly, gatesOn = false))
        assertEquals("mark-read" to "Mark as Read", readItem(unread, gatesOn = false))
        // DMs and unjoined channels never include threads.
        assertEquals("mark-read" to "Mark as Read", readItem(unread, gatesOn = true, directMessage = true))
        assertEquals("mark-read" to "Mark as Read", readItem(unread.copy(joined = false), gatesOn = true))
        assertEquals("mark-unread" to "Mark as Unread", readItem(RaftHomeConversationItem(id = "c-2", name = "two"), gatesOn = true))
    }

    @Test
    fun conversationMenuLeadsWithMoveToSectionEvenWithoutCustomSections() {
        val channelMenu =
            raftHomeConversationMenuItems(
                conversation = RaftHomeConversationItem(id = "c-1", name = "one", activityMuteSupported = true),
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false
            )
        assertEquals(listOf("move-to-section", "mark-unread", "toggle-pin", "mute-channel"), channelMenu.map { it.id })
        val dmMenu =
            raftHomeConversationMenuItems(
                conversation = RaftHomeConversationItem(id = "dm-1", name = "Cindy"),
                pinTargetIsPinned = true,
                sortableFromMenu = false,
                activeIsDirectMessage = true,
                activeCanArchive = false
            )
        // web3.png: Move to Section / Mark as Unread / Unpin / Close Chat.
        assertEquals(listOf("move-to-section", "mark-unread", "toggle-pin", "close-dm"), dmMenu.map { it.id })
        assertEquals("Unpin", dmMenu.single { it.id == "toggle-pin" }.label)
        assertTrue(dmMenu.single { it.id == "close-dm" }.dividerBefore)
        assertTrue(
            raftHomeConversationMenuItems(
                conversation = RaftHomeConversationItem(id = "c-1", name = "one"),
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false,
                canMoveToSection = false
            ).none { it.id == "move-to-section" }
        )
    }
}
