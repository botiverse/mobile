package ai.slock.compose

import ai.slock.compose.data.TasksViewMode
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun homeLazyItemCountIncludesVisibleSectionsAndRows() {
        assertEquals(
            14,
            homeLazyItemCount(
                channelsCount = 3,
                directMessagesCount = 2,
                channelsExpanded = true,
                directMessagesExpanded = true,
                contentRefreshEnabled = true
            )
        )
        assertEquals(
            8,
            homeLazyItemCount(
                channelsCount = 3,
                directMessagesCount = 2,
                channelsExpanded = false,
                directMessagesExpanded = false,
                contentRefreshEnabled = false
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
