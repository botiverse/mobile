package build.raft.app

import build.raft.app.data.FeedbackAttachmentBytes
import build.raft.app.data.FeedbackComment
import build.raft.app.data.FeedbackReporterCloseReason
import build.raft.app.data.FeedbackResult
import build.raft.app.data.FeedbackTicket
import build.raft.app.data.FeedbackTicketDetail
import build.raft.app.data.FeedbackTicketKind
import build.raft.app.data.FeedbackTicketListRead
import build.raft.app.data.FeedbackTicketPage
import build.raft.app.data.FeedbackTicketStatus
import build.raft.app.data.FeedbackTicketsListPhase
import build.raft.app.data.SettingsFeedbackTicketsRepository
import build.raft.app.data.SettingsFeedbackTicketsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Hands 464d47ae (iOS 1.23): feedback filed from Settings › Feedback › New Feedback was missing
 * from the list until a manual refresh. The list read it in only when its page showed again, and
 * on iOS the list page, covered by the form, never got that reappear back. The list now follows the
 * form's filed tickets directly: here the page never reappears at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFeedbackFiledTicketHandoffTest {
    private fun ticket(id: String) =
        FeedbackTicket(
            id = id,
            kind = FeedbackTicketKind.Bug,
            status = FeedbackTicketStatus.Open,
            closureReason = null,
            message = "Ticket $id",
            createdAt = 1_000,
            updatedAt = 1_000,
            unread = false,
            unreadCount = 0,
            attachmentCount = 0,
            commentCount = 0,
        )

    private fun page(vararg tickets: FeedbackTicket): FeedbackResult<FeedbackTicketListRead> =
        FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(tickets.toList(), null, 0)))

    private class FakeRepository : SettingsFeedbackTicketsRepository {
        val listAnswers = ArrayDeque<FeedbackResult<FeedbackTicketListRead>>()
        val ticketAnswers = ArrayDeque<FeedbackResult<FeedbackTicketDetail>>()
        var listReads = 0

        override suspend fun listTickets(cursor: String?, limit: Int): FeedbackResult<FeedbackTicketListRead> {
            listReads += 1
            return listAnswers.removeFirst()
        }

        override suspend fun ticket(ticketId: String, commentCursor: String?, commentLimit: Int): FeedbackResult<FeedbackTicketDetail> =
            ticketAnswers.removeFirst()

        override suspend fun reply(ticketId: String, body: String, submissionId: String): FeedbackResult<FeedbackComment> =
            error("not used")

        override suspend fun close(ticketId: String, reason: FeedbackReporterCloseReason): FeedbackResult<Unit> = error("not used")

        override suspend fun attachment(ticketId: String, attachmentId: String): FeedbackResult<FeedbackAttachmentBytes> =
            error("not used")
    }

    @BeforeTest
    @AfterTest
    fun clearHandoff() {
        SettingsFeedbackFiledTicketHandoff.take()
    }

    @Test
    fun aTicketFiledFromTheFormIsInTheListAndOpenWithoutThePageShowingAgain() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += page(ticket("old"))
        val store = SettingsFeedbackTicketsStore(repository, backgroundScope)
        store.start()
        backgroundScope.launch { store.followSettingsFeedbackFiledTickets() }
        runCurrent()
        assertEquals(listOf("old"), store.state.value.list.tickets.map { it.id })
        assertEquals(1, repository.listReads)

        // The form files a ticket and closes; the list page is never told it shows again.
        repository.listAnswers += page(ticket("new"), ticket("old"))
        repository.ticketAnswers += FeedbackResult.Ok(FeedbackTicketDetail(ticket("new"), emptyList(), emptyList(), null, 0))
        SettingsFeedbackFiledTicketHandoff.offer("new")
        runCurrent()

        val state = store.state.value
        assertEquals(FeedbackTicketsListPhase.Ready, state.list.phase)
        assertEquals(listOf("new", "old"), state.list.tickets.map { it.id })
        assertEquals("new", state.detail?.ticketId)
        assertEquals(2, repository.listReads)
        // Taken once: nothing is left for a later appearance to open again.
        assertNull(SettingsFeedbackFiledTicketHandoff.offered.value)
    }

    @Test
    fun nothingFiledReadsTheListOnce() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += page(ticket("old"))
        val store = SettingsFeedbackTicketsStore(repository, backgroundScope)
        store.start()
        backgroundScope.launch { store.followSettingsFeedbackFiledTickets() }
        runCurrent()

        assertEquals(1, repository.listReads)
        assertNull(store.state.value.detail)
    }
}
