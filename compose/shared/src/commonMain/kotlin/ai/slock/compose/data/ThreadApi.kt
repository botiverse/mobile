package ai.slock.compose.data

import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val threadApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal interface SlockThreadApi {
    suspend fun channelMessages(threadId: String, limit: Int = 50, beforeSeq: Long? = null): ThreadApiResult<ThreadMessagesPage>
    suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int = 50): ThreadApiResult<ThreadMessagesPage>
    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList()
    ): ThreadApiResult<ThreadMessagesPage>
    suspend fun attachmentUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget>
    suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit>
    suspend fun saveMessage(messageId: String): ThreadApiResult<Unit>
    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): ThreadApiResult<Unit>
    suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult>
}

internal class SlockThreadApiClient(
    private val transport: SharedThreadNetworkApi
) : SlockThreadApi {
    override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?): ThreadApiResult<ThreadMessagesPage> =
        transport.channelMessages(threadId = threadId, limit = limit, beforeSeq = beforeSeq)
            .mapThreadBody("thread_messages_contract_mismatch") { element, bodyText ->
                val root = element as? JsonObject ?: return@mapThreadBody null
                val messages = root["messages"] as? JsonArray ?: return@mapThreadBody null
                ThreadMessagesPage(
                    rawBodyText = bodyText,
                    messageCount = messages.size,
                    canLoadMoreBefore = root.boolean("hasMore", "historyLimited"),
                    canLoadMoreAfter = root.boolean("hasNextPage")
                )
            }

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): ThreadApiResult<ThreadMessagesPage> =
        transport.messageSync(channelId = channelId, sinceSeq = sinceSeq, limit = limit)
            .mapThreadBody("thread_sync_contract_mismatch") { element, bodyText ->
                val messages = element as? JsonArray ?: return@mapThreadBody null
                ThreadMessagesPage(
                    rawBodyText = bodyText,
                    messageCount = messages.size,
                    canLoadMoreBefore = null,
                    canLoadMoreAfter = false
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
        ).mapThreadBody("thread_send_contract_mismatch") { element, bodyText ->
            val root = element as? JsonObject ?: return@mapThreadBody null
            if (root.string("id").isNullOrBlank() || root.string("content").isNullOrBlank()) {
                return@mapThreadBody null
            }
            ThreadMessagesPage(
                rawBodyText = bodyText,
                messageCount = 1,
                canLoadMoreBefore = null,
                canLoadMoreAfter = null
            )
        }

    override suspend fun attachmentUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        transport.attachmentUrl(attachmentId)
            .mapThreadBody("thread_attachment_url_contract_mismatch") { element, _ ->
                val root = element as? JsonObject ?: return@mapThreadBody null
                val url = root.string("url")?.takeIf { it.isNotBlank() } ?: return@mapThreadBody null
                ThreadAttachmentOpenTarget(
                    url = url,
                    expiresAt = root.string("expiresAt")
                )
            }

    override suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit> =
        transport.markChannelReadAll(channelId).mapThreadUnit()

    override suspend fun saveMessage(messageId: String): ThreadApiResult<Unit> =
        transport.saveMessage(messageId).mapThreadUnit()

    override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): ThreadApiResult<Unit> =
        transport.updateTaskStatus(taskId, status).mapThreadUnit()

    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult> =
        transport.createOrOpenThread(channelId, parentMessageId)
            .mapThreadBody("thread_create_contract_mismatch") { element, _ ->
                val root = element as? JsonObject ?: return@mapThreadBody null
                val threadChannelId = root.string("threadChannelId")?.takeIf { it.isNotBlank() }
                    ?: return@mapThreadBody null
                ThreadActionResult(
                    threadChannelId = ChannelId(threadChannelId),
                    parentMessageId = root.string("parentMessageId")?.let(::MessageId),
                    changed = root.boolean("created") ?: false
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
    internal val rawBodyText: String,
    val messageCount: Int,
    val canLoadMoreBefore: Boolean?,
    val canLoadMoreAfter: Boolean?
)

internal data class ThreadAttachmentOpenTarget(
    val url: String,
    val expiresAt: String?
)

private fun HomeNetworkResponse.toThreadApiFailure(): ThreadApiResult.Failure =
    ThreadApiResult.Failure(code = code, errorBodyText = errorBodyText)

private inline fun <T> HomeNetworkResponse.mapThreadBody(
    contractCode: String,
    mapper: (JsonElement, String) -> T?
): ThreadApiResult<T> {
    if (!isSuccessful) return toThreadApiFailure()
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    val element = runCatching { threadApiJson.parseToJsonElement(body) }.getOrNull()
        ?: return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    val value = mapper(element, body)
        ?: return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    return ThreadApiResult.Success(value)
}

private fun HomeNetworkResponse.mapThreadUnit(): ThreadApiResult<Unit> =
    if (isSuccessful) ThreadApiResult.Success(Unit) else toThreadApiFailure()

private fun JsonObject.string(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }

private fun JsonObject.boolean(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key ->
        when (val primitive = this[key] as? JsonPrimitive) {
            null -> null
            else -> primitive.contentOrNull?.trim()?.lowercase()?.let { value ->
                when (value) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
        }
    }

@kotlinx.serialization.Serializable
private data class SharedThreadSendMessageBody(
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
    threadApiJson.encodeToString(
        SharedThreadSendMessageBody(
            channelId = channelId,
            content = body,
            attachmentIds = attachmentIds,
            asTask = asTask
        )
    )
