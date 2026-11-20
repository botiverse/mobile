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
import androidx.compose.ui.test.hasScrollAction
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
 * the newest message (Android 1.27.0, #proj-mobile #227): it floated over the list's end, and the
 * list made room for it with a taller last row that the reading anchor then scrolled back.
 *
 * The line is a row of the conversation's layout now: the list's viewport ends where the line
 * starts. Composes the production [ThreadMessagesList] bottom-up, the way
 * [ThreadMessageMenuSurvivesPayloadUpdateUiTest] does, parked at the newest message both ways the
 * app parks there (opened on that message, as from Inbox or Activity: an anchored viewport; and
 * following the latest message), then shows and hides the line as the typing store would.
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

    /** The list's viewport: the tallest scrollable on the page. */
    private fun ComposeUiTest.listViewport(): Rect =
        onAllNodes(hasScrollAction()).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.height }
            ?: error("no scrollable list on the page")

    /** The newest message's text on screen (its row's node where the row merges its text), or null. */
    private fun ComposeUiTest.newestMessage(): Rect? =
        onAllNodesWithText(NewestBody, substring = true).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.bottom }

    /** The line's box: its live region fills it and is named by the typing sentence. */
    private fun ComposeUiTest.line(): Rect? =
        onAllNodes(hasContentDescription(TypingSentence)).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.height }

    private fun ComposeUiTest.assertTheLineNeverCoversTheNewestMessage(focusNewest: Boolean) {
        val harness = Harness(threadPayload(focusedMessageId = NewestId.takeIf { focusNewest }))
        host(harness)
        val page = onRoot().fetchSemanticsNode().boundsInRoot
        val nearPx = NearTheListEndDp * density.density
        fun state() =
            "page=$page list=${listViewport()} line=${line()} newest=${newestMessage()} " +
                "log=${harness.log.filter { "viewport" in it || "anchor" in it }.takeLast(8)}"

        val opened = newestMessage()
        assertTrue(opened != null && opened.bottom <= page.bottom + 0.5f, "the page did not open at the newest message: ${state()}")
        assertTrue(line() == null, "the line shows before anybody types: ${state()}")

        // Somebody starts typing after the first layout, as the typing store reports it.
        runOnIdle { harness.typing = ThreadAgentTypingUi(serverId = "srv-1", agents = listOf(ThreadTypingAgent("agent-niko", "Niko", null))) }
        settle()

        val lineBox = line() ?: error("the line did not show: ${state()}")
        val listBox = listViewport()
        val newest = newestMessage() ?: error("the newest message left the screen when the line showed: ${state()}")
        // The line is the page's last row, and the list ends where it starts: nothing of the list
        // is under it.
        assertTrue(kotlin.math.abs(lineBox.bottom - page.bottom) <= 0.5f, "the line is not at the page's bottom edge: ${state()}")
        assertTrue(listBox.bottom <= lineBox.top + 0.5f, "the list runs on under the line: ${state()}")
        // The report: the newest message's last line under the line.
        assertTrue(newest.bottom <= lineBox.top + 0.5f, "the line covers the newest message: ${state()}")
        // And it is still the list's end: the bottom-up list kept its bottom when it got shorter.
        assertTrue(lineBox.top - newest.bottom <= nearPx, "the newest message is no longer at the list's end: ${state()}")

        // Nobody types any more: the list takes the line's place back, still at its end.
        runOnIdle { harness.typing = null }
        settle()

        assertTrue(line() == null, "the line still shows after typing ended: ${state()}")
        val listAfter = listViewport()
        val newestAfter = newestMessage() ?: error("the newest message left the screen when the line hid: ${state()}")
        assertTrue(kotlin.math.abs(listAfter.bottom - page.bottom) <= 0.5f, "the list did not take the line's place back: ${state()}")
        assertTrue(page.bottom - newestAfter.bottom <= nearPx, "the newest message is no longer at the list's end: ${state()}")
    }

    /** Opened on the newest message (Inbox, Activity): the viewport is anchored on it. */
    @Test
    fun anchoredOnTheNewestMessageTheLineSitsBelowIt() =
        runRobolectricComposeUiTest { assertTheLineNeverCoversTheNewestMessage(focusNewest = true) }

    /** Opened at the bottom: the viewport follows the latest message. */
    @Test
    fun followingTheLatestMessageTheLineSitsBelowIt() =
        runRobolectricComposeUiTest { assertTheLineNeverCoversTheNewestMessage(focusNewest = false) }

    private companion object {
        const val NewestId = "m40"
        const val NewestBody = "The newest message ends here"

        /** thread_agent_typing_one. */
        const val TypingSentence = "Niko is typing…"

        /**
         * How far above the list's end the newest message's text may end and still be the list's
         * last row: the 16dp end padding plus the row's own bottom padding.
         */
        const val NearTheListEndDp = 64f

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
