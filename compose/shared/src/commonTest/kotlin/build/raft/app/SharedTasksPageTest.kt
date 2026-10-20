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
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SharedTasksPageTest {
    @Test
    fun taskDisplaySortExtractsEachLargeSnapshotKeyExactlyOnce() {
        val tasks =
            (1..3_515).map { number ->
                TaskSummary(
                    id = TaskId("task-$number"),
                    title = "Task $number",
                    status = TaskStatus.Todo,
                    taskNumber = number,
                    updatedAtLabel = (1_700_000_000_000L + number).toString()
                )
            }
        var keyExtractions = 0

        val sorted =
            tasks.sortedByTaskDisplayOrder { task ->
                keyExtractions += 1
                task.taskDisplayOrderKey()
            }

        assertEquals(tasks.size, keyExtractions)
        assertEquals(3_515, sorted.first().taskNumber)
        assertEquals(1, sorted.last().taskNumber)
    }

    @Test
    fun tasksHeaderSubtitleShowsTotalWhenUnfiltered() {
        assertEquals("42 channel tasks", tasksHeaderSubtitle(totalTasks = 42, visibleTasks = 42))
    }

    @Test
    fun tasksHeaderSubtitleShowsVisibleOfTotalWhenFiltered() {
        assertEquals("12 of 42 channel tasks", tasksHeaderSubtitle(totalTasks = 42, visibleTasks = 12))
    }

    @Test
    fun tasksEmptyStateCopyMatchesReactFilterText() {
        assertEquals(
            SharedTasksEmptyStateCopy(
                title = "No tasks match this filter",
                description = "Try a different combination or clear the current selection."
            ),
            sharedTasksEmptyStateCopy(hasAnyFilter = true, isChannelMode = true)
        )
    }

    @Test
    fun tasksEmptyStateCopyMatchesReactChannelModeText() {
        assertEquals(
            SharedTasksEmptyStateCopy(
                title = "No tasks yet",
                description = "Create one with the New Task button."
            ),
            sharedTasksEmptyStateCopy(hasAnyFilter = false, isChannelMode = true)
        )
    }

    @Test
    fun tasksEmptyStateCopyMatchesReactServerWideText() {
        assertEquals(
            SharedTasksEmptyStateCopy(
                title = "No tasks yet",
                description = "DM and agent task boards are intentionally excluded from this server-wide view."
            ),
            sharedTasksEmptyStateCopy(hasAnyFilter = false, isChannelMode = false)
        )
    }

    @Test
    fun serverWideEmptyTasksSkipsToolbarWithoutCreateAction() {
        assertEquals(false, sharedTasksEmptyToolbarVisible(createTasksChannelId = null))
        assertEquals(true, sharedTasksEmptyToolbarVisible(createTasksChannelId = ChannelId("channel-1")))
    }

    @Test
    fun tasksToolbarActionRowKeepsCreateAndViewSwitchTogetherWhenTheyFit() {
        assertEquals(
            listOf(listOf(TasksToolbarActionControl.NewTask, TasksToolbarActionControl.ViewSwitch)),
            tasksToolbarActionRows(
                maxWidth = 728.dp,
                showCreateButton = true
            )
        )
    }

    @Test
    fun tasksToolbarActionRowWrapsViewSwitchAsGroupOnNarrowWidth() {
        assertEquals(
            listOf(
                listOf(TasksToolbarActionControl.NewTask),
                listOf(TasksToolbarActionControl.ViewSwitch)
            ),
            tasksToolbarActionRows(
                maxWidth = 420.dp,
                showCreateButton = true
            )
        )
    }

    @Test
    fun tasksToolbarActionRowOnlyShowsViewSwitchWhenCreateIsUnavailable() {
        assertEquals(
            listOf(listOf(TasksToolbarActionControl.ViewSwitch)),
            tasksToolbarActionRows(
                maxWidth = 728.dp,
                showCreateButton = false
            )
        )
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

    @Test
    fun tasksBeyondBoundsKeepsSceneEnterPremeasureSmall() {
        assertEquals(0, sharedTasksBeyondBoundsItemCount(0))
        assertEquals(1, sharedTasksBeyondBoundsItemCount(1))
        assertEquals(4, sharedTasksBeyondBoundsItemCount(40))
        assertEquals(4, sharedTasksBeyondBoundsItemCount(1752))
    }

    @Test
    fun tasksStatusChipClickIdentityTracksRecycledCardBinding() {
        val first = tasksStatusChipClickIdentity("task-1", TaskStatus.Todo)

        assertEquals("tasks-status:task-1:Todo", first)
        assertNotEquals(first, tasksStatusChipClickIdentity("task-2", TaskStatus.Todo))
        assertNotEquals(first, tasksStatusChipClickIdentity("task-1", TaskStatus.InProgress))
        assertEquals(first, tasksStatusChipClickIdentity("task-1", TaskStatus.Todo))
    }

    @Test
    fun tasksStatusMenuOwnerIdentityStaysSemanticAcrossStatusChanges() {
        val owner = tasksStatusMenuOwnerIdentity("task-1")

        assertEquals("task:task-1", owner)
        assertNotEquals(owner, tasksStatusChipClickIdentity("task-1", TaskStatus.Todo))
        assertNotEquals(owner, tasksStatusChipClickIdentity("task-1", TaskStatus.InProgress))
        assertEquals(owner, tasksStatusMenuOwnerIdentity("task-1"))
    }

    // #130 filter-search caller-local editor seed contract. The editor is the sole
    // owner of its TextFieldValue within a filter session; slockFilterSearchSessionSeed
    // is invoked only from a LaunchedEffect keyed on the openFilterId session token,
    // so these decisions are the only way the durable String reaches the local editor.
    @Test
    fun filterSearchSeedsFromDurableWithCaretAtEndOnSessionOpen() {
        val seed = slockFilterSearchSessionSeed(openFilterId = "channel", thisFilterId = "channel", durableSearch = "design")
        assertEquals("design", seed?.text)
        assertEquals(TextRange("design".length), seed?.selection)
    }

    @Test
    fun filterSearchSeedCaretUsesCharacterCountForCjk() {
        val seed = slockFilterSearchSessionSeed(openFilterId = "creator", thisFilterId = "creator", durableSearch = "设计师")
        assertEquals("设计师", seed?.text)
        // caret at end by character index (3), so mid-text CJK editing starts sane;
        // the live editor owns the selection/composition for the rest of the session.
        assertEquals(TextRange(3), seed?.selection)
    }

    @Test
    fun filterSearchNewSessionSeedsFreshFromResetDurable() {
        // SetOpenFilter clears the non-active box on close; the reopened session
        // seeds an empty editor rather than reopening with stale local text.
        val seed = slockFilterSearchSessionSeed(openFilterId = "assignee", thisFilterId = "assignee", durableSearch = "")
        assertEquals("", seed?.text)
        assertEquals(TextRange(0), seed?.selection)
    }

    @Test
    fun filterSearchDoesNotReseedInactiveFilterFromStaleDurable() {
        // While "channel" owns the open session, a stale or echoed "creator" durable
        // String must never reseat the creator editor (would clobber its composition).
        assertNull(slockFilterSearchSessionSeed(openFilterId = "channel", thisFilterId = "creator", durableSearch = "stale-echo"))
        // No open session at all → nothing is seeded.
        assertNull(slockFilterSearchSessionSeed(openFilterId = null, thisFilterId = "channel", durableSearch = "x"))
    }

    @Test
    fun filterSearchSessionSeedTargetsOnlyTheOpenFilter() {
        val open = "creator"
        assertNull(slockFilterSearchSessionSeed(open, "channel", "c"))
        assertEquals("cr", slockFilterSearchSessionSeed(open, "creator", "cr")?.text)
        assertNull(slockFilterSearchSessionSeed(open, "assignee", "a"))
    }

}
