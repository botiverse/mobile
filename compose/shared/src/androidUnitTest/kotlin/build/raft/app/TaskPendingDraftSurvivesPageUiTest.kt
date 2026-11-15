package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import build.raft.app.cmp.ProvideRaftPageCover
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.RaftPageCoverSignal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A message started in a task that has no conversation yet (the "No discussion in this task yet"
 * page) was lost once the channel page that opened it went away (leaving the channel, Home and
 * back, a link): the draft lived in that page's own [TaskOpenSurfaceFlow]. It now lives in the
 * window's [TaskPendingDraftStore], per server and task, which every page's flow is given.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskPendingDraftSurvivesPageUiTest {
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

    /** The window's store, as [SharedForegroundBannerStore.taskPendingDrafts] holds it. */
    private val drafts = TaskPendingDraftStore()

    /** Whether the channel page is composed; each composition is a new page with its own flow. */
    private var pageOpen by mutableStateOf(true)

    /** The flow of the page composed now (each page makes its own, as ThreadPage does). */
    private var pageFlow: TaskOpenSurfaceFlow? = null

    private var ids = 0

    private fun ComposeUiTest.channelPage() {
        val transport = HostBridgeTransport { _, _, _ -> }
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        setContent {
            if (pageOpen) {
                val flow = remember { TaskOpenSurfaceFlow(drafts, newRandomId = { "rid-${++ids}" }) }
                pageFlow = flow
                val signal = remember { RaftPageCoverSignal() }
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
                ProvideRaftPageCover(host, signal) {
                    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                        RaftDesignSystemTheme {
                            TaskOpenSurfaceHost(flow = flow, onSend = { _, _ -> }, serverId = SERVER)
                        }
                    }
                }
            } else {
                pageFlow = null
            }
        }
    }

    @Test
    fun aDraftTypedInATaskWithoutADiscussionSurvivesItsPageAndGoesWithTheFirstSend() =
        runRobolectricComposeUiTest {
            val task = pendingTask("t-1")
            channelPage()
            val firstFlow = checkNotNull(pageFlow)
            runOnIdle { firstFlow.open(TaskOpenSurface.Pending(task)) }
            onNode(hasText("No discussion in this task yet")).assertExists()

            onAllNodes(hasSetTextAction()).onFirst().performTextInput("half a thought")
            waitForIdle()
            assertEquals("half a thought", drafts.draft(SERVER, "t-1"), "the typed text is the task's draft")

            // The channel page goes (the reader left the channel, or a link opened elsewhere).
            runOnIdle { pageOpen = false }
            waitForIdle()
            onNode(hasText("No discussion in this task yet")).assertDoesNotExist()

            // A new channel page opens the same task again: the draft is there.
            runOnIdle { pageOpen = true }
            waitForIdle()
            val secondFlow = checkNotNull(pageFlow)
            assertNotSame(firstFlow, secondFlow, "a new page, a new flow")
            runOnIdle { secondFlow.open(TaskOpenSurface.Pending(task)) }
            onNode(hasText("No discussion in this task yet")).assertExists()
            onNode(hasText("half a thought")).assertExists()
            assertEquals("half a thought", secondFlow.draft(SERVER, "t-1"))

            // Per server and task: another task, or the same task id on another server, has none.
            assertEquals("", secondFlow.draft(SERVER, "t-2"))
            assertEquals("", secondFlow.draft("server-2", "t-1"))

            // The first send creates the conversation: the draft is gone, for every page.
            runOnIdle {
                val opened =
                    runBlocking {
                        secondFlow.send(
                            text = "half a thought",
                            serverId = SERVER,
                            tasksDataSource = SharedTasksDataSource(ConversationApi(), diagnosticsLogger = {}),
                            sendMessage = { _, _, _ -> ThreadApiResult.Success(Unit) },
                            currentServerId = { SERVER },
                            fallbackError = "Couldn't send the message",
                        )
                    }
                assertEquals(ChannelId("conv-local"), opened?.second)
            }
            assertEquals("", drafts.draft(SERVER, "t-1"), "the sent text is no draft any more")
        }

    @Test
    fun aRetryFromAReopenedPageReusesTheFirstSendsRandomId() =
        runRobolectricComposeUiTest {
            val task = pendingTask("t-1")
            val sent = mutableListOf<String>()
            channelPage()
            val firstFlow = checkNotNull(pageFlow)
            runOnIdle {
                firstFlow.open(TaskOpenSurface.Pending(task))
                // The server took it but the answer was lost: the text stays as the draft.
                runBlocking {
                    firstFlow.send(
                        text = "On it",
                        serverId = SERVER,
                        tasksDataSource = SharedTasksDataSource(ConversationApi(), diagnosticsLogger = {}),
                        sendMessage = { _, _, randomId ->
                            sent += randomId
                            ThreadApiResult.Failure(code = 504)
                        },
                        currentServerId = { SERVER },
                        fallbackError = "Couldn't send the message",
                    )
                }
            }
            runOnIdle { pageOpen = false }
            waitForIdle()
            runOnIdle { pageOpen = true }
            waitForIdle()
            val secondFlow = checkNotNull(pageFlow)
            assertEquals("On it", secondFlow.draft(SERVER, "t-1"))
            runOnIdle {
                secondFlow.open(TaskOpenSurface.Pending(task))
                runBlocking {
                    secondFlow.send(
                        text = "On it",
                        serverId = SERVER,
                        tasksDataSource = SharedTasksDataSource(ConversationApi(), diagnosticsLogger = {}),
                        sendMessage = { _, _, randomId ->
                            sent += randomId
                            ThreadApiResult.Success(Unit)
                        },
                        currentServerId = { SERVER },
                        fallbackError = "Couldn't send the message",
                    )
                }
            }
            assertEquals(listOf("rid-1", "rid-1"), sent, "the server dedupes the retry: one message")
            assertEquals("", drafts.draft(SERVER, "t-1"))
        }

    /** A v2 task whose conversation is not created yet (no message in it). */
    private fun pendingTask(id: String): TaskSummary {
        val json =
            """{"id":"$id","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"$id","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,
               "messageId":"$id","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":true,"isLegacy":false}"""
        return parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")
    }

    /** POST /tasks/:id/conversation creates the conversation. */
    private class ConversationApi : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun openTaskConversation(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
            HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}""")
    }

    private companion object {
        const val SERVER = "server-1"
    }
}
