package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProductFeedbackState(
    val tickets: List<ProductFeedbackTicketSummary> = emptyList(),
    val nextCursor: String? = null,
    val selectedTicket: ProductFeedbackTicketDetail? = null,
    val unreadTotal: Int = 0,
    val loading: Boolean = false,
    val failure: ProductFeedbackFailure? = null,
    val pendingSubmission: ProductFeedbackPendingSubmission? = null
)

internal data class ProductFeedbackPendingSubmission(
    val ticketId: String?,
    val snapshot: ProductFeedbackSubmissionSnapshot
)

internal sealed interface ProductFeedbackIntent {
    data object Refresh : ProductFeedbackIntent
    data object LoadMore : ProductFeedbackIntent
    data class OpenTicket(val ticketId: String) : ProductFeedbackIntent
    data class LoadMoreComments(val ticketId: String) : ProductFeedbackIntent
    data class Create(val snapshot: ProductFeedbackSubmissionSnapshot) : ProductFeedbackIntent
    data class Reply(val ticketId: String, val snapshot: ProductFeedbackSubmissionSnapshot) : ProductFeedbackIntent
    data object RetryPending : ProductFeedbackIntent
    data object DismissFailure : ProductFeedbackIntent
}

internal class ProductFeedbackStore(
    private val repository: ProductFeedbackRepository
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ProductFeedbackState())
    val state: StateFlow<ProductFeedbackState> = mutableState.asStateFlow()

    suspend fun dispatch(intent: ProductFeedbackIntent) {
        mutex.withLock {
            when (intent) {
                ProductFeedbackIntent.Refresh -> loadTickets(cursor = null)
                ProductFeedbackIntent.LoadMore -> mutableState.value.nextCursor?.let { loadTickets(it) }
                is ProductFeedbackIntent.OpenTicket -> loadTicket(intent.ticketId, cursor = null)
                is ProductFeedbackIntent.LoadMoreComments -> {
                    val selected = mutableState.value.selectedTicket
                    if (selected?.ticket?.id == intent.ticketId) {
                        selected.nextCommentCursor?.let { loadTicket(intent.ticketId, it) }
                    }
                }
                is ProductFeedbackIntent.Create -> submitCreate(intent.snapshot)
                is ProductFeedbackIntent.Reply -> submitReply(intent.ticketId, intent.snapshot)
                ProductFeedbackIntent.RetryPending -> {
                    val pending = mutableState.value.pendingSubmission ?: return@withLock
                    if (pending.ticketId == null) {
                        submitCreate(pending.snapshot)
                    } else {
                        submitReply(pending.ticketId, pending.snapshot)
                    }
                }
                ProductFeedbackIntent.DismissFailure -> {
                    mutableState.value = mutableState.value.copy(failure = null)
                }
            }
        }
    }

    private suspend fun loadTickets(cursor: String?) {
        beginLoading()
        when (val result = repository.listTickets(cursor)) {
            is ProductFeedbackResult.Success -> {
                val page = result.value
                val tickets =
                    if (cursor == null) page.tickets
                    else (mutableState.value.tickets + page.tickets).distinctBy(ProductFeedbackTicketSummary::id)
                mutableState.value =
                    mutableState.value.copy(
                        tickets = tickets,
                        nextCursor = page.nextCursor,
                        unreadTotal = page.unreadTotal,
                        loading = false,
                        failure = null
                    )
            }
            is ProductFeedbackResult.Failure -> fail(result.error)
        }
    }

    private suspend fun loadTicket(ticketId: String, cursor: String?) {
        beginLoading()
        when (val result = repository.getTicket(ticketId, cursor)) {
            is ProductFeedbackResult.Success -> applyAuthoritativeDetail(result.value, appendComments = cursor != null)
            is ProductFeedbackResult.Failure -> fail(result.error)
        }
    }

    private suspend fun submitCreate(snapshot: ProductFeedbackSubmissionSnapshot) {
        beginSubmission(ticketId = null, snapshot = snapshot)
        when (val result = repository.create(snapshot)) {
            is ProductFeedbackResult.Success -> applyAuthoritativeDetail(result.value, appendComments = false)
            is ProductFeedbackResult.Failure -> failSubmission(result.error, ticketId = null, snapshot = snapshot)
        }
    }

    private suspend fun submitReply(ticketId: String, snapshot: ProductFeedbackSubmissionSnapshot) {
        beginSubmission(ticketId, snapshot)
        when (val result = repository.reply(ticketId, snapshot)) {
            is ProductFeedbackResult.Success -> applyAuthoritativeDetail(result.value, appendComments = false)
            is ProductFeedbackResult.Failure -> failSubmission(result.error, ticketId, snapshot)
        }
    }

    private fun applyAuthoritativeDetail(detail: ProductFeedbackTicketDetail, appendComments: Boolean) {
        val current = mutableState.value
        val selected = current.selectedTicket
        val mergedDetail =
            if (appendComments && selected?.ticket?.id == detail.ticket.id) {
                detail.copy(comments = (selected.comments + detail.comments).distinctBy(ProductFeedbackComment::id))
            } else {
                detail
            }
        mutableState.value =
            current.copy(
                tickets = current.tickets.replaceOrPrepend(detail.ticket),
                selectedTicket = mergedDetail,
                unreadTotal = detail.unreadTotal,
                loading = false,
                failure = null,
                pendingSubmission = null
            )
    }

    private fun beginLoading() {
        mutableState.value = mutableState.value.copy(loading = true, failure = null)
    }

    private fun beginSubmission(ticketId: String?, snapshot: ProductFeedbackSubmissionSnapshot) {
        mutableState.value =
            mutableState.value.copy(
                loading = true,
                failure = null,
                pendingSubmission = ProductFeedbackPendingSubmission(ticketId, snapshot)
            )
    }

    private fun fail(error: ProductFeedbackFailure) {
        mutableState.value = mutableState.value.copy(loading = false, failure = error)
    }

    private fun failSubmission(
        error: ProductFeedbackFailure,
        ticketId: String?,
        snapshot: ProductFeedbackSubmissionSnapshot
    ) {
        mutableState.value =
            mutableState.value.copy(
                loading = false,
                failure = error,
                pendingSubmission =
                    ProductFeedbackPendingSubmission(ticketId, snapshot).takeIf { error.retryable }
            )
    }
}

private fun List<ProductFeedbackTicketSummary>.replaceOrPrepend(
    ticket: ProductFeedbackTicketSummary
): List<ProductFeedbackTicketSummary> =
    listOf(ticket) + filterNot { it.id == ticket.id }
