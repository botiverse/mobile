package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.StringDesc
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The task head's two editors on a phone, laid out as the task conversation page lays them out
 * (the head the first row of a short conversation) and on the pending task page: tapping the Status chip opens the status menu and a pick dispatches the task
 * channel store's UpdateStatus; tapping the Assignee opens the picker and a pick dispatches
 * AssignTask (web TaskProperties' InlineBadgeEditors, slock staging 2d1156a89). A read-only task
 * has neither editor.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskConversationHeadEditUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private val dispatched = mutableListOf<SharedTasksRouteIntent>()

    private val artin = MemberProfile(id = MemberId("user-artin"), displayName = "artin", handle = "artin", kind = MemberKind.Human)
    private val lagoon = MemberProfile(id = MemberId("agent-lagoon"), displayName = "Lagoon", handle = "lagoon", kind = MemberKind.Agent)

    private val task =
        TaskSummary(
            id = TaskId("task-35"),
            title = "Task header controls do nothing when tapped",
            status = TaskStatus.InReview,
            taskNumber = 35,
            messageId = MessageId("msg-host"),
            channelId = ChannelId("channel-apps"),
            channelName = "proj-apps",
            assignee = artin,
            creator = MemberProfile(id = MemberId("app-father"), displayName = "AppFather", handle = "appfather", kind = MemberKind.Agent),
            updatedAtLabel = "2026-11-03T10:30:00Z",
            createdAt = "2026-11-03T10:30:00Z",
            hostMessageId = MessageId("msg-host"),
        )

    @Test
    fun tappingTheStatusChipOpensTheStatusMenuAndAPickUpdatesTheStatus() =
        runRobolectricComposeUiTest {
            setContent { Host { ConversationPage(task) } }

            onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            // Every status, as on the web: the menu is open.
            listOf("Todo", "In Progress", "Done", "Closed").forEach { onNodeWithText(it).assertIsDisplayed() }

            onNodeWithText("Done").performClick()
            waitForIdle()

            assertEquals(listOf<SharedTasksRouteIntent>(SharedTasksRouteIntent.UpdateStatus(task, TaskStatus.Done)), dispatched)
        }

    @Test
    fun tappingTheAssigneeOpensThePickerAndAPickAssignsTheTask() =
        runRobolectricComposeUiTest {
            setContent { Host { ConversationPage(task) } }

            onNodeWithTag(TaskHeadAssigneeTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Lagoon").assertIsDisplayed().performClick()
            waitForIdle()

            assertEquals(listOf<SharedTasksRouteIntent>(SharedTasksRouteIntent.AssignTask(task, lagoon)), dispatched)
        }

    @Test
    fun onThePendingTaskPageTheStatusChipAndTheAssigneeWorkTheSameWay() =
        runRobolectricComposeUiTest {
            // As the app shows it: full screen in its own dialog window over the host page.
            setContent { Host { TaskPendingConversationWindow(onDismissRequest = {}) { PendingPage(task) } } }

            onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Closed").performClick()
            waitForIdle()
            onNodeWithTag(TaskHeadAssigneeTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Unassigned").performClick()
            waitForIdle()

            assertEquals(
                listOf(SharedTasksRouteIntent.UpdateStatus(task, TaskStatus.Closed), SharedTasksRouteIntent.AssignTask(task, null)),
                dispatched,
            )
        }

    @Test
    fun aReadOnlyTaskHasNeitherEditor() =
        runRobolectricComposeUiTest {
            setContent { Host { ConversationPage(task.copy(readOnlyReason = "historical_joint")) } }

            // The status is the plain badge (no chevron, no tap) and the assignee a plain fact.
            onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).performClick()
            onNodeWithTag(TaskHeadAssigneeTestTag, useUnmergedTree = true).assertDoesNotExist()
            waitForIdle()
            onNodeWithText("Todo").assertDoesNotExist()
            assertEquals(emptyList(), dispatched)
        }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    /**
     * ThreadPage's task conversation on a phone: the top bar, the head above the messages
     * ([TaskHeadPlacement.Page], no room on a phone: it only tells the state the page is a phone's),
     * the messages with the head as their first row ([TaskHeadPlacement.FirstRow]; a short
     * conversation, so it is in view), and the head pulled down from the bar over them
     * ([TaskHeadPlacement.PullDown], up while the row is in view).
     */
    @Composable
    private fun ConversationPage(task: TaskSummary) {
        val scrollState = remember { TaskHeadScrollState() }
        val edits = remember { TaskHeadEdits { dispatched += it } }
        val route: @Composable (TaskHeadPlacement) -> Unit =
            remember(task) {
                { placement ->
                    TaskConversationHeadRoute(
                        task = task,
                        state = TaskConversationHeaderState(ChannelId("conv-35")),
                        details = TaskConversationDetailsState(),
                        statusUpdating = false,
                        assigneeCandidates = listOf(artin, lagoon),
                        timeContext = null,
                        onUpdateStatus = { edits.updateStatus(task, it) },
                        onAssign = { edits.assign(task, it) },
                        onOpenSource = {},
                        onOpenEarlierDiscussion = {},
                        scrollState = scrollState,
                        placement = placement,
                    )
                }
            }
        val headRow = rememberTaskConversationHeadRow(scrollState) { route(TaskHeadPlacement.FirstRow) }
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).taskHeadFloatOutsidePress(scrollState)) {
            Box(Modifier.fillMaxWidth().height(56.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    route(TaskHeadPlacement.Page)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollState.connection), reverseLayout = true) {
                        items(3) { index -> RaftText("Message $index", Modifier.padding(16.dp)) }
                        // Reversed: the last row is the top one, where the conversation begins.
                        item { TaskConversationHeadRowContent(headRow) }
                    }
                }
                route(TaskHeadPlacement.PullDown)
            }
        }
    }

    @Composable
    private fun PendingPage(task: TaskSummary) {
        val edits = remember { TaskHeadEdits { dispatched += it } }
        TaskPendingConversationPage(
            task = task,
            details = TaskHeadDetailsHandle(TaskConversationDetailsState(), null),
            members = listOf(artin, lagoon),
            timeContext = null,
            editable = true,
            statusUpdating = false,
            sending = false,
            errorText = null,
            joined = true,
            draft = "",
            onDraftChange = {},
            onBack = {},
            onSend = {},
            onJoin = {},
            onUpdateStatus = { edits.updateStatus(task, it) },
            onAssign = { edits.assign(task, it) },
        )
    }
}
