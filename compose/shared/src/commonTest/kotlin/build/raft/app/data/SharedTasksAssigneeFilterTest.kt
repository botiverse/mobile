package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedTasksAssigneeFilterTest {
    @Test
    fun creatorAndAssigneeFiltersShareServerDirectoryAndSupportUnassignedTasks() {
        val artin = member("Artin")
        val cindy = member("Cindy")
        val friday = member("Friday", kind = MemberKind.Agent)
        val serverDirectory = listOf(cindy, friday, artin)
        val content =
            TasksContent(
                summaryCounts = emptyMap(),
                tasks =
                    listOf(
                        task("task-1", TaskStatus.Todo, creator = artin),
                        task("task-2", TaskStatus.Done, creator = cindy),
                        task("task-3", TaskStatus.InProgress, creator = cindy, assignee = artin)
                    )
            )

        val unassigned =
            content.visibleContent(
                selectedFilter = null,
                presentationState =
                    TasksPresentationState().reduce(
                        TasksPresentationIntent.ToggleAssignee(TasksUnassignedAssigneeFilterId)
                    ),
                showChannelFilter = false,
                identityOptions = serverDirectory
            )
        assertEquals(listOf("Artin", "Cindy", "Friday"), unassigned.creatorOptions.map { it.displayName })
        assertEquals(unassigned.creatorOptions, unassigned.assigneeOptions)
        assertEquals(setOf(TasksUnassignedAssigneeFilterId), unassigned.selectedAssigneeIds)
        assertEquals(listOf("task-1", "task-2"), unassigned.filteredTasks.map { it.id.value })

        val assignedToArtin =
            content.visibleContent(
                selectedFilter = null,
                presentationState =
                    TasksPresentationState().reduce(
                        TasksPresentationIntent.ToggleAssignee(artin.tasksIdentityFilterId())
                    ),
                showChannelFilter = false,
                identityOptions = serverDirectory
            )
        assertEquals(listOf("task-3"), assignedToArtin.filteredTasks.map { it.id.value })
        assertEquals(
            setOf(
                TasksUnassignedAssigneeFilterId,
                artin.tasksIdentityFilterId(),
                cindy.tasksIdentityFilterId(),
                friday.tasksIdentityFilterId()
            ),
            tasksAvailableAssigneeFilterIds(unassigned.assigneeOptions)
        )

        val createdByFriday =
            content.visibleContent(
                selectedFilter = null,
                presentationState =
                    TasksPresentationState().reduce(
                        TasksPresentationIntent.ToggleCreator(friday.tasksIdentityFilterId())
                    )
                        .reduce(TasksPresentationIntent.ToggleCreator("missing"))
                        .reduce(
                            TasksPresentationIntent.PruneUnavailableCreators(
                                serverDirectory.map { it.tasksIdentityFilterId() }.toSet()
                            )
                        ),
                showChannelFilter = false,
                identityOptions = serverDirectory
            )
        assertEquals(setOf(friday.tasksIdentityFilterId()), createdByFriday.selectedCreatorIds)
        assertEquals(emptyList(), createdByFriday.filteredTasks)
        assertEquals(
            emptySet(),
            TasksPresentationState(selectedCreatorIds = setOf(friday.tasksIdentityFilterId()))
                .reduce(TasksPresentationIntent.ClearAllFilters)
                .selectedCreatorIds
        )
    }

    @Test
    fun productionDirectoryDoesNotFallBackToTaskProjectedIdentities() {
        val artin = member("Artin")
        val content =
            TasksContent(
                summaryCounts = emptyMap(),
                tasks = listOf(task("task-1", TaskStatus.Todo, creator = artin, assignee = artin))
            )

        val visible =
            content.visibleContent(
                selectedFilter = null,
                presentationState = TasksPresentationState(),
                showChannelFilter = false,
                identityOptions = emptyList()
            )

        assertEquals(emptyList(), visible.creatorOptions)
        assertEquals(emptyList(), visible.assigneeOptions)
    }

    private fun member(
        displayName: String,
        kind: MemberKind = MemberKind.Human
    ): MemberProfile =
        MemberProfile(
            id = MemberId("member-$displayName"),
            displayName = displayName,
            handle = displayName,
            kind = kind,
            presence = Presence.Active
        )

    private fun task(
        id: String,
        status: TaskStatus,
        creator: MemberProfile,
        assignee: MemberProfile? = null
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = "Task $id",
            status = status,
            assignee = assignee,
            creator = creator,
            updatedAtLabel = "now"
        )
}
