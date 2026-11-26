package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxTaskConversation
import build.raft.app.data.TaskWaitingAssignedBy
import build.raft.app.data.TaskWaitingOnYou
import build.raft.app.data.TaskWaitingVia
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric UI tests for `task_waiting_on_you_v0` (botiverse/slock #16759) on
 * [ActivityConversationListRow]: wide badge, phone pixel, accessible detail name, live-task
 * in-place clearing, and flag-off suppression.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityTaskWaitingUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val sinceIso = "2026-11-26T10:00:00Z"
    private val nowContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            nowEpochMillis = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-26T10:05:00Z", 0)),
            timeFormat = RaftTimeFormat24Hour,
        )

    private fun waitingRow(waiting: TaskWaitingOnYou?): InboxItem =
        InboxItem(
            key = "thread:task-conv-1",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("task-conv-1"),
            channelName = "general",
            threadChannelId = ChannelId("task-conv-1"),
            parentChannelId = ChannelId("ch-1"),
            parentChannelName = "general",
            parentChannelKind = ConversationKind.Channel,
            latestActivityPreview = "Assigned for review",
            latestActivitySenderName = "Alice",
            latestActivitySenderKind = MemberKind.Human,
            latestActivityAt = sinceIso,
            taskNumber = 12,
            taskStatus = TaskStatus.InProgress,
            taskConversation = InboxTaskConversation(taskId = TaskId("task-1"), taskTitle = "Ship parity fix"),
            taskWaiting = waiting,
        )

    private fun ComposeUiTest.showTaskRow(
        scale: RaftScale,
        waiting: TaskWaitingOnYou?,
        liveTasksById: Map<TaskId, TaskSummary> = emptyMap(),
        taskWaitingEnabled: Boolean = true,
    ) {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val row = waitingRow(waiting)
        val context =
            ActivityConversationListContext(
                currentMemberId = "user-me",
                channelSymbolsByChannelId = mapOf("ch-1" to "#"),
                liveTasksById = liveTasksById,
                taskWaitingEnabled = taskWaitingEnabled,
            )
        setContent {
            RaftTheme(
                appearance = RaftAppearance.Light,
                scale = scale,
                pointerInput = if (scale == RaftScale.Wide) RaftPointerInput.Fine else RaftPointerInput.Touch,
            ) {
                ActivityConversationListRow(
                    item = row,
                    model = row.activityConversationRowModel(context, InboxFilter.All),
                    divider = false,
                    selected = false,
                    timeContext = nowContext,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = row.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth().testTag("task-row"),
                    onSwipeDone = {},
                )
            }
        }
    }

    @Test
    fun wideRowShowsWaitingBadgeWithAssignDetailInAccessibleName() =
        runRobolectricComposeUiTest {
            showTaskRow(
                scale = RaftScale.Wide,
                waiting =
                    TaskWaitingOnYou(
                        since = sinceIso,
                        via = TaskWaitingVia.Assign,
                        assignedBy = TaskWaitingAssignedBy(MemberKind.Human, "u-alice", "Alice"),
                        reason = "Need your call on API shape",
                        reasonTruncated = true,
                    ),
            )
            onNodeWithTag(ActivityTaskWaitingMarkTag, useUnmergedTree = true)
                .assertExists()
                .assertContentDescriptionContains("Waiting on you", substring = true)
                .assertContentDescriptionContains("In progress", substring = true)
                .assertContentDescriptionContains("Alice assigned it to you", substring = true)
                .assertContentDescriptionContains("5m ago", substring = true)
                .assertContentDescriptionContains("Need your call on API shape…", substring = true)
        }

    @Test
    fun phoneRowShowsCompactPixelWithFullWaitingAccessibleName() =
        runRobolectricComposeUiTest {
            showTaskRow(
                scale = RaftScale.Phone,
                waiting =
                    TaskWaitingOnYou(
                        since = sinceIso,
                        via = TaskWaitingVia.Claim,
                    ),
            )
            onNodeWithTag(ActivityTaskWaitingMarkTag, useUnmergedTree = true)
                .assertExists()
                .assertContentDescriptionContains("Waiting on you", substring = true)
                .assertContentDescriptionContains("You took it", substring = true)
                .assertContentDescriptionContains("5m ago", substring = true)
        }

    @Test
    fun liveTaskDoneClearsWaitingBadgeInPlace() =
        runRobolectricComposeUiTest {
            val doneTask =
                TaskSummary(
                    id = TaskId("task-1"),
                    title = "Ship parity fix",
                    status = TaskStatus.Done,
                    assignee = MemberProfile(MemberId("user-me"), "Me", "me", MemberKind.Human),
                    updatedAtLabel = sinceIso,
                )
            showTaskRow(
                scale = RaftScale.Wide,
                waiting = TaskWaitingOnYou(since = sinceIso, via = TaskWaitingVia.System),
                liveTasksById = mapOf(TaskId("task-1") to doneTask),
            )
            onNodeWithTag(ActivityTaskWaitingMarkTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun flagOffHidesWaitingBadgeEvenWhenCachedRowHasWaiting() =
        runRobolectricComposeUiTest {
            showTaskRow(
                scale = RaftScale.Wide,
                waiting = TaskWaitingOnYou(since = sinceIso, via = TaskWaitingVia.System),
                taskWaitingEnabled = false,
            )
            onNodeWithTag(ActivityTaskWaitingMarkTag, useUnmergedTree = true).assertDoesNotExist()
        }
}
