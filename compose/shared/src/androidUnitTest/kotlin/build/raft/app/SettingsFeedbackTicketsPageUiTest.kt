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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.FeedbackAttachment
import build.raft.app.data.FeedbackAttachmentBytes
import build.raft.app.data.FeedbackAuthorType
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
import build.raft.app.data.SettingsFeedbackTicketsRepository
import build.raft.app.data.SettingsFeedbackTicketsStore
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftTextarea
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private val idea =
        FeedbackTicket(
            id = "22222222-2222-4333-8444-555555555555",
            kind = FeedbackTicketKind.Feedback,
            status = FeedbackTicketStatus.Resolved,
            closureReason = null,
            message = "Dark mode for the composer",
            createdAt = now - 3 * 86_400_000L,
            updatedAt = now - 2 * 86_400_000L,
            unread = false,
            unreadCount = 0,
            attachmentCount = 0,
            commentCount = 0,
        )

    private class FakeRepository(
        var list: FeedbackResult<FeedbackTicketListRead>,
        val details: Map<String, FeedbackTicketDetail> = emptyMap(),
    ) : SettingsFeedbackTicketsRepository {
        val replies = mutableListOf<Pair<String, String>>()
        val extraComments = mutableListOf<FeedbackComment>()

        override suspend fun listTickets(cursor: String?, limit: Int) = list

        override suspend fun ticket(ticketId: String, commentCursor: String?, commentLimit: Int): FeedbackResult<FeedbackTicketDetail> {
            val detail = details.getValue(ticketId)
            return FeedbackResult.Ok(detail.copy(comments = detail.comments + extraComments))
        }

        override suspend fun reply(ticketId: String, body: String, submissionId: String): FeedbackResult<FeedbackComment> {
            replies += ticketId to body
            val comment = FeedbackComment("reply-${replies.size}", FeedbackAuthorType.Reporter, body, 1_762_700_060_000L)
            extraComments += comment
            return FeedbackResult.Ok(comment)
        }

        override suspend fun close(ticketId: String, reason: FeedbackReporterCloseReason) = FeedbackResult.Ok(Unit)

        override suspend fun attachment(ticketId: String, attachmentId: String): FeedbackResult<FeedbackAttachmentBytes> =
            FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Unavailable, "x"))
    }

    private fun pageOf(vararg tickets: FeedbackTicket): FeedbackResult<FeedbackTicketListRead> =
        FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(tickets.toList(), null, 1)))

    private val crashDetail =
        FeedbackTicketDetail(
            ticket = crash.copy(latestComment = null),
            comments = listOf(FeedbackComment("c1", FeedbackAuthorType.Staff, "Looking into it", now - 600_000L)),
            attachments = listOf(FeedbackAttachment("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "launch.png", "image/png", 2048, now - 3_600_000L)),
            nextCommentCursor = null,
            unreadTotal = 0,
        )

    @Composable
    private fun Page(
        repository: FakeRepository,
        onNewFeedback: () -> Unit = {},
        onCopyTicketLink: ((String) -> Unit)? = null,
        agentDraftSent: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    ) {
        val scope = rememberCoroutineScope()
        val store = remember { SettingsFeedbackTicketsStore(repository, scope, agentDraftSent = agentDraftSent).also { it.start() } }
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
                        onFilter = store::setFilter,
                        onOpenTicket = store::openTicket,
                        onReplyChange = store::setReplyDraft,
                        onSendReply = store::sendReply,
                        onCopyTicketLink = onCopyTicketLink,
                    ),
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

    @Test
    fun theListShowsEachTicketsStatusLatestReplyAndNewCount() =
        runRobolectricComposeUiTest {
            val repository = FakeRepository(pageOf(crash, idea))
            setContent { Page(repository) }
            waitForIdle()

            onNodeWithText("Feedback").assertIsDisplayed()
            onNodeWithText("New Feedback").assertIsDisplayed()
            onNodeWithText("All").assertIsDisplayed()
            onNodeWithText("Crash on launch").assertIsDisplayed()
            onNodeWithText("Team: Looking into it").assertIsDisplayed()
            onNodeWithText("In progress").assertIsDisplayed()
            onNodeWithText("1 reply").assertIsDisplayed()
            onNodeWithText("1 new").assertIsDisplayed()
            onNodeWithText("14:43").assertIsDisplayed()
            onNodeWithText("Dark mode for the composer").assertIsDisplayed()
            onNodeWithText("Resolved").assertIsDisplayed()
            onNodeWithText("No replies yet").assertIsDisplayed()

            // Active hides the resolved one.
            onNodeWithText("Active").performClick()
            waitForIdle()
            onNodeWithText("Dark mode for the composer").assertDoesNotExist()
            onNodeWithText("Crash on launch").assertIsDisplayed()
        }

    @Test
    fun theAgentTipLeadsTheListUntilAnAgentsDraftIsSentAndNeverOnATicket() =
        runRobolectricComposeUiTest {
            // slock #14053 / #14076: one info line above New Feedback, no button, no ×.
            val drafts = build.raft.app.data.RaftFeedbackAgentDraftSentRepository { null }
            val repository = FakeRepository(pageOf(crash), mapOf(crash.id to crashDetail))
            setContent { Page(repository, agentDraftSent = drafts.sent("user-1")) }
            waitForIdle()
            onNodeWithTag(SettingsFeedbackAgentHintTag).assertIsDisplayed()
            onNodeWithText("Did you know? Any agent can draft your feedback. Just ask, and you’ll review it before it’s sent.").assertIsDisplayed()
            onNode(hasContentDescription("Dismiss")).assertDoesNotExist()

            // Not on a ticket.
            onNodeWithText("Crash on launch").performClick()
            waitForIdle()
            onNodeWithTag(SettingsFeedbackAgentHintTag).assertDoesNotExist()
            onNode(hasContentDescription("Back")).performClick()
            waitForIdle()
            onNodeWithTag(SettingsFeedbackAgentHintTag).assertIsDisplayed()

            // An agent's draft sent (from a thread, while the list is open): gone for good.
            runOnIdle { drafts.markSent("user-1") }
            waitForIdle()
            onNodeWithTag(SettingsFeedbackAgentHintTag).assertDoesNotExist()
            onNodeWithText("New Feedback").assertIsDisplayed()
        }

    @Test
    fun noTicketsIsTheEmptyStateAndNewFeedbackOpensTheForm() =
        runRobolectricComposeUiTest {
            var opened = 0
            val repository = FakeRepository(pageOf())
            setContent { Page(repository, onNewFeedback = { opened += 1 }) }
            waitForIdle()
            onNodeWithText("No feedback yet").assertIsDisplayed()
            onNodeWithText("Share an idea or report a bug — team replies will show up here.").assertIsDisplayed()
            onNodeWithText("All").assertDoesNotExist()
            onNodeWithText("New Feedback").performClick()
            runOnIdle { assertEquals(1, opened) }
        }

    @Test
    fun aFailedListSaysSoAndTryAgainReadsItAgain() =
        runRobolectricComposeUiTest {
            val repository = FakeRepository(FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Unavailable, "feedback_service_unavailable")))
            setContent { Page(repository) }
            waitForIdle()
            onNodeWithText("Couldn’t load feedback. Try again.").assertIsDisplayed()
            repository.list = pageOf(idea)
            onNodeWithText("Try Again").performClick()
            waitForIdle()
            onNodeWithText("Dark mode for the composer").assertIsDisplayed()
        }

    @Test
    fun aTicketOpensOnItsThreadWithItsAttachment() =
        runRobolectricComposeUiTest {
            val repository = FakeRepository(pageOf(crash, idea), mapOf(crash.id to crashDetail))
            setContent { Page(repository) }
            waitForIdle()
            onNodeWithText("Crash on launch").performClick()
            waitForIdle()

            onNodeWithText("Bug").assertIsDisplayed()
            onNodeWithText("In progress").assertIsDisplayed()
            onNodeWithTag(SettingsFeedbackTicketThreadTag).performScrollToNode(hasText("Steps: open the app", substring = true))
            onNodeWithText("You").assertExists()
            onNode(hasContentDescription("Open attachment launch.png")).assertExists()
            onNodeWithTag(SettingsFeedbackTicketThreadTag).performScrollToNode(hasText("Looking into it"))
            onNodeWithText("Team").assertExists()
            // The list is behind it; Back returns to it with the row read.
            onNode(hasContentDescription("Back")).performClick()
            waitForIdle()
            onNodeWithText("New Feedback").assertIsDisplayed()
            onNodeWithText("1 new").assertDoesNotExist()
        }

    @Test
    fun aReplySentFromTheTicketJoinsItsThread() =
        runRobolectricComposeUiTest {
            val repository = FakeRepository(pageOf(crash), mapOf(crash.id to crashDetail))
            setContent { Page(repository) }
            waitForIdle()
            onNodeWithText("Crash on launch").performClick()
            waitForIdle()

            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ReplyField))).performTextInput("It still crashes on 1.22.1")
            onNodeWithText("Send").performClick()
            waitForIdle()

            runOnIdle { assertEquals(listOf(crash.id to "It still crashes on 1.22.1"), repository.replies) }
            onNodeWithTag(SettingsFeedbackTicketThreadTag).performScrollToNode(hasText("It still crashes on 1.22.1"))
            onNodeWithText("It still crashes on 1.22.1").assertExists()
        }

    @Test
    fun copyLinkInTheTicketsHeadCopiesThatTicket() =
        runRobolectricComposeUiTest {
            val copied = mutableListOf<String>()
            val repository = FakeRepository(pageOf(crash), mapOf(crash.id to crashDetail))
            setContent { Page(repository, onCopyTicketLink = { copied += it }) }
            waitForIdle()
            // Not on the list: only an open ticket has a link.
            onNode(hasContentDescription("Copy Link")).assertDoesNotExist()
            onNodeWithText("Crash on launch").performClick()
            waitForIdle()

            onNode(hasContentDescription("Close Ticket")).assertIsDisplayed()
            onNode(hasContentDescription("Copy Link")).assertIsDisplayed().performClick()
            runOnIdle { assertEquals(listOf(crash.id), copied) }
        }

    @Test
    fun noCopyLinkWhereNoHandsAppReadsTheTicket() =
        runRobolectricComposeUiTest {
            val repository = FakeRepository(pageOf(crash), mapOf(crash.id to crashDetail))
            setContent { Page(repository) }
            waitForIdle()
            onNodeWithText("Crash on launch").performClick()
            waitForIdle()
            onNode(hasContentDescription("Close Ticket")).assertIsDisplayed()
            onNode(hasContentDescription("Copy Link")).assertDoesNotExist()
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
