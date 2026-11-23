package build.raft.app.data

import build.raft.app.model.ThreadId

/**
 * Search in this thread (web `useThreadSearch`, botiverse/slock staging e4c09353f): the thread is
 * searched in the client, over the whole thread. The bar loads it by paging the thread's own
 * messages until there are none left:
 *
 *  - `GET messages/channel/{threadChannelId}?limit=100&before={oldest loaded seq}` while a page comes
 *    back full, then `…&after={newest loaded seq}` the same way (`packages/server/src/routes/messages.ts`
 *    `GET /channel/:channelId`: `limit` defaults to 50 and is capped at 200; `before` / `after` are
 *    a seq, never both; 400 `invalid_message_page_cursor` otherwise). The answer is
 *    `{ messages, threadSummariesByParentMessageId, historyLimited, hiddenHistory?, messageWindow }`,
 *    rows oldest first, each with its `senderName` (`messageService.listMessagesWithCoverage`).
 *  - Only rows of the thread channel itself count (web `onlyThreadMessages`: `channelId` equals the
 *    thread's).
 *
 * Rows decode through [parseSharedChannelMessages], the shared parser of this endpoint.
 */
internal const val THREAD_FIND_PAGE_LIMIT = 100

/** One message as the find bar searches it (web `ThreadSearchMessage`: id, content, senderName). */
internal data class ThreadFindMessage(
    val id: String,
    val seq: Long?,
    val senderName: String,
    val content: String,
)

/** One page of the thread for the find bar. */
internal sealed interface ThreadFindPageLoad {
    /**
     * The thread's rows of the page, and [rawCount] rows the server sent (paging compares this with
     * the limit, never the rows kept).
     */
    data class Page(val messages: List<ThreadFindMessage>, val rawCount: Int) : ThreadFindPageLoad

    /** The page could not be read; [error] carries the request's trace id. */
    data class Failed(val error: ScreenError) : ThreadFindPageLoad
}

/** The find bar's reads of the thread (a test hands answers directly). */
internal fun interface ThreadFindPageSource {
    suspend fun page(
        threadChannelId: String,
        beforeSeq: Long?,
        afterSeq: Long?,
        limit: Int,
        priority: DataRequestPriority,
    ): ThreadFindPageLoad
}

/** A `messages/channel/{threadChannelId}` page as the find bar reads it; a body it can't read is a failure. */
internal fun parseSharedThreadFindPage(bodyText: String, threadChannelId: String): ThreadFindPageLoad {
    val payload = parseSharedChannelMessages(bodyText, fallbackThreadId = ThreadId(threadChannelId))
    if (payload.decodeFailed || (payload.rawMessageCount > 0 && payload.messages.isEmpty())) {
        return ThreadFindPageLoad.Failed(ScreenError("thread_find_contract_mismatch", retryable = true))
    }
    return ThreadFindPageLoad.Page(
        messages =
            payload.messages
                .filter { it.channelId?.value == threadChannelId }
                .map { message ->
                    ThreadFindMessage(
                        id = message.id.value,
                        seq = message.seq,
                        senderName = message.author.displayName,
                        content = message.body,
                    )
                },
        rawCount = payload.rawMessageCount,
    )
}

internal class NetworkRaftThreadFindApi(
    private val networkClient: RaftNetworkClient,
) : ThreadFindPageSource {
    override suspend fun page(
        threadChannelId: String,
        beforeSeq: Long?,
        afterSeq: Long?,
        limit: Int,
        priority: DataRequestPriority,
    ): ThreadFindPageLoad {
        val channelId = threadChannelId.trim()
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = priority,
                    path = "messages/channel/$channelId",
                    query =
                        listOfNotNull(
                            "limit" to limit.toString(),
                            beforeSeq?.let { "before" to it.toString() },
                            afterSeq?.let { "after" to it.toString() },
                        ),
                )
            }.getOrElse { error ->
                return ThreadFindPageLoad.Failed(
                    ScreenError("thread_find_network", retryable = true, serverMessage = error.message, traceId = null)
                )
            }
        if (!response.isSuccessful) {
            val envelope = response.parseHttpErrorEnvelope()
            return ThreadFindPageLoad.Failed(
                ScreenError(
                    code = if (response.code in 500..599) "server_unavailable" else "thread_find_http_${response.code}",
                    retryable = response.code in 500..599 || response.code == 429,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        }
        return when (val load = parseSharedThreadFindPage(response.bodyText.orEmpty(), channelId)) {
            is ThreadFindPageLoad.Failed -> load.copy(error = load.error.copy(traceId = response.traceId))
            is ThreadFindPageLoad.Page -> load
        }
    }
}
