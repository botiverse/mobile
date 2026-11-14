package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import build.raft.app.ShellNavigationIntakeFixtures.channelRoute
import build.raft.app.ShellNavigationIntakeFixtures.notificationIntent
import build.raft.app.ShellNavigationIntakeFixtures.pendingTask
import build.raft.app.assistant.ThreadAssistantPanelTestNavigator
import build.raft.app.assistant.ThreadAssistantPanelTestPage
import build.raft.app.data.AppLocale
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ConversationKind
import build.raft.app.resources.StringDesc
import build.raft.designsystem.text.RaftText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A task without a conversation opens as a window over its page (TaskOpenSurface.Pending), not a
 * page. A message link, a notification or banner open, or a shortcut arriving then went under it:
 * into the channel under the window it was skipped as "already the top page" (its message never
 * shown), into another channel the page was pushed under the window, which stayed on top. The
 * window now closes first ([RaftIncomingNavigationSignal]), and the open goes through.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskSurfaceIncomingNavigationUiTest {
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

    private val inbox = RaftMessageLinkInbox(nowEpochMillis = { 1_000L })
    private val intake = RaftShellNavigationIntake(SharedWindowNavigationCoordinator(), inbox, RaftArtifactLinkInbox(nowEpochMillis = { 1_000L }))
    private val host = FakeShellNavigationIntakeHost()

    /**
     * #general's page with the pending task open over it, the window's intake running as the app
     * shell runs it; each open the intake performs shows its page.
     */
    private fun ComposeUiTest.channelPageWithPendingTask(flow: TaskOpenSurfaceFlow) {
        val signal = RaftIncomingNavigationSignal()
        host.cover = signal
        host.topPage = channelRoute(host)
        val pages = mutableStateListOf("general")
        host.onPerform = { action ->
            if (action is WindowNavigationAction.OpenConversation) {
                val request = action.request
                pages += "${request.conversationIdentity.channelId?.value}@${request.anchorMessageId}"
            }
        }
        setContent {
            LaunchedEffect(intake) { intake.run(host) }
            ThreadAssistantPanelTestPage(ThreadAssistantPanelTestNavigator { false }) {
                Column { RaftText("top page: ${pages.last()}") }
                TaskOpenSurfaceIncomingNavigationEffect(flow, signal)
                TaskOpenSurfaceHost(flow = flow, onSend = { _, _ -> }, serverId = ShellNavigationIntakeFixtures.SERVER)
            }
        }
    }

    @Test
    fun aLinkIntoTheChannelUnderThePendingTaskClosesItAndOpensAtTheMessage() =
        runRobolectricComposeUiTest {
            val task = pendingTask()
            val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-1" })
            flow.open(TaskOpenSurface.Pending(task))
            flow.onDraftChange(task.id.value, "half a thought")
            channelPageWithPendingTask(flow)
            onNode(hasText("No discussion in this task yet")).assertExists()

            runOnIdle {
                inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "android_view")
            }
            waitForIdle()

            onNode(hasText("No discussion in this task yet")).assertDoesNotExist()
            onNode(hasText("top page: channel-1@message-9")).assertExists()
            assertNull(flow.surface)
            assertEquals("half a thought", flow.draft(task.id.value), "the draft stays for the next open")
            assertEquals(emptyList(), host.logs.filter { it.startsWith("window_navigation_open_skipped") })
        }

    @Test
    fun aNotificationIntoAnotherChannelClosesThePendingTaskAndShowsThatChannel() =
        runRobolectricComposeUiTest {
            val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-1" })
            flow.open(TaskOpenSurface.Pending(pendingTask()))
            channelPageWithPendingTask(flow)
            onNode(hasText("No discussion in this task yet")).assertExists()

            runOnIdle { intake.enqueue(notificationIntent(channelId = "channel-2", messageId = "message-4")) }
            waitForIdle()

            onNode(hasText("No discussion in this task yet")).assertDoesNotExist()
            onNode(hasText("top page: channel-2@message-4")).assertExists()
            assertNull(flow.surface)
        }
}
