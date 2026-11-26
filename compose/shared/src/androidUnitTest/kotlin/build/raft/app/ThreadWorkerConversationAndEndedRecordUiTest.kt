package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import build.raft.app.data.WORKER_ENDED_RECORD_SUBTYPE
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
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

@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h700dp")
class ThreadWorkerConversationAndEndedRecordUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-worker-v1-ui-test"
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

    private class SlotHarness(
        val payload: RaftThreadScreenPayload,
        initialTyping: ThreadAgentTypingUi? = null,
        initialWorker: ThreadWorkerConversationUi? = null,
    ) {
        var typing by mutableStateOf(initialTyping)
        var worker by mutableStateOf(initialWorker)
    }

    private fun ComposeUiTest.hostThreadList(harness: SlotHarness) {
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadReverseLayout provides true,
                LocalThreadAgentTyping provides harness.typing,
                LocalThreadWorkerConversation provides harness.worker,
                LocalThreadWorkerV1Enabled provides true,
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
                            diagnosticsLogger = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun sampleWorkerLine(onOpenAgent: (String) -> Unit = {}): ThreadWorkerConversationUi {
        val sageParts =
            WorkerLineParts(
                who = "Worker w3 of @Sage is handling this",
                tail = "12m · running Bash",
                tone = WorkerLineTone.Default,
                scope = WorkerLineScope.Tail,
                urgency = 0L,
                heldMs = 12 * 60_000L,
            )
        val unknownParts =
            WorkerLineParts(
                who = "A Worker is handling this",
                tail = "4m",
                tone = WorkerLineTone.Default,
                scope = WorkerLineScope.Tail,
                urgency = 0L,
                heldMs = 4 * 60_000L,
            )
        return ThreadWorkerConversationUi(
            serverId = "srv-1",
            rows =
                listOf(
                    ThreadWorkerConversationRow(
                        agentId = "agent-sage",
                        agentName = "Sage",
                        named = true,
                        avatarWireValue = null,
                        worker = "w3",
                        parts = sageParts,
                    ),
                    ThreadWorkerConversationRow(
                        agentId = "agent-unknown",
                        agentName = "A Worker of an agent here",
                        named = false,
                        avatarWireValue = null,
                        worker = null,
                        parts = unknownParts,
                    ),
                ),
            line =
                WorkerLineParts(
                    who = "2 Workers are handling this",
                    tail = "12m",
                    tone = WorkerLineTone.Default,
                    scope = WorkerLineScope.Tail,
                    urgency = 0L,
                    heldMs = 12 * 60_000L,
                ),
            popoverTitle = "Workers handling this conversation",
            onOpenAgent = onOpenAgent,
        )
    }

    private fun samplePayload() =
        RaftThreadScreenPayload(
            instanceId = "thread-worker-ui",
            sourceThreadId = "ch-thread",
            streamChannelId = "ch-thread",
            parentChannelId = "ch-parent",
            parentMessageId = "msg-root",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            status = "content",
            messages =
                listOf(
                    RaftThreadMessageItem(
                        id = "m1",
                        authorId = "u1",
                        authorName = "Alice",
                        seq = 1L,
                        body = "Can a Worker check the logs?",
                        timestampLabel = "2026-11-26T10:00:00.000Z",
                    ),
                ),
            canSend = true,
        )

    @Test
    fun workerConversationLineShowsInTypingSlotOpensUpwardPopoverAndOpensNamedAgent() = runRobolectricComposeUiTest {
        val opened = mutableListOf<String>()
        val harness = SlotHarness(samplePayload(), initialWorker = sampleWorkerLine(onOpenAgent = { opened += it }))
        hostThreadList(harness)

        onNodeWithTag("worker-conversation-line").assertIsDisplayed()
        onNodeWithTag("worker-conversation-trigger")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("2 Workers are handling this · 12m")
            .performClick()
        waitForIdle()

        onNodeWithTag("worker-conversation-popover").assertIsDisplayed()
        onNodeWithText("Workers handling this conversation").assertIsDisplayed()
        onNodeWithTag("worker-conversation-popover-row-agent-sage").assertIsDisplayed()
        onNodeWithTag("worker-conversation-popover-row-agent-unknown").assertIsDisplayed()

        // Unnamed row opens nothing and keeps the popover open.
        onNodeWithTag("worker-conversation-popover-row-agent-unknown").performClick()
        waitForIdle()
        assertEquals(emptyList(), opened)
        onNodeWithTag("worker-conversation-popover").assertIsDisplayed()

        // Named row opens the agent and dismisses the popover.
        onNodeWithTag("worker-conversation-popover-row-agent-sage").performClick()
        waitForIdle()
        assertEquals(listOf("agent-sage"), opened)
        assertTrue(onAllNodesWithTag("worker-conversation-popover").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun typingIndicatorWinsSharedSlotAndWorkerLineReturnsWhenTypingStops() = runRobolectricComposeUiTest {
        val harness = SlotHarness(samplePayload(), initialWorker = sampleWorkerLine())
        hostThreadList(harness)

        onNodeWithTag("worker-conversation-line").assertIsDisplayed()

        runOnIdle {
            harness.typing = ThreadAgentTypingUi(serverId = "srv-1", agents = listOf(ThreadTypingAgent("agent-niko", "Niko", null)))
        }
        waitForIdle()

        assertTrue(onAllNodesWithTag("worker-conversation-line").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasContentDescription("Niko is typing…")).fetchSemanticsNodes().isNotEmpty())

        runOnIdle { harness.typing = null }
        waitForIdle()

        onNodeWithTag("worker-conversation-line").assertIsDisplayed()
    }

    @Test
    fun workerEndedSystemRowCollapsesAndExpandsWhenWorkerV1Enabled() = runRobolectricComposeUiTest {
        val endedText = "Worker w3 of @Sage finished (ran 12m): Verified checks and closed the thread."
        var expandCalls = 0
        setContent {
            CompositionLocalProvider(LocalThreadWorkerV1Enabled provides true) {
                RaftDesignSystemTheme {
                    ThreadSystemMessageRow(
                        message =
                            RaftThreadMessageItem(
                                id = "m-ended",
                                authorId = "system",
                                authorName = "System",
                                authorKind = "system",
                                messageType = "System",
                                systemSubtype = WORKER_ENDED_RECORD_SUBTYPE,
                                body = endedText,
                                timestampLabel = "2026-11-26T10:12:00.000Z",
                            ),
                        timestampValue = "2026-11-26T10:12:00.000Z",
                        onExpand = { expandCalls += 1 },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("worker-ended-record").assertIsDisplayed()
        assertTrue(onAllNodesWithTag("worker-ended-record-text").fetchSemanticsNodes().isEmpty())

        onNodeWithTag("worker-ended-record-toggle").performClick()
        waitForIdle()

        onNodeWithTag("worker-ended-record-text").assertIsDisplayed()
        assertEquals(1, expandCalls)

        onNodeWithTag("worker-ended-record-toggle").performClick()
        waitForIdle()

        assertTrue(onAllNodesWithTag("worker-ended-record-text").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun workerEndedFallsBackToPlainSystemRowWhenWorkerV1OffAndOtherSubtypesStayPlain() = runRobolectricComposeUiTest {
        val endedText = "Worker w3 of @Sage finished (ran 12m): Done."
        setContent {
            CompositionLocalProvider(LocalThreadWorkerV1Enabled provides false) {
                RaftDesignSystemTheme {
                    ThreadSystemMessageRow(
                        message =
                            RaftThreadMessageItem(
                                id = "m-ended-off",
                                authorId = "system",
                                authorName = "System",
                                authorKind = "system",
                                messageType = "System",
                                systemSubtype = WORKER_ENDED_RECORD_SUBTYPE,
                                body = endedText,
                                timestampLabel = "2026-11-26T10:12:00.000Z",
                            ),
                        timestampValue = "2026-11-26T10:12:00.000Z",
                    )
                }
            }
        }
        waitForIdle()

        assertTrue(onAllNodesWithTag("worker-ended-record").fetchSemanticsNodes().isEmpty())
        onNodeWithText(endedText).assertIsDisplayed()
    }
}
