package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.FeedbackAttachmentBytes
import build.raft.app.data.FeedbackAuthorType
import build.raft.app.data.FeedbackClient
import build.raft.app.data.FeedbackComment
import build.raft.app.data.FeedbackErrorKind
import build.raft.app.data.FeedbackFailure
import build.raft.app.data.FeedbackLatestComment
import build.raft.app.data.FeedbackReporterCloseReason
import build.raft.app.data.FeedbackResult
import build.raft.app.data.FeedbackTicket
import build.raft.app.data.FeedbackTicketDetail
import build.raft.app.data.FeedbackTicketDetailState
import build.raft.app.data.FeedbackTicketKind
import build.raft.app.data.FeedbackTicketListRead
import build.raft.app.data.FeedbackTicketPage
import build.raft.app.data.FeedbackTicketStatus
import build.raft.app.data.FeedbackTicketsListPhase
import build.raft.app.data.FeedbackTicketsListSnapshot
import build.raft.app.data.FeedbackTicketsListState
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SettingsFeedbackTicketsRepository
import build.raft.app.data.SettingsFeedbackTicketsState
import build.raft.app.data.SettingsFeedbackTicketsStore
import build.raft.app.model.RaftTimeContext
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.raftPageRouteIdentity
import build.raft.app.settings.SETTINGS_FEEDBACK_TICKETS_DETAIL_ONLY
import build.raft.app.settings.SETTINGS_FEEDBACK_TICKETS_PAGE
import build.raft.app.settings.SETTINGS_FEEDBACK_TICKETS_TICKET_ID
import build.raft.app.settings.SETTINGS_HANDS_FEEDBACK_PAGE
import build.raft.app.settings.SettingsFeedbackTicketSummaryCache
import build.raft.app.settings.SettingsFeedbackTicketsActions
import build.raft.app.settings.SettingsFeedbackTicketsListTag
import build.raft.app.settings.SettingsFeedbackTicketsNewFeedbackTag
import build.raft.app.settings.SettingsFeedbackTicketsPrivacyNoticeTag
import build.raft.app.settings.SettingsFeedbackTicketsScreen
import build.raft.app.settings.settingsFeedbackTicketsPageData
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.gesture.LocalRaftBackSwipeRegions
import build.raft.designsystem.gesture.RaftBackSwipeRegions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Feedback, natively (1.23, #90): the ticket list (status, first line, latest reply,
 * replies, new count, time), a ticket's thread, and a reply sent from it. The store is real; the
 * server is a fake repository. The reply field is a plain field here (the page uses the
 * conversation composer, which these tests do not drive).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class SettingsFeedbackTicketsPageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val now = 1_762_700_000_000L // 2025-11-09T14:53:20Z
    private val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = now / 86_400_000L, nowEpochMillis = now)

    private val crash =
        FeedbackTicket(
            id = "11111111-2222-4333-8444-555555555555",
            kind = FeedbackTicketKind.Bug,
            status = FeedbackTicketStatus.InProgress,
            closureReason = null,
            message = "Crash on launch\nSteps: open the app",
            createdAt = now - 3_600_000L,
            updatedAt = now - 600_000L,
            unread = true,
            unreadCount = 1,
            attachmentCount = 1,
            commentCount = 1,
            latestComment = FeedbackLatestComment(FeedbackAuthorType.Staff, "Looking into it", now - 600_000L),
        )

    private class FakeRepository(
        var list: FeedbackResult<FeedbackTicketListRead>,
        val details: Map<String, FeedbackTicketDetail> = emptyMap(),
        val listGate: CompletableDeferred<FeedbackResult<FeedbackTicketListRead>>? = null,
    ) : SettingsFeedbackTicketsRepository {
        val replies = mutableListOf<Pair<String, String>>()
        val extraComments = mutableListOf<FeedbackComment>()

        override suspend fun listTickets(cursor: String?, limit: Int) = listGate?.await() ?: list

        override suspend fun ticket(ticketId: String, commentCursor: String?, commentLimit: Int, source: String?): FeedbackResult<FeedbackTicketDetail> {
            val detail = details.getValue(ticketId)
            return FeedbackResult.Ok(detail.copy(comments = detail.comments + extraComments))
        }

        override suspend fun reply(ticketId: String, body: String, submissionId: String, source: String?): FeedbackResult<FeedbackComment> {
            replies += ticketId to body
            val comment = FeedbackComment("reply-${replies.size}", FeedbackAuthorType.Reporter, body, 1_762_700_060_000L)
            extraComments += comment
            return FeedbackResult.Ok(comment)
        }

        override suspend fun close(ticketId: String, reason: FeedbackReporterCloseReason, source: String?) = FeedbackResult.Ok(Unit)

        override suspend fun attachment(ticketId: String, attachmentId: String, source: String?): FeedbackResult<FeedbackAttachmentBytes> =
            FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Unavailable, "x"))
    }

    private fun pageOf(vararg tickets: FeedbackTicket): FeedbackResult<FeedbackTicketListRead> =
        FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(tickets.toList(), null, 1)))

    @Composable
    private fun Page(
        repository: FakeRepository,
        onNewFeedback: () -> Unit = {},
        onCopyTicketLink: ((String) -> Unit)? = null,
        agentDraftSent: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
        ownClient: FeedbackClient? = FeedbackClient.Android,
        privacyMode: RaftPrivacyMode = RaftPrivacyMode.Off,
        onStoreReady: (SettingsFeedbackTicketsStore) -> Unit = {},
    ) {
        val scope = rememberCoroutineScope()
        val store = remember { SettingsFeedbackTicketsStore(repository, scope, agentDraftSent = agentDraftSent).also { it.start(); onStoreReady(it) } }
        val state by store.state.collectAsState()
        Host {
            SettingsFeedbackTicketsScreen(
                state = state,
                timeContext = timeContext,
                actions =
                    SettingsFeedbackTicketsActions(
                        onBack = store::closeTicketView,
                        onNewFeedback = onNewFeedback,
                        onRetryList = store::retryList,
                        onLoadMore = store::loadMore,
                        onFilter = store::setFilter,
                        onOpenTicket = store::openTicket,
                        onReplyChange = store::setReplyDraft,
                        onSendReply = store::sendReply,
                        onCopyTicketLink = onCopyTicketLink,
                    ),
                ownClient = ownClient,
                privacyMode = privacyMode,
                replyComposer = { draft, enabled, sending, error, onChange, onSend ->
                    Column {
                        RaftTextarea(value = draft, onValueChange = onChange, modifier = Modifier.testTag(ReplyField), placeholder = "Write a reply…")
                        RaftButton(text = "Send", onClick = onSend, enabled = enabled && !sending)
                        error?.let { androidx.compose.material3.Text(it) }
                    }
                },
            )
        }
    }

    /** `RAFT_PRIVACY_MODE`: New Feedback (its archive goes to Hands) only once the server answered off. */
    @Test
    fun newFeedbackIsOfferedOnlyWhenTheServerAnsweredPrivacyModeOff() {
        val cases =
            listOf(
                RaftPrivacyMode.Off to null,
                RaftPrivacyMode.On to "This server runs in privacy mode, so feedback can't be sent to Raft from this app.",
                RaftPrivacyMode.Unknown to "Feedback can't be sent yet: this server hasn't said whether privacy mode is on.",
            )
        for ((mode, notice) in cases) {
            runRobolectricComposeUiTest {
                val repository = FakeRepository(pageOf(crash))
                setContent { Page(repository, privacyMode = mode) }
                waitForIdle()

                // The tickets already filed stay readable either way.
                onNodeWithText("Crash on launch").assertIsDisplayed()
                if (notice == null) {
                    onNodeWithTag(SettingsFeedbackTicketsNewFeedbackTag).assertIsDisplayed()
                    onAllNodesWithTag(SettingsFeedbackTicketsPrivacyNoticeTag).assertCountEquals(0)
                } else {
                    onAllNodesWithTag(SettingsFeedbackTicketsNewFeedbackTag).assertCountEquals(0)
                    onAllNodesWithText("New Feedback").assertCountEquals(0)
                    onNodeWithTag(SettingsFeedbackTicketsPrivacyNoticeTag).assertIsDisplayed()
                    onNodeWithText(notice).assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun ticketListPreservesScrollPositionAcrossDetailRoundTripAndResetsOnFilterChange() {
        val tickets =
            (0 until 24).map { index ->
                FeedbackTicket(
                    id = "11111111-2222-4333-8444-${index.toString().padStart(12, '0')}",
                    kind = FeedbackTicketKind.Bug,
                    status = FeedbackTicketStatus.Open,
                    closureReason = null,
                    message = "Feedback ticket item #$index",
                    createdAt = now - (index + 1) * 60_000L,
                    updatedAt = now - index * 10_000L,
                    unread = false,
                    unreadCount = 0,
                    attachmentCount = 0,
                    commentCount = 0,
                    latestComment = null,
                )
            }
        val target = tickets[18]
        val detail =
            FeedbackTicketDetail(
                ticket = target,
                comments = listOf(FeedbackComment("c-1", FeedbackAuthorType.Staff, "Detail body for #18", now - 1_000L)),
                attachments = emptyList(),
                nextCommentCursor = null,
                unreadTotal = 0,
            )
        runRobolectricComposeUiTest {
            val repository = FakeRepository(pageOf(*tickets.toTypedArray()), details = mapOf(target.id to detail))
            var storeRef: SettingsFeedbackTicketsStore? = null
            setContent { Page(repository, onStoreReady = { storeRef = it }) }
            waitForIdle()

            onNodeWithTag(SettingsFeedbackTicketsListTag).performScrollToIndex(18)
            waitForIdle()
            onNodeWithText("Feedback ticket item #18").assertIsDisplayed()
            onNodeWithText("Feedback ticket item #0").assertDoesNotExist()

            // Open ticket detail, verify it rendered, then navigate back to the list.
            onNodeWithText("Feedback ticket item #18").performClick()
            waitForIdle()
            onNodeWithText("Detail body for #18").assertIsDisplayed()

            runOnIdle { storeRef!!.closeTicketView() }
            waitForIdle()
            onNodeWithText("Feedback ticket item #18").assertIsDisplayed()
            onNodeWithText("Feedback ticket item #0").assertDoesNotExist()

            // Switching filter tab resets scroll back to top.
            runOnIdle { storeRef!!.setFilter(build.raft.app.data.FeedbackTicketFilter.Active) }
            waitForIdle()
            onNodeWithText("Feedback ticket item #0").assertIsDisplayed()
        }
    }

    @Test
    fun ticketDetailStackPageHasDistinctRouteIdentityAndPopsBackToList() {
        val scope = RaftShellStoreScope.create(shellInstanceId = "shell-1", initialServerId = "srv-1")
        val listJson =
            Json.parseToJsonElement(settingsFeedbackTicketsPageData(scope).toString()).jsonObject
        val detailJson =
            Json.parseToJsonElement(
                settingsFeedbackTicketsPageData(
                    storeScope = scope,
                    ticketId = crash.id,
                    detailOnly = true,
                ).toString(),
            ).jsonObject

        assertNotEquals(
            raftPageRouteIdentity(SETTINGS_FEEDBACK_TICKETS_PAGE, listJson),
            raftPageRouteIdentity(SETTINGS_FEEDBACK_TICKETS_PAGE, detailJson),
        )

        // Simulates Settings (root) -> Feedback List -> Feedback Detail -> swipe/back pops to Feedback List.
        val stack = RaftPageStack()
        stack.open(SETTINGS_FEEDBACK_TICKETS_PAGE, listJson, from = stack.pages.last())
        assertEquals(2, stack.pages.size)

        stack.open(SETTINGS_FEEDBACK_TICKETS_PAGE, detailJson, from = stack.pages.last())
        assertEquals(3, stack.pages.size)
        assertEquals(crash.id, stack.pages.last().key.pageData[SETTINGS_FEEDBACK_TICKETS_TICKET_ID]?.toString()?.trim('"'))

        assertTrue(stack.back())
        assertEquals(2, stack.pages.size)
        assertEquals(SETTINGS_FEEDBACK_TICKETS_PAGE, stack.pages.last().key.pageName)
        assertFalse(stack.pages.last().key.pageData.containsKey(SETTINGS_FEEDBACK_TICKETS_TICKET_ID))
    }

    @Test
    fun pushedDetailModeDelegatesSystemBackToHostWhileHeaderBackClosesPage() {
        val detail =
            FeedbackTicketDetail(
                ticket = crash,
                comments = listOf(FeedbackComment("c-1", FeedbackAuthorType.Staff, "Detail body", now - 1_000L)),
                attachments = emptyList(),
                nextCommentCursor = null,
                unreadTotal = 0,
            )
        val detailState =
            SettingsFeedbackTicketsState(
                detail =
                    FeedbackTicketDetailState(
                        ticketId = crash.id,
                        summary = crash,
                        detail = detail,
                        loading = false,
                    ),
            )

        // Pushed detail page (handlesDetailBackInPlace = false): system Back falls through to the host
        // stack handler (so swipe-back / predictive back pops the page), while the top-left Back button
        // still calls actions.onBack.
        runRobolectricComposeUiTest {
            var backCalls = 0
            var hostPageBacks = 0
            var dispatcher: OnBackPressedDispatcher? = null
            setContent {
                Host {
                    dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    RaftBackHandler(enabled = true, label = "test_host_page") { hostPageBacks += 1 }
                    SettingsFeedbackTicketsScreen(
                        state = detailState,
                        timeContext = timeContext,
                        actions = SettingsFeedbackTicketsActions(onBack = { backCalls += 1 }),
                        replyComposer = { _, _, _, _, _, _ -> },
                        handlesDetailBackInPlace = false,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Detail body").assertIsDisplayed()

            runOnIdle { assertNotNull(dispatcher).onBackPressed() }
            waitForIdle()
            assertEquals(0, backCalls)
            assertEquals(1, hostPageBacks)

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            assertEquals(1, backCalls)
            assertEquals(1, hostPageBacks)
        }

        // In-place detail mode (handlesDetailBackInPlace = true): system Back is intercepted by the
        // screen's RaftBackHandler instead of falling through to the host.
        runRobolectricComposeUiTest {
            var backCalls = 0
            var hostPageBacks = 0
            var dispatcher: OnBackPressedDispatcher? = null
            setContent {
                Host {
                    dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    RaftBackHandler(enabled = true, label = "test_host_page") { hostPageBacks += 1 }
                    SettingsFeedbackTicketsScreen(
                        state = detailState,
                        timeContext = timeContext,
                        actions = SettingsFeedbackTicketsActions(onBack = { backCalls += 1 }),
                        replyComposer = { _, _, _, _, _, _ -> },
                        handlesDetailBackInPlace = true,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Detail body").assertIsDisplayed()

            runOnIdle { assertNotNull(dispatcher).onBackPressed() }
            waitForIdle()
            assertEquals(1, backCalls)
            assertEquals(0, hostPageBacks)
        }
    }

    @Test
    fun summaryCacheIsScopedAndOneShot() {
        SettingsFeedbackTicketSummaryCache.clear()
        try {
            SettingsFeedbackTicketSummaryCache.put("shell-1:srv-a", crash)
            // A different scope cannot read the seeded summary.
            assertNull(SettingsFeedbackTicketSummaryCache.take("shell-1:srv-b", crash.id))
            // The matching scope receives it once, and subsequent takes return null.
            assertEquals(crash, SettingsFeedbackTicketSummaryCache.take("shell-1:srv-a", crash.id))
            assertNull(SettingsFeedbackTicketSummaryCache.take("shell-1:srv-a", crash.id))
        } finally {
            SettingsFeedbackTicketSummaryCache.clear()
        }
    }

    @Test
    fun pullToRefreshIsDisabledDuringInitialLoadingAndEnabledOnceReady() {
        runRobolectricComposeUiTest {
            var refreshCalls = 0
            var state by mutableStateOf(
                SettingsFeedbackTicketsState(
                    list = FeedbackTicketsListState(phase = FeedbackTicketsListPhase.Loading),
                ),
            )
            setContent {
                Host {
                    SettingsFeedbackTicketsScreen(
                        state = state,
                        timeContext = timeContext,
                        actions = SettingsFeedbackTicketsActions(onRefresh = { refreshCalls += 1 }),
                        replyComposer = { _, _, _, _, _, _ -> },
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Loading feedback").assertIsDisplayed()

            // Pulling down during initial Loading must NOT trigger onRefresh.
            onNodeWithTag(SettingsFeedbackTicketsListTag).performTouchInput {
                swipeDown(startY = top + 40f, endY = bottom - 40f, durationMillis = 300L)
            }
            waitForIdle()
            assertEquals(0, refreshCalls, "pull-to-refresh must stay disabled during initial Loading")

            // Once Ready, the same pull gesture triggers onRefresh.
            state =
                SettingsFeedbackTicketsState(
                    list =
                        FeedbackTicketsListState(
                            phase = FeedbackTicketsListPhase.Ready,
                            tickets = listOf(crash),
                        ),
                )
            waitForIdle()
            onNodeWithText("Crash on launch").assertIsDisplayed()
            onNodeWithTag(SettingsFeedbackTicketsListTag).performTouchInput {
                swipeDown(startY = top + 40f, endY = bottom - 40f, durationMillis = 300L)
            }
            waitForIdle()
            assertEquals(1, refreshCalls, "pull-to-refresh must work once Ready")
        }
    }

    @Test
    fun warmListSnapshotRendersCachedTicketsOnFrameOneWhileRefreshing() {
        runRobolectricComposeUiTest {
            val gate = CompletableDeferred<FeedbackResult<FeedbackTicketListRead>>()
            val repository = FakeRepository(list = pageOf(crash), listGate = gate)
            setContent {
                val scope = rememberCoroutineScope()
                val store =
                    remember {
                        SettingsFeedbackTicketsStore(
                            repository = repository,
                            scope = scope,
                            initialListSnapshot = FeedbackTicketsListSnapshot(tickets = listOf(crash), nextCursor = null),
                        ).also { it.start() }
                    }
                val state by store.state.collectAsState()
                Host {
                    SettingsFeedbackTicketsScreen(
                        state = state,
                        timeContext = timeContext,
                        actions = SettingsFeedbackTicketsActions(),
                        replyComposer = { _, _, _, _, _, _ -> },
                    )
                }
            }
            waitForIdle()
            // Even before the network gate completes, cached tickets render with no full-screen Loading flash.
            onNodeWithText("Crash on launch").assertIsDisplayed()
            onAllNodesWithText("Loading feedback").assertCountEquals(0)
        }
    }

    @Test
    fun deepLinkedTicketSwipeBackBlocksHostPopLandsOnListAndThenReleasesHostPop() {
        val detail =
            FeedbackTicketDetail(
                ticket = crash,
                comments = listOf(FeedbackComment("c-1", FeedbackAuthorType.Staff, "Deep linked detail body", now - 1_000L)),
                attachments = emptyList(),
                nextCommentCursor = null,
                unreadTotal = 0,
            )
        runRobolectricComposeUiTest {
            val regions = RaftBackSwipeRegions()
            val repository = FakeRepository(list = pageOf(crash), details = mapOf(crash.id to detail))
            var storeRef: SettingsFeedbackTicketsStore? = null
            var hostPageBacks = 0
            var dispatcher: OnBackPressedDispatcher? = null
            setContent {
                val scope = rememberCoroutineScope()
                val store =
                    remember {
                        SettingsFeedbackTicketsStore(repository, scope).also {
                            it.start(initialTicketId = crash.id, loadList = true)
                            storeRef = it
                        }
                    }
                val state by store.state.collectAsState()
                Host {
                    CompositionLocalProvider(LocalRaftBackSwipeRegions provides regions) {
                        dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                        RaftBackHandler(enabled = true, label = "test_host_page", blocksBackSwipe = false) { hostPageBacks += 1 }
                        SettingsFeedbackTicketsScreen(
                            state = state,
                            timeContext = timeContext,
                            actions = SettingsFeedbackTicketsActions(onBack = store::closeTicketView),
                            replyComposer = { _, _, _, _, _, _ -> },
                            handlesDetailBackInPlace = true,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Deep linked detail body").assertIsDisplayed()
            assertTrue(
                regions.contentConsumesBackSwipeAt(20f, 300f),
                "in-place deep-linked ticket must block host back-swipe so swipe lands on Feedback list",
            )

            // Swiping right on the detail lands on the Feedback list instead of popping to the caller.
            onNodeWithText("Deep linked detail body").performTouchInput {
                swipeRight(startX = left + 24f, endX = right - 24f, durationMillis = 150L)
            }
            waitForIdle()
            assertNull(storeRef?.state?.value?.detail)
            onNodeWithText("Crash on launch").assertIsDisplayed()
            assertFalse(
                regions.contentConsumesBackSwipeAt(20f, 300f),
                "once back on the Feedback list, host back-swipe must be released",
            )

            // Next Back leaves Feedback back to the caller.
            runOnIdle { assertNotNull(dispatcher).onBackPressed() }
            waitForIdle()
            assertEquals(1, hostPageBacks)
        }
    }

    @Test
    fun pushedDetailModeDoesNotBlockHostBackSwipe() {
        val detail =
            FeedbackTicketDetail(
                ticket = crash,
                comments = listOf(FeedbackComment("c-1", FeedbackAuthorType.Staff, "Pushed detail body", now - 1_000L)),
                attachments = emptyList(),
                nextCommentCursor = null,
                unreadTotal = 0,
            )
        val detailState =
            SettingsFeedbackTicketsState(
                detail =
                    FeedbackTicketDetailState(
                        ticketId = crash.id,
                        summary = crash,
                        detail = detail,
                        loading = false,
                    ),
            )
        runRobolectricComposeUiTest {
            val regions = RaftBackSwipeRegions()
            setContent {
                Host {
                    CompositionLocalProvider(LocalRaftBackSwipeRegions provides regions) {
                        SettingsFeedbackTicketsScreen(
                            state = detailState,
                            timeContext = timeContext,
                            actions = SettingsFeedbackTicketsActions(),
                            replyComposer = { _, _, _, _, _, _ -> },
                            handlesDetailBackInPlace = false,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Pushed detail body").assertIsDisplayed()
            assertFalse(
                regions.contentConsumesBackSwipeAt(20f, 300f),
                "dedicated pushed detail page must leave native swipe-back enabled to pop to the list underneath",
            )
        }
    }

    @Test
    fun externallyFiledTicketReplacesFormWithInPlaceTicketPageAndDistinctReopenIdentity() {
        val scope = RaftShellStoreScope.create(shellInstanceId = "shell-1", initialServerId = "srv-1")
        val formJson = Json.parseToJsonElement(JSONObject().apply { put("pageName", SETTINGS_HANDS_FEEDBACK_PAGE) }.toString()).jsonObject
        val inPlaceTicketJson =
            Json.parseToJsonElement(
                settingsFeedbackTicketsPageData(
                    storeScope = scope,
                    ticketId = crash.id,
                    replaceCurrentPage = true,
                ).toString(),
            ).jsonObject
        val pushedDetailJson =
            Json.parseToJsonElement(
                settingsFeedbackTicketsPageData(
                    storeScope = scope,
                    ticketId = crash.id,
                    detailOnly = true,
                ).toString(),
            ).jsonObject

        // Caller -> New Feedback form -> filing replaces the form atomically with the in-place ticket page.
        val stack = RaftPageStack()
        stack.open(SETTINGS_HANDS_FEEDBACK_PAGE, formJson, from = stack.pages.last())
        assertEquals(2, stack.pages.size)

        stack.open(SETTINGS_FEEDBACK_TICKETS_PAGE, inPlaceTicketJson, from = stack.pages.last())
        assertEquals(2, stack.pages.size)
        assertEquals(SETTINGS_FEEDBACK_TICKETS_PAGE, stack.pages.last().key.pageName)
        assertEquals(crash.id, stack.pages.last().key.pageData[SETTINGS_FEEDBACK_TICKETS_TICKET_ID]?.toString()?.trim('"'))
        assertFalse(stack.pages.last().key.pageData.containsKey(SETTINGS_FEEDBACK_TICKETS_DETAIL_ONLY))

        // After Back lands on the list inside this page, tapping the same ticket pushes detailOnly cleanly.
        assertNotEquals(
            raftPageRouteIdentity(SETTINGS_FEEDBACK_TICKETS_PAGE, inPlaceTicketJson),
            raftPageRouteIdentity(SETTINGS_FEEDBACK_TICKETS_PAGE, pushedDetailJson),
        )
        stack.open(SETTINGS_FEEDBACK_TICKETS_PAGE, pushedDetailJson, from = stack.pages.last())
        assertEquals(3, stack.pages.size)
        assertTrue(stack.back())
        assertEquals(2, stack.pages.size)
        assertEquals(SETTINGS_FEEDBACK_TICKETS_PAGE, stack.pages.last().key.pageName)
    }

    private companion object {
        const val ReplyField = "feedback_reply_field"
    }
}

@Composable
private fun Host(content: @Composable () -> Unit) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
        RaftDesignSystemTheme { content() }
    }
}
