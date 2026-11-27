package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE
import build.raft.app.data.RAFT_APP_LOGIN_MMKV_NAMESPACE
import build.raft.app.data.RAFT_AUTH_SESSION_MMKV_NAMESPACE
import build.raft.app.data.RAFT_DEVICE_STATE_MMKV_NAMESPACE
import build.raft.app.data.RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.LocalThreadReverseLayout
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadEventHandler
import build.raft.app.thread.ThreadLatestJumpOutcome
import build.raft.app.thread.ThreadLinkedTasksProjection
import build.raft.app.thread.ThreadMessagesList
import build.raft.app.thread.ThreadNewerPageLoadOutcome
import build.raft.app.thread.toThreadTimelineRenderConfig
import build.raft.app.thread.toThreadTimelineSlice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Characterizes where the message list keeps its open action menu (ThreadMessagesListContent's
 * menu state, remembered under the thread instance key, #4995): a long press opens a message's
 * menu, and realtime updates of the same thread (the message's text edited, a new message
 * appended) leave that menu open on that message; another thread instance starts with it closed,
 * even with the same messages. Composes the production [ThreadMessagesList] the way
 * [ThreadBackToBottomJumpUiTest] does, over a payload the test replaces as the store would.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h700dp")
class ThreadMessageMenuSurvivesPayloadUpdateUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The menu's reaction row logs through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-message-menu-survives-payload-update"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        PagerManager.register(livePager)
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        // As ThreadBackToBottomJumpUiTest: the list reads the shared stores.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        build.raft.app.image.raftInstallImageLoader()
        listOf(
            RAFT_AUTH_SESSION_MMKV_NAMESPACE,
            RaftLocalPreferenceStore.KuiklyNamespace,
            RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE,
            RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE,
            RAFT_DEVICE_STATE_MMKV_NAMESPACE,
            RAFT_APP_LOGIN_MMKV_NAMESPACE,
            SharedApiEnvironmentStore.Namespace,
        ).forEach { namespace ->
            val store = InMemoryRaftKeyValueStore()
            RaftSharedKeyValueStoreProvider.installAppScopeFactory(namespace) { store }
        }
    }

    @AfterTest
    fun tearDown() {
        PagerManager.unregister(livePager)
        StringDesc.localeType = previousLocale
        RaftSharedStoreRegistry.clearForTests()
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

    /** The list's inputs, replaced by the test as the thread store and the route would. */
    private class Harness {
        var threadInstanceKey by mutableStateOf(ThreadA)
        var payload by mutableStateOf(threadPayload(ThreadA, messages()))
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    private fun ComposeUiTest.host(harness: Harness) {
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                // As production: the thread list is always bottom-up (ThreadPage threadReverseLayoutEnabled).
                LocalThreadReverseLayout provides true,
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        val payload = harness.payload
                        ThreadMessagesList(
                            threadInstanceKey = harness.threadInstanceKey,
                            routeActive = true,
                            hostForeground = true,
                            renderConfig = payload.toThreadTimelineRenderConfig(),
                            messageBodyTextStyle = TextStyle.Default,
                            timelineSlice = payload.toThreadTimelineSlice(),
                            replyMetadataByParentId = emptyMap(),
                            activeReplyDraftParentMessageIds = emptySet(),
                            composerKeyboardInsetDp = 0f,
                            linkedTasksProjection = ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap()),
                            highlightedMentions = emptySet(),
                            onThreadEvent = ThreadEventHandler { action, itemId, _, _, _, _ -> harness.events += "$action:$itemId" },
                            onLoadNewer = { ThreadNewerPageLoadOutcome.Empty },
                            onJumpToLatest = { ThreadLatestJumpOutcome.Landed },
                            onOpenMember = {},
                            onMentionAuthor = {},
                            onOpenMarkdownTag = { _, _ -> },
                            onOpenMarkdownUrl = {},
                            diagnosticsLogger = { line -> harness.log += line },
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    private fun ComposeUiTest.menuShown(): Boolean =
        onAllNodesWithText(CopyMarkdown, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Long-presses the second message's text: its row opens the (Classic) message menu. */
    private fun ComposeUiTest.openMenuOnSecondMessage(harness: Harness) {
        onNodeWithText(SecondBody, substring = true).performTouchInput { longClick() }
        settle()
        assertTrue(menuShown(), "the long press opened no menu: log=${harness.log.takeLast(12)}")
    }

    /** The open menu's Copy Markdown: the message it copies is the one the menu is for. */
    private fun ComposeUiTest.copyFromMenu() {
        val copy = onAllNodes(hasClickAction() and (hasText(CopyMarkdown) or hasContentDescription(CopyMarkdown)))
        copy.assertCountEquals(1)
        copy[0].performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun theOpenMenuStaysOnItsMessageThroughRealtimeUpdatesOfTheSameThread() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            host(harness)
            openMenuOnSecondMessage(harness)

            // Realtime: the menu's own message is edited (a new message object, same id).
            runOnIdle {
                harness.payload =
                    threadPayload(ThreadA, messages().map { if (it.id == "m2") it.copy(body = "$SecondBody (edited)") else it })
            }
            settle()
            assertTrue(menuShown(), "an edit of the message closed its menu: log=${harness.log.takeLast(12)}")

            // Realtime: a new message arrives at the bottom of the same thread.
            runOnIdle {
                harness.payload =
                    threadPayload(
                        ThreadA,
                        messages().map { if (it.id == "m2") it.copy(body = "$SecondBody (edited)") else it } +
                            message(4, "A fourth message just arrived")
                    )
            }
            settle()
            assertTrue(menuShown(), "a new message closed the open menu: log=${harness.log.takeLast(12)}")

            // Still the second message's menu: Copy copies m2.
            copyFromMenu()
            assertEquals(listOf("copyMessage:m2"), harness.events.filter { it.startsWith("copyMessage") })
            assertFalse(menuShown(), "the menu closes after Copy")
        }

    @Test
    fun anotherThreadInstanceStartsWithTheMenuClosed() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            host(harness)
            openMenuOnSecondMessage(harness)

            // Another thread instance with the very same messages: only the key changes, so the
            // menu can only close because the list's menu state is per thread instance.
            runOnIdle {
                harness.threadInstanceKey = ThreadB
                harness.payload = threadPayload(ThreadB, messages())
            }
            settle()
            assertFalse(menuShown(), "the menu stayed open in another thread instance")
            onAllNodesWithText(SecondBody, substring = true).fetchSemanticsNodes().let { nodes ->
                assertTrue(nodes.isNotEmpty(), "the second message is still on screen")
            }
        }

    private companion object {
        const val ThreadA = "thread-menu-a"
        const val ThreadB = "thread-menu-b"
        const val SecondBody = "The second message has the menu"

        /** The Classic menu's always-present copy row (thread_action_copy_markdown). */
        const val CopyMarkdown = "Copy Markdown"

        fun message(seq: Int, body: String) =
            RaftThreadMessageItem(
                id = "m$seq",
                authorId = "u1",
                authorName = "Artin",
                seq = seq.toLong(),
                body = body,
                timestampLabel = "2026-07-30T02:00:${(seq % 60).toString().padStart(2, '0')}Z",
            )

        fun messages() =
            listOf(
                message(1, "The first message"),
                message(2, SecondBody),
                message(3, "The third message"),
            )

        fun threadPayload(instanceId: String, messages: List<RaftThreadMessageItem>) =
            RaftThreadScreenPayload(
                instanceId = instanceId,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                status = "content",
                messages = messages,
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                canSend = true,
            )
    }
}
