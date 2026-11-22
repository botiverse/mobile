package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskStatus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedTasksPageTest {
    @Test
    fun tasksHeaderSubtitleShowsVisibleOfTotalWhenFiltered() {
        assertEquals("12 of 42 channel tasks", tasksHeaderSubtitle(totalTasks = 42, visibleTasks = 12))
    }

    @Test
    fun taskMenusOpenOnlyWhereTheReaderMayWriteTheChannel() {
        // Web canWriteTasksInChannel (slock #15155): status, Backlog reason and assignee menus.
        val task = TaskSummary(id = TaskId("task-1"), title = "Ship it", status = TaskStatus.Todo, updatedAtLabel = "", channelId = ChannelId("ch-reader"))
        val reader: (ChannelId?) -> Boolean = { it != ChannelId("ch-reader") }
        assertEquals(true, task.opensTaskMutationMenus(standIn = false) { true })
        assertEquals(false, task.opensTaskMutationMenus(standIn = false, canWriteTaskChannel = reader))
        assertEquals(true, task.copy(channelId = ChannelId("ch-member")).opensTaskMutationMenus(standIn = false, canWriteTaskChannel = reader))
        assertEquals(false, task.opensTaskMutationMenus(standIn = true) { true }, "the offline stand-in")
        assertEquals(false, task.copy(readOnlyReason = "historical_joint_task").opensTaskMutationMenus(standIn = false) { true })
    }

    @Test
    fun assigneeFilterAlwaysOffersLocalizedUnassignedChoice() {
        val options =
            tasksAssigneeFilterOptions(
                assignees =
                    listOf(
                        MemberProfile(
                            id = MemberId("same-id"),
                            displayName = "Human",
                            handle = "human",
                            kind = MemberKind.Human,
                            presence = Presence.Active
                        ),
                        MemberProfile(
                            id = MemberId("same-id"),
                            displayName = "Agent",
                            handle = "agent",
                            kind = MemberKind.Agent,
                            presence = Presence.Active
                        )
                    ),
                unassignedLabel = "Unassigned"
            )

        assertEquals(listOf("unassigned", "user:same-id", "agent:same-id"), options.map { it.id })
        assertEquals(listOf("Unassigned", "Human", "Agent"), options.map { it.label })
    }

    // #130 filter-search caller-local editor seed contract. The editor is the sole
    // owner of its TextFieldValue within a filter session; raftFilterSearchSessionSeed
    // is invoked only from a LaunchedEffect keyed on the openFilterId session token,
    // so these decisions are the only way the durable String reaches the local editor.
    @Test
    fun filterSearchSeedsFromDurableWithCaretAtEndOnSessionOpen() {
        val seed = raftFilterSearchSessionSeed(openFilterId = "channel", thisFilterId = "channel", durableSearch = "design")
        assertEquals("design", seed?.text)
        assertEquals(TextRange("design".length), seed?.selection)
    }

    @Test
    fun filterSearchDoesNotReseedInactiveFilterFromStaleDurable() {
        // While "channel" owns the open session, a stale or echoed "creator" durable
        // String must never reseat the creator editor (would clobber its composition).
        assertNull(raftFilterSearchSessionSeed(openFilterId = "channel", thisFilterId = "creator", durableSearch = "stale-echo"))
        // No open session at all → nothing is seeded.
        assertNull(raftFilterSearchSessionSeed(openFilterId = null, thisFilterId = "channel", durableSearch = "x"))
    }

    @Test
    fun collapsedSectionChevronTurnsRightAndOpenSectionPointsDown() {
        // Backlog, Done and Closed start collapsed (raft-design tasks.md); their header chevron is what says
        // the "Done 1" header expands (raft-ui `-rotate-90` collapsed, `rotate-0` open).
        assertEquals(
            setOf(TaskStatus.Backlog, TaskStatus.Done, TaskStatus.Closed),
            build.raft.app.data.TasksPresentationState.Default.collapsedStatuses
        )
        assertEquals(-90f, taskSectionChevronRotation(collapsed = true))
        assertEquals(0f, taskSectionChevronRotation(collapsed = false))
    }
}
