package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import build.raft.app.cmp.ProvideRaftPageCover
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first reply in a task without a conversation (Jony, iOS 1.25.0): the send creates the
 * conversation and pushes its page over the host page. The push fires the host page's cover signal
 * ([RaftPageCoverSignal]) before it runs, while the page still composes; the pending page's window
 * must close then. On iOS a covered page composes no more, so a pending page that waited for the
 * host's visibility to change ([TaskOpenSurfaceFlow.finishHandOff]) stayed over the conversation
 * page, a window layer with the old empty state and the sent draft, and Back could not close it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskPendingHandOffCoverUiTest {
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

    /** The platform navigator: records each push and whether the pending page was still open then. */
    private class RecordingNavigator(private val pendingOpen: () -> Boolean) : RaftCmpNavigator {
        val pushes = mutableListOf<Pair<String, Boolean>>()

        override fun openPage(pageName: String, pageData: JsonObject) {
            pushes += pageName to pendingOpen()
        }

        override fun closePage() = Unit
    }

    private lateinit var pageNavigator: RaftCmpNavigator

    /** The host page as RaftCmpRoot provides it, with the pending task page open over it. */
    private fun ComposeUiTest.hostPage(navigator: RaftCmpNavigator, flow: TaskOpenSurfaceFlow) {
        val transport = HostBridgeTransport { _, _, _ -> }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        setContent {
            val signal = remember { RaftPageCoverSignal() }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            ProvideRaftPageCover(host, signal) {
                pageNavigator = LocalRaftHost.current.navigator
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        TaskOpenSurfaceHost(flow = flow, onSend = { _, _ -> }, serverId = SERVER)
                    }
                }
            }
        }
    }

    @Test
    fun theFirstReplysConversationPageClosesThePendingPageAsItIsPushed() =
        runRobolectricComposeUiTest {
            val task = pendingTask()
            val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-1" })
            flow.open(TaskOpenSurface.Pending(task))
            flow.onDraftChange(task.id.value, "On it")
            val navigator = RecordingNavigator { flow.surface != null }
            hostPage(navigator, flow)
            onNode(hasText("No discussion in this task yet")).assertExists()

            // As the host's onSend does: the send returns, then the conversation page is pushed
            // at once (no recomposition in between), without animation.
            runOnIdle {
                val opened =
                    runBlocking {
                        flow.send(
                            text = "On it",
                            serverId = SERVER,
                            tasksDataSource = SharedTasksDataSource(ConversationApi(), diagnosticsLogger = {}),
                            sendMessage = { _, _, _ -> ThreadApiResult.Success(Unit) },
                            currentServerId = { SERVER },
                            fallbackError = "Couldn't send the message",
                        )
                    }
                assertEquals(ChannelId("conv-local"), opened?.second)
                pageNavigator.openPage(CONVERSATION_PAGE)
            }

            assertEquals(listOf(CONVERSATION_PAGE to false), navigator.pushes, "the pending page closed before the push")
            assertNull(flow.surface)
            assertFalse(flow.handingOff)
            assertEquals("", flow.draft(task.id.value), "the sent text is no draft any more")
            waitForIdle()
            onNode(hasText("No discussion in this task yet")).assertDoesNotExist()
        }

    @Test
    fun aPushWithNoFirstSendRunningClosesThePendingPageAsBackDoes() =
        runRobolectricComposeUiTest {
            val task = pendingTask()
            val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-1" })
            flow.open(TaskOpenSurface.Pending(task))
            hostPage(RecordingNavigator { flow.surface != null }, flow)
            // A cover with no hand-off running closes the page as Back does.
            runOnIdle { pageNavigator.openPage(CONVERSATION_PAGE) }
            waitForIdle()
            assertNull(flow.surface)
            onNode(hasText("No discussion in this task yet")).assertDoesNotExist()
        }

    /** A v2 task whose conversation is not created yet (no message in it). */
    private fun pendingTask(): TaskSummary {
        val json =
            """{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"t-1","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,
               "messageId":"t-1","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":true,"isLegacy":false}"""
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
        const val CONVERSATION_PAGE = "compose_thread"
    }
}
