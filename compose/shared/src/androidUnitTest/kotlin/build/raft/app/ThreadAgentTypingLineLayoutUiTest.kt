package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
import build.raft.app.thread.LocalThreadAgentTyping
import build.raft.app.thread.LocalThreadReverseLayout
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadAgentTypingLineHeight
import build.raft.app.thread.ThreadAgentTypingUi
import build.raft.app.thread.ThreadEventHandler
import build.raft.app.thread.ThreadLatestJumpOutcome
import build.raft.app.thread.ThreadLinkedTasksProjection
import build.raft.app.thread.ThreadMessagesList
import build.raft.app.thread.ThreadNewerPageLoadOutcome
import build.raft.app.thread.ThreadTypingAgent
import build.raft.app.thread.key
import build.raft.app.thread.threadBottomSpacerAndAnchorTrailingHeight
import build.raft.app.thread.toThreadTimelineRenderConfig
import build.raft.app.thread.toThreadTimelineSlice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The line above the composer ("Niko is typing…", "1 agent working here") floats over the list's
 * end, and the end makes room for it only while it shows (web AgentTypingLine and
 * AgentTypingListEnd, #proj-mobile:#271): while nobody types or works, the list ends as on the web,
 * with nothing reserved under the newest message.
 *
 * Composes the production [ThreadMessagesList] bottom-up, the way
 * [ThreadMessageMenuSurvivesPayloadUpdateUiTest] does, and shows and hides the line as the typing
 * store would. Each change is checked in the very frame that lays it out, so no frame shows the
 * line over the newest message or a reader's rows out of place, and again once every effect ran:
 * - a reader at the newest message stays at the bottom: the newest message rises by the line's
 *   slot, clear of the line, and comes back down when the line goes. Both ways the app parks there:
 *   following the latest message, and opened on that message (Inbox, Activity: an anchored
 *   viewport, where the line once covered the newest message's last line, Android 1.27.0,
 *   #proj-mobile #227);
 * - a reader scrolled up into the history keeps their place: the first message on screen, its
 *   offset and every other message on screen stay where they are.
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

    private class Harness(payload: RaftThreadScreenPayload) {
        var payload by mutableStateOf(payload)

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

    /** Lets every effect run: the viewport's commands, the reading anchor, the bottom anchors. */
    private fun ComposeUiTest.settle() {
        mainClock.autoAdvance = true
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    /** Applies [change] and lets exactly one frame lay it out: what the reader sees first. */
    private fun ComposeUiTest.oneFrame(change: () -> Unit) {
        mainClock.autoAdvance = false
        runOnIdle(change)
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /** The newest message's text on screen (its row's node where the row merges its text), or null. */
    private fun ComposeUiTest.newestMessage(): Rect? =
        onAllNodesWithText(NewestBody, substring = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.height > 0f }
            .maxByOrNull { it.bottom }

    /** The line's box: its live region fills it and is named by the typing sentence. */
    private fun ComposeUiTest.line(): Rect? =
        onAllNodes(hasContentDescription(TypingSentence)).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.height }

    /** Every earlier message on screen, by its body, and where it is. */
    private fun ComposeUiTest.messagesOnScreen(): Map<String, Rect> =
        onAllNodesWithText(EarlierBody, substring = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f }
            .associate { node ->
                val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                (texts.firstOrNull { EarlierBody in it } ?: texts.joinToString("|")) to node.boundsInRoot
            }

    private fun ComposeUiTest.state(harness: Harness): String =
        "page=${onRoot().fetchSemanticsNode().boundsInRoot} line=${line()} newest=${newestMessage()} " +
            "log=${harness.log.filter { "anchor" in it || "typing_line_end" in it || "viewport_intent" in it }.takeLast(8)}"

    private fun Rect.sameAs(other: Rect): Boolean =
        kotlin.math.abs(top - other.top) <= 0.5f && kotlin.math.abs(bottom - other.bottom) <= 0.5f

    /** How far the list's end grows while the line shows: the line's slot, in pixels. */
    private fun ComposeUiTest.slotPx(): Float {
        val shown = with(density) { threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = true).roundToPx() }
        val idle = with(density) { threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = false).roundToPx() }
        return (shown - idle).toFloat()
    }

    /**
     * The newest message's row ends where the list's end starts, which is the list's own end and
     * the room above the line (the shown end minus the line): its text ends at least that far
     * above the line.
     */
    private fun ComposeUiTest.clearOfTheLine(newest: Rect, line: Rect): Boolean =
        line.top - newest.bottom >=
            (threadBottomSpacerAndAnchorTrailingHeight(typingLineShown = true) - ThreadAgentTypingLineHeight).value *
            density.density - 0.5f

    private fun ComposeUiTest.assertTheReaderAtTheNewestMessageStaysAtTheBottom(focusNewest: Boolean) {
        val harness = Harness(threadPayload(focusedMessageId = NewestId.takeIf { focusNewest }))
        host(harness)
        val opened = newestMessage() ?: error("the page did not open at the newest message: ${state(harness)}")
        assertTrue(line() == null, "the line shows before anybody types: ${state(harness)}")
        val risen = opened.translate(0f, -slotPx())

        // Somebody starts typing, as the typing store reports it. In the frame the line shows, the
        // list's end has grown by the line's slot and the newest message has risen with it.
        oneFrame { harness.typing = nikoTyping() }
        val lineBox = line() ?: error("the line did not show: ${state(harness)}")
        val withLine = newestMessage() ?: error("the newest message left the screen when the line showed: ${state(harness)}")
        // The report: the newest message's last line under the line.
        assertTrue(clearOfTheLine(withLine, lineBox), "the line covers the newest message: ${state(harness)}")
        assertTrue(withLine.sameAs(risen), "the newest message did not rise by the line's slot: was $opened ${state(harness)}")
        // Nothing puts it back once the effects have run (the reading anchor, the bottom anchors).
        settle()
        val settledWithLine = newestMessage() ?: error("the newest message left the screen: ${state(harness)}")
        assertTrue(settledWithLine.sameAs(risen), "the newest message moved after the line showed: was $risen ${state(harness)}")

        // Nobody types any more: in the frame the line goes, the newest message is back where it opened.
        oneFrame { harness.typing = null }
        assertTrue(line() == null, "the line still shows after typing ended: ${state(harness)}")
        val withoutLine = newestMessage() ?: error("the newest message left the screen when the line hid: ${state(harness)}")
        assertTrue(withoutLine.sameAs(opened), "the newest message did not come back down: was $opened ${state(harness)}")
        settle()
        val settledWithoutLine = newestMessage() ?: error("the newest message left the screen: ${state(harness)}")
        assertTrue(settledWithoutLine.sameAs(opened), "the newest message moved after the line hid: was $opened ${state(harness)}")
    }

    /** Opened at the bottom: the viewport follows the latest message. */
    @Test
    fun followingTheLatestMessageTheListFollowsTheLine() =
        runRobolectricComposeUiTest { assertTheReaderAtTheNewestMessageStaysAtTheBottom(focusNewest = false) }

    /**
     * Opened on the newest message (Inbox, Activity): the viewport is anchored on it, at the list's
     * end. The line coming makes it follow the end, rather than the reading anchor holding the newest
     * message under the line.
     */
    @Test
    fun openedOnTheNewestMessageTheListFollowsTheLine() =
        runRobolectricComposeUiTest { assertTheReaderAtTheNewestMessageStaysAtTheBottom(focusNewest = true) }

    /** Scrolled up by the reader's own drag: no message on screen moves as the line comes and goes. */
    @Test
    fun aReaderScrolledUpKeepsTheirPlaceAsTheLineComesAndGoes() =
        runRobolectricComposeUiTest {
            val harness = Harness(threadPayload(focusedMessageId = null))
            host(harness)
            // Bottom-up list: dragging down goes back into the history. A slow drag, as
            // MembersAgentActivityLogTailUiTest: a screen up, no fling to the very top.
            onRoot().performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 1_000) }
            settle()
            assertTrue(newestMessage() == null, "the drag did not leave the newest message: ${state(harness)}")
            val reading = messagesOnScreen()
            assertTrue(reading.isNotEmpty(), "no message on screen after the drag: ${state(harness)}")
            val first = reading.entries.minBy { it.value.top }

            fun assertInPlace(moment: String) {
                val now = messagesOnScreen()
                assertTrue(now[first.key]?.sameAs(first.value) == true, "the first message on screen moved $moment: was ${first.value}, now ${now[first.key]} ${state(harness)}")
                assertEquals(reading.keys, now.keys, "the messages on screen changed $moment: ${state(harness)}")
                reading.forEach { (text, was) ->
                    assertTrue(now.getValue(text).sameAs(was), "\"$text\" moved $moment: was $was, now ${now[text]} ${state(harness)}")
                }
            }

            oneFrame { harness.typing = nikoTyping() }
            assertTrue(line() != null, "the line did not show: ${state(harness)}")
            assertInPlace("in the frame the line showed")
            settle()
            assertInPlace("after the line showed")

            oneFrame { harness.typing = null }
            assertTrue(line() == null, "the line still shows after typing ended: ${state(harness)}")
            assertInPlace("in the frame the line hid")
            settle()
            assertInPlace("after the line hid")
        }

    private companion object {
        const val NewestId = "m70"
        const val NewestBody = "The newest message ends here"
        const val EarlierBody = "An earlier message"

        /** thread_agent_typing_one. */
        const val TypingSentence = "Niko is typing…"

        fun nikoTyping() = ThreadAgentTypingUi(serverId = "srv-1", agents = listOf(ThreadTypingAgent("agent-niko", "Niko", null)))

        fun message(seq: Int, body: String) =
            RaftThreadMessageItem(
                id = "m$seq",
                authorId = "u1",
                authorName = "Artin",
                seq = seq.toLong(),
                body = body,
                timestampLabel =
                    "2026-07-30T02:${(seq / 60).toString().padStart(2, '0')}:${(seq % 60).toString().padStart(2, '0')}Z",
            )

        /** Many more messages than fit the window, so the list really scrolls and a drag leaves its end. */
        fun threadPayload(focusedMessageId: String?) =
            RaftThreadScreenPayload(
                instanceId = "thread-typing-line-${focusedMessageId ?: "bottom"}",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                status = "content",
                messages = (10..69).map { message(it, "$EarlierBody $it") } + message(70, NewestBody),
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                focusedMessageId = focusedMessageId,
                focusedMessageRequestNonce = if (focusedMessageId != null) 1 else 0,
                canSend = true,
            )
    }
}
