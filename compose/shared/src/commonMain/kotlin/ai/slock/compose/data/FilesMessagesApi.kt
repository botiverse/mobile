package ai.slock.compose.data

import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val filesMessagesApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Typed files/search/message-adjacent boundary.
 *
 * Canonical response roots: channel files use `{ files, nextCursor? }`,
 * attachment URL/HTML URL use `{ url, expiresAt? }`, previews use
 * `{ status, data?, truncated? }`, search uses `{ results, hasMore? }`,
 * context uses `{ channelId, messages, targetMessageId?, historyLimited? }`,
 * and reaction mutations return the updated message object.
 */
internal interface SlockFilesMessagesApi {
    suspend fun channelFiles(channelId: ChannelId, limit: Int = 50, cursor: String? = null): HomeNetworkResponse
    suspend fun attachmentUrl(attachmentId: AttachmentId, disposition: String? = null): HomeNetworkResponse
    suspend fun attachmentPreview(attachmentId: AttachmentId): HomeNetworkResponse
    suspend fun attachmentHtmlPreviewUrl(attachmentId: AttachmentId): HomeNetworkResponse
    suspend fun messageSearch(query: String, limit: Int = 20, offset: Int = 0, sort: String? = null): HomeNetworkResponse
    suspend fun messageContext(messageId: MessageId, channelId: ChannelId? = null): HomeNetworkResponse
    suspend fun addReaction(messageId: MessageId, emoji: String): HomeNetworkResponse
    suspend fun removeReaction(messageId: MessageId, emoji: String): HomeNetworkResponse
}

internal class SlockFilesMessagesApiClient(
    private val transport: SlockFilesMessagesApi
) {
    suspend fun channelFiles(channelId: ChannelId, limit: Int = 50, cursor: String? = null): DataResult<FilesPage> =
        transport.channelFiles(channelId, limit, cursor)
            .mapFilesBody("channel_files_contract_mismatch") { root ->
                val files = root["files"] as? JsonArray ?: return@mapFilesBody null
                FilesPage(itemCount = files.size, nextCursor = root.string("nextCursor"))
            }

    suspend fun attachmentUrl(attachmentId: AttachmentId, disposition: String? = null): DataResult<AttachmentUrlTarget> =
        transport.attachmentUrl(attachmentId, disposition)
            .mapFilesBody("attachment_url_contract_mismatch") { root ->
                AttachmentUrlTarget(
                    url = root.string("url")?.takeIf { it.isNotBlank() } ?: return@mapFilesBody null,
                    expiresAt = root.string("expiresAt")
                )
            }

    suspend fun attachmentPreview(attachmentId: AttachmentId): DataResult<AttachmentPreviewSummary> =
        transport.attachmentPreview(attachmentId)
            .mapFilesBody("attachment_preview_contract_mismatch") { root ->
                val status = root.string("status") ?: return@mapFilesBody null
                AttachmentPreviewSummary(
                    status = status,
                    kind = (root["data"] as? JsonObject)?.string("kind"),
                    truncated = root.boolean("truncated") ?: false
                )
            }

    suspend fun attachmentHtmlPreviewUrl(attachmentId: AttachmentId): DataResult<AttachmentUrlTarget> =
        transport.attachmentHtmlPreviewUrl(attachmentId)
            .mapFilesBody("attachment_html_preview_url_contract_mismatch") { root ->
                AttachmentUrlTarget(
                    url = root.string("url")?.takeIf { it.isNotBlank() } ?: return@mapFilesBody null,
                    expiresAt = root.string("expiresAt") ?: return@mapFilesBody null
                )
            }

    suspend fun messageSearch(query: String, limit: Int = 20, offset: Int = 0, sort: String? = null): DataResult<MessageSearchPage> =
        transport.messageSearch(query, limit, offset, sort)
            .mapFilesBody("message_search_contract_mismatch") { root ->
                val results = root["results"] as? JsonArray ?: return@mapFilesBody null
                MessageSearchPage(resultCount = results.size, hasMore = root.boolean("hasMore") ?: false)
            }

    suspend fun messageContext(messageId: MessageId, channelId: ChannelId? = null): DataResult<MessageContextSummary> =
        transport.messageContext(messageId, channelId)
            .mapFilesBody("message_context_contract_mismatch") { root ->
                val messages = root["messages"] as? JsonArray ?: return@mapFilesBody null
                MessageContextSummary(
                    channelId = root.string("channelId")?.takeIf { it.isNotBlank() } ?: return@mapFilesBody null,
                    targetMessageId = root.string("targetMessageId"),
                    messageCount = messages.size,
                    historyLimited = root.boolean("historyLimited") ?: false
                )
            }

    suspend fun addReaction(messageId: MessageId, emoji: String): DataResult<MessageMutationSummary> =
        transport.addReaction(messageId, emoji).toMessageMutation("message_reaction_contract_mismatch")

    suspend fun removeReaction(messageId: MessageId, emoji: String): DataResult<MessageMutationSummary> =
        transport.removeReaction(messageId, emoji).toMessageMutation("message_reaction_contract_mismatch")

    private fun HomeNetworkResponse.toMessageMutation(contractCode: String): DataResult<MessageMutationSummary> =
        mapFilesBody(contractCode) { root ->
            MessageMutationSummary(
                messageId = root.string("id")?.takeIf { it.isNotBlank() } ?: return@mapFilesBody null,
                reactionCount = (root["reactions"] as? JsonArray)?.size ?: 0
            )
        }
}

internal data class FilesPage(val itemCount: Int, val nextCursor: String?)
internal data class AttachmentUrlTarget(val url: String, val expiresAt: String?)
internal data class AttachmentPreviewSummary(val status: String, val kind: String?, val truncated: Boolean)
internal data class MessageSearchPage(val resultCount: Int, val hasMore: Boolean)
internal data class MessageContextSummary(
    val channelId: String,
    val targetMessageId: String?,
    val messageCount: Int,
    val historyLimited: Boolean
)
internal data class MessageMutationSummary(val messageId: String, val reactionCount: Int)

private inline fun <T> HomeNetworkResponse.mapFilesBody(
    contractCode: String,
    mapper: (JsonObject) -> T?
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val root = runCatching { filesMessagesApiJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = mapper(root)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.boolean(key: String): Boolean? =
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

@kotlinx.serialization.Serializable
private data class ReactionBody(val emoji: String)

internal fun buildSharedReactionBody(emoji: String): String =
    filesMessagesApiJson.encodeToString(ReactionBody(emoji = emoji))
