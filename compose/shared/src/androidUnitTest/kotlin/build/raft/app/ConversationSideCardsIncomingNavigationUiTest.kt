package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import build.raft.app.ShellNavigationIntakeFixtures.channelRoute
import build.raft.app.ShellNavigationIntakeFixtures.notificationIntent
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
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task v2's Tasks / Files cards are one open setting for the person, and on a phone they cover
 * the conversation. A message link, a notification or a shortcut arriving while one was open
 * landed under it: the message anchored but hidden, and into the same channel nothing seemed to
 * happen. The cover now closes first ([ConversationSideCardsIncomingNavigationEffect]) and the
 * open goes through; beside a wide conversation the cards stay. Each page is the production
 * [ConversationSideCardsCover] over a stand-in conversation showing where it is anchored.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConversationSideCardsIncomingNavigationUiTest {
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
    private val cards = ConversationSideCardsStore()

    /**
     * The window's page stack starting at [firstPage] (`channel` or `channel@message`), the top
     * page shown, its Tasks card open; the window's intake running as the app shell runs it, each
     * open it performs pushing that conversation at its message. [wide]: the cards stand beside
     * the conversation (no top page reported on a wide layout).
     */
    private fun ComposeUiTest.pagesWithTasksOpen(firstPage: String = "channel-1", wide: Boolean = false) {
        val signal = RaftIncomingNavigationSignal()
        host.cover = signal
        host.topPage = if (wide) null else channelRoute(host)
        cards.open(ConversationSideCard.Tasks)
        val pages = mutableStateListOf(firstPage)
        host.onPerform = { action ->
            if (action is WindowNavigationAction.OpenConversation) {
                val request = action.request
                pages += "${request.conversationIdentity.channelId?.value}@${request.anchorMessageId}"
            }
        }
        setContent {
            LaunchedEffect(intake) { intake.run(host) }
            ThreadAssistantPanelTestPage(ThreadAssistantPanelTestNavigator { false }) {
                // A new page is a new composition, as a pushed conversation page is.
                key(pages.size) { ConversationPage(pages.last(), signal, wide) }
            }
        }
    }

    @Composable
    private fun ConversationPage(page: String, signal: RaftIncomingNavigationSignal, wide: Boolean) {
        val state by cards.state.collectAsState()
        ConversationSideCardsIncomingNavigationEffect(
            store = cards,
            signal = signal,
            coverConversation = !wide,
            focusedMessageId = page.substringAfter('@', "").ifBlank { null },
        )
        val conversation = @Composable { RaftText("chat: $page") }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { conversation() }
                if (state.tasks) RaftText("tasks card beside")
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                conversation()
                ConversationSideCardsCover(
                    cards = state,
                    onPick = cards::focus,
                    onBack = null,
                    onSwipeClose = cards::closeAll,
                    tasksContent = { RaftText("tasks card") },
                    filesContent = { RaftText("files card") },
                )
            }
        }
    }

    @Test
    fun aNotificationIntoAnotherChannelClosesTheTasksCoverAndShowsTheMessage() =
        runRobolectricComposeUiTest {
            pagesWithTasksOpen()
            onNode(hasText("tasks card")).assertExists()

            runOnIdle { intake.enqueue(notificationIntent(channelId = "channel-2", messageId = "message-4")) }
            waitForIdle()

            onNode(hasText("tasks card")).assertDoesNotExist()
            onNode(hasText("chat: channel-2@message-4")).assertExists()
            assertEquals(ConversationSideCardsState(), cards.state.value, "no card stays open over the conversation")
        }

    @Test
    fun aLinkIntoTheChannelUnderTheTasksCoverClosesItAndOpensAtTheMessage() =
        runRobolectricComposeUiTest {
            pagesWithTasksOpen()
            onNode(hasText("tasks card")).assertExists()

            runOnIdle {
                inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "android_view")
            }
            waitForIdle()

            onNode(hasText("tasks card")).assertDoesNotExist()
            onNode(hasText("chat: channel-1@message-9")).assertExists()
            assertEquals(ConversationSideCardsState(), cards.state.value)
            assertEquals(emptyList(), host.logs.filter { it.startsWith("window_navigation_open_skipped") })
        }

    @Test
    fun aPageOpenedAtAMessageOnAPhoneClosesTheTasksCover() =
        runRobolectricComposeUiTest {
            // Cold start: the conversation opens at its message before any page heard the signal.
            pagesWithTasksOpen(firstPage = "channel-2@message-4")
            waitForIdle()

            onNode(hasText("tasks card")).assertDoesNotExist()
            onNode(hasText("chat: channel-2@message-4")).assertExists()
            assertEquals(ConversationSideCardsState(), cards.state.value)
        }

    @Test
    fun onAWideLayoutTheTasksCardStaysBesideTheOpenedMessage() =
        runRobolectricComposeUiTest {
            pagesWithTasksOpen(wide = true)
            onNode(hasText("tasks card beside")).assertExists()

            runOnIdle { intake.enqueue(notificationIntent(channelId = "channel-2", messageId = "message-4")) }
            waitForIdle()

            onNode(hasText("chat: channel-2@message-4")).assertExists()
            onNode(hasText("tasks card beside")).assertExists()
            assertTrue(cards.state.value.tasks, "the cards beside the conversation stay open")
        }
}
