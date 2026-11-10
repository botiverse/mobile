package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The native ticket list and ticket: the web FeedbackWorkspace's reads, rules and order. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFeedbackTicketsStoreTest {
    private fun ticket(
        id: String,
        status: FeedbackTicketStatus = FeedbackTicketStatus.Open,
        unread: Boolean = false,
        comments: Int = 0,
        createdAt: Long = 1_000,
    ) = FeedbackTicket(
        id = id,
        kind = FeedbackTicketKind.Bug,
        status = status,
        closureReason = null,
        message = "Ticket $id\nmore",
        createdAt = createdAt,
        updatedAt = createdAt,
        unread = unread,
        unreadCount = if (unread) 1 else 0,
        attachmentCount = 0,
        commentCount = comments,
    )

    private class FakeRepository : SettingsFeedbackTicketsRepository {
        val listAnswers = ArrayDeque<FeedbackResult<FeedbackTicketListRead>>()
        val ticketAnswers = ArrayDeque<FeedbackResult<FeedbackTicketDetail>>()
        val replyAnswers = ArrayDeque<FeedbackResult<FeedbackComment>>()
        var closeAnswer: FeedbackResult<Unit> = FeedbackResult.Ok(Unit)
        var attachmentAnswer: FeedbackResult<FeedbackAttachmentBytes> = FeedbackResult.Ok(FeedbackAttachmentBytes(byteArrayOf(9), "image/png"))
        val listCursors = mutableListOf<String?>()
        val ticketReads = mutableListOf<Pair<String, String?>>()
        val replies = mutableListOf<Triple<String, String, String>>()
        val closes = mutableListOf<Pair<String, FeedbackReporterCloseReason>>()

        override suspend fun listTickets(cursor: String?, limit: Int): FeedbackResult<FeedbackTicketListRead> {
            listCursors += cursor
            return listAnswers.removeFirst()
        }

        override suspend fun ticket(ticketId: String, commentCursor: String?, commentLimit: Int): FeedbackResult<FeedbackTicketDetail> {
            ticketReads += ticketId to commentCursor
            return ticketAnswers.removeFirst()
        }

        override suspend fun reply(ticketId: String, body: String, submissionId: String): FeedbackResult<FeedbackComment> {
            replies += Triple(ticketId, body, submissionId)
            return replyAnswers.removeFirst()
        }

        override suspend fun close(ticketId: String, reason: FeedbackReporterCloseReason): FeedbackResult<Unit> {
            closes += ticketId to reason
            return closeAnswer
        }

        override suspend fun attachment(ticketId: String, attachmentId: String): FeedbackResult<FeedbackAttachmentBytes> = attachmentAnswer
    }

    private fun page(vararg tickets: FeedbackTicket, next: String? = null): FeedbackResult<FeedbackTicketListRead> =
        FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(tickets.toList(), next, 0)))

    private fun detail(ticket: FeedbackTicket, vararg comments: FeedbackComment, next: String? = null): FeedbackResult<FeedbackTicketDetail> =
        FeedbackResult.Ok(FeedbackTicketDetail(ticket, comments.toList(), emptyList(), next, 0))

    private fun failed(kind: FeedbackErrorKind) = FeedbackResult.Failed(FeedbackFailure(kind, "x"))

    private fun TestScope.store(repository: FakeRepository, ids: Iterator<String> = generateSequence(1) { it + 1 }.map { "sub-$it" }.iterator()) =
        SettingsFeedbackTicketsStore(repository, backgroundScope, newSubmissionId = { ids.next() })

    @Test
    fun theListLoadsThenFiltersByActiveAndEnded() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += page(ticket("a"), ticket("b", FeedbackTicketStatus.InProgress), ticket("c", FeedbackTicketStatus.Resolved), ticket("d", FeedbackTicketStatus.Closed))
        val store = store(repository)
        store.start()
        assertEquals(FeedbackTicketsListPhase.Loading, store.state.value.list.phase)
        runCurrent()

        val list = store.state.value.list
        assertEquals(FeedbackTicketsListPhase.Ready, list.phase)
        assertEquals(listOf("a", "b", "c", "d"), list.visibleTickets.map { it.id })
        store.setFilter(FeedbackTicketFilter.Active)
        assertEquals(listOf("a", "b"), store.state.value.list.visibleTickets.map { it.id })
        store.setFilter(FeedbackTicketFilter.Ended)
        assertEquals(listOf("c", "d"), store.state.value.list.visibleTickets.map { it.id })
    }

    @Test
    fun noTicketsIsTheNoFeedbackEmptyStateWhateverTheFilter() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += page()
        val store = store(repository)
        store.start()
        runCurrent()
        store.setFilter(FeedbackTicketFilter.Ended)
        val list = store.state.value.list
        assertTrue(list.visibleTickets.isEmpty())
        assertEquals(FeedbackTicketFilter.All, list.emptyKind)
    }

    @Test
    fun aFailedFirstReadIsTheErrorStateAndRetryReadsAgain() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += failed(FeedbackErrorKind.Unavailable)
        repository.listAnswers += page(ticket("a"))
        val store = store(repository)
        store.start()
        runCurrent()
        assertEquals(FeedbackTicketsListPhase.Failed, store.state.value.list.phase)
        assertEquals(FeedbackErrorKind.Unavailable, store.state.value.list.error)

        store.retryList()
        runCurrent()
        assertEquals(FeedbackTicketsListPhase.Ready, store.state.value.list.phase)
        assertNull(store.state.value.list.error)
        assertEquals(listOf("a"), store.state.value.list.tickets.map { it.id })
    }

    @Test
    fun aDegradedFirstPageKeepsLoadingAndAsksAgainAfterTheServersWait() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Degraded(5))
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Degraded(5))
        repository.listAnswers += page(ticket("a"))
        val store = store(repository)
        store.start()
        runCurrent()
        assertEquals(FeedbackTicketsListPhase.Loading, store.state.value.list.phase)
        assertTrue(store.state.value.list.degraded)
        advanceTimeBy(5_001)
        assertEquals(2, repository.listCursors.size)
        // The second wait doubles (web): nothing at 5s more, the read at 10s.
        advanceTimeBy(5_000)
        assertEquals(2, repository.listCursors.size)
        advanceTimeBy(5_001)
        assertEquals(3, repository.listCursors.size)
        assertEquals(FeedbackTicketsListPhase.Ready, store.state.value.list.phase)
        assertFalse(store.state.value.list.degraded)
    }

    @Test
    fun aServerWithoutTheTicketRoutesShowsTheErrorWithRetryAndItsTraceId() = runTest {
        val repository = FakeRepository()
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        repository.listAnswers += FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.NotSupported, "feedback_http_404", 404, traceId = traceId))
        repository.listAnswers += page(ticket("a"))
        val store = store(repository)
        store.start()
        runCurrent()
        // No web page to hand over to: the native error state, with the failed read's trace id.
        val failed = store.state.value.list
        assertEquals(FeedbackTicketsListPhase.Failed, failed.phase)
        assertEquals(FeedbackErrorKind.NotSupported, failed.error)
        assertEquals(traceId, failed.errorTraceId)
        store.retryList()
        runCurrent()
        val ready = store.state.value.list
        assertEquals(FeedbackTicketsListPhase.Ready, ready.phase)
        assertEquals(listOf("a"), ready.tickets.map { it.id })
        assertNull(ready.error)
        assertNull(ready.errorTraceId)
    }

    @Test
    fun loadMoreAppendsTheNextPageAndARefreshKeepsTheRowsOnFailure() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += page(ticket("a"), next = "c2")
        repository.listAnswers += page(ticket("b"))
        repository.listAnswers += failed(FeedbackErrorKind.Unavailable)
        val store = store(repository)
        store.start()
        runCurrent()
        store.loadMore()
        runCurrent()
        assertEquals(listOf(null, "c2"), repository.listCursors)
        assertEquals(listOf("a", "b"), store.state.value.list.tickets.map { it.id })
        assertNull(store.state.value.list.nextCursor)

        store.refresh()
        assertTrue(store.state.value.list.refreshing)
        runCurrent()
        val list = store.state.value.list
        assertEquals(FeedbackTicketsListPhase.Ready, list.phase)
        assertEquals(FeedbackErrorKind.Unavailable, list.error)
        assertFalse(list.refreshing)
        assertEquals(listOf("a", "b"), list.tickets.map { it.id })
    }

    @Test
    fun openingATicketReadsItAndItsRowIsNoLongerUnread() = runTest {
        val repository = FakeRepository()
        val unread = ticket("a", unread = true, comments = 1)
        repository.listAnswers += page(unread, ticket("b"))
        repository.ticketAnswers += detail(unread.copy(unread = false, unreadCount = 0), FeedbackComment("c1", FeedbackAuthorType.Staff, "Hi", 2_000))
        val store = store(repository)
        store.start()
        runCurrent()

        store.openTicket("a")
        val opening = assertNotNull(store.state.value.detail)
        assertEquals("Ticket a", opening.ticket?.title)
        assertTrue(opening.loading)
        runCurrent()

        val open = assertNotNull(store.state.value.detail)
        assertFalse(open.loading)
        assertEquals(listOf("c1"), open.detail?.comments?.map { it.id })
        assertFalse(store.state.value.list.tickets.first { it.id == "a" }.unread)
        store.closeTicketView()
        assertNull(store.state.value.detail)
    }

    @Test
    fun startingOnATicketOpensItOverTheList() = runTest {
        val repository = FakeRepository()
        repository.listAnswers += page(ticket("a"))
        repository.ticketAnswers += detail(ticket("a"))
        val store = store(repository)
        store.start(initialTicketId = "a")
        runCurrent()
        assertEquals("a", store.state.value.detail?.ticketId)
        assertEquals(listOf<Pair<String, String?>>("a" to null), repository.ticketReads)
    }

    @Test
    fun loadMoreRepliesMergesTheNextCommentPage() = runTest {
        val repository = FakeRepository()
        val t = ticket("a")
        repository.listAnswers += page(t)
        repository.ticketAnswers += detail(t, FeedbackComment("c1", FeedbackAuthorType.Staff, "1", 2_000), next = "n1")
        repository.ticketAnswers += detail(t, FeedbackComment("c2", FeedbackAuthorType.Reporter, "2", 3_000))
        val store = store(repository)
        store.start("a")
        runCurrent()
        store.loadMoreReplies()
        runCurrent()
        assertEquals(listOf("a" to null, "a" to "n1"), repository.ticketReads)
        assertEquals(listOf("c1", "c2"), store.state.value.detail?.detail?.comments?.map { it.id })
        assertNull(store.state.value.detail?.detail?.nextCommentCursor)
    }

    @Test
    fun aReplyIsSentAddedToTheThreadAndTheTicketMovesToTheTop() = runTest {
        val repository = FakeRepository()
        val a = ticket("a")
        val b = ticket("b")
        repository.listAnswers += page(a, b)
        repository.ticketAnswers += detail(b)
        val reply = FeedbackComment("c-new", FeedbackAuthorType.Reporter, "Still broken", 9_000)
        repository.replyAnswers += FeedbackResult.Ok(reply)
        repository.ticketAnswers += detail(b.copy(commentCount = 1), reply)
        val store = store(repository)
        store.start("b")
        runCurrent()

        store.setReplyDraft("  Still broken ")
        assertTrue(store.state.value.detail!!.canSend)
        store.sendReply()
        assertTrue(store.state.value.detail!!.sending)
        runCurrent()

        assertEquals(listOf(Triple("b", "Still broken", "sub-1")), repository.replies)
        val open = store.state.value.detail!!
        assertFalse(open.sending)
        assertEquals("", open.replyDraft)
        assertEquals(listOf("c-new"), open.detail?.comments?.map { it.id })
        assertEquals(listOf("b", "a"), store.state.value.list.tickets.map { it.id })
        assertEquals("Still broken", store.state.value.list.tickets.first().latestComment?.bodyPreview)
        // Read again after the reply, as the web does.
        assertEquals(listOf<Pair<String, String?>>("b" to null, "b" to null), repository.ticketReads)
    }

    @Test
    fun aFailedReplyKeepsItsTextAndRetriesWithTheSameSubmissionId() = runTest {
        val repository = FakeRepository()
        val a = ticket("a")
        repository.listAnswers += page(a)
        repository.ticketAnswers += detail(a)
        repository.replyAnswers += failed(FeedbackErrorKind.Unavailable)
        repository.replyAnswers += FeedbackResult.Ok(FeedbackComment("c1", FeedbackAuthorType.Reporter, "Hello", 5_000))
        repository.ticketAnswers += detail(a)
        val store = store(repository)
        store.start("a")
        runCurrent()

        store.setReplyDraft("Hello")
        store.sendReply()
        // The field cleared itself on send.
        store.setReplyDraft("")
        runCurrent()
        val failedState = store.state.value.detail!!
        assertEquals(FeedbackErrorKind.Unavailable, failedState.replyError)
        assertEquals("Hello", failedState.replyDraft)

        store.sendReply()
        runCurrent()
        assertEquals(listOf("sub-1", "sub-1"), repository.replies.map { it.third })
        assertNull(store.state.value.detail!!.replyError)
    }

    @Test
    fun closingMarksTheTicketClosedWithItsReason() = runTest {
        val repository = FakeRepository()
        val a = ticket("a", FeedbackTicketStatus.Resolved)
        repository.listAnswers += page(a)
        repository.ticketAnswers += detail(a)
        repository.ticketAnswers += detail(a.copy(status = FeedbackTicketStatus.Closed, closureReason = FeedbackClosureReason.Completed))
        val store = store(repository)
        store.start("a")
        runCurrent()
        assertTrue(store.state.value.detail!!.canClose)

        store.closeTicket(FeedbackReporterCloseReason.Completed)
        assertTrue(store.state.value.detail!!.closing)
        runCurrent()

        assertEquals(listOf("a" to FeedbackReporterCloseReason.Completed), repository.closes)
        val closed = store.state.value.detail!!
        assertFalse(closed.closing)
        assertEquals(FeedbackTicketStatus.Closed, closed.ticket?.status)
        assertFalse(closed.canClose)
        assertEquals(FeedbackTicketStatus.Resolved, store.state.value.list.tickets.single().displayStatus)
    }

    @Test
    fun aFailedCloseSaysWhyAndKeepsTheTicketOpen() = runTest {
        val repository = FakeRepository()
        val a = ticket("a")
        repository.listAnswers += page(a)
        repository.ticketAnswers += detail(a)
        repository.closeAnswer = failed(FeedbackErrorKind.RateLimited)
        val store = store(repository)
        store.start("a")
        runCurrent()
        store.closeTicket(FeedbackReporterCloseReason.NoLongerNeeded)
        runCurrent()
        val open = store.state.value.detail!!
        assertEquals(FeedbackErrorKind.RateLimited, open.closeError)
        assertEquals(FeedbackTicketStatus.Open, open.ticket?.status)
        store.clearCloseError()
        assertNull(store.state.value.detail!!.closeError)
    }

    @Test
    fun anAttachmentIsReadForTheViewerOrSaysItCouldNotOpen() = runTest {
        val repository = FakeRepository()
        val a = ticket("a")
        repository.listAnswers += page(a)
        repository.ticketAnswers += detail(a)
        val store = store(repository)
        store.start("a")
        runCurrent()
        val attachment = FeedbackAttachment("f1", "shot.png", "image/png", 10, 0)
        val opened = mutableListOf<FeedbackOpenedAttachment>()
        store.openAttachment(attachment) { opened += it }
        assertEquals("f1", store.state.value.detail!!.openingAttachmentId)
        runCurrent()
        assertEquals("shot.png", opened.single().attachment.filename)
        assertNull(store.state.value.detail!!.openingAttachmentId)

        repository.attachmentAnswer = failed(FeedbackErrorKind.Unavailable)
        store.openAttachment(attachment) { opened += it }
        runCurrent()
        assertEquals(1, opened.size)
        assertTrue(store.state.value.detail!!.attachmentError)
    }
}
