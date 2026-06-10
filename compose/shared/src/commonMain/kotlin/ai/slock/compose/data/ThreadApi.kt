package ai.slock.compose.data

import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val threadApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Typed Thread/Message boundary.
 *
 * Server contracts:
 *  - `packages/server/src/routes/messages.ts`:
 *      GET messages → `{ messages, historyLimited }` (no `hasMore`/`hasNextPage`)
 *      GET messages/sync → root array
 *      POST messages → created message object (`{ id, content, seq, ... }`)
 *  - `packages/server/src/routes/files.ts`: `{ url, expiresAt? }`.
 *  - `packages/server/src/routes/threads.ts`: `{ threadChannelId,
 *    parentMessageId?, created? }`.
 *
 * Field names are exact camelCase. No snake_case fallbacks, no
 * `hasMore`/`hasNextPage`/`downloadUrl` speculation.
 */
internal interface SlockThreadApi {
    suspend fun channelMessages(threadId: String, limit: Int = 50, beforeSeq: Long? = null): ThreadApiResult<ThreadMessagesPage>
    suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int = 50): ThreadApiResult<ThreadMessagesPage>
    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList()
    ): ThreadApiResult<ThreadMessagesPage>
    suspend fun attachmentUrl(attachmentId: String, disposition: String? = null): ThreadApiResult<ThreadAttachmentOpenTarget>
    suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit>
    suspend fun saveMessage(messageId: String): ThreadApiResult<Unit>
    suspend fun addReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary>
    suspend fun removeReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary>
    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): ThreadApiResult<Unit>
    suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult>
}

internal class SlockThreadApiClient(
    private val transport: SharedThreadNetworkApi
) : SlockThreadApi {
    override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?): ThreadApiResult<ThreadMessagesPage> =
        transport.channelMessages(threadId = threadId, limit = limit, beforeSeq = beforeSeq)
            .mapThreadBody("thread_messages_contract_mismatch", ChannelMessagesResponseDto.serializer()) { wire ->
                ThreadMessagesPage(
                    messages = wire.messages,
                    canLoadMoreBefore = wire.historyLimited,
                    canLoadMoreAfter = null,
                )
            }

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): ThreadApiResult<ThreadMessagesPage> =
        transport.messageSync(channelId = channelId, sinceSeq = sinceSeq, limit = limit)
            .mapThreadBody(
                "thread_sync_contract_mismatch",
                ListSerializer(WireMessage.serializer())
            ) { wire ->
                ThreadMessagesPage(
                    messages = wire,
                    canLoadMoreBefore = null,
                    canLoadMoreAfter = false,
                )
            }

    override suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String>
    ): ThreadApiResult<ThreadMessagesPage> =
        transport.sendMessage(
            channelId = channelId,
            body = body,
            asTask = asTask,
            attachmentIds = attachmentIds
        ).mapThreadBody("thread_send_contract_mismatch", WireMessage.serializer()) { wire ->
            if (wire.id.isBlank() || wire.content.isBlank()) return@mapThreadBody null
            ThreadMessagesPage(
                messages = listOf(wire),
                canLoadMoreBefore = null,
                canLoadMoreAfter = null,
            )
        }

    override suspend fun attachmentUrl(attachmentId: String, disposition: String?): ThreadApiResult<ThreadAttachmentOpenTarget> =
        transport.attachmentUrl(attachmentId, disposition)
            .mapThreadBody(
                "thread_attachment_url_contract_mismatch",
                ThreadAttachmentUrlResponseDto.serializer()
            ) { wire ->
                val url = wire.url.takeIf { it.isNotBlank() } ?: return@mapThreadBody null
                ThreadAttachmentOpenTarget(url = url, expiresAt = wire.expiresAt)
            }

    override suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit> =
        transport.markChannelReadAll(channelId).mapThreadUnit()

    override suspend fun saveMessage(messageId: String): ThreadApiResult<Unit> =
        transport.saveMessage(messageId).mapThreadUnit()

    override suspend fun addReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> =
        transport.addReaction(messageId, emoji)
            .mapThreadBody("thread_reaction_contract_mismatch", MessageMutationResponseDto.serializer()) { wire ->
                wire.toMessageMutationSummary()
            }

    override suspend fun removeReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> =
        transport.removeReaction(messageId, emoji)
            .mapThreadBody("thread_reaction_contract_mismatch", MessageMutationResponseDto.serializer()) { wire ->
                wire.toMessageMutationSummary()
            }

    override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): ThreadApiResult<Unit> =
        transport.updateTaskStatus(taskId, status).mapThreadUnit()

    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult> =
        transport.createOrOpenThread(channelId, parentMessageId)
            .mapThreadBody("thread_create_contract_mismatch", CreateOrOpenThreadResponseDto.serializer()) { wire ->
                val tid = wire.threadChannelId.takeIf { it.isNotBlank() } ?: return@mapThreadBody null
                ThreadActionResult(
                    threadChannelId = ChannelId(tid),
                    parentMessageId = wire.parentMessageId?.let(::MessageId),
                    changed = wire.created ?: false,
                    replyCount = wire.replyCount ?: 0,
                )
            }
}

internal sealed interface ThreadApiResult<out T> {
    data class Success<T>(val data: T) : ThreadApiResult<T>
    data class Failure(
        val code: Int,
        val errorBodyText: String? = null,
        val contractCode: String? = null
    ) : ThreadApiResult<Nothing>
}

internal data class ThreadMessagesPage(
    val messages: List<WireMessage>,
    val canLoadMoreBefore: Boolean?,
    val canLoadMoreAfter: Boolean?
) {
    val messageCount: Int
        get() = messages.size
}

internal data class ThreadAttachmentOpenTarget(
    val url: String,
    val expiresAt: String?
)

private fun HomeNetworkResponse.toThreadApiFailure(): ThreadApiResult.Failure =
    ThreadApiResult.Failure(code = code, errorBodyText = errorBodyText)

private inline fun <Wire, T> HomeNetworkResponse.mapThreadBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    crossinline transform: (Wire) -> T?,
): ThreadApiResult<T> {
    if (!isSuccessful) return toThreadApiFailure()
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    val wire = try {
        threadApiJson.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    }
    val value = transform(wire)
        ?: return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    return ThreadApiResult.Success(value)
}

private fun HomeNetworkResponse.mapThreadUnit(): ThreadApiResult<Unit> =
    if (isSuccessful) ThreadApiResult.Success(Unit) else toThreadApiFailure()

// ── Wire DTOs ──

@Serializable
private data class ChannelMessagesResponseDto(
    val messages: List<WireMessage>,
    val historyLimited: Boolean? = null,
)

@Serializable
private data class ThreadAttachmentUrlResponseDto(
    val url: String,
    val expiresAt: String? = null,
)

@Serializable
private data class CreateOrOpenThreadResponseDto(
    val threadChannelId: String,
    val parentMessageId: String? = null,
    val created: Boolean? = null,
    val replyCount: Int? = null,
)

// ── Request bodies ──

@Serializable
data class ThreadSendMessageRequestBody(
    val channelId: String,
    val content: String,
    val attachmentIds: List<String> = emptyList(),
    val asTask: Boolean = false
)

internal fun buildSharedThreadSendMessageBody(
    channelId: String,
    body: String,
    attachmentIds: List<String> = emptyList(),
    asTask: Boolean = false
): String =
    buildSharedThreadSendMessageBody(
        ThreadSendMessageRequestBody(
            channelId = channelId,
            content = body,
            attachmentIds = attachmentIds,
            asTask = asTask
        )
    )

fun buildSharedThreadSendMessageBody(request: ThreadSendMessageRequestBody): String =
    threadApiJson.encodeToString(
        request
    )
