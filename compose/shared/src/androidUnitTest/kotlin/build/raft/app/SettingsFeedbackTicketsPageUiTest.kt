package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import build.raft.app.data.FeedbackTicketKind
import build.raft.app.data.FeedbackTicketListRead
import build.raft.app.data.FeedbackTicketPage
import build.raft.app.data.FeedbackTicketStatus
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.SettingsFeedbackTicketsRepository
import build.raft.app.data.SettingsFeedbackTicketsStore
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftTextarea
import kotlin.test.Test
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
    ) : SettingsFeedbackTicketsRepository {
        val replies = mutableListOf<Pair<String, String>>()
        val extraComments = mutableListOf<FeedbackComment>()

        override suspend fun listTickets(cursor: String?, limit: Int) = list

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
