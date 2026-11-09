package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The web page's status tabs: All, Active (open / in progress), Ended (resolved / closed). */
enum class FeedbackTicketFilter { All, Active, Ended }

enum class FeedbackTicketsListPhase { Loading, Ready, Failed }

data class FeedbackTicketsListState(
    val phase: FeedbackTicketsListPhase = FeedbackTicketsListPhase.Loading,
    val tickets: List<FeedbackTicket> = emptyList(),
    val nextCursor: String? = null,
    val filter: FeedbackTicketFilter = FeedbackTicketFilter.All,
    /** A refresh of a list on screen (pull, resume, Retry over a list). */
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** Why the list could not be read: the whole page when [phase] is Failed, else a banner over the rows. */
    val error: FeedbackErrorKind? = null,
    /** A Retry of [error] is running. */
    val retrying: Boolean = false,
    /** The server answered "can't read right now"; the store is asking again (web onListDegradedChange). */
    val degraded: Boolean = false,
) {
    val visibleTickets: List<FeedbackTicket>
        get() =
            when (filter) {
                FeedbackTicketFilter.All -> tickets
                FeedbackTicketFilter.Active -> tickets.filter { it.status.isActive }
                FeedbackTicketFilter.Ended -> tickets.filter { !it.status.isActive }
            }

    /** Which empty state shows: none at all ("No feedback yet"), else the filter's. */
    val emptyKind: FeedbackTicketFilter get() = if (tickets.isEmpty()) FeedbackTicketFilter.All else filter
}

data class FeedbackTicketDetailState(
    val ticketId: String,
    /** The list row it was opened from, for the head while the first read runs. */
    val summary: FeedbackTicket? = null,
    val detail: FeedbackTicketDetail? = null,
    val loading: Boolean = true,
    val loadError: FeedbackErrorKind? = null,
    val retrying: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMoreReplies: Boolean = false,
    val replyDraft: String = "",
    val sending: Boolean = false,
    val replyError: FeedbackErrorKind? = null,
    val closing: Boolean = false,
    val closeError: FeedbackErrorKind? = null,
    /** An attachment is being read for the image viewer. */
    val openingAttachmentId: String? = null,
    val attachmentError: Boolean = false,
) {
    val ticket: FeedbackTicket? get() = detail?.ticket ?: summary

    /** The ticket can still be closed by the reporter (web `canClose`). */
    val canClose: Boolean get() = ticket?.let { it.status != FeedbackTicketStatus.Closed } == true

    val canSend: Boolean get() = replyDraft.isNotBlank() && !sending && replyDraft.trim().length <= FEEDBACK_REPLY_MAX_CHARS
}

data class SettingsFeedbackTicketsState(
    val list: FeedbackTicketsListState = FeedbackTicketsListState(),
    val detail: FeedbackTicketDetailState? = null,
    /** This server has no ticket routes: the page hands over to the web page. */
    val notSupported: Boolean = false,
)

/** An attachment read for the image viewer, handed to the page once. */
data class FeedbackOpenedAttachment(val attachment: FeedbackAttachment, val bytes: FeedbackAttachmentBytes)

/**
 * The native Settings › Feedback ticket list and ticket (the web page's FeedbackWorkspace, with
 * the same reads, rules and order): list with status tabs, pull to refresh, "Load More"; a ticket
 * with its thread, "Load More Replies", a reply, and close with a reason.
 */
class SettingsFeedbackTicketsStore(
    private val repository: SettingsFeedbackTicketsRepository,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val newSubmissionId: () -> String = ::newFeedbackSubmissionId,
    private val degradedRetryMaxMillis: Long = FEEDBACK_DEGRADED_RETRY_MAX_MILLIS,
) {
    private val mutableState = MutableStateFlow(SettingsFeedbackTicketsState())
    val state: StateFlow<SettingsFeedbackTicketsState> = mutableState.asStateFlow()

    private var started = false
    private var listJob: Job? = null
    private var listGeneration = 0
    private var detailJob: Job? = null
    private var detailGeneration = 0
    private var actionJob: Job? = null
    private var attachmentJob: Job? = null

    /** The reply's idempotency key, kept while the same text is retried (web stableFeedbackSubmission). */
    private var replySubmission: Pair<String, String>? = null

    /** Loads the list once; [initialTicketId] opens that ticket over it (a card's "View in Settings"). */
    fun start(initialTicketId: String? = null) {
        if (started) return
        started = true
        loadList(mode = ListLoad.Initial)
        initialTicketId?.trim()?.takeIf { it.isNotEmpty() }?.let { openTicket(it) }
    }

    /** Pull to refresh, the page reappearing: re-reads the first page (and the open ticket). */
    fun refresh() {
        if (mutableState.value.list.phase == FeedbackTicketsListPhase.Ready) {
            loadList(mode = ListLoad.Refresh)
        } else {
            loadList(mode = ListLoad.Initial)
        }
        if (mutableState.value.detail?.detail != null) refreshTicket()
    }

    fun retryList() {
        val list = mutableState.value.list
        loadList(mode = if (list.phase == FeedbackTicketsListPhase.Ready && list.loadingMoreFailed) ListLoad.More else ListLoad.Retry)
    }

    fun loadMore() {
        val list = mutableState.value.list
        if (list.phase != FeedbackTicketsListPhase.Ready || list.nextCursor == null || list.loadingMore || list.refreshing) return
        loadList(mode = ListLoad.More)
    }

    fun setFilter(filter: FeedbackTicketFilter) {
        mutableState.update { it.copy(list = it.list.copy(filter = filter)) }
    }

    private enum class ListLoad { Initial, Refresh, Retry, More }

    private var lastFailedLoadWasMore = false

    private val FeedbackTicketsListState.loadingMoreFailed: Boolean get() = error != null && lastFailedLoadWasMore

    private fun loadList(mode: ListLoad) {
        listJob?.cancel()
        val generation = ++listGeneration
        val cursor = if (mode == ListLoad.More) mutableState.value.list.nextCursor else null
        mutableState.update { state ->
            val list = state.list
            state.copy(
                list =
                    when (mode) {
                        ListLoad.Initial -> list.copy(phase = FeedbackTicketsListPhase.Loading, error = null, retrying = false)
                        ListLoad.Refresh -> list.copy(refreshing = true)
                        ListLoad.Retry ->
                            if (list.phase == FeedbackTicketsListPhase.Ready) list.copy(retrying = true, refreshing = true) else list.copy(retrying = true)
                        ListLoad.More -> list.copy(loadingMore = true, retrying = list.error != null)
                    }
            )
        }
        listJob =
            scope.launch {
                var retryMillis: Long? = null
                while (true) {
                    val result = repository.listTickets(cursor = cursor)
                    if (generation != listGeneration) return@launch
                    when (result) {
                        is FeedbackResult.Ok ->
                            when (val read = result.value) {
                                is FeedbackTicketListRead.Page -> {
                                    lastFailedLoadWasMore = false
                                    mutableState.update { state ->
                                        val list = state.list
                                        val tickets =
                                            if (cursor != null) mergeFeedbackTicketPages(list.tickets, read.page.tickets) else read.page.tickets
                                        state.copy(
                                            list =
                                                list.copy(
                                                    phase = FeedbackTicketsListPhase.Ready,
                                                    tickets = tickets.withReadTicket(state.detail),
                                                    nextCursor = read.page.nextCursor,
                                                    refreshing = false,
                                                    loadingMore = false,
                                                    error = null,
                                                    retrying = false,
                                                    degraded = false,
                                                )
                                        )
                                    }
                                    logger("settings_feedback_tickets_list_ready count=${read.page.tickets.size} more=${read.page.nextCursor != null}")
                                    return@launch
                                }
                                is FeedbackTicketListRead.Degraded -> {
                                    // Not "no feedback": keep loading (or the rows shown) and ask again,
                                    // from the server's Retry-After, doubling up to a minute (web).
                                    val wait =
                                        retryMillis?.let { minOf(it * 2, degradedRetryMaxMillis) }
                                            ?: minOf(read.retryAfterSeconds * 1_000L, degradedRetryMaxMillis)
                                    retryMillis = wait
                                    mutableState.update { it.copy(list = it.list.copy(degraded = true)) }
                                    logger("settings_feedback_tickets_list_degraded waitMs=$wait")
                                    delay(wait)
                                    if (generation != listGeneration) return@launch
                                }
                            }
                        is FeedbackResult.Failed -> {
                            val failure = result.failure
                            logger("settings_feedback_tickets_list_failed kind=${failure.kind} code=${failure.code}")
                            if (failure.kind == FeedbackErrorKind.NotSupported && mutableState.value.list.phase != FeedbackTicketsListPhase.Ready) {
                                mutableState.update { it.copy(notSupported = true) }
                                return@launch
                            }
                            lastFailedLoadWasMore = cursor != null
                            mutableState.update { state ->
                                val list = state.list
                                state.copy(
                                    list =
                                        list.copy(
                                            phase = if (list.phase == FeedbackTicketsListPhase.Ready) FeedbackTicketsListPhase.Ready else FeedbackTicketsListPhase.Failed,
                                            refreshing = false,
                                            loadingMore = false,
                                            retrying = false,
                                            degraded = false,
                                            error = failure.kind,
                                        )
                                )
                            }
                            return@launch
                        }
                    }
                }
            }
    }

    fun openTicket(ticketId: String) {
        val id = ticketId.trim().takeIf { it.isNotEmpty() } ?: return
        val summary = mutableState.value.list.tickets.firstOrNull { it.id == id }
        replySubmission = null
        actionJob?.cancel()
        attachmentJob?.cancel()
        mutableState.update { it.copy(detail = FeedbackTicketDetailState(ticketId = id, summary = summary)) }
        loadTicket(mode = TicketLoad.Initial)
    }

    /** Back to the list. */
    fun closeTicketView() {
        detailJob?.cancel()
        actionJob?.cancel()
        attachmentJob?.cancel()
        detailGeneration++
        replySubmission = null
        mutableState.update { it.copy(detail = null) }
    }

    fun refreshTicket() {
        if (mutableState.value.detail?.detail == null) return
        loadTicket(mode = TicketLoad.Refresh)
    }

    fun retryTicket() {
        val detail = mutableState.value.detail ?: return
        loadTicket(mode = if (detail.loadMoreRepliesFailed) TicketLoad.More else TicketLoad.Retry)
    }

    fun loadMoreReplies() {
        val detail = mutableState.value.detail ?: return
        if (detail.detail?.nextCommentCursor == null || detail.loadingMoreReplies) return
        loadTicket(mode = TicketLoad.More)
    }

    private enum class TicketLoad { Initial, Refresh, Retry, More }

    private var lastFailedTicketLoadWasMore = false

    private val FeedbackTicketDetailState.loadMoreRepliesFailed: Boolean get() = loadError != null && detail != null && lastFailedTicketLoadWasMore

    private fun loadTicket(mode: TicketLoad) {
        val current = mutableState.value.detail ?: return
        detailJob?.cancel()
        val generation = ++detailGeneration
        val ticketId = current.ticketId
        val cursor = if (mode == TicketLoad.More) current.detail?.nextCommentCursor else null
        updateDetail(ticketId) { detail ->
            when (mode) {
                TicketLoad.Initial -> detail.copy(loading = true, loadError = null)
                TicketLoad.Refresh -> detail.copy(refreshing = true)
                TicketLoad.Retry -> detail.copy(retrying = true, loading = detail.detail == null)
                TicketLoad.More -> detail.copy(loadingMoreReplies = true, retrying = detail.loadError != null)
            }
        }
        detailJob =
            scope.launch {
                val result = repository.ticket(ticketId, commentCursor = cursor)
                if (generation != detailGeneration) return@launch
                when (result) {
                    is FeedbackResult.Ok -> {
                        lastFailedTicketLoadWasMore = false
                        val read = result.value
                        mutableState.update { state ->
                            val detail = state.detail?.takeIf { it.ticketId == ticketId } ?: return@update state
                            val merged =
                                if (cursor != null && detail.detail != null) {
                                    read.copy(comments = mergeFeedbackComments(detail.detail.comments, read.comments))
                                } else {
                                    read
                                }
                            state.copy(
                                detail =
                                    detail.copy(
                                        detail = merged,
                                        loading = false,
                                        refreshing = false,
                                        retrying = false,
                                        loadingMoreReplies = false,
                                        loadError = null,
                                    ),
                                // The first page read the ticket (web onReadSuccess): its row is no
                                // longer unread, and shows the ticket as it is now.
                                list = state.list.copy(tickets = state.list.tickets.updatedWith(read.ticket, markRead = cursor == null)),
                            )
                        }
                        logger("settings_feedback_tickets_detail_ready comments=${read.comments.size} more=${read.nextCommentCursor != null}")
                    }
                    is FeedbackResult.Failed -> {
                        lastFailedTicketLoadWasMore = cursor != null
                        logger("settings_feedback_tickets_detail_failed kind=${result.failure.kind} code=${result.failure.code}")
                        updateDetail(ticketId) { detail ->
                            detail.copy(
                                loading = false,
                                refreshing = false,
                                retrying = false,
                                loadingMoreReplies = false,
                                loadError = result.failure.kind,
                            )
                        }
                    }
                }
            }
    }

    fun setReplyDraft(text: String) {
        updateDetail(mutableState.value.detail?.ticketId ?: return) { it.copy(replyDraft = text, replyError = null) }
    }

    fun sendReply() {
        val detail = mutableState.value.detail ?: return
        if (!detail.canSend || detail.closing || detail.detail == null) return
        val ticketId = detail.ticketId
        val body = detail.replyDraft.trim()
        val submission = replySubmission?.takeIf { it.first == "$ticketId\n$body" } ?: ("$ticketId\n$body" to newSubmissionId())
        replySubmission = submission
        updateDetail(ticketId) { it.copy(sending = true, replyError = null) }
        actionJob =
            scope.launch {
                val result = repository.reply(ticketId, body, submission.second)
                when (result) {
                    is FeedbackResult.Ok -> {
                        replySubmission = null
                        logger("settings_feedback_tickets_reply_sent")
                        mutableState.update { state ->
                            val current = state.detail?.takeIf { it.ticketId == ticketId } ?: return@update state
                            val read = current.detail
                            val next =
                                read?.copy(
                                    comments = mergeFeedbackComments(read.comments, listOf(result.value)),
                                    ticket = read.ticket.copy(commentCount = read.ticket.commentCount + 1, updatedAt = result.value.createdAt),
                                )
                            state.copy(
                                detail =
                                    current.copy(
                                        detail = next,
                                        sending = false,
                                        // A draft typed while it was sending stays.
                                        replyDraft = if (current.replyDraft.trim() == body) "" else current.replyDraft,
                                    ),
                                list = next?.let { state.list.copy(tickets = state.list.tickets.upserted(it.ticket, result.value)) } ?: state.list,
                            )
                        }
                        // Read it again as the web does after a reply (the server's own view of it).
                        loadTicket(mode = TicketLoad.Refresh)
                    }
                    is FeedbackResult.Failed -> {
                        logger("settings_feedback_tickets_reply_failed kind=${result.failure.kind} code=${result.failure.code}")
                        // The field cleared on send: the text comes back with the error.
                        updateDetail(ticketId) {
                            it.copy(
                                sending = false,
                                replyError = result.failure.kind,
                                replyDraft = if (it.replyDraft.isBlank()) body else it.replyDraft,
                            )
                        }
                    }
                }
            }
    }

    fun closeTicket(reason: FeedbackReporterCloseReason) {
        val detail = mutableState.value.detail ?: return
        if (!detail.canClose || detail.closing || detail.sending) return
        val ticketId = detail.ticketId
        updateDetail(ticketId) { it.copy(closing = true, closeError = null) }
        actionJob =
            scope.launch {
                when (val result = repository.close(ticketId, reason)) {
                    is FeedbackResult.Ok -> {
                        logger("settings_feedback_tickets_closed reason=${reason.wire}")
                        mutableState.update { state ->
                            val current = state.detail?.takeIf { it.ticketId == ticketId } ?: return@update state
                            val closed =
                                current.detail?.let { read ->
                                    read.copy(
                                        ticket =
                                            read.ticket.copy(
                                                status = FeedbackTicketStatus.Closed,
                                                closureReason =
                                                    if (reason == FeedbackReporterCloseReason.Completed) FeedbackClosureReason.Completed else FeedbackClosureReason.NoLongerNeeded,
                                            )
                                    )
                                }
                            state.copy(
                                detail = current.copy(detail = closed, closing = false),
                                list = closed?.let { state.list.copy(tickets = state.list.tickets.upserted(it.ticket, null)) } ?: state.list,
                            )
                        }
                        loadTicket(mode = TicketLoad.Refresh)
                    }
                    is FeedbackResult.Failed -> {
                        logger("settings_feedback_tickets_close_failed kind=${result.failure.kind} code=${result.failure.code}")
                        updateDetail(ticketId) { it.copy(closing = false, closeError = result.failure.kind) }
                    }
                }
            }
    }

    fun clearCloseError() {
        updateDetail(mutableState.value.detail?.ticketId ?: return) { it.copy(closeError = null) }
    }

    /** Reads [attachment] and hands it to [onOpened] (the image viewer); a failure says so on the page. */
    fun openAttachment(attachment: FeedbackAttachment, onOpened: (FeedbackOpenedAttachment) -> Unit) {
        val detail = mutableState.value.detail ?: return
        val ticketId = detail.ticketId
        attachmentJob?.cancel()
        updateDetail(ticketId) { it.copy(openingAttachmentId = attachment.id, attachmentError = false) }
        attachmentJob =
            scope.launch {
                val result = repository.attachment(ticketId, attachment.id)
                val stillOpen = mutableState.value.detail?.ticketId == ticketId
                when (result) {
                    is FeedbackResult.Ok -> {
                        updateDetail(ticketId) { it.copy(openingAttachmentId = null) }
                        if (stillOpen) onOpened(FeedbackOpenedAttachment(attachment, result.value))
                    }
                    is FeedbackResult.Failed -> {
                        logger("settings_feedback_tickets_attachment_failed kind=${result.failure.kind}")
                        updateDetail(ticketId) { it.copy(openingAttachmentId = null, attachmentError = true) }
                    }
                }
            }
    }

    fun dismissAttachmentError() {
        updateDetail(mutableState.value.detail?.ticketId ?: return) { it.copy(attachmentError = false) }
    }

    private fun updateDetail(ticketId: String, transform: (FeedbackTicketDetailState) -> FeedbackTicketDetailState) {
        mutableState.update { state ->
            val detail = state.detail?.takeIf { it.ticketId == ticketId } ?: return@update state
            state.copy(detail = transform(detail))
        }
    }
}

/** Web `DEGRADED_RETRY_MAX_MS`. */
const val FEEDBACK_DEGRADED_RETRY_MAX_MILLIS: Long = 60_000L

@OptIn(ExperimentalUuidApi::class)
private fun newFeedbackSubmissionId(): String = Uuid.random().toString()

/** The open ticket's row stays read while its page is open (a refresh may still report it unread). */
private fun List<FeedbackTicket>.withReadTicket(detail: FeedbackTicketDetailState?): List<FeedbackTicket> {
    val openId = detail?.takeIf { it.detail != null }?.ticketId ?: return this
    return map { if (it.id == openId) it.copy(unread = false, unreadCount = 0) else it }
}

/** [ticket]'s row as the detail read it, keeping the list-only latest comment. */
private fun List<FeedbackTicket>.updatedWith(ticket: FeedbackTicket, markRead: Boolean): List<FeedbackTicket> =
    map { row ->
        if (row.id != ticket.id) {
            row
        } else {
            ticket.copy(
                latestComment = ticket.latestComment ?: row.latestComment,
                unread = if (markRead) false else row.unread,
                unreadCount = if (markRead) 0 else row.unreadCount,
            )
        }
    }

/**
 * A ticket changed here (replied to, closed) moves to the top as on the web (FeedbackInbox
 * `upsertTicket`), with [reply] as its latest comment when there is one.
 */
private fun List<FeedbackTicket>.upserted(ticket: FeedbackTicket, reply: FeedbackComment?): List<FeedbackTicket> {
    val previous = firstOrNull { it.id == ticket.id }
    val latest =
        reply?.let { FeedbackLatestComment(it.authorType, it.body, it.createdAt) }
            ?: ticket.latestComment
            ?: previous?.latestComment
    val row = ticket.copy(latestComment = latest, unread = false, unreadCount = 0)
    return listOf(row) + filter { it.id != ticket.id }
}
