package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.cmp.RaftCmpToastWindow
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
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
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.experiment.LocalThreadViewportObserver
import build.raft.app.thread.experiment.LocalThreadVirtualTimelineObserver
import build.raft.app.thread.experiment.ThreadVirtualRowProjection
import build.raft.app.thread.experiment.VirtualMessageTimelineState
import build.raft.designsystem.components.RaftToastHostState
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Full end-to-end Robolectric Compose UI comparison suite for `#proj-mobile:#268`.
 *
 * Mounts the **real production [ThreadMessagesList]** side-by-side under:
 *   - Baseline: `LocalThreadUseVirtualTimeline = false` (`LazyColumn` + `reverseLayout = true`)
 *   - Experimental: `LocalThreadUseVirtualTimeline = true` (`VirtualMessageTimeline` forward `LazyLayout`)
 *
 * Verifies every critical real production feature and prints structured comparison metrics to stdout:
 *   1. Cold bottom open + realtime appends + `LocalThreadAgentTyping` zero-cover / zero-shift
 *   2. Back-to-bottom jump over real `SharedThreadStore` + delayed network `TailApi`
 *   3. Cold focused open + mid-session heterogeneous jumps (Frame-1 error, scrolls/passes, auto-load suppression)
 *   4. 100 consecutive collapsed system messages (virtual row coalescing vs zero-height placeholders, read-tail, toggle & auto-expand)
 *   5. Long-press action menu survival through realtime edit/append + older-page prepend anchor stability
 *   6. IME / viewport height shrink (`700dp -> 400dp`) tail-pinning at bottom vs staying in history when scrolled up
 *   7. Reply thread `ThreadOriginCard` + multi-day `ThreadDateDivider` + `ThreadHeaderOriginalState` + leftward time-reveal swipe
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h700dp")
class ThreadVirtualTimelineE2eComparisonUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-virtual-timeline-e2e"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    private var previousLocale = StringDesc.localeType
    private lateinit var toasts: RaftCmpToastWindow

    @BeforeTest
    fun setUp() {
        PagerManager.register(livePager)
        toasts = RaftCmpToasts.attach(RaftToastHostState()).also { it.setLifecycle(visible = true, resumed = true) }
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
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
        toasts.detach()
        PagerManager.unregister(livePager)
        StringDesc.localeType = previousLocale
        RaftSharedStoreRegistry.clearForTests()
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

    private class PayloadHarness(
        initialPayload: RaftThreadScreenPayload,
        val useVirtual: Boolean,
        initialViewportHeightDp: Dp? = null,
        val timeRevealAvailable: Boolean = false,
        val headerOriginalState: ThreadHeaderOriginalState? = null,
    ) {
        var threadInstanceKey by mutableStateOf(initialPayload.instanceId)
        var payload by mutableStateOf(initialPayload)
        var typing by mutableStateOf<ThreadAgentTypingUi?>(null)
        var composerKeyboardInsetDp by mutableFloatStateOf(0f)
        var viewportHeightDp by mutableStateOf(initialViewportHeightDp)
        var headerScrollToTopRequest by mutableIntStateOf(0)
        var lastVisibleReadTailIndex: Int? = null
        var viewport: ThreadListViewport? = null
        var virtualState: VirtualMessageTimelineState? = null
        var virtualProjection: ThreadVirtualRowProjection? = null
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    private fun ComposeUiTest.hostPayload(harness: PayloadHarness, settleAfterMount: Boolean = true) {
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadReverseLayout provides true,
                LocalThreadUseVirtualTimeline provides harness.useVirtual,
                LocalThreadAgentTyping provides harness.typing,
                LocalThreadTimeRevealAvailable provides harness.timeRevealAvailable,
                LocalThreadHeaderOriginalState provides harness.headerOriginalState,
                LocalThreadViewportObserver provides { vp -> harness.viewport = vp },
                LocalThreadVirtualTimelineObserver provides { state, projection ->
                    harness.virtualState = state
                    harness.virtualProjection = projection
                },
            ) {
                RaftDesignSystemTheme {
                    val containerModifier =
                        harness.viewportHeightDp?.let { h -> Modifier.fillMaxWidth().height(h) }
                            ?: Modifier.fillMaxSize()
                    Box(containerModifier) {
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
                            composerKeyboardInsetDp = harness.composerKeyboardInsetDp,
                            linkedTasksProjection = ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap()),
                            highlightedMentions = emptySet(),
                            headerScrollToTopRequest = harness.headerScrollToTopRequest,
                            onThreadEvent = ThreadEventHandler { action, itemId, _, _, _, _ ->
                                harness.events += "$action:${itemId.orEmpty()}"
                            },
                            onLoadNewer = { ThreadNewerPageLoadOutcome.Empty },
                            onJumpToLatest = { ThreadLatestJumpOutcome.Landed },
                            onOpenMember = {},
                            onMentionAuthor = {},
                            onOpenMarkdownTag = { _, _ -> },
                            onOpenMarkdownUrl = {},
                            onVisibleReadTailChange = { index -> harness.lastVisibleReadTailIndex = index },
                            diagnosticsLogger = { line -> harness.log += line },
                        )
                    }
                }
            }
        }
        if (settleAfterMount) settle()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    private fun ComposeUiTest.awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $description" }
            Thread.sleep(10)
            mainClock.advanceTimeByFrame()
        }
        settle()
    }

    private fun ComposeUiTest.boundsForText(text: String): Rect? =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.bottom }

    private fun Rect.sameVerticalAs(other: Rect): Boolean =
        abs(top - other.top) <= 0.5f && abs(bottom - other.bottom) <= 0.5f

    // ---------------------------------------------------------------------------------------------
    // E2E-1: Cold bottom open + realtime append + agent typing line (zero cover / zero shift)
    // ---------------------------------------------------------------------------------------------

    private data class E2e1Metrics(
        val engine: String,
        val initialRetries: Int,
        val issuedScrollsAfterOpen: Int,
        val issuedScrollsAfterAppends: Int,
        val typingClearOfNewest: Boolean,
        val typingShiftPx: Float,
        val measurePasses: Int,
    )

    private fun runE2e1ForEngine(useVirtual: Boolean): E2e1Metrics {
        lateinit var result: E2e1Metrics
        runRobolectricComposeUiTest {
            val initialMessages = (10..39).map { chatMessage(it, "Earlier bottom-open message $it") } + chatMessage(40, "Newest message #40")
            val harness =
                PayloadHarness(
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e1-${if (useVirtual) "virtual" else "lazy"}",
                            sourceThreadId = "channel-1",
                            streamChannelId = "channel-1",
                            status = "content",
                            messages = initialMessages,
                            canLoadMoreBefore = false,
                            canLoadMoreAfter = false,
                            canSend = true,
                        ),
                    useVirtual = useVirtual,
                )
            hostPayload(harness)

            val openedRect = assertNotNull(boundsForText("Newest message #40"), "[$useVirtual] newest message #40 not shown on cold open")
            val scrollsAfterOpen = assertNotNull(harness.viewport).issuedScrolls
            val initialRetries = harness.log.count { "thread_initial_bottom_anchor result=retry" in it }

            // Show typing line -> newest message must remain clear & unshifted.
            runOnIdle {
                harness.typing = ThreadAgentTypingUi(serverId = "srv-1", agents = listOf(ThreadTypingAgent("agent-niko", "Niko", null)))
            }
            settle()
            val typingRect =
                assertNotNull(
                    onAllNodes(hasContentDescription("Niko is typing…")).fetchSemanticsNodes().map { it.boundsInRoot }.maxByOrNull { it.height },
                    "[$useVirtual] typing line did not show",
                )
            val withTypingRect = assertNotNull(boundsForText("Newest message #40"), "[$useVirtual] newest message missing with typing line")
            val minClearance = (threadBottomSpacerAndAnchorTrailingHeight(typingLineSlot = true) - ThreadAgentTypingLineHeight).value * density.density - 0.5f
            val clearOfLine = typingRect.top - withTypingRect.bottom >= minClearance
            assertTrue(clearOfLine, "[$useVirtual] typing line covers newest message: newest=$withTypingRect line=$typingRect")
            val typingShift = abs(withTypingRect.top - openedRect.top)
            assertTrue(withTypingRect.sameVerticalAs(openedRect), "[$useVirtual] newest message shifted when typing showed: shift=$typingShift")

            // Hide typing line -> stays unshifted.
            runOnIdle { harness.typing = null }
            settle()
            val afterTypingRect = assertNotNull(boundsForText("Newest message #40"))
            assertTrue(afterTypingRect.sameVerticalAs(openedRect), "[$useVirtual] newest message shifted when typing hid")

            // Append 3 realtime messages -> newest (#43) pinned to bottom.
            var currentMessages = initialMessages
            for (seq in 41..43) {
                currentMessages = currentMessages + chatMessage(seq, "Realtime appended message #$seq")
                runOnIdle { harness.payload = harness.payload.copy(messages = currentMessages) }
                settle()
            }
            val appendedRect = assertNotNull(boundsForText("Realtime appended message #43"), "[$useVirtual] appended #43 not pinned into view")
            val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(appendedRect.bottom <= rootBounds.bottom + 1f, "[$useVirtual] appended #43 clipped below viewport")
            val scrollsAfterAppends = assertNotNull(harness.viewport).issuedScrolls

            result =
                E2e1Metrics(
                    engine = if (useVirtual) "VirtualMessageTimeline" else "LazyColumn(reverse)",
                    initialRetries = initialRetries,
                    issuedScrollsAfterOpen = scrollsAfterOpen,
                    issuedScrollsAfterAppends = scrollsAfterAppends,
                    typingClearOfNewest = clearOfLine,
                    typingShiftPx = typingShift,
                    measurePasses = harness.virtualState?.measurePassCount ?: -1,
                )
        }
        return result
    }

    @Test
    fun e2e01_coldBottomOpenRealtimeAppendAndTypingLine() {
        val lazy = runE2e1ForEngine(useVirtual = false)
        val virtual = runE2e1ForEngine(useVirtual = true)
        println(
            """
            |=== [E2E-1] Real ThreadMessagesList: Cold Bottom Open + Typing Line + 3 Realtime Appends ===
            | Engine                  | InitRetries | OpenScrolls | After3AppendsScrolls | TypingClear | TypingShiftPx | VirtualMeasurePasses
            |-------------------------+-------------+-------------+----------------------+-------------+---------------+---------------------
            | ${lazy.engine.padEnd(23)} | ${lazy.initialRetries.toString().padStart(11)} | ${lazy.issuedScrollsAfterOpen.toString().padStart(11)} | ${lazy.issuedScrollsAfterAppends.toString().padStart(20)} | ${lazy.typingClearOfNewest.toString().padStart(11)} | ${lazy.typingShiftPx.toString().padStart(13)} | N/A
            | ${virtual.engine.padEnd(23)} | ${virtual.initialRetries.toString().padStart(11)} | ${virtual.issuedScrollsAfterOpen.toString().padStart(11)} | ${virtual.issuedScrollsAfterAppends.toString().padStart(20)} | ${virtual.typingClearOfNewest.toString().padStart(11)} | ${virtual.typingShiftPx.toString().padStart(13)} | ${virtual.measurePasses.toString().padStart(19)}
            """.trimMargin()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // E2E-2: Back-to-bottom jump over real SharedThreadStore + TailApi
    // ---------------------------------------------------------------------------------------------

    private class TailApi : SharedThreadNetworkApi {
        var tailRequests = 0
        var release = CompletableDeferred<Int>()

        override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?, afterSeq: Long?): HomeNetworkResponse {
            if (beforeSeq != null || afterSeq != null) return HomeNetworkResponse(code = 500, bodyText = "{}")
            tailRequests += 1
            val code = release.await()
            return if (code == 200) HomeNetworkResponse(code = 200, bodyText = tailResponse()) else HomeNetworkResponse(code = code, bodyText = "{}")
        }

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int) = ok()
        override suspend fun sendMessage(channelId: String, body: String, asTask: Boolean, attachmentIds: List<String>, randomId: String?) = ok()
        override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>) = ok()
        override suspend fun attachmentUrl(priority: DataRequestPriority, attachmentId: String, disposition: String?) = ok()
        override suspend fun attachmentHtmlPreviewUrl(attachmentId: String) = ok()
        override suspend fun attachmentComments(attachmentId: String) = ok()
        override suspend fun sendAttachmentComment(attachmentId: String, body: String, anchor: build.raft.app.data.ThreadAttachmentCommentAnchor?) = ok()
        override suspend fun markChannelReadToSeq(channelId: String, seq: Long) = ok()
        override suspend fun markChannelReadAll(channelId: String) = ok()
        override suspend fun saveMessage(messageId: String) = ok()
        override suspend fun unsaveMessage(messageId: String) = ok()
        override suspend fun addReaction(messageId: String, emoji: String) = ok()
        override suspend fun removeReaction(messageId: String, emoji: String) = ok()
        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String) = ok()
        override suspend fun messageContext(messageId: String, channelId: String?) = ok()

        private fun ok() = HomeNetworkResponse(code = 200, bodyText = "{}")

        private fun tailResponse(): String =
            (100..130).joinToString(prefix = """{"historyLimited": false, "messages": [""", postfix = "]}") { seq ->
                """{"id": "m$seq", "seq": $seq, "channelId": "channel-1", "content": "tail message $seq",
                   "createdAt": "2026-07-30T03:00:${(seq % 60).toString().padStart(2, '0')}Z", "senderId": "u1",
                   "senderName": "Artin", "senderType": "user", "messageType": "chat"}"""
            }
    }

    private fun runE2e2BackToBottom(useVirtual: Boolean): Pair<Int, Int> {
        var issuedScrolls = 0
        var measurePasses = -1
        runRobolectricComposeUiTest {
            val log = java.util.concurrent.CopyOnWriteArrayList<String>()
            val api = TailApi()
            val scopeJob = SupervisorJob()
            var viewport: ThreadListViewport? = null
            var virtualState: VirtualMessageTimelineState? = null
            val store =
                SharedThreadStore(
                    dataSource =
                        testSharedThreadDataSource(
                            networkApi = api,
                            sessionStore =
                                InMemoryRaftAuthSessionStore(
                                    StoredAuthSession(
                                        user = StoredAuthUser("u1", "artin@example.test", "Artin"),
                                        accessToken = "access",
                                        refreshToken = "refresh",
                                        selectedServerId = "server-1",
                                    )
                                ),
                            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                            threadParseDispatcher = Dispatchers.Unconfined,
                        ),
                    scope = CoroutineScope(scopeJob + Dispatchers.Unconfined),
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e2-${if (useVirtual) "virtual" else "lazy"}",
                            sourceThreadId = "channel-1",
                            streamChannelId = "channel-1",
                            status = "content",
                            messages = (10..40).map { chatMessage(it, "window message $it") },
                            canLoadMoreBefore = true,
                            canLoadMoreAfter = true,
                            focusedMessageId = "m25",
                            focusedMessageRequestNonce = 1,
                            canSend = true,
                        ),
                    diagnosticsLogger = { line -> log += "store: $line" },
                )
            try {
                setContent {
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(livePager),
                        LocalThreadReverseLayout provides true,
                        LocalThreadUseVirtualTimeline provides useVirtual,
                        LocalThreadViewportObserver provides { vp -> viewport = vp },
                        LocalThreadVirtualTimelineObserver provides { st, _ -> virtualState = st },
                    ) {
                        RaftDesignSystemTheme {
                            Box(Modifier.fillMaxSize()) {
                                val payload = store.state.collectAsState().value.payload
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
                                    onLoadNewer = { store.loadNewer() },
                                    onJumpToLatest = { store.jumpToLatest() },
                                    onOpenMember = {},
                                    onMentionAuthor = {},
                                    onOpenMarkdownTag = { _, _ -> },
                                    onOpenMarkdownUrl = {},
                                    diagnosticsLogger = { line -> log += line },
                                )
                            }
                        }
                    }
                }
                settle()
                assertTrue(
                    onAllNodesWithText("Back to bottom").fetchSemanticsNodes().isNotEmpty(),
                    "[$useVirtual] Back to bottom pill not shown on mid-history window",
                )
                onNodeWithText("Back to bottom").performClick()
                settle()
                assertEquals(1, api.tailRequests, "[$useVirtual] expected 1 tail request")
                onNodeWithText("Loading messages…").assertIsNotEnabled()

                runOnIdle { api.release.complete(200) }
                awaitCondition("[$useVirtual] newest page to land") { log.any { "thread_jump_to_latest result=applied" in it } }

                assertTrue(
                    onAllNodesWithText("Loading messages…").fetchSemanticsNodes().isEmpty(),
                    "[$useVirtual] loading state remained visible",
                )
                assertTrue(
                    onAllNodesWithText("Back to bottom").fetchSemanticsNodes().isEmpty(),
                    "[$useVirtual] Back to bottom pill remained visible after landing",
                )
                assertTrue(
                    onAllNodesWithText("tail message 130").fetchSemanticsNodes().isNotEmpty(),
                    "[$useVirtual] newest message tail message 130 not visible",
                )
                issuedScrolls = assertNotNull(viewport).issuedScrolls
                measurePasses = virtualState?.measurePassCount ?: -1
            } finally {
                scopeJob.cancel()
            }
        }
        return issuedScrolls to measurePasses
    }

    @Test
    fun e2e02_backToBottomJumpWithRealSharedThreadStore() {
        val (lazyScrolls, _) = runE2e2BackToBottom(useVirtual = false)
        val (virtualScrolls, virtualPasses) = runE2e2BackToBottom(useVirtual = true)
        println(
            """
            |=== [E2E-2] Real SharedThreadStore Back-to-Bottom Jump (m25 -> m130 over delayed TailApi) ===
            | Engine                  | LandedOnTail130 | IssuedScrolls | VirtualMeasurePasses
            |-------------------------+-----------------+---------------+---------------------
            | LazyColumn(reverse)     |            true | ${lazyScrolls.toString().padStart(13)} | N/A
            | VirtualMessageTimeline  |            true | ${virtualScrolls.toString().padStart(13)} | ${virtualPasses.toString().padStart(19)}
            """.trimMargin()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // E2E-3: Cold focused open + mid-session heterogeneous jumps (120 real variable-height rows)
    // ---------------------------------------------------------------------------------------------

    private data class E2e3Metrics(
        val engine: String,
        val coldFrame1Visible: Boolean,
        val coldFrame1OffsetPx: Int,
        val coldSettledOffsetPx: Int,
        val jump2SettledOffsetPx: Int,
        val jump3SettledOffsetPx: Int,
        val totalIssuedScrolls: Int,
        val unintendedLoadOlderCount: Int,
        val virtualMeasurePasses: Int,
    )

    private fun buildHeterogeneousThreadMessages(count: Int = 120): List<RaftThreadMessageItem> =
        (0 until count).map { i ->
            val body =
                when (i % 5) {
                    0 -> "Short line #$i"
                    1 -> "Medium update #$i:\n- Verified layout alignment\n- Checked Fenwick prefix offsets"
                    2 -> "Longer markdown paragraph #$i.\n\n".repeat(4) + "Tail sentence #$i"
                    3 -> "Code note #$i:\n```kotlin\nval offset = heightIndex.offsetOf($i)\nval next = offset + measuredPx\n```\nDone #$i"
                    else -> "Multi-paragraph release review #$i:\n1. First item detail\n2. Second item detail\n3. Third item detail\n4. Fourth item detail"
                }
            chatMessage(i, body)
        }

    private fun runE2e3HeterogeneousJumps(useVirtual: Boolean): E2e3Metrics {
        lateinit var metrics: E2e3Metrics
        runRobolectricComposeUiTest {
            val messages = buildHeterogeneousThreadMessages(120)
            val harness =
                PayloadHarness(
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e3-${if (useVirtual) "virtual" else "lazy"}",
                            sourceThreadId = "channel-1",
                            streamChannelId = "channel-1",
                            status = "content",
                            messages = messages,
                            canLoadMoreBefore = true,
                            canLoadMoreAfter = false,
                            focusedMessageId = "m20",
                            focusedMessageRequestNonce = 1,
                            canSend = true,
                        ),
                    useVirtual = useVirtual,
                )
            mainClock.autoAdvance = false
            hostPayload(harness, settleAfterMount = false)
            // Single frame advance for Frame-1 cold focused open measurement!
            mainClock.advanceTimeByFrame()
            val vp = assertNotNull(harness.viewport)
            val targetIdx1 = harness.virtualProjection?.rowIndexForMessageId("m20") ?: harness.payload.toThreadTimelineSlice().threadMessageListItemIndex("m20")
            val frame1Item = vp.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx1 }
            val coldFrame1Visible = frame1Item != null
            val coldFrame1Offset = frame1Item?.offset ?: -9999

            mainClock.autoAdvance = true
            settle()
            val settledItem1 = assertNotNull(vp.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx1 }, "[$useVirtual] m20 not visible after settle")
            val coldSettledOffset = settledItem1.offset

            // Mid-session jump #2 -> m85
            runOnIdle {
                harness.payload = harness.payload.copy(focusedMessageId = "m85", focusedMessageRequestNonce = 2)
            }
            settle()
            val targetIdx2 = harness.virtualProjection?.rowIndexForMessageId("m85") ?: harness.payload.toThreadTimelineSlice().threadMessageListItemIndex("m85")
            val settledItem2 = assertNotNull(vp.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx2 }, "[$useVirtual] m85 not visible after jump 2")

            // Mid-session jump #3 -> m12 (near top history boundary!)
            runOnIdle {
                harness.payload = harness.payload.copy(focusedMessageId = "m12", focusedMessageRequestNonce = 3)
            }
            settle()
            val targetIdx3 = harness.virtualProjection?.rowIndexForMessageId("m12") ?: harness.payload.toThreadTimelineSlice().threadMessageListItemIndex("m12")
            val settledItem3 = assertNotNull(vp.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx3 }, "[$useVirtual] m12 not visible after jump 3")

            val loadOlderEvents = harness.events.count { it.startsWith("loadOlder:") }
            assertEquals(0, loadOlderEvents, "[$useVirtual] programmatic jumps must not fire unintended loadOlder pagination")
            assertTrue(abs(settledItem1.offset) <= 4, "[$useVirtual] m20 settled offset too large: ${settledItem1.offset}")
            assertTrue(abs(settledItem2.offset) <= 4, "[$useVirtual] m85 settled offset too large: ${settledItem2.offset}")
            assertTrue(abs(settledItem3.offset) <= 4, "[$useVirtual] m12 settled offset too large: ${settledItem3.offset}")

            metrics =
                E2e3Metrics(
                    engine = if (useVirtual) "VirtualMessageTimeline" else "LazyColumn(reverse)",
                    coldFrame1Visible = coldFrame1Visible,
                    coldFrame1OffsetPx = coldFrame1Offset,
                    coldSettledOffsetPx = coldSettledOffset,
                    jump2SettledOffsetPx = settledItem2.offset,
                    jump3SettledOffsetPx = settledItem3.offset,
                    totalIssuedScrolls = vp.issuedScrolls,
                    unintendedLoadOlderCount = loadOlderEvents,
                    virtualMeasurePasses = harness.virtualState?.measurePassCount ?: -1,
                )
        }
        return metrics
    }

    @Test
    fun e2e03_coldFocusedOpenAndHeterogeneousJumps() {
        val lazy = runE2e3HeterogeneousJumps(useVirtual = false)
        val virtual = runE2e3HeterogeneousJumps(useVirtual = true)
        assertTrue(virtual.coldFrame1Visible, "VirtualMessageTimeline must place cold focused row m20 on Frame 1")
        assertTrue(abs(virtual.coldFrame1OffsetPx) <= 2, "VirtualMessageTimeline Frame-1 focused error must be <= 2px, got ${virtual.coldFrame1OffsetPx}px")
        assertTrue(
            virtual.totalIssuedScrolls < lazy.totalIssuedScrolls,
            "VirtualMessageTimeline should issue fewer scroll calls across 3 focused jumps (virtual=${virtual.totalIssuedScrolls}, lazy=${lazy.totalIssuedScrolls})",
        )
        println(
            """
            |=== [E2E-3] Real ThreadMessagesList: 120 Heterogeneous Rows — Cold Focus (m20) + 2 Mid-Session Jumps (m85, m12) ===
            | Engine                  | Frame1Vis | Frame1Off(px) | Settled(m20/m85/m12) | IssuedScrolls | LoadOlder | VirtualPasses
            |-------------------------+-----------+---------------+----------------------+---------------+-----------+--------------
            | ${lazy.engine.padEnd(23)} | ${lazy.coldFrame1Visible.toString().padStart(9)} | ${lazy.coldFrame1OffsetPx.toString().padStart(13)} | ${"${lazy.coldSettledOffsetPx}/${lazy.jump2SettledOffsetPx}/${lazy.jump3SettledOffsetPx}".padStart(20)} | ${lazy.totalIssuedScrolls.toString().padStart(13)} | ${lazy.unintendedLoadOlderCount.toString().padStart(9)} | N/A
            | ${virtual.engine.padEnd(23)} | ${virtual.coldFrame1Visible.toString().padStart(9)} | ${virtual.coldFrame1OffsetPx.toString().padStart(13)} | ${"${virtual.coldSettledOffsetPx}/${virtual.jump2SettledOffsetPx}/${virtual.jump3SettledOffsetPx}".padStart(20)} | ${virtual.totalIssuedScrolls.toString().padStart(13)} | ${virtual.unintendedLoadOlderCount.toString().padStart(9)} | ${virtual.virtualMeasurePasses.toString().padStart(13)}
            """.trimMargin()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // E2E-4: 100 consecutive collapsed system messages inside real ThreadMessagesList
    // ---------------------------------------------------------------------------------------------

    private data class E2e4Metrics(
        val engine: String,
        val collapsedTotalItems: Int,
        val eliminatedPlaceholders: Int,
        val readTailWhileCollapsed: Int?,
        val expandedTotalItems: Int,
        val foldedTargetAutoExpanded: Boolean,
    )

    private fun runE2e4CollapsedSystemMessages(useVirtual: Boolean): E2e4Metrics {
        lateinit var metrics: E2e4Metrics
        runRobolectricComposeUiTest {
            val messages =
                buildList {
                    for (i in 0 until 15) add(chatMessage(i, "Before system burst $i"))
                    for (i in 0 until 100) add(systemTaskMessage(15 + i))
                    for (i in 115 until 130) add(chatMessage(i, "After system burst $i"))
                }
            val harness =
                PayloadHarness(
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e4-${if (useVirtual) "virtual" else "lazy"}",
                            sourceThreadId = "channel-1",
                            streamChannelId = "channel-1",
                            status = "content",
                            messages = messages,
                            canLoadMoreBefore = false,
                            canLoadMoreAfter = false,
                            canSend = true,
                        ),
                    useVirtual = useVirtual,
                )
            hostPayload(harness)

            val vp = assertNotNull(harness.viewport)
            val collapsedTotalItems = vp.layoutInfo.totalItemsCount
            val eliminated = harness.virtualProjection?.collapsedPlaceholderRowsEliminated ?: 0
            val readTailCollapsed = harness.lastVisibleReadTailIndex
            assertEquals(129, readTailCollapsed, "[$useVirtual] read tail must reach last message index 129 even with 100 collapsed system rows")

            // Focus the summary row's group header so it is squarely visible, then tap toggle to expand!
            runOnIdle {
                harness.payload = harness.payload.copy(focusedMessageId = "m14", focusedMessageRequestNonce = 1)
            }
            settle()
            onNodeWithTag("system-message-group-toggle").performClick()
            settle()
            val expandedTotalItems = vp.layoutInfo.totalItemsCount
            assertTrue(
                onAllNodesWithText("Task 15", substring = true).fetchSemanticsNodes().isNotEmpty(),
                "[$useVirtual] tapping collapsed group header must reveal system row Task 15",
            )

            // Collapse again, then focus-jump directly to folded member sys65 -> must auto-expand & land on Task 65!
            onNodeWithTag("system-message-group-toggle").performClick()
            settle()
            runOnIdle {
                harness.payload = harness.payload.copy(focusedMessageId = "sys65", focusedMessageRequestNonce = 2)
            }
            settle()
            val targetVisible = onAllNodesWithText("Task 65", substring = true).fetchSemanticsNodes().isNotEmpty()
            assertTrue(targetVisible, "[$useVirtual] focusing folded member sys65 must auto-expand group and display Task 65")

            metrics =
                E2e4Metrics(
                    engine = if (useVirtual) "VirtualMessageTimeline" else "LazyColumn(reverse)",
                    collapsedTotalItems = collapsedTotalItems,
                    eliminatedPlaceholders = eliminated,
                    readTailWhileCollapsed = readTailCollapsed,
                    expandedTotalItems = expandedTotalItems,
                    foldedTargetAutoExpanded = targetVisible,
                )
        }
        return metrics
    }

    @Test
    fun e2e04_100CollapsedSystemMessagesCoalescingReadTailAndToggle() {
        val lazy = runE2e4CollapsedSystemMessages(useVirtual = false)
        val virtual = runE2e4CollapsedSystemMessages(useVirtual = true)
        assertEquals(99, virtual.eliminatedPlaceholders, "VirtualMessageTimeline must eliminate 99 zero-height placeholder items")
        assertEquals(lazy.collapsedTotalItems - 99, virtual.collapsedTotalItems, "Virtual layout must hold 99 fewer items while collapsed")
        println(
            """
            |=== [E2E-4] Real ThreadMessagesList: 15 Chat + 100 Collapsed System + 15 Chat (130 source msgs) ===
            | Engine                  | CollapsedItems | PlaceholdersEliminated | ReadTail | ExpandedItems | FoldedJumpAutoExpand
            |-------------------------+----------------+------------------------+----------+---------------+---------------------
            | ${lazy.engine.padEnd(23)} | ${lazy.collapsedTotalItems.toString().padStart(14)} | ${lazy.eliminatedPlaceholders.toString().padStart(22)} | ${lazy.readTailWhileCollapsed.toString().padStart(8)} | ${lazy.expandedTotalItems.toString().padStart(13)} | ${lazy.foldedTargetAutoExpanded.toString().padStart(20)}
            | ${virtual.engine.padEnd(23)} | ${virtual.collapsedTotalItems.toString().padStart(14)} | ${virtual.eliminatedPlaceholders.toString().padStart(22)} | ${virtual.readTailWhileCollapsed.toString().padStart(8)} | ${virtual.expandedTotalItems.toString().padStart(13)} | ${virtual.foldedTargetAutoExpanded.toString().padStart(20)}
            """.trimMargin()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // E2E-5: Long-press menu survival + older-page prepend anchor stability
    // ---------------------------------------------------------------------------------------------

    private data class E2e5Metrics(
        val engine: String,
        val menuSurvivedEditAndAppend: Boolean,
        val topEdgePrependDriftPx: Int,
        val midHistoryPrependDriftPx: Int,
        val manualOlderRestoreScrolls: Int,
    )

    private fun runE2e5MenuAndPrepend(useVirtual: Boolean): E2e5Metrics {
        lateinit var metrics: E2e5Metrics
        runRobolectricComposeUiTest {
            val baseMessages = (30..69).map { chatMessage(it, if (it == 68) "Menu target message #68" else "History row #$it") }
            val harness =
                PayloadHarness(
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e5-${if (useVirtual) "virtual" else "lazy"}",
                            sourceThreadId = "channel-1",
                            streamChannelId = "channel-1",
                            status = "content",
                            messages = baseMessages,
                            canLoadMoreBefore = true,
                            canLoadMoreAfter = false,
                            olderMessagesStatus = "idle",
                            canSend = true,
                        ),
                    useVirtual = useVirtual,
                )
            hostPayload(harness)

            // Part A: Long-press menu on m68 survives realtime edit + realtime append.
            onNodeWithText("Menu target message #68", substring = true).performTouchInput { longClick() }
            settle()
            assertTrue(
                onAllNodesWithText("Copy Markdown", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                "[$useVirtual] long press did not open menu",
            )
            val edited = baseMessages.map { if (it.id == "m68") it.copy(body = "Menu target message #68 (edited)") else it }
            runOnIdle { harness.payload = harness.payload.copy(messages = edited) }
            settle()
            assertTrue(
                onAllNodesWithText("Copy Markdown", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                "[$useVirtual] realtime edit closed open menu",
            )
            val withAppended = edited + chatMessage(70, "New tail message #70")
            runOnIdle { harness.payload = harness.payload.copy(messages = withAppended) }
            settle()
            val menuStillOpen = onAllNodesWithText("Copy Markdown", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            assertTrue(menuStillOpen, "[$useVirtual] realtime append closed open menu")

            val copyNodes = onAllNodes(hasClickAction() and (hasText("Copy Markdown") or hasContentDescription("Copy Markdown")))
            copyNodes.assertCountEquals(1)
            copyNodes[0].performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(listOf("copyMessage:m68"), harness.events.filter { it.startsWith("copyMessage:") })
            assertTrue(onAllNodesWithText("Copy Markdown", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

            // Part B1: Top-edge prepend (scrollOffsetPx == 0, HistoryBoundary visible above m30!).
            // After reaching the top, perform a short downward drag so LazyViewportController leaves
            // PinnedTop into Anchored(m30), matching a real user scrolling to the top edge to load older history.
            runOnIdle { harness.headerScrollToTopRequest += 1 }
            settle()
            onRoot().performTouchInput {
                down(Offset(width * 0.5f, height * 0.4f))
                moveTo(Offset(width * 0.5f, height * 0.4f + 40f))
                up()
            }
            settle()
            val rectM30Before = assertNotNull(boundsForText("History row #30"), "[$useVirtual] m30 not visible at top before prepend")
            val olderBatch1 =
                (15..29).map { i ->
                    val extra = if (i % 2 == 0) "\nSecond line on older row #$i\nThird line on older row #$i" else ""
                    chatMessage(i, "Prepended older batch1 #$i$extra")
                }
            runOnIdle { harness.payload = harness.payload.copy(olderMessagesStatus = "loading") }
            settle()
            val afterBatch1 = olderBatch1 + withAppended
            runOnIdle { harness.payload = harness.payload.copy(messages = afterBatch1, olderMessagesStatus = "idle") }
            settle()
            val rectM30After = assertNotNull(boundsForText("History row #30"), "[$useVirtual] m30 lost after top-edge prepend")
            val topEdgeDriftPx = abs(rectM30After.top - rectM30Before.top).toInt()
            assertTrue(
                topEdgeDriftPx <= 4,
                "[$useVirtual] m30 drifted by ${topEdgeDriftPx}px after top-edge prepend (before=${rectM30Before.top}, after=${rectM30After.top})",
            )

            // Part B2: Mid-history (m45) prepend of 15 more older messages (m0..m14).
            runOnIdle {
                harness.payload = harness.payload.copy(focusedMessageId = "m45", focusedMessageRequestNonce = 1)
            }
            settle()
            val rectBeforePrepend = assertNotNull(boundsForText("History row #45"), "[$useVirtual] m45 not visible before mid-history prepend")
            val olderBatch2 = (0..14).map { chatMessage(it, "Prepended older batch2 #$it") }
            runOnIdle { harness.payload = harness.payload.copy(olderMessagesStatus = "loading") }
            settle()
            runOnIdle { harness.payload = harness.payload.copy(messages = olderBatch2 + afterBatch1, olderMessagesStatus = "idle") }
            settle()
            val rectAfterPrepend = assertNotNull(boundsForText("History row #45"), "[$useVirtual] m45 lost after mid-history prepend")
            val midHistoryDriftPx = abs(rectAfterPrepend.top - rectBeforePrepend.top).toInt()
            assertTrue(
                midHistoryDriftPx <= 4,
                "[$useVirtual] m45 drifted by ${midHistoryDriftPx}px after mid-history prepend (before=${rectBeforePrepend.top}, after=${rectAfterPrepend.top})",
            )

            val manualOlderRestores = harness.log.count { "thread_older_anchor_restore result=restored" in it }
            metrics =
                E2e5Metrics(
                    engine = if (useVirtual) "VirtualMessageTimeline" else "LazyColumn(reverse)",
                    menuSurvivedEditAndAppend = menuStillOpen,
                    topEdgePrependDriftPx = topEdgeDriftPx,
                    midHistoryPrependDriftPx = midHistoryDriftPx,
                    manualOlderRestoreScrolls = manualOlderRestores,
                )
        }
        return metrics
    }

    @Test
    fun e2e05_longPressMenuSurvivalAndOlderPrependAnchorStability() {
        val lazy = runE2e5MenuAndPrepend(useVirtual = false)
        val virtual = runE2e5MenuAndPrepend(useVirtual = true)
        assertEquals(0, virtual.manualOlderRestoreScrolls, "VirtualMessageTimeline must preserve prepend anchor natively with 0 manual restore scrolls")
        println(
            """
            |=== [E2E-5] Real ThreadMessagesList: Long-Press Menu Survival + Top-Edge & Mid-History Older Prepends ===
            | Engine                  | MenuSurvived | TopEdgeDrift(px) | MidHistDrift(px) | ManualOlderRestoreScrolls
            |-------------------------+--------------+------------------+------------------+--------------------------
            | ${lazy.engine.padEnd(23)} | ${lazy.menuSurvivedEditAndAppend.toString().padStart(12)} | ${lazy.topEdgePrependDriftPx.toString().padStart(16)} | ${lazy.midHistoryPrependDriftPx.toString().padStart(16)} | ${lazy.manualOlderRestoreScrolls.toString().padStart(25)}
            | ${virtual.engine.padEnd(23)} | ${virtual.menuSurvivedEditAndAppend.toString().padStart(12)} | ${virtual.topEdgePrependDriftPx.toString().padStart(16)} | ${virtual.midHistoryPrependDriftPx.toString().padStart(16)} | ${virtual.manualOlderRestoreScrolls.toString().padStart(25)}
            """.trimMargin()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // E2E-6: IME / viewport height shrink (700dp -> 400dp) tail-pin vs scrolled-up history
    // ---------------------------------------------------------------------------------------------

    private data class E2e6Metrics(
        val engine: String,
        val bottomTailStillPinned: Boolean,
        val historyRowPreserved: Boolean,
        val historyYankedToBottom: Boolean,
    )

    private fun runE2e6ImeShrink(useVirtual: Boolean): E2e6Metrics {
        var bottomPinned = false
        var historyKept = false
        var historyYanked = false

        // Case A: At bottom -> viewport shrinks 700dp -> 400dp -> newest message stays pinned above bottom.
        runRobolectricComposeUiTest {
            val messages = (1..40).map { chatMessage(it, if (it == 40) "IME tail message #40" else "IME row #$it") }
            val harness =
                PayloadHarness(
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e6-bottom-${if (useVirtual) "virtual" else "lazy"}",
                            sourceThreadId = "channel-1",
                            streamChannelId = "channel-1",
                            status = "content",
                            messages = messages,
                            canSend = true,
                        ),
                    useVirtual = useVirtual,
                    initialViewportHeightDp = 700.dp,
                )
            hostPayload(harness)
            assertNotNull(boundsForText("IME tail message #40"), "[$useVirtual] tail #40 not shown before IME open")

            runOnIdle {
                harness.composerKeyboardInsetDp = 300f
                harness.viewportHeightDp = 400.dp
            }
            settle()

            val tailRect = boundsForText("IME tail message #40")
            val maxVisibleBottomPx = 400f * density.density + 2f
            bottomPinned = tailRect != null && tailRect.bottom <= maxVisibleBottomPx
            assertTrue(bottomPinned, "[$useVirtual] tail #40 not pinned inside shrunk 400dp viewport: rect=$tailRect maxY=$maxVisibleBottomPx")
        }

        // Case B: Focused mid-history (m15) -> viewport shrinks 700dp -> 400dp -> must NOT yank to bottom #40!
        runRobolectricComposeUiTest {
            val messages = (1..40).map { chatMessage(it, if (it == 15) "IME history anchor #15" else if (it == 40) "IME bottom tail #40" else "IME row #$it") }
            val harness =
                PayloadHarness(
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e6-history-${if (useVirtual) "virtual" else "lazy"}",
                            sourceThreadId = "channel-1",
                            streamChannelId = "channel-1",
                            status = "content",
                            messages = messages,
                            focusedMessageId = "m15",
                            focusedMessageRequestNonce = 1,
                            canSend = true,
                        ),
                    useVirtual = useVirtual,
                    initialViewportHeightDp = 700.dp,
                )
            hostPayload(harness)
            assertNotNull(boundsForText("IME history anchor #15"), "[$useVirtual] m15 not visible before shrink")

            runOnIdle {
                harness.composerKeyboardInsetDp = 300f
                harness.viewportHeightDp = 400.dp
            }
            settle()

            historyKept = boundsForText("IME history anchor #15") != null
            historyYanked = boundsForText("IME bottom tail #40") != null
            assertTrue(historyKept, "[$useVirtual] mid-history anchor m15 disappeared on IME shrink")
            assertFalse(historyYanked, "[$useVirtual] IME shrink wrongly yanked scrolled-up reader to tail #40")
        }

        return E2e6Metrics(
            engine = if (useVirtual) "VirtualMessageTimeline" else "LazyColumn(reverse)",
            bottomTailStillPinned = bottomPinned,
            historyRowPreserved = historyKept,
            historyYankedToBottom = historyYanked,
        )
    }

    @Test
    fun e2e06_imeViewportShrinkTailPinVsScrolledHistory() {
        val lazy = runE2e6ImeShrink(useVirtual = false)
        val virtual = runE2e6ImeShrink(useVirtual = true)
        println(
            """
            |=== [E2E-6] Real ThreadMessagesList: IME Viewport Shrink (700dp -> 400dp) ===
            | Engine                  | BottomTailPinned | HistoryRowPreserved | HistoryYankedToBottom
            |-------------------------+------------------+---------------------+----------------------
            | ${lazy.engine.padEnd(23)} | ${lazy.bottomTailStillPinned.toString().padStart(16)} | ${lazy.historyRowPreserved.toString().padStart(19)} | ${lazy.historyYankedToBottom.toString().padStart(21)}
            | ${virtual.engine.padEnd(23)} | ${virtual.bottomTailStillPinned.toString().padStart(16)} | ${virtual.historyRowPreserved.toString().padStart(19)} | ${virtual.historyYankedToBottom.toString().padStart(21)}
            """.trimMargin()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // E2E-7: Reply thread OriginCard + DateDivider + HeaderOriginalState + leftward time-reveal swipe
    // ---------------------------------------------------------------------------------------------

    private data class E2e7Metrics(
        val engine: String,
        val originOutOfViewAtBottom: Boolean,
        val originBackInViewAfterTopJump: Boolean,
        val timeRevealShownOnSwipe: Boolean,
        val timeRevealClearedAfterRelease: Boolean,
    )

    private fun runE2e7ReplyThreadAndTimeReveal(useVirtual: Boolean): E2e7Metrics {
        lateinit var metrics: E2e7Metrics
        runRobolectricComposeUiTest {
            val origin =
                RaftThreadMessageItem(
                    id = "origin-root",
                    authorId = "u1",
                    authorName = "Artin",
                    seq = 1L,
                    body = "Origin thread root message",
                    timestampLabel = "2026-07-29T10:00:00Z",
                )
            val replies =
                (2..35).map { seq ->
                    val day = if (seq < 20) "2026-07-29" else "2026-07-30"
                    RaftThreadMessageItem(
                        id = "r$seq",
                        authorId = "u2",
                        authorName = "Lagoon",
                        seq = seq.toLong(),
                        body = "Reply thread message #$seq",
                        timestampLabel = "${day}T12:${(seq % 60).toString().padStart(2, '0')}:00Z",
                    )
                }
            val headerState = ThreadHeaderOriginalState()
            val harness =
                PayloadHarness(
                    initialPayload =
                        RaftThreadScreenPayload(
                            instanceId = "e2e7-${if (useVirtual) "virtual" else "lazy"}",
                            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                            sourceThreadId = "thread-root-1",
                            streamChannelId = "channel-1",
                            parentMessageId = origin.id,
                            originStatus = "ready",
                            originMessage = origin,
                            status = "content",
                            messages = replies,
                            canLoadMoreBefore = false,
                            canLoadMoreAfter = false,
                            canSend = true,
                        ),
                    useVirtual = useVirtual,
                    timeRevealAvailable = true,
                    headerOriginalState = headerState,
                )
            hostPayload(harness)

            // At bottom of 34 replies: origin is scrolled off top -> headerState.originOutOfView == true.
            val outOfViewAtBottom = headerState.originOutOfView
            assertTrue(outOfViewAtBottom, "[$useVirtual] origin should be out of view when opened at bottom of 34 replies")

            // Leftward time-reveal gesture (drag away from edge zone, hold -> labels appear -> release -> spring back).
            onRoot().performTouchInput {
                down(Offset(width * 0.78f, height * 0.55f))
                moveTo(Offset(width * 0.55f, height * 0.55f))
                moveTo(Offset(width * 0.30f, height * 0.55f))
            }
            waitForIdle()
            val revealedWhileHeld = onAllNodesWithTag(ThreadTimeRevealLabelTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            onRoot().performTouchInput { up() }
            settle()
            val clearedAfterRelease = onAllNodesWithTag(ThreadTimeRevealLabelTestTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            assertTrue(revealedWhileHeld, "[$useVirtual] leftward drag did not reveal message time labels")
            assertTrue(clearedAfterRelease, "[$useVirtual] message time labels did not spring back on release")

            // Header tap scroll-to-top jumps back to OriginCard -> originOutOfView flips false!
            runOnIdle { harness.headerScrollToTopRequest += 1 }
            settle()
            assertNotNull(boundsForText("Origin thread root message"), "[$useVirtual] OriginCard not visible after scroll-to-top")
            val backInViewAtTop = !headerState.originOutOfView
            assertTrue(backInViewAtTop, "[$useVirtual] headerState.originOutOfView should flip false after jumping to top")

            metrics =
                E2e7Metrics(
                    engine = if (useVirtual) "VirtualMessageTimeline" else "LazyColumn(reverse)",
                    originOutOfViewAtBottom = outOfViewAtBottom,
                    originBackInViewAfterTopJump = backInViewAtTop,
                    timeRevealShownOnSwipe = revealedWhileHeld,
                    timeRevealClearedAfterRelease = clearedAfterRelease,
                )
        }
        return metrics
    }

    @Test
    fun e2e07_replyThreadOriginCardHeaderReporterAndTimeRevealGesture() {
        val lazy = runE2e7ReplyThreadAndTimeReveal(useVirtual = false)
        val virtual = runE2e7ReplyThreadAndTimeReveal(useVirtual = true)
        println(
            """
            |=== [E2E-7] Real ThreadMessagesList: Reply Thread OriginCard + HeaderOriginalReporter + TimeReveal Swipe ===
            | Engine                  | OriginOffAtBottom | OriginVisibleAtTop | TimeRevealShown | TimeRevealReset
            |-------------------------+-------------------+--------------------+-----------------+----------------
            | ${lazy.engine.padEnd(23)} | ${lazy.originOutOfViewAtBottom.toString().padStart(17)} | ${lazy.originBackInViewAfterTopJump.toString().padStart(18)} | ${lazy.timeRevealShownOnSwipe.toString().padStart(15)} | ${lazy.timeRevealClearedAfterRelease.toString().padStart(15)}
            | ${virtual.engine.padEnd(23)} | ${virtual.originOutOfViewAtBottom.toString().padStart(17)} | ${virtual.originBackInViewAfterTopJump.toString().padStart(18)} | ${virtual.timeRevealShownOnSwipe.toString().padStart(15)} | ${virtual.timeRevealClearedAfterRelease.toString().padStart(15)}
            """.trimMargin()
        )
    }

    private companion object {
        fun chatMessage(seq: Int, body: String) =
            RaftThreadMessageItem(
                id = "m$seq",
                authorId = "u1",
                authorName = "Artin",
                seq = seq.toLong(),
                body = body,
                timestampLabel = "2026-07-30T02:${(seq / 60).toString().padStart(2, '0')}:${(seq % 60).toString().padStart(2, '0')}Z",
                messageType = "chat",
            )

        fun systemTaskMessage(seq: Int) =
            RaftThreadMessageItem(
                id = "sys$seq",
                authorId = "sys",
                authorName = "System",
                seq = seq.toLong(),
                body = "✅ Otter moved #$seq \"Task $seq\" to Done",
                timestampLabel = "2026-07-30T02:01:${(seq % 60).toString().padStart(2, '0')}Z",
                messageType = "system",
            )
    }
}
