package build.raft.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
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
import build.raft.app.thread.LocalThreadReverseLayout
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadEventHandler
import build.raft.app.thread.ThreadLinkedTasksProjection
import build.raft.app.thread.ThreadMessagesList
import build.raft.app.thread.toThreadTimelineRenderConfig
import build.raft.app.thread.toThreadTimelineSlice
import build.raft.designsystem.components.RaftToastHostState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * "Back to bottom" from a window that does not hold the newest messages (left by a search hit, a
 * link, the first unread): one tap must be enough. Composes the production [ThreadMessagesList]
 * bottom-up (as the app always lays it out) over a real [SharedThreadStore], whose request for the
 * newest page waits until the test releases it, as the network does.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h700dp")
class ThreadBackToBottomJumpUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-back-to-bottom-jump"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    private var previousLocale = StringDesc.localeType

    /** The window's toasts (the page's errors land here, not in the platform toast). */
    private lateinit var toasts: RaftCmpToastWindow

    @BeforeTest
    fun setUp() {
        PagerManager.register(livePager)
        toasts = RaftCmpToasts.attach(RaftToastHostState()).also { it.setLifecycle(visible = true, resumed = true) }
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        // The list reads the shared stores (SQLDelight, app-scope key-value stores), as
        // TaskConversationHeadRealTapTest sets them up.
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

    /**
     * The page's store over a network whose tail request waits for [release].
     *
     * The response is decoded inline ([Dispatchers.Unconfined]), not on the production parse pool:
     * the button's coroutine runs on the Compose test's unconfined dispatcher, so after a decode on
     * the pool everything after it (the store's result line, the button back from loading, the
     * failure toast, the 64 ms release of the button's one-jump guard) ran on the pool thread
     * while the test went on. A test that saw "result=failed" could read the toasts before the
     * toast was posted (CI: `but was:<[]>`), or tap Back to Bottom before the guard's release was
     * even scheduled, so the retry tap was ignored as in flight and never landed (CI: "timed out
     * waiting for the retry to land"). Inline, a release runs the whole jump on the test thread,
     * inside the test's own `runOnIdle`/`performClick`.
     */
    private class Harness {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val api = TailApi()
        val scopeJob = SupervisorJob()
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
                initialPayload = window(),
                diagnosticsLogger = { line -> log += "store: $line" },
            )
    }

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

    private fun ComposeUiTest.host(harness: Harness) {
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                // As production: the thread list is always bottom-up (ThreadPage threadReverseLayoutEnabled).
                LocalThreadReverseLayout provides true,
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        val payload = harness.store.state.collectAsState().value.payload
                        ThreadMessagesList(
                            threadInstanceKey = "thread-jump",
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
                            onLoadNewer = { harness.store.loadNewer() },
                            onJumpToLatest = { harness.store.jumpToLatest() },
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
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    /** Waits (bounded, in real time as well as on the test clock) for the store's request to end. */
    private fun ComposeUiTest.awaitStore(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $description" }
            Thread.sleep(10)
            mainClock.advanceTimeByFrame()
        }
        settle()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    private fun ComposeUiTest.backToBottomShown(): Boolean = onAllNodesWithText(BackToBottom).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.loadingShown(): Boolean = onAllNodesWithText(Loading).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.newestShown(): Boolean = onAllNodesWithText("tail message 130").fetchSemanticsNodes().isNotEmpty()

    private fun Harness.clicks(): Int = log.count { it.startsWith("thread_back_to_bottom_click latestItemIndex") }

    /**
     * The report: from a window short of the newest messages, Back to Bottom needed many taps. One
     * tap is enough now: it asks for the newest page and says so (busy, "Loading messages…"), the
     * further taps meanwhile start nothing, and the list lands at the bottom when the page arrives.
     */
    @Test
    fun oneTapLoadsTheNewestPageShowsLoadingAndLandsAtTheBottom() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            try {
                host(harness)
                assertTrue(backToBottomShown(), "the window ends before the newest messages: the button shows")

                onNodeWithText(BackToBottom).performClick()
                settle()
                assertEquals(1, harness.api.tailRequests)
                // Loading: the button says so and is disabled while the newest page is on the way.
                onNodeWithText(Loading).assertIsNotEnabled()
                saveLoadingCapture()

                // The person taps again (twice) while it loads: no second request, nothing restarted.
                repeat(2) {
                    onNodeWithText(Loading).performClick()
                    settle()
                }
                assertEquals(1, harness.api.tailRequests)
                assertEquals(1, harness.clicks())

                runOnIdle { harness.api.release.complete(200) }
                awaitStore("the newest page to land") { harness.log.any { "thread_jump_to_latest result=applied" in it } }

                assertFalse(loadingShown(), "still loading after the page landed")
                assertFalse(backToBottomShown(), "the button still shows after one tap")
                assertTrue(newestShown(), "the newest message is not on screen")
                assertEquals(1, harness.api.tailRequests)
            } finally {
                harness.scopeJob.cancel()
            }
        }

    /**
     * The other half of "many taps": the pill is 32dp tall and its touch target 44dp (raft-ui), but
     * the reach past its box was Compose's near-hit, which loses to the list under it. A finger a
     * few dp above or below the pill scrolled or tapped the list and Back to Bottom did nothing.
     */
    @Test
    fun aFingerJustOffThePillStillPressesIt() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            try {
                host(harness)
                // The pill's drawn box: its border, not the (taller) hit box the semantics report.
                val hit = onNodeWithText(BackToBottom).fetchSemanticsNode().boundsInRoot
                val drawnTop = hit.top + PillHitOverhangDp * density.density
                val drawnBottom = hit.bottom - PillHitOverhangDp * density.density
                for (y in listOf(drawnTop - 4 * density.density, drawnBottom + 4 * density.density)) {
                    val before = harness.clicks()
                    onRoot().performTouchInput { click(Offset(hit.center.x, y)) }
                    settle()
                    assertEquals(before + 1, harness.clicks(), "a tap 4dp off the pill's edge (y=$y) did not press it")
                    // Let that jump land so the next tap starts a new one.
                    runOnIdle { harness.api.release.complete(500) }
                    awaitStore("the jump to end") { !loadingShown() }
                    harness.api.release = CompletableDeferred()
                }
            } finally {
                harness.scopeJob.cancel()
            }
        }

    /** The loading button for the review (not a golden): build/reports/thread-back-to-bottom-loading.png. */
    private fun ComposeUiTest.saveLoadingCapture() {
        val image = onRoot().captureToImage().asAndroidBitmap()
        val file = java.io.File("build/reports/thread-back-to-bottom-loading.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val BackToBottom = "Back to bottom"
        const val Loading = "Loading messages…"
        const val JumpLoadFailed = "Couldn't load these messages. Try again."

        /** RaftMessageListJumpHitOverhang: (44 - 32) / 2. */
        const val PillHitOverhangDp = 6f

        fun message(seq: Int, body: String) =
            RaftThreadMessageItem(
                id = "m$seq",
                authorId = "u1",
                authorName = "Artin",
                seq = seq.toLong(),
                body = body,
                timestampLabel = "2026-07-30T02:00:${(seq % 60).toString().padStart(2, '0')}Z",
            )

        fun window() =
            RaftThreadScreenPayload(
                instanceId = "thread-jump",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                status = "content",
                messages = (10..40).map { message(it, "window message $it") },
                canLoadMoreBefore = true,
                canLoadMoreAfter = true,
                // The window a search hit / link / unread jump left: around a focused message.
                focusedMessageId = "m25",
                focusedMessageRequestNonce = 1,
                canSend = true,
            )
    }
}
