package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val threadOutlineJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * The conversation outline (消息时间线) of a thread: what people themselves wrote in it, plus the
 * message it started from, whoever wrote it.
 *
 * Server contract (botiverse/slock #13534, a9bf79d66; `packages/server/src/routes/messages.ts`
 * `GET /channel/:channelId/human-outline`, `services/messageService.ts`
 * `listHumanOutlineMessages`):
 *  - `GET messages/channel/{channelId}/human-outline?limit=&before=` (limit 1..500, default 200;
 *    `before` a seq) → `{ items: [{ id, seq, createdAt, senderId, senderName, preview,
 *    attachmentCount }], hasMore }`, newest first. Only `chat` rows from `user` senders that no
 *    assistant sent for them (`delegated_by_agent_id`), not retracted, inside the plan's history
 *    window. On the page that reaches the thread's start (`hasMore` false) the thread's parent
 *    message comes last, whoever wrote it; its seq is its own conversation's.
 *  - `preview` is plain text on one line, at most 400 characters; empty for attachments only.
 *  - 404 `channel_not_found`, 403 without access, 400 `invalid_message_page_cursor`. An older
 *    server has no route (404): the outline is an aid and simply doesn't show.
 */
internal const val THREAD_OUTLINE_PAGE_SIZE = 200

@Serializable
private data class WireThreadOutlineItem(
    val id: String = "",
    val seq: Long = 0,
    val createdAt: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val preview: String = "",
    val attachmentCount: Int = 0,
)

@Serializable
private data class WireThreadOutlinePage(
    val items: List<WireThreadOutlineItem> = emptyList(),
    val hasMore: Boolean = false,
)

/** One row of the outline: a message a person wrote, or the thread's first message. */
internal data class ThreadOutlineItem(
    val id: String,
    val seq: Long,
    val createdAt: String,
    val senderId: String,
    val senderName: String,
    /** Plain text on one line; empty for a message with only attachments. */
    val preview: String,
    val attachmentCount: Int = 0,
)

/** One page as the server sends it: [items] newest first. */
internal data class ThreadOutlinePage(
    val items: List<ThreadOutlineItem>,
    val hasMore: Boolean,
)

internal fun parseSharedThreadOutlinePage(bodyText: String): ThreadOutlinePage? = runCatching {
    val wire = threadOutlineJson.decodeFromString(WireThreadOutlinePage.serializer(), bodyText)
    ThreadOutlinePage(
        items =
            wire.items
                .filter { it.id.isNotBlank() && it.seq > 0 }
                .map { item ->
                    ThreadOutlineItem(
                        id = item.id,
                        seq = item.seq,
                        createdAt = item.createdAt,
                        senderId = item.senderId,
                        senderName = item.senderName,
                        preview = item.preview,
                        attachmentCount = item.attachmentCount.coerceAtLeast(0),
                    )
                },
        hasMore = wire.hasMore,
    )
}.getOrNull()

internal interface RaftThreadOutlineApi {
    /** A page of [channelId]'s outline older than [beforeSeq] (the newest without), or null when it can't be read. */
    suspend fun page(channelId: String, beforeSeq: Long? = null): ThreadOutlinePage?
}

internal class NetworkRaftThreadOutlineApi(
    private val networkClient: RaftNetworkClient
) : RaftThreadOutlineApi {
    override suspend fun page(channelId: String, beforeSeq: Long?): ThreadOutlinePage? {
        val response =
            networkClient.request(
                // An aid beside the list on screen: its refresh, never ahead of the timeline's own load.
                priority = DataRequestPriority.Visible,
                path = "messages/channel/${channelId.trim()}/human-outline",
                query =
                    listOfNotNull(
                        "limit" to THREAD_OUTLINE_PAGE_SIZE.toString(),
                        beforeSeq?.let { "before" to it.toString() },
                    ),
            )
        if (!response.isSuccessful) return null
        return response.bodyText?.let(::parseSharedThreadOutlinePage)
    }
}
