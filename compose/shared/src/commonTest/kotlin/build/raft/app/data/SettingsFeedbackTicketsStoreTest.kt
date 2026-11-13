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

        /** Each ticket call's operation and the ticket app it named (null: this app's). */
        val sources = mutableListOf<Pair<String, String?>>()

        override suspend fun listTickets(cursor: String?, limit: Int): FeedbackResult<FeedbackTicketListRead> {
            listCursors += cursor
            return listAnswers.removeFirst()
        }

        override suspend fun ticket(ticketId: String, commentCursor: String?, commentLimit: Int, source: String?): FeedbackResult<FeedbackTicketDetail> {
            ticketReads += ticketId to commentCursor
            sources += "detail" to source
            return ticketAnswers.removeFirst()
        }

        override suspend fun reply(ticketId: String, body: String, submissionId: String, source: String?): FeedbackResult<FeedbackComment> {
            replies += Triple(ticketId, body, submissionId)
            sources += "reply" to source
            return replyAnswers.removeFirst()
        }

        override suspend fun close(ticketId: String, reason: FeedbackReporterCloseReason, source: String?): FeedbackResult<Unit> {
            closes += ticketId to reason
            sources += "close" to source
            return closeAnswer
        }

        override suspend fun attachment(ticketId: String, attachmentId: String, source: String?): FeedbackResult<FeedbackAttachmentBytes> {
            sources += "attachment" to source
            return attachmentAnswer
        }
    }

    private fun page(vararg tickets: FeedbackTicket, next: String? = null): FeedbackResult<FeedbackTicketListRead> =
        FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(tickets.toList(), next, 0)))

    private fun detail(ticket: FeedbackTicket, vararg comments: FeedbackComment, next: String? = null): FeedbackResult<FeedbackTicketDetail> =
        FeedbackResult.Ok(FeedbackTicketDetail(ticket, comments.toList(), emptyList(), next, 0))

    private fun failed(kind: FeedbackErrorKind) = FeedbackResult.Failed(FeedbackFailure(kind, "x"))

    private fun TestScope.store(repository: FakeRepository, ids: Iterator<String> = generateSequence(1) { it + 1 }.map { "sub-$it" }.iterator()) =
        SettingsFeedbackTicketsStore(repository, backgroundScope, newSubmissionId = { ids.next() })

    @Test
    fun theAgentTipShowsUntilThisPersonSendsAnAgentsDraft() = runTest {
        // slock #14053 / #14076: the list's tip, gone once an agent-drafted card is sent (here or
        // from a thread while the list is open).
        val drafts = RaftFeedbackAgentDraftSentRepository { SyncTestPreferences() }
        val repository = FakeRepository()
        repository.listAnswers += page(ticket("a"))
        val store = SettingsFeedbackTicketsStore(repository, backgroundScope, agentDraftSent = drafts.sent("user-1"))
        assertFalse(store.state.value.showAgentHint)
        store.start()
        runCurrent()
        assertTrue(store.state.value.showAgentHint)

        drafts.markSent("user-2")
        runCurrent()
        assertTrue(store.state.value.showAgentHint)
        drafts.markSent("user-1")
        runCurrent()
        assertFalse(store.state.value.showAgentHint)

        // Someone who already sent one never sees it; nor does a list with no flag source.
        val sentStore = SettingsFeedbackTicketsStore(FakeRepository().also { it.listAnswers += page() }, backgroundScope, agentDraftSent = drafts.sent("user-1"))
        sentStore.start()
        runCurrent()
        assertFalse(sentStore.state.value.showAgentHint)
        val plain = store(FakeRepository().also { it.listAnswers += page() })
        plain.start()
        runCurrent()
        assertFalse(plain.state.value.showAgentHint)
    }

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
    fun theFirstPagesSummedTotalGoesToTheDotAndAnOpenedTicketAsksForTheSumAgain() = runTest {
        // slock #14722: `unread_total` sums every app on the first page only; a cursor page's is null
        // ("not known": keep the value). A ticket read answers only its own app's total, so the dot
        // reads the sum again instead of taking it.
        val repository = FakeRepository()
        val unread = ticket("a", unread = true, comments = 1)
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Degraded(5))
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(listOf(unread), "c2", unreadTotal = 3)))
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(listOf(ticket("b")), null, unreadTotal = null)))
        repository.ticketAnswers += FeedbackResult.Ok(FeedbackTicketDetail(unread.copy(unread = false, unreadCount = 0), emptyList(), emptyList(), null, unreadTotal = 0))
        repository.ticketAnswers += failed(FeedbackErrorKind.Unavailable)
        val totals = mutableListOf<Int?>()
        val ticketReads = mutableListOf<Pair<String?, Int?>>()
        val store =
            SettingsFeedbackTicketsStore(repository, backgroundScope, captureUnreadListRead = { { totals += it.total } }, onTicketRead = { source, total -> ticketReads += source to total })
        store.start()
        runCurrent()
        // A degraded page knows no total: nothing is handed on.
        assertEquals(emptyList<Int?>(), totals)
        advanceTimeBy(5_001)
        assertEquals(listOf<Int?>(3), totals)
        store.loadMore()
        runCurrent()
        assertEquals(listOf("a", "b"), store.state.value.list.tickets.map { it.id })
        assertEquals(listOf<Int?>(3), totals, "a cursor page's null total is not 0")

        store.openTicket("a")
        runCurrent()
        assertEquals(listOf<Int?>(3), totals, "a ticket's total is its app's, not the sum")
        // Its own app's total, for that app (no source named: this app's).
        assertEquals(listOf<Pair<String?, Int?>>(null to 0), ticketReads)
        // A failed read hands nothing on.
        store.refreshTicket()
        runCurrent()
        assertEquals(1, ticketReads.size)
    }

    @Test
    fun anEmptyPageWithANextCursorIsReadOnUntilTicketsOrTheEnd() = runTest {
        // slock #14722: a merged page may hold no tickets and still have a next cursor; the list ends
        // only at `next_cursor: null`.
        val repository = FakeRepository()
        // The first page's totals; the cursor pages carry none.
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(emptyList(), "c1", 3, mapOf("web" to 2, "ios" to 1))))
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(emptyList(), "c2", null)))
        repository.listAnswers += FeedbackResult.Ok(FeedbackTicketListRead.Page(FeedbackTicketPage(listOf(ticket("a")), "c3", null)))
        repository.listAnswers += page(next = "c4")
        repository.listAnswers += page(next = null)
        val totals = mutableListOf<FeedbackUnreadRead>()
        // Captured as each request goes out, before its answer (RaftFeedbackUnreadStore.captureListRead).
        val capturedBeforeRequest = mutableListOf<Int>()
        val store =
            SettingsFeedbackTicketsStore(
                repository,
                backgroundScope,
                captureUnreadListRead = {
                    capturedBeforeRequest += repository.listCursors.size
                    val take: (FeedbackUnreadRead) -> Unit = { totals += it }
                    take
                },
            )
        store.start()
        runCurrent()
        assertEquals(listOf(null, "c1", "c2"), repository.listCursors)
        assertEquals(listOf(FeedbackUnreadRead(3, mapOf("web" to 2, "ios" to 1))), totals, "the first page's totals are kept")
        assertEquals(listOf(0, 1, 2), capturedBeforeRequest)
        val list = store.state.value.list
        assertEquals(FeedbackTicketsListPhase.Ready, list.phase)
        assertEquals(listOf("a"), list.tickets.map { it.id })
        assertEquals("c3", list.nextCursor)

        // Load More past an empty page reaches the end, and the rows stay.
        store.loadMore()
        runCurrent()
        assertEquals(listOf(null, "c1", "c2", "c3", "c4"), repository.listCursors)
        assertEquals(listOf("a"), store.state.value.list.tickets.map { it.id })
        assertNull(store.state.value.list.nextCursor)
        assertFalse(store.state.value.list.loadingMore)
    }

    @Test
    fun emptyPagesAreFollowedOnlySoFarThenLoadMoreGoesOn() = runTest {
        val repository = FakeRepository()
        repeat(FEEDBACK_EMPTY_PAGE_FOLLOW_MAX + 1) { repository.listAnswers += page(next = "e$it") }
        val store = store(repository)
        store.start()
        runCurrent()
        assertEquals(FEEDBACK_EMPTY_PAGE_FOLLOW_MAX + 1, repository.listCursors.size)
        val list = store.state.value.list
        assertEquals(FeedbackTicketsListPhase.Ready, list.phase)
        assertTrue(list.tickets.isEmpty())
        assertEquals("e$FEEDBACK_EMPTY_PAGE_FOLLOW_MAX", list.nextCursor, "Load More stays offered")
    }

    @Test
    fun aTicketOfAnotherAppIsReadAnsweredClosedAndItsFilesOpenedInItsApp() = runTest {
        val repository = FakeRepository()
        val web = ticket("w", comments = 0).copy(source = "web", client = FeedbackClient.Desktop)
        repository.listAnswers += page(web, ticket("own"))
        repository.ticketAnswers += detail(web)
        repository.replyAnswers += FeedbackResult.Ok(FeedbackComment("r1", FeedbackAuthorType.Reporter, "Thanks", 5_000))
        repository.ticketAnswers += detail(web)
        repository.ticketAnswers += detail(web)
        repository.ticketAnswers += detail(ticket("own"))
        val store = store(repository)
        store.start()
        runCurrent()

        store.openTicket("w")
        runCurrent()
        store.setReplyDraft("Thanks")
        store.sendReply()
        runCurrent()
        store.closeTicket(FeedbackReporterCloseReason.Completed)
        runCurrent()
        store.openAttachment(FeedbackAttachment("f1", "a.png", "image/png", 1, 1)) {}
        runCurrent()
        assertEquals(
            listOf<Pair<String, String?>>("detail" to "web", "reply" to "web", "detail" to "web", "close" to "web", "detail" to "web", "attachment" to "web"),
            repository.sources,
        )

        // A ticket of this app (no source named) asks its own app.
        repository.sources.clear()
        store.closeTicketView()
        store.openTicket("own")
        runCurrent()
        assertEquals(listOf<Pair<String, String?>>("detail" to null), repository.sources)
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
