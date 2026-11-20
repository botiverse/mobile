package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The line above the composer ("Niko is typing…", "1 agent working here") covered the last line of
 * the newest message (Android 1.27.0, #proj-mobile #227). The line floats over the list's end; the
 * list made room for it with a taller last row only while it showed, and the scroll owners parked
 * the newest message for the short row.
 *
 * The list's end always holds the line's slot now, and the scroll owners use the same height
 * (`ThreadBottomSpacerAndAnchorTrailingHeight`). Composes the production [ThreadMessagesList]
 * bottom-up, the way [ThreadMessageMenuSurvivesPayloadUpdateUiTest] does, parked at the newest
 * message both ways the app parks there (opened on that message, as from Inbox or Activity: an
 * anchored viewport; and following the latest message), and shows and hides the line as the typing
 * store would: the newest message is clear of the line and does not move.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h700dp")
class ThreadAgentTypingLineLayoutUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-agent-typing-line-layout"
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

    private class Harness(val payload: RaftThreadScreenPayload) {
        /** What `rememberThreadAgentTyping` gives the page: null until somebody types or works here. */
        var typing by mutableStateOf<ThreadAgentTypingUi?>(null)
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    private fun ComposeUiTest.host(harness: Harness) {
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                // As production: the thread list is always bottom-up (ThreadPage threadReverseLayoutEnabled).
                LocalThreadReverseLayout provides true,
                LocalThreadAgentTyping provides harness.typing,
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        val payload = harness.payload
                        ThreadMessagesList(
                            threadInstanceKey = payload.instanceId,
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
                            onThreadEvent = ThreadEventHandler { _, _, _, _, _, _ -> },
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

    /** The newest message's text on screen (its row's node where the row merges its text), or null. */
    private fun ComposeUiTest.newestMessage(): Rect? =
        onAllNodesWithText(NewestBody, substring = true).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.bottom }

    /** The line's box: its live region fills it and is named by the typing sentence. */
    private fun ComposeUiTest.line(): Rect? =
        onAllNodes(hasContentDescription(TypingSentence)).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.height }

    private fun ComposeUiTest.state(harness: Harness): String =
        "page=${onRoot().fetchSemanticsNode().boundsInRoot} line=${line()} newest=${newestMessage()} " +
            "log=${harness.log.filter { "anchor" in it }.takeLast(8)}"

    private fun Rect.sameAs(other: Rect): Boolean =
        kotlin.math.abs(top - other.top) <= 0.5f && kotlin.math.abs(bottom - other.bottom) <= 0.5f

    /**
     * The newest message's row ends where the list's end starts, which is the list's own end
     * (the end minus the line) above the line: its text ends at least that far above the line.
     */
    private fun ComposeUiTest.clearOfTheLine(newest: Rect, line: Rect): Boolean =
        line.top - newest.bottom >= (ListEndDp - LineDp) * density.density - 0.5f

    private fun ComposeUiTest.assertTheLineComingAndGoingLeavesTheNewestMessageAlone(focusNewest: Boolean) {
        val harness = Harness(threadPayload(focusedMessageId = NewestId.takeIf { focusNewest }))
        host(harness)
        val opened = newestMessage() ?: error("the page did not open at the newest message: ${state(harness)}")
        assertTrue(line() == null, "the line shows before anybody types: ${state(harness)}")

        // Somebody starts typing after the first layout, as the typing store reports it.
        runOnIdle { harness.typing = nikoTyping() }
        settle()

        val lineBox = line() ?: error("the line did not show: ${state(harness)}")
        val withLine = newestMessage() ?: error("the newest message left the screen when the line showed: ${state(harness)}")
        // The report: the newest message's last line under the line.
        assertTrue(clearOfTheLine(withLine, lineBox), "the line covers the newest message: ${state(harness)}")
        assertTrue(withLine.sameAs(opened), "the newest message moved when the line showed: was $opened ${state(harness)}")

        // Nobody types any more.
        runOnIdle { harness.typing = null }
        settle()

        assertTrue(line() == null, "the line still shows after typing ended: ${state(harness)}")
        val withoutLine = newestMessage() ?: error("the newest message left the screen when the line hid: ${state(harness)}")
        assertTrue(withoutLine.sameAs(opened), "the newest message moved when the line hid: was $opened ${state(harness)}")
    }

    /** Opened on the newest message (Inbox, Activity): the viewport is anchored on it. */
    @Test
    fun anchoredOnTheNewestMessageTheLineNeitherCoversNorMovesIt() =
        runRobolectricComposeUiTest { assertTheLineComingAndGoingLeavesTheNewestMessageAlone(focusNewest = true) }

    /** Opened at the bottom: the viewport follows the latest message. */
    @Test
    fun followingTheLatestMessageTheLineNeitherCoversNorMovesIt() =
        runRobolectricComposeUiTest { assertTheLineComingAndGoingLeavesTheNewestMessageAlone(focusNewest = false) }

    /** Somebody is already typing when the conversation opens on its newest message. */
    @Test
    fun openedOnTheNewestMessageWithTheLineAlreadyShowingItIsClearOfTheLine() =
        runRobolectricComposeUiTest {
            val harness = Harness(threadPayload(focusedMessageId = NewestId)).apply { typing = nikoTyping() }
            host(harness)
            val lineBox = line() ?: error("the line did not show: ${state(harness)}")
            val newest = newestMessage() ?: error("the page did not open at the newest message: ${state(harness)}")
            assertTrue(clearOfTheLine(newest, lineBox), "the line covers the newest message: ${state(harness)}")
        }

    /**
     * Somebody is already typing when the conversation opens at the bottom: the initial bottom
     * anchor finds the newest message where its check expects it, on its first attempt (the list's
     * end and the check's trailing padding are the same height), instead of retrying.
     */
    @Test
    fun openedAtTheBottomWithTheLineAlreadyShowingTheInitialAnchorHoldsOnItsFirstAttempt() =
        runRobolectricComposeUiTest {
            val harness = Harness(threadPayload(focusedMessageId = null)).apply { typing = nikoTyping() }
            host(harness)
            val lineBox = line() ?: error("the line did not show: ${state(harness)}")
            val newest = newestMessage() ?: error("the page did not open at the newest message: ${state(harness)}")
            assertTrue(clearOfTheLine(newest, lineBox), "the line covers the newest message: ${state(harness)}")
            val initialAnchor = harness.log.filter { it.startsWith("thread_initial_bottom_anchor ") }
            assertTrue(initialAnchor.none { "result=retry" in it }, "the initial anchor retried: $initialAnchor")
            assertTrue(initialAnchor.any { "result=anchored" in it }, "the initial anchor never reported anchored: $initialAnchor")
        }

    private companion object {
        const val NewestId = "m40"
        const val NewestBody = "The newest message ends here"

        /** thread_agent_typing_one. */
        const val TypingSentence = "Niko is typing…"

        /** The list's end under the newest message, and the line floating over the bottom of it. */
        const val ListEndDp = 46f
        const val LineDp = 24f

        fun nikoTyping() = ThreadAgentTypingUi(serverId = "srv-1", agents = listOf(ThreadTypingAgent("agent-niko", "Niko", null)))

        fun message(seq: Int, body: String) =
            RaftThreadMessageItem(
                id = "m$seq",
                authorId = "u1",
                authorName = "Artin",
                seq = seq.toLong(),
                body = body,
                timestampLabel = "2026-07-30T02:00:${(seq % 60).toString().padStart(2, '0')}Z",
            )

        /** More messages than fit the window, so the list really scrolls. */
        fun threadPayload(focusedMessageId: String?) =
            RaftThreadScreenPayload(
                instanceId = "thread-typing-line-${focusedMessageId ?: "bottom"}",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                status = "content",
                messages = (10..39).map { message(it, "An earlier message $it") } + message(40, NewestBody),
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                focusedMessageId = focusedMessageId,
                focusedMessageRequestNonce = if (focusedMessageId != null) 1 else 0,
                canSend = true,
            )
    }
}
