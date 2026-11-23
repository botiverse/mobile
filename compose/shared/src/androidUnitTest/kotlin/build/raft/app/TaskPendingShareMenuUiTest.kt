package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.share_out_task
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
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
 * Pending task page's ⋯ menu ([TaskPendingConversationPage]): when the share loop is on for a
 * non-DM task it offers Share task and Copy link (same actions as a task with a conversation), and
 * toasts in the shared host stay visible over the full-screen dialog window; without [shareEntry]
 * (flag off or DM task) no ⋯ button is shown.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskPendingShareMenuUiTest {
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

    private val task =
        TaskSummary(
            id = TaskId("cf5d7bb5-6bbe-4304-bc87-1ab40601a98e"),
            title = "Share before the first message",
            status = TaskStatus.Todo,
            taskNumber = 6,
            messageId = MessageId("msg-host"),
            channelId = ChannelId("channel-all"),
            channelName = "all",
            channelType = "channel",
            updatedAtLabel = "2026-11-20T10:00:00Z",
            hostMessageId = MessageId("msg-host"),
        )

    @Test
    fun pendingTaskHeaderMoreMenuRunsShareTaskAndCopyLinkAndShowsToasts() =
        runRobolectricComposeUiTest {
            var shares = 0
            var copies = 0
            val toastHost = RaftToastHostState()
            val entry =
                ThreadHeaderShareEntry(
                    shareLabel = Res.string.share_out_task,
                    onShare = { shares += 1 },
                    onCopyLink = {
                        copies += 1
                        toastHost.show("Copied")
                    },
                )
            setContent {
                Host(toastHost) {
                    TaskPendingConversationWindow(onDismissRequest = {}) {
                        PendingPage(shareEntry = entry)
                    }
                }
            }

            onNodeWithTag(TaskPendingMoreMenuTestTag).assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("Share task").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(1, shares)

            onNodeWithTag(TaskPendingMoreMenuTestTag).performClick()
            waitForIdle()
            onNodeWithText("Copy link").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(1, copies)
            onAllNodesWithText("Copied").assertCountEquals(1)
            onNodeWithText("Copied").assertIsDisplayed()
        }

    @Test
    fun closingPendingTaskDialogHandsActiveToastBackToBaseHost() =
        runRobolectricComposeUiTest {
            val toastHost = RaftToastHostState()
            var dialogOpen by mutableStateOf(true)
            val entry =
                ThreadHeaderShareEntry(
                    shareLabel = Res.string.share_out_task,
                    onShare = {},
                    onCopyLink = { toastHost.show("Copied") },
                )
            setContent {
                Host(toastHost) {
                    if (dialogOpen) {
                        TaskPendingConversationWindow(onDismissRequest = { dialogOpen = false }) {
                            PendingPage(shareEntry = entry)
                        }
                    }
                }
            }

            onNodeWithTag(TaskPendingMoreMenuTestTag).performClick()
            waitForIdle()
            onNodeWithText("Copy link").performClick()
            waitForIdle()
            onAllNodesWithText("Copied").assertCountEquals(1)

            dialogOpen = false
            waitForIdle()
            onAllNodesWithText("Copied").assertCountEquals(1)
            onNodeWithText("Copied").assertIsDisplayed()
        }

    @Test
    fun pendingTaskHeaderHasNoMoreMenuWithoutShareEntry() =
        runRobolectricComposeUiTest {
            setContent {
                Host(toastHost = null) {
                    TaskPendingConversationWindow(onDismissRequest = {}) {
                        PendingPage(shareEntry = null)
                    }
                }
            }

            onNodeWithTag(TaskPendingMoreMenuTestTag).assertDoesNotExist()
        }

    @Composable
    private fun Host(toastHost: RaftToastHostState?, content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(
            LocalActivity provides RaftPageActivity(pager),
            LocalRaftToastHostState provides toastHost,
        ) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize()) {
                    content()
                    toastHost?.let { RaftToastHost(it) }
                }
            }
        }
    }

    @Composable
    private fun PendingPage(shareEntry: ThreadHeaderShareEntry?) {
        TaskPendingConversationPage(
            task = task,
            details = TaskHeadDetailsHandle(TaskConversationDetailsState(), null),
            members = emptyList(),
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
            onUpdateStatus = {},
            onAssign = {},
            shareEntry = shareEntry,
        )
    }
}
