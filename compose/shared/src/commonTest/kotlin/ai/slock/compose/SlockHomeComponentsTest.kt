package ai.slock.compose

import ai.slock.compose.data.TasksViewMode
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockHomeComponentsTest {
    @Test
    fun homeDmStatusFallsBackToOfflineWhenPresenceIsMissing() {
        assertEquals(SlockPresence.Offline, homeDmStatusPresence(null))
    }

    @Test
    fun homeDmStatusKeepsKnownPresence() {
        assertEquals(SlockPresence.Online, homeDmStatusPresence(SlockPresence.Online))
        assertEquals(SlockPresence.Unknown, homeDmStatusPresence(SlockPresence.Unknown))
        assertEquals(SlockPresence.Offline, homeDmStatusPresence(SlockPresence.Offline))
    }

    @Test
    fun homeSidebarSelectionPrefersTopActionOverConversation() {
        assertEquals(
            SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Activity),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = true,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Search),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = true,
                activitySelected = false,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Saved),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = false,
                savedSelected = true
            )
        )
    }

    @Test
    fun homeSidebarSelectionDistinguishesConversationSections() {
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "channel-1", conversationSection = SlockHomeConversationSection.Channel),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(
                    SlockHomeConversationItem(id = "channel-1", name = "home"),
                    SlockHomeConversationItem(id = "joint-1", name = "partner", kind = "joint")
                ),
                directMessages = listOf(SlockHomeConversationItem(id = "dm-1", name = "artin")),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "joint-1", conversationSection = SlockHomeConversationSection.JointChannel),
            homeSidebarSelection(
                selectedChannelId = "joint-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "joint-1", name = "partner", kind = "joint")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "dm-1", conversationSection = SlockHomeConversationSection.DirectMessage),
            homeSidebarSelection(
                selectedChannelId = "dm-1",
                selectedConversationSection = null,
                channels = emptyList(),
                directMessages = listOf(SlockHomeConversationItem(id = "dm-1", name = "artin")),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false
            )
        )
    }

    @Test
    fun sidebarSelectionRequiresMatchingConversationSection() {
        val pinnedSelection =
            SlockHomeSidebarSelection(
                conversationId = "channel-1",
                conversationSection = SlockHomeConversationSection.Pinned
            )
        val pinnedSectionSelection = pinnedSelection.sectionSelection(SlockHomeConversationSection.Pinned)
        val channelSectionSelection = pinnedSelection.sectionSelection(SlockHomeConversationSection.Channel)

        assertEquals(SlockHomeConversationSection.Pinned, pinnedSectionSelection.section)
        assertTrue(pinnedSectionSelection.selects("channel-1"))
        assertFalse(channelSectionSelection.selects("channel-1"))
        assertFalse(pinnedSectionSelection.selects("channel-2"))
    }

    @Test
    fun homeLazyItemCountIncludesVisibleSectionsAndRows() {
        assertEquals(
            14,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 0,
                directMessagesCount = 2,
                channelsExpanded = true,
                jointChannelsExpanded = true,
                directMessagesExpanded = true,
                contentRefreshEnabled = true
            )
        )
        assertEquals(
            8,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 0,
                directMessagesCount = 2,
                channelsExpanded = false,
                jointChannelsExpanded = false,
                directMessagesExpanded = false,
                contentRefreshEnabled = false
            )
        )
    }

    @Test
    fun homeLazyItemCountTracksChannelAndJointSectionsIndependently() {
        assertEquals(
            15,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 2,
                directMessagesCount = 2,
                channelsExpanded = false,
                jointChannelsExpanded = true,
                directMessagesExpanded = true,
                contentRefreshEnabled = true
            )
        )
        assertEquals(
            16,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 2,
                directMessagesCount = 2,
                channelsExpanded = true,
                jointChannelsExpanded = false,
                directMessagesExpanded = true,
                contentRefreshEnabled = true
            )
        )
    }

    @Test
    fun sharedListBeyondBoundsPremeasuresShortListsWithoutGrowingUnbounded() {
        assertEquals(3, sharedListBeyondBoundsItemCount(0))
        assertEquals(12, sharedListBeyondBoundsItemCount(12))
        assertEquals(96, sharedListBeyondBoundsItemCount(400))
    }

    @Test
    fun sidebarSortModesMatchReactOrdering() {
        val manual =
            listOf(
                SlockHomeConversationItem(id = "b", name = "Beta", lastMessageAt = "2026-05-19T10:00:00Z"),
                SlockHomeConversationItem(id = "a", name = "Alpha", lastMessageAt = "2026-05-20T10:00:00Z"),
                SlockHomeConversationItem(id = "c", name = "gamma", lastMessageAt = "2026-05-18T10:00:00Z")
            )

        assertEquals(listOf("b", "a", "c"), manual.sortedForSidebar(SlockSidebarSortMode.Manual) { it.name }.map { it.id })
        assertEquals(listOf("a", "b", "c"), manual.sortedForSidebar(SlockSidebarSortMode.Recent) { it.name }.map { it.id })
        assertEquals(listOf("a", "b", "c"), manual.sortedForSidebar(SlockSidebarSortMode.Az) { it.name }.map { it.id })
    }

    @Test
    fun membersLazyItemCountIncludesAgentGroupsAndVisibleHumans() {
        val agents =
            listOf(
                testMember("agent-1", MemberKind.Agent, permissionBadges = listOf("computer:mac")),
                testMember("agent-2", MemberKind.Agent, permissionBadges = listOf("computer:mac")),
                testMember("agent-3", MemberKind.Agent, permissionBadges = listOf("computer:linux"))
            )
        assertEquals(
            11,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = true,
                humansExpanded = true
            )
        )
        assertEquals(
            4,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = false,
                humansExpanded = false
            )
        )
    }

    @Test
    fun tasksLazyItemCountTracksCurrentModeAndErrors() {
        assertEquals(
            6,
            sharedTasksLazyItemCount(
                mode = TasksViewMode.Board,
                filteredTasksCount = 20,
                boardColumnCount = 3,
                hasNavigationError = true,
                hasStatusUpdateError = false
            )
        )
        assertEquals(
            3,
            sharedTasksLazyItemCount(
                mode = TasksViewMode.List,
                filteredTasksCount = 0,
                boardColumnCount = 3,
                hasNavigationError = false,
                hasStatusUpdateError = false
            )
        )
    }

    private fun testMember(
        id: String,
        kind: MemberKind,
        roleDescription: String? = null,
        permissionBadges: List<String> = emptyList()
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = kind,
            roleDescription = roleDescription,
            permissionBadges = permissionBadges
        )
}
