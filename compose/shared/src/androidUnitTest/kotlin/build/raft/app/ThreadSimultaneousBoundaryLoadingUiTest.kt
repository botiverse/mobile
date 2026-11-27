package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Short-list Robolectric Compose UI contract tests for #proj-mobile:#390 on the production
 * [ThreadMessagesList] (3 messages so both outer boundaries are simultaneously visible in the
 * viewport):
 * 1. Simultaneous older + newer loading renders exactly 1 boundary loading indicator (both spinner
 *    and label nodes), strictly at the outer boundary (above the first message, never between
 *    messages).
 * 2. When the winning boundary finishes while the other is still loading, the remaining boundary
 *    cleanly becomes the sole visible indicator at its outer boundary.
 * 3. When both finish, 0 loading indicators remain.
 * 4. Staggered start (newer starts first, then older starts) preserves newer without flipping to
 *    older, then hands off to older when newer finishes, then clears to 0 when older finishes.
 * 5. Error retry buttons and non-loading boundary labels ("Beginning of conversation",
 *    "Older messages are not available offline") are never suppressed while the opposite boundary
 *    is loading.
 * 6. Durable stand-in older loading (`canLoadMoreBefore = false`, `spec.loading = true`) also
 *    arbitrates with newer loading, and switching conversations (`threadInstanceKey`) resets
 *    arbitration state.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h700dp")
class ThreadSimultaneousBoundaryLoadingUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-simultaneous-boundary-loading"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        PagerManager.register(livePager)
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
        PagerManager.unregister(livePager)
        StringDesc.localeType = previousLocale
        RaftSharedStoreRegistry.clearForTests()
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

    private class Harness(initialPayload: RaftThreadScreenPayload) {
        var threadInstanceKey by mutableStateOf(initialPayload.instanceId)
        var payload by mutableStateOf(initialPayload)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        var loadNewerCalls = 0
        var autoLoadNewerGate = CompletableDeferred<ThreadNewerPageLoadOutcome>()

        fun releaseAutoLoadGate() {
            autoLoadNewerGate.complete(ThreadNewerPageLoadOutcome.Empty)
            autoLoadNewerGate = CompletableDeferred()
        }
    }

    private fun ComposeUiTest.host(harness: Harness) {
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadReverseLayout provides true,
                LocalRaftSpinnerStillStep provides 0,
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
                            onThreadEvent = ThreadEventHandler { action, itemId, _, _, _, _ ->
                                harness.events += if (itemId != null) "$action:$itemId" else action
                            },
                            onLoadNewer = {
                                harness.loadNewerCalls += 1
                                // Short non-scrolling lists with canLoadMoreAfter=true && newerMessagesStatus="idle"
                                // auto-trigger loadNewer on idle; keep that synthetic request in flight until the test
                                // transitions state so waitForIdle() never spins in an empty-return loop.
                                if (harness.payload.newerMessagesStatus == "idle") {
                                    harness.autoLoadNewerGate.await()
                                } else {
                                    ThreadNewerPageLoadOutcome.Empty
                                }
                            },
                            onJumpToLatest = { ThreadLatestJumpOutcome.Landed },
                            onOpenMember = {},
                            onMentionAuthor = {},
                            onOpenMarkdownTag = { _, _ -> },
                            onOpenMarkdownUrl = {},
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
    }

    private data class VisibleNode(val label: String, val bounds: Rect)

    private fun SemanticsNode.collectAll(into: MutableList<SemanticsNode>) {
        into += this
        children.forEach { it.collectAll(into) }
    }

    private fun ComposeUiTest.allUnmergedNodes(): List<SemanticsNode> =
        buildList { onRoot(useUnmergedTree = true).fetchSemanticsNode().collectAll(this) }

    /** Every visible spinner node in the list whose contentDescription mentions "Loading". */
    private fun ComposeUiTest.visibleLoadingSpinners(): List<VisibleNode> =
        allUnmergedNodes().mapNotNull { node ->
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().firstOrNull()
            if (desc != null && "Loading" in desc && node.boundsInRoot.height > 0f) {
                VisibleNode(label = desc, bounds = node.boundsInRoot)
            } else {
                null
            }
        }

    /** Every visible text node in the list whose text mentions "Loading". */
    private fun ComposeUiTest.visibleLoadingLabels(): List<VisibleNode> =
        allUnmergedNodes().mapNotNull { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
            if (text.isNotEmpty() && "Loading" in text && node.boundsInRoot.height > 0f) {
                VisibleNode(label = text, bounds = node.boundsInRoot)
            } else {
                null
            }
        }

    private fun ComposeUiTest.messageBounds(body: String): Rect =
        onAllNodesWithText(body, substring = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .firstOrNull { it.height > 0f }
            ?: error("Message '$body' is not visible on screen")

    private fun ComposeUiTest.assertThreeMessagesInChronologicalOrder(): Pair<Rect, Rect> {
        val first = messageBounds(FirstBody)
        val middle = messageBounds(MiddleBody)
        val last = messageBounds(LastBody)
        assertTrue(first.bottom <= middle.top + 0.5f, "first=$first should sit above middle=$middle")
        assertTrue(middle.bottom <= last.top + 0.5f, "middle=$middle should sit above last=$last")
        return first to last
    }

    private fun ComposeUiTest.assertSoleLoadingIndicatorAboveFirstMessage(expectedLabel: String) {
        val (first, last) = assertThreeMessagesInChronologicalOrder()
        val spinners = visibleLoadingSpinners()
        val labels = visibleLoadingLabels()
        assertEquals(listOf(expectedLabel), spinners.map { it.label }, "expected exactly 1 spinner ($expectedLabel), got $spinners")
        assertEquals(listOf(expectedLabel), labels.map { it.label }, "expected exactly 1 loading label ($expectedLabel), got $labels")
        assertTrue(
            spinners.single().bounds.bottom <= first.top + 0.5f,
            "older spinner ${spinners.single().bounds} must sit strictly above first message $first (last=$last)",
        )
        assertTrue(
            labels.single().bounds.bottom <= first.top + 0.5f,
            "older loading label ${labels.single().bounds} must sit strictly above first message $first (last=$last)",
        )
        assertFalse(
            labels.single().bounds.top >= first.top && labels.single().bounds.bottom <= last.bottom,
            "loading indicator must never sit between messages",
        )
    }

    private fun ComposeUiTest.assertSoleLoadingIndicatorBelowLastMessage(expectedLabel: String) {
        val (first, last) = assertThreeMessagesInChronologicalOrder()
        val spinners = visibleLoadingSpinners()
        val labels = visibleLoadingLabels()
        assertEquals(listOf(expectedLabel), spinners.map { it.label }, "expected exactly 1 spinner ($expectedLabel), got $spinners")
        assertEquals(listOf(expectedLabel), labels.map { it.label }, "expected exactly 1 loading label ($expectedLabel), got $labels")
        assertTrue(
            spinners.single().bounds.top >= last.bottom - 0.5f,
            "newer spinner ${spinners.single().bounds} must sit strictly below last message $last (first=$first)",
        )
        assertTrue(
            labels.single().bounds.top >= last.bottom - 0.5f,
            "newer loading label ${labels.single().bounds} must sit strictly below last message $last (first=$first)",
        )
        assertFalse(
            labels.single().bounds.top >= first.top && labels.single().bounds.bottom <= last.bottom,
            "loading indicator must never sit between messages",
        )
    }

    private fun ComposeUiTest.assertZeroLoadingIndicators() {
        assertThreeMessagesInChronologicalOrder()
        assertEquals(emptyList(), visibleLoadingSpinners(), "expected 0 loading spinners")
        assertEquals(emptyList(), visibleLoadingLabels(), "expected 0 loading labels")
    }

    @Test
    fun simultaneousOlderAndNewerLoadingShowsSingleBoundaryIndicatorAndHandsOffWhenWinnerFinishes() =
        runRobolectricComposeUiTest {
            val harness =
                Harness(
                    shortPayload(
                        instanceId = ThreadA,
                        canLoadMoreBefore = true,
                        olderMessagesStatus = "loading",
                        canLoadMoreAfter = true,
                        newerMessagesStatus = "loading",
                    )
                )
            try {
                host(harness)

                // Case 1: Both older and newer loading simultaneously -> exactly 1 indicator (Older, above first message).
                assertSoleLoadingIndicatorAboveFirstMessage(LoadingOlder)

                // Case 2: Winner (older) finishes while newer is still loading -> exactly 1 indicator (Newer, below last message).
                runOnIdle {
                    harness.payload = harness.payload.copy(olderMessagesStatus = "idle", newerMessagesStatus = "loading")
                }
                settle()
                assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)

                // Case 3: Both finish -> 0 loading indicators remain.
                runOnIdle {
                    harness.payload = harness.payload.copy(olderMessagesStatus = "idle", newerMessagesStatus = "idle")
                }
                settle()
                assertZeroLoadingIndicators()
            } finally {
                harness.releaseAutoLoadGate()
            }
        }

    @Test
    fun staggeredNewerThenOlderLoadingPreservesNewerWithoutFlickerThenHandsOffToOlder() =
        runRobolectricComposeUiTest {
            val harness =
                Harness(
                    shortPayload(
                        instanceId = ThreadA,
                        canLoadMoreBefore = true,
                        olderMessagesStatus = "idle",
                        canLoadMoreAfter = true,
                        newerMessagesStatus = "loading",
                    )
                )
            try {
                host(harness)

                // Newer starts loading first -> sole indicator sits below last message.
                assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)

                // Case 4a: Older starts loading while newer is still active -> newer stays the sole indicator (no flicker).
                runOnIdle {
                    harness.payload = harness.payload.copy(olderMessagesStatus = "loading", newerMessagesStatus = "loading")
                }
                settle()
                assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)

                // Case 4b: Newer finishes while older is still loading -> older takes over above first message.
                runOnIdle {
                    harness.payload = harness.payload.copy(olderMessagesStatus = "loading", newerMessagesStatus = "idle")
                }
                settle()
                assertSoleLoadingIndicatorAboveFirstMessage(LoadingOlder)

                // Case 4c: Older finishes -> 0 loading indicators remain.
                runOnIdle {
                    harness.payload = harness.payload.copy(olderMessagesStatus = "idle", newerMessagesStatus = "idle")
                }
                settle()
                assertZeroLoadingIndicators()
            } finally {
                harness.releaseAutoLoadGate()
            }
        }

    @Test
    fun errorRetryAndNonLoadingBoundaryLabelsAreNeverSuppressedWhileOtherBoundaryLoads() =
        runRobolectricComposeUiTest {
            // Case 5a: Older error retry button stays visible & clickable while newer is loading.
            val harness =
                Harness(
                    shortPayload(
                        instanceId = ThreadA,
                        canLoadMoreBefore = true,
                        olderMessagesStatus = "error",
                        canLoadMoreAfter = true,
                        newerMessagesStatus = "loading",
                    )
                )
            host(harness)
            assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)
            val (first, _) = assertThreeMessagesInChronologicalOrder()
            val retryOlderBounds = onNodeWithText(RetryOlder).fetchSemanticsNode().boundsInRoot
            assertTrue(retryOlderBounds.bottom <= first.top + 0.5f, "Retry older button must sit above first message")
            onNodeWithText(RetryOlder).performClick()
            settle()
            assertTrue("retryLoadOlder" in harness.events, "expected retryLoadOlder event, got ${harness.events}")

            // Case 5b: Newer error retry button stays visible & clickable while older is loading.
            runOnIdle {
                harness.loadNewerCalls = 0
                harness.payload = harness.payload.copy(olderMessagesStatus = "loading", newerMessagesStatus = "error")
            }
            settle()
            assertSoleLoadingIndicatorAboveFirstMessage(LoadingOlder)
            val (_, last) = assertThreeMessagesInChronologicalOrder()
            val retryNewerBounds = onNodeWithText(RetryNewer).fetchSemanticsNode().boundsInRoot
            assertTrue(retryNewerBounds.top >= last.bottom - 0.5f, "Retry new button must sit below last message")
            onNodeWithText(RetryNewer).performClick()
            settle()
            assertEquals(1, harness.loadNewerCalls, "Retry new button must trigger onLoadNewer while older loads")

            // Case 5c: Non-loading "Beginning of conversation" stays visible while newer is loading.
            runOnIdle {
                harness.payload =
                    harness.payload.copy(
                        canLoadMoreBefore = false,
                        olderMessagesStatus = "idle",
                        durableLastKnown = false,
                        mainLoadFailed = false,
                        canLoadMoreAfter = true,
                        newerMessagesStatus = "loading",
                    )
            }
            settle()
            assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)
            val beginningBounds = onNodeWithText(BeginningOfConversation).fetchSemanticsNode().boundsInRoot
            assertTrue(beginningBounds.bottom <= first.top + 0.5f, "Beginning of conversation must stay visible above first message")

            // Case 5d: Non-loading offline label stays visible while newer is loading.
            runOnIdle {
                harness.payload =
                    harness.payload.copy(
                        canLoadMoreBefore = false,
                        olderMessagesStatus = "idle",
                        durableLastKnown = true,
                        durableReachedOldest = false,
                        mainLoadFailed = true,
                        canLoadMoreAfter = true,
                        newerMessagesStatus = "loading",
                    )
            }
            settle()
            assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)
            val offlineBounds = onNodeWithText(OfflineOlderUnavailable).fetchSemanticsNode().boundsInRoot
            assertTrue(offlineBounds.bottom <= first.top + 0.5f, "Offline label must stay visible above first message")
        }

    @Test
    fun durableStandInOlderLoadingAlsoArbitratesWithNewerLoadingAndResetsOnThreadSwitch() =
        runRobolectricComposeUiTest {
            // Start with newer loading owning activeSlot on Thread A.
            val harness =
                Harness(
                    shortPayload(
                        instanceId = ThreadA,
                        canLoadMoreBefore = false,
                        olderMessagesStatus = "idle",
                        canLoadMoreAfter = true,
                        newerMessagesStatus = "loading",
                        durableLastKnown = false,
                        durableReachedOldest = false,
                        mainLoadFailed = false,
                    )
                )
            try {
                host(harness)
                assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)

                // Durable stand-in older loading (canLoadMoreBefore = false, spec.loading = true) activates
                // while newer is still loading -> newer remains the sole indicator (stand-in older suppressed).
                runOnIdle {
                    harness.payload =
                        harness.payload.copy(
                            durableLastKnown = true,
                            durableReachedOldest = false,
                            mainLoadFailed = false,
                            newerMessagesStatus = "loading",
                        )
                }
                settle()
                assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)

                // Newer finishes -> durable stand-in older loading takes over above first message.
                runOnIdle {
                    harness.payload = harness.payload.copy(newerMessagesStatus = "idle")
                }
                settle()
                assertSoleLoadingIndicatorAboveFirstMessage(LoadingOlder)

                // Re-establish Newer as activeSlot on Thread A, then switch to Thread B with both boundaries
                // loading simultaneously -> switching threadInstanceKey resets activeSlot so Thread B picks Older.
                runOnIdle {
                    harness.payload =
                        harness.payload.copy(
                            durableLastKnown = false,
                            canLoadMoreBefore = true,
                            olderMessagesStatus = "idle",
                            canLoadMoreAfter = true,
                            newerMessagesStatus = "loading",
                        )
                    harness.releaseAutoLoadGate()
                }
                settle()
                assertSoleLoadingIndicatorBelowLastMessage(LoadingNewer)

                runOnIdle {
                    harness.threadInstanceKey = ThreadB
                    harness.payload =
                        shortPayload(
                            instanceId = ThreadB,
                            canLoadMoreBefore = true,
                            olderMessagesStatus = "loading",
                            canLoadMoreAfter = true,
                            newerMessagesStatus = "loading",
                        )
                }
                settle()
                assertSoleLoadingIndicatorAboveFirstMessage(LoadingOlder)
            } finally {
                harness.releaseAutoLoadGate()
            }
        }

    private companion object {
        const val ThreadA = "thread-boundary-a"
        const val ThreadB = "thread-boundary-b"
        const val FirstBody = "Historical message 10"
        const val MiddleBody = "Focused anchor message 11"
        const val LastBody = "Historical message 12"

        const val LoadingOlder = "Loading older messages…"
        const val LoadingNewer = "Loading new messages…"
        const val RetryOlder = "Retry older messages"
        const val RetryNewer = "Retry new messages"
        const val BeginningOfConversation = "Beginning of conversation"
        const val OfflineOlderUnavailable = "Older messages are not available offline"

        fun message(seq: Int, body: String) =
            RaftThreadMessageItem(
                id = "m$seq",
                authorId = "u1",
                authorName = "Artin",
                seq = seq.toLong(),
                body = body,
                timestampLabel = "2026-07-30T02:00:${(seq % 60).toString().padStart(2, '0')}Z",
            )

        fun shortPayload(
            instanceId: String,
            canLoadMoreBefore: Boolean,
            olderMessagesStatus: String,
            canLoadMoreAfter: Boolean,
            newerMessagesStatus: String,
            durableLastKnown: Boolean = false,
            durableReachedOldest: Boolean = false,
            mainLoadFailed: Boolean = false,
        ) =
            RaftThreadScreenPayload(
                instanceId = instanceId,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                status = "content",
                messages =
                    listOf(
                        message(10, FirstBody),
                        message(11, MiddleBody),
                        message(12, LastBody),
                    ),
                canLoadMoreBefore = canLoadMoreBefore,
                olderMessagesStatus = olderMessagesStatus,
                canLoadMoreAfter = canLoadMoreAfter,
                newerMessagesStatus = newerMessagesStatus,
                durableLastKnown = durableLastKnown,
                durableReachedOldest = durableReachedOldest,
                mainLoadFailed = mainLoadFailed,
                focusedMessageId = "m11",
                focusedMessageRequestNonce = 1,
                canSend = true,
            )
    }
}
