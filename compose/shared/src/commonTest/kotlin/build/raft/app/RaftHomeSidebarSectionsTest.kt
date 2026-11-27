package build.raft.app

import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.data.SettingsServerSidebarSectionPlacement
import build.raft.app.thread.icon
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

    private fun agentDm(id: String, name: String, memberId: String, lastMessageAt: String? = null): RaftHomeConversationItem =
        RaftHomeConversationItem(
            id = id,
            name = name,
            kind = "dm",
            peerType = "agent",
            presenceMemberId = memberId,
            lastMessageAt = lastMessageAt
        )

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
    fun externalShowsOnlyWhileItHoldsAChannelWhateverHideEmptySections() {
        // Web SidebarChatSections (slock #15939): `isEmptySectionHidden(jointCount, …, alwaysHide = true)`.
        // An empty External is not drawn even with empty sections shown, and not for its "+" either.
        fun keys(hasExternal: Boolean, hide: Boolean) =
            sectionKeys(
                homeSidebarSectionOrder(
                    sectionOrder = listOf("system:pinned", "system:joint", "system:channels", "system:dms"),
                    customSections = emptyList(),
                    customSectionRows = emptyList(),
                    hasPinned = true,
                    hasJointChannels = hasExternal,
                    hasDirectMessages = true,
                    hideEmptySections = hide
                )
            )
        listOf(true, false).forEach { hide ->
            assertEquals(listOf("system:pinned", "system:channels", "system:dms"), keys(hasExternal = false, hide = hide), "hide=$hide")
            assertEquals(
                listOf("system:pinned", "system:joint", "system:channels", "system:dms"),
                keys(hasExternal = true, hide = hide),
                "hide=$hide"
            )
        }
    }

    @Test
    fun hideEmptySectionsDropsEmptyPinnedAndDmsButKeepsChannelsAndCustomSections() {
        // Web sidebarEmptySectionVisibility.ts: the preference hides empty
        // Pinned / DMs; Channels and custom sections always render. An empty
        // External is not drawn either way.
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
            listOf("sec-empty", "system:pinned", "sec-a", "system:channels", "system:dms"),
            keys(hide = false)
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
    fun customSectionRowsDeduplicatesDualChannelAndAgentPlacementsForSameAgentDm() {
        // Hands 699b6e0b / f1378334 / 02d751c7: server can return both a legacy
        // `channel:<dmId>` placement and a canonical `agent:<memberId>` placement
        // in the same custom section. Both resolve to the same conversation id,
        // which used to crash LazyColumn with a duplicate `custom:` key.
        val dm = agentDm(id = "5f948044-6bb2-4ad9-aab6-456c92432605", name = "Bot", memberId = "agent-1")
        val other = channel("c-other", "other")
        val rows =
            sidebarCustomSectionRows(
                channels = listOf(other),
                directMessages = listOf(dm),
                customSections = listOf(section("4aacdafb-d77d-49e0-a370-43900bee9b7c", "Custom")),
                sectionOrder = listOf("4aacdafb-d77d-49e0-a370-43900bee9b7c"),
                sectionPlacements =
                    listOf(
                        placement("channel", dm.id, "4aacdafb-d77d-49e0-a370-43900bee9b7c", 0),
                        placement("channel", other.id, "4aacdafb-d77d-49e0-a370-43900bee9b7c", 1),
                        placement("agent", "agent-1", "4aacdafb-d77d-49e0-a370-43900bee9b7c", 2)
                    ),
                pinnedConversationIds = emptySet()
            )
        // Canonical `agent:` placement keeps its manual position (after `other`), emitted once.
        assertEquals(listOf(other.id, dm.id), rows.single().items.map { it.id })
    }

    @Test
    fun customSectionRowsPrefersCanonicalAgentPlacementAcrossDifferentCustomSections() {
        val dm = agentDm(id = "dm-agent-1", name = "Bot", memberId = "agent-1")
        val rows =
            sidebarCustomSectionRows(
                channels = emptyList(),
                directMessages = listOf(dm),
                customSections = listOf(section("sec-old", "Old"), section("sec-new", "New")),
                sectionOrder = listOf("sec-old", "sec-new"),
                sectionPlacements =
                    listOf(
                        placement("channel", dm.id, "sec-old", 0),
                        placement("agent", "agent-1", "sec-new", 0)
                    ),
                pinnedConversationIds = emptySet()
            )
        assertEquals(emptyList(), rows[0].items.map { it.id })
        assertEquals(listOf(dm.id), rows[1].items.map { it.id })
    }

    @Test
    fun customSectionRowsKeepsLegacyChannelPlacementWhenNoCanonicalAgentPlacementExists() {
        val dm = agentDm(id = "dm-agent-1", name = "Bot", memberId = "agent-1")
        val rows =
            sidebarCustomSectionRows(
                channels = emptyList(),
                directMessages = listOf(dm),
                customSections = listOf(section("sec-1", "Agents")),
                sectionOrder = listOf("sec-1"),
                sectionPlacements = listOf(placement("channel", dm.id, "sec-1", 0)),
                pinnedConversationIds = emptySet()
            )
        assertEquals(listOf(dm.id), rows.single().items.map { it.id })
    }

    @Test
    fun customSectionRowsDeduplicatesRepeatedIdenticalPlacements() {
        val c1 = channel("c-1", "one")
        val rows =
            sidebarCustomSectionRows(
                channels = listOf(c1),
                directMessages = emptyList(),
                customSections = listOf(section("sec-1", "One"), section("sec-2", "Two")),
                sectionOrder = listOf("sec-1", "sec-2"),
                sectionPlacements =
                    listOf(
                        placement("channel", "c-1", "sec-1", 0),
                        placement("channel", "c-1", "sec-1", 1),
                        placement("channel", "c-1", "sec-2", 0)
                    ),
                pinnedConversationIds = emptySet()
            )
        assertEquals(listOf("c-1"), rows[0].items.map { it.id })
        assertEquals(emptyList(), rows[1].items.map { it.id })
    }

    @Test
    fun customSectionRowsSkipsPinnedAgentDmByMemberIdUnderDualAndLegacyPlacements() {
        val dm = agentDm(id = "dm-agent-1", name = "Bot", memberId = "agent-1")
        val sec = section("sec-1", "Agents")
        val dualRows =
            sidebarCustomSectionRows(
                channels = emptyList(),
                directMessages = listOf(dm),
                customSections = listOf(sec),
                sectionOrder = listOf("sec-1"),
                sectionPlacements =
                    listOf(
                        placement("channel", dm.id, "sec-1", 0),
                        placement("agent", "agent-1", "sec-1", 1)
                    ),
                pinnedConversationIds = setOf("agent-1")
            )
        assertEquals(emptyList(), dualRows.single().items)
        val legacyOnlyRows =
            sidebarCustomSectionRows(
                channels = emptyList(),
                directMessages = listOf(dm),
                customSections = listOf(sec),
                sectionOrder = listOf("sec-1"),
                sectionPlacements = listOf(placement("channel", dm.id, "sec-1", 0)),
                pinnedConversationIds = setOf("agent-1")
            )
        assertEquals(emptyList(), legacyOnlyRows.single().items)
    }

    @Test
    fun sidebarCustomShownConversationIdsOnlyHidesConversationsRenderedInLiveSections() {
        val c1 = channel("c-1", "live")
        val c2 = channel("c-2", "orphaned")
        val rows =
            sidebarCustomSectionRows(
                channels = listOf(c1, c2),
                directMessages = emptyList(),
                customSections = listOf(section("sec-1", "Live")),
                sectionOrder = listOf("sec-1"),
                sectionPlacements =
                    listOf(
                        placement("channel", "c-1", "sec-1", 0),
                        placement("channel", "c-2", "deleted-section", 0)
                    ),
                pinnedConversationIds = emptySet()
            )
        assertEquals(setOf("c-1"), rows.sidebarCustomShownConversationIds())
    }

    @Test
    fun homeSidebarLazyKeyRegistryProducesUniqueKeysEvenWhenIdentitiesRepeat() {
        val registry = HomeSidebarLazyKeyRegistry()
        val firstPass = registry.keysFor(serverId = "s-1", section = "channel", identities = listOf("a", "b", "a"))
        assertEquals(firstPass.size, firstPass.toSet().size)
        val secondPass = registry.keysFor(serverId = "s-1", section = "channel", identities = listOf("a", "b", "a", "c", "c"))
        assertEquals(secondPass.size, secondPass.toSet().size)
        // Keys must stay stable across subsequent recompositions without growing `#dup-N` suffixes.
        val thirdPass = registry.keysFor(serverId = "s-1", section = "channel", identities = listOf("a", "b", "a", "c", "c"))
        assertEquals(secondPass, thirdPass)
    }

    @Test
    fun orderedBySidebarIdsDeduplicatesWhenOrderIsEmpty() {
        val items = listOf(channel("c-1", "first"), channel("c-2", "second"), channel("c-1", "dup"))
        assertEquals(listOf("c-1", "c-2"), items.orderedBySidebarIds(emptyList()).map { it.id })
        assertEquals(listOf("c-2", "c-1"), items.orderedBySidebarIds(emptyList(), pinnedFirstId = "c-2").map { it.id })
    }

    @Test
    fun customSectionMenuMirrorsWebSectionContextMenu() {
        val items = raftHomeSectionMenuItems(sortMode = RaftSidebarSortMode.Recent, hideEmptySections = true)
        assertEquals(
            listOf(
                "sort:manual" to false,
                "sort:recent" to false,
                "sort:az" to false,
                "toggle-hide-empty" to true,
                "rename" to true,
                "create" to false,
                "delete" to true,
            ),
            items.map { it.id to it.dividerBefore }
        )
        assertEquals(listOf("sort:recent", "toggle-hide-empty"), items.filter { it.checked }.map { it.id })
        assertEquals("Sort", items.first().groupLabel)
        assertEquals("Display", items.single { it.id == "toggle-hide-empty" }.groupLabel)
    }

    @Test
    fun systemSectionMenuOffersSortDisplayCreateAndNewSectionWithV4Dividers() {
        val channels =
            raftHomeSectionMenuItems(
                sortMode = RaftSidebarSortMode.Manual,
                hideEmptySections = false,
                custom = false,
                canCreateChannel = true
            )
        assertEquals(
            listOf(
                "sort:manual" to false,
                "sort:recent" to false,
                "sort:az" to false,
                "toggle-hide-empty" to true,
                "create-channel" to true,
                "create" to true,
            ),
            channels.map { it.id to it.dividerBefore }
        )
        assertEquals(listOf("sort:manual"), channels.filter { it.checked }.map { it.id })

        val externalWithCreate =
            raftHomeSectionMenuItems(
                sortMode = RaftSidebarSortMode.Az,
                custom = false,
                canCreateExternalChannel = true,
            )
        assertEquals(
            listOf(
                "sort:manual" to false,
                "sort:recent" to false,
                "sort:az" to false,
                "toggle-hide-empty" to true,
                "create-external-channel" to true,
                "create" to true,
            ),
            externalWithCreate.map { it.id to it.dividerBefore }
        )
        assertEquals(RaftLucideIconName.ExternalChannel, externalWithCreate.single { it.id == "create-external-channel" }.icon)

        val labels = RaftHomeShellLabels(sortMenuTitle = "排序", displaySection = "显示", hideEmptySections = "隐藏空的分区", newSectionMenu = "新建分区")
        val joint = raftHomeSectionMenuItems(labels = labels, sortMode = RaftSidebarSortMode.Az, custom = false)
        assertEquals(listOf("排序", "显示"), joint.mapNotNull { it.groupLabel })
        assertEquals("新建分区", joint.last().label)
        assertTrue(joint.last().dividerBefore)
        assertTrue(joint.none { it.id == "create-channel" || it.id == "create-external-channel" })
    }

    @Test
    fun homeConversationRowMenuFollowsWebMenuOrderV4GroupsAndDividers() {
        val channel = RaftHomeConversationItem(id = "c-1", name = "general", activityMuteSupported = true)
        val channelMenu =
            raftHomeConversationMenuItems(
                conversation = channel,
                pinTargetIsPinned = false,
                sortableFromMenu = true,
                activeIsDirectMessage = false,
                activeCanArchive = true,
                activeCanLeave = true,
                notificationLevelLabel = "Notifications: Default",
            )
        assertEquals(
            listOf(
                "mark-unread" to false,
                "notification-level" to false,
                "toggle-pin" to true,
                "move-to-section" to false,
                "sort-channels" to false,
                "archive-channel" to true,
                "leave-channel" to true,
            ),
            channelMenu.map { it.id to it.dividerBefore },
        )

        val dm = RaftHomeConversationItem(id = "dm-1", name = "Alice", unreadCount = 2, activityMuteSupported = true)
        val dmMenu =
            raftHomeConversationMenuItems(
                conversation = dm,
                pinTargetIsPinned = true,
                sortableFromMenu = false,
                activeIsDirectMessage = true,
                activeCanArchive = false,
                activeCanLeave = false,
            )
        assertEquals(
            listOf(
                "mark-read" to false,
                "mute-channel" to false,
                "toggle-pin" to true,
                "move-to-section" to false,
                "close-dm" to true,
            ),
            dmMenu.map { it.id to it.dividerBefore },
        )
    }

    @Test
    fun moveToSectionPicksWriteWhatChannelSettingsWrites() {
        val sections = listOf(section("sec-1", "One"))
        fun moves(actionId: String, pinned: Boolean = false, placed: String? = null) =
            raftHomeMoveToSectionValue(actionId)
                ?.let { channelSectionMoves(ChannelSectionPlacement(sections, pinned = pinned, placementSectionId = placed), it) }
        // Home replaces Remove from section, and also unpins.
        assertEquals(listOf(ChannelSectionMove.ClearPlacement), moves("home", placed = "sec-1"))
        assertEquals(listOf(ChannelSectionMove.Unpin), moves("home", pinned = true))
        assertEquals(listOf(ChannelSectionMove.Unpin, ChannelSectionMove.ClearPlacement), moves("home", pinned = true, placed = "sec-1"))
        // Home always writes an orphaned placement away; already home writes nothing.
        assertEquals(listOf(ChannelSectionMove.ClearPlacement), moves("home", placed = "deleted"))
        assertEquals(emptyList<ChannelSectionMove>(), moves("home"))
        // Picking the current section does nothing.
        assertEquals(emptyList<ChannelSectionMove>(), moves("pinned", pinned = true))
        assertEquals(emptyList<ChannelSectionMove>(), moves("section:sec-1", placed = "sec-1"))
        assertEquals(listOf(ChannelSectionMove.Pin), moves("pinned", placed = "sec-1"))
        assertEquals(listOf(ChannelSectionMove.MoveTo("sec-1")), moves("section:sec-1", pinned = true))
        assertEquals(null, moves("create"))
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
}
