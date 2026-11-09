package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.StringDesc
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
 * Backlog in the app as on the web (slock #12963): a task v2 task's status menu offers Backlog
 * first; picking it asks for an optional reason (web TaskBacklogReasonPopover) and only "Move to
 * Backlog" writes, with the reason; Cancel writes nothing. Create Task has a Status field on task v2
 * (Backlog, Todo, In Progress; Todo by default) and none with task v2 off.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskBacklogReasonUiTest {
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

    private val task =
        TaskSummary(
            id = TaskId("task-25"),
            title = "Offline drafts",
            status = TaskStatus.Todo,
            taskNumber = 25,
            messageId = MessageId("msg-host"),
            channelId = ChannelId("channel-apps"),
            channelName = "proj-apps",
            updatedAtLabel = "2026-11-08T10:30:00Z",
            createdAt = "2026-11-08T10:30:00Z",
            conversationAnchored = true,
        )

    @Test
    fun pickingBacklogAsksForAReasonAndMovesTheTaskWithIt() =
        runRobolectricComposeUiTest {
            setContent { Host { Head(task) } }

            onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Backlog").assertIsDisplayed().performClick()
            waitForIdle()

            // Nothing is written until the reason is confirmed.
            assertEquals(emptyList<SharedTasksRouteIntent>(), dispatched)
            onNodeWithText("Move #25 to Backlog").assertIsDisplayed()
            onNodeWithText("Reason (optional)").assertIsDisplayed()
            onNode(hasSetTextAction()).performTextInput("  waits for the server-side record ")
            onNodeWithTag(TaskBacklogReasonConfirmTestTag, useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(
                listOf<SharedTasksRouteIntent>(SharedTasksRouteIntent.UpdateStatus(task, TaskStatus.Backlog, "waits for the server-side record")),
                dispatched,
            )
            onNodeWithTag(TaskBacklogReasonDialogTestTag).assertDoesNotExist()
        }

    @Test
    fun aBlankReasonMovesWithoutOneAndCancelWritesNothing() =
        runRobolectricComposeUiTest {
            setContent { Host { Head(task) } }

            onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Backlog").performClick()
            waitForIdle()
            onNodeWithTag(TaskBacklogReasonCancelTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(emptyList<SharedTasksRouteIntent>(), dispatched)

            onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Backlog").performClick()
            waitForIdle()
            onNodeWithTag(TaskBacklogReasonConfirmTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(listOf<SharedTasksRouteIntent>(SharedTasksRouteIntent.UpdateStatus(task, TaskStatus.Backlog, null)), dispatched)
        }

    @Test
    fun otherStatusesStillMoveAtOnce() =
        runRobolectricComposeUiTest {
            setContent { Host { Head(task) } }

            onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Done").performClick()
            waitForIdle()

            onNodeWithTag(TaskBacklogReasonDialogTestTag).assertDoesNotExist()
            assertEquals(listOf<SharedTasksRouteIntent>(SharedTasksRouteIntent.UpdateStatus(task, TaskStatus.Done)), dispatched)
        }

    @Test
    fun createTaskOnTaskV2HasAStatusFieldThatDefaultsToTodo() =
        runRobolectricComposeUiTest {
            val submitted = mutableListOf<Pair<List<String>, TaskStatus?>>()
            setContent {
                Host {
                    TasksCreateDialog(
                        loading = false,
                        errorCode = null,
                        onDismiss = {},
                        onSubmit = { titles, status -> submitted += titles to status },
                        statusField = true,
                    )
                }
            }

            onNodeWithTag(TaskCreateStatusTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Todo").assertIsDisplayed()
            onNodeWithTag(TaskCreateStatusTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            listOf("Backlog", "In Progress").forEach { onNodeWithText(it).assertIsDisplayed() }
            onNodeWithText("Backlog").performClick()
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("Offline drafts")
            onNodeWithText("Create").performClick()
            waitForIdle()

            // A create in Backlog asks nothing.
            onNodeWithTag(TaskBacklogReasonDialogTestTag).assertDoesNotExist()
            assertEquals(listOf<Pair<List<String>, TaskStatus?>>(listOf("Offline drafts") to TaskStatus.Backlog), submitted)
        }

    @Test
    fun createTaskWithTaskV2OffHasNoStatusField() =
        runRobolectricComposeUiTest {
            val submitted = mutableListOf<Pair<List<String>, TaskStatus?>>()
            setContent {
                Host {
                    TasksCreateDialog(
                        loading = false,
                        errorCode = null,
                        onDismiss = {},
                        onSubmit = { titles, status -> submitted += titles to status },
                    )
                }
            }

            onNodeWithTag(TaskCreateStatusTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNode(hasSetTextAction()).performTextInput("Offline drafts")
            onNodeWithText("Create").performClick()
            waitForIdle()
            assertEquals(listOf<Pair<List<String>, TaskStatus?>>(listOf("Offline drafts") to null), submitted)
        }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    /** The task head as a phone's conversation shows it (its first row), writing through the task channel store's intents. */
    @Composable
    private fun Head(task: TaskSummary) {
        val edits = remember { TaskHeadEdits { dispatched += it } }
        Box(Modifier.fillMaxSize()) {
            TaskConversationHeadRoute(
                task = task,
                state = TaskConversationHeaderState(ChannelId("conv-25")),
                details = TaskConversationDetailsState(),
                statusUpdating = false,
                assigneeCandidates = emptyList(),
                timeContext = null,
                onUpdateStatus = { edits.updateStatus(task, it) },
                onAssign = { edits.assign(task, it) },
                onOpenSource = {},
                onOpenEarlierDiscussion = {},
                scrollState = remember { TaskHeadScrollState() },
                onMoveToBacklog = { edits.moveToBacklog(task, it) },
                // A phone's head is the conversation's first row (the pinned Page placement needs a wide window).
                placement = TaskHeadPlacement.FirstRow,
            )
        }
    }
}
