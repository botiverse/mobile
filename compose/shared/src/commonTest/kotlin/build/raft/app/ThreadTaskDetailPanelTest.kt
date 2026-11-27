package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.thread.ThreadLinkedTasksProjection
import build.raft.app.thread.assignUpdateIntent
import build.raft.app.thread.taskAssigneeIdentityOptions
import build.raft.app.thread.threadEventRequiresWriteAccess
import build.raft.app.thread.threadTaskAssigneeRows
import build.raft.app.thread.threadTaskDetailPanelUseLegacy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #258: the inline task chip's combined detail panel — fail-closed fallback
 * selection and the assignee row ordering/search contract. The panel must be
 * reachable only through the same rules as the board's assignee pill (task #244,
 * stdrc msg=4c357cb1): "unassigned" sentinel first and never filtered away,
 * humans before agents, System excluded, search on the visible display name.
 */
class ThreadTaskDetailPanelTest {

    private fun member(name: String, kind: MemberKind, id: String = name) = MemberProfile(
        id = MemberId(id),
        displayName = name,
        handle = name,
        kind = kind
    )

    private val directory = listOf(
        member("Cindy", MemberKind.Agent),
        member("alex-jc-qa1", MemberKind.Human),
        member("qa-system", MemberKind.System),
        member("artin", MemberKind.Human),
        member("SlockTestOranix", MemberKind.Agent)
    )

    @Test
    fun legacyStatusMenuWhenLinkedTaskSummaryMissing() {
        assertTrue(threadTaskDetailPanelUseLegacy(null))
    }

    @Test
    fun unassignedSentinelAlwaysFirstAndNeverFilteredBySearch() {
        val rows = threadTaskAssigneeRows(directory, "")
        assertNull(rows.first(), "unassigned sentinel (null row) must be first")
        val searched = threadTaskAssigneeRows(directory, "zzz-no-match")
        assertNull(searched.firstOrNull(), "unassigned sentinel survives even a no-match search")
    }

    @Test
    fun systemMembersExcludedFromAssigneeRows() {
        val rows = threadTaskAssigneeRows(directory, "")
        assertTrue(rows.filterNotNull().none { it.kind == MemberKind.System })
    }

    @Test
    fun setTaskAssigneeIsAWriteGatedThreadEvent() {
        assertTrue(threadEventRequiresWriteAccess("setTaskAssignee"))
    }

    @Test
    fun assignUpdateIntentResolvesLinkedTaskAndMapsNullAssignee() {
        val task =
            TaskSummary(
                id = TaskId("task-9"),
                title = "t258",
                status = TaskStatus.Todo,
                updatedAtLabel = "now"
            )
        val projection = ThreadLinkedTasksProjection(authoritative = true, lookup = mapOf("task-id:task-9" to task))
        val unassignedIntent = projection.assignUpdateIntent("task-9", null)
        assertNotNull(unassignedIntent)
        assertNull(unassignedIntent.assignee)
        assertEquals("task-9", unassignedIntent.task.id.value)
        val member = member("alex-jc-qa1", MemberKind.Human)
        val assignedIntent = projection.assignUpdateIntent("task-9", member)
        assertEquals(member, assignedIntent?.assignee)
    }

    // artin 2026-09-01/02: the task assignee picker lists CURRENT CHANNEL members
    // once the channel roster slice has loaded; before that it reuses the
    // synchronous channel roster from the thread payload — never the server roster.
    @Test
    fun assigneeOptionsUseChannelScopeOnceLoaded() {
        val channelMembers = listOf(member("alex-jc-qa1", MemberKind.Human), member("artin", MemberKind.Human))
        assertEquals(
            channelMembers,
            taskAssigneeIdentityOptions(
                channelMembers = channelMembers,
                channelMembersLoaded = true,
                payloadMembers = directory
            )
        )
    }
}
