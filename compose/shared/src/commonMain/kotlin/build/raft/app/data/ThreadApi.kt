package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.parsedMessageTypeOrNull
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

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
internal interface RaftThreadApi {
    suspend fun channelMessages(
        threadId: String,
        limit: Int = 50,
        beforeSeq: Long? = null,
        afterSeq: Long? = null
    ): ThreadApiResult<ThreadMessagesPage>
    suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int = 50): ThreadApiResult<ThreadMessagesPage>

    /**
     * Task #168 step 1: the gap catch-up's latest page — the same `messages/channel/{id}?limit=` request
     * as an uncursored [channelMessages], named separately so the catch-up is its own call site.
     */
    suspend fun gapCatchUpChannelMessages(threadId: String, limit: Int): ThreadApiResult<ThreadMessagesPage> =
        channelMessages(threadId = threadId, limit = limit)
    suspend fun messageContext(messageId: String, channelId: String? = null): ThreadApiResult<SharedMessagesPayload>
    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList(),
        randomId: String? = null
    ): ThreadApiResult<ThreadMessagesPage>
    suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): ThreadApiResult<List<PendingMentionActionExecuteResult>>
    suspend fun attachmentUrl(
        priority: DataRequestPriority,
        attachmentId: String,
        disposition: String? = null
    ): ThreadApiResult<ThreadAttachmentOpenTarget>
    suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget>
    suspend fun attachmentComments(attachmentId: String): ThreadApiResult<ThreadAttachmentCommentsPage>
    suspend fun sendAttachmentComment(attachmentId: String, body: String): ThreadApiResult<Unit>
    suspend fun markChannelReadToSeq(channelId: String, seq: Long): ThreadApiResult<Unit>
    suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit>
    suspend fun saveMessage(messageId: String): ThreadApiResult<Unit>
    suspend fun unsaveMessage(messageId: String): ThreadApiResult<Unit>
    suspend fun addReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary>
    suspend fun removeReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary>
    suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult>
}

internal class RaftThreadApiClient(
    private val transport: SharedThreadNetworkApi,
    private val diagnosticsLogger: (String) -> Unit = {}
) : RaftThreadApi {
    override suspend fun channelMessages(
        threadId: String,
        limit: Int,
        beforeSeq: Long?,
        afterSeq: Long?
    ): ThreadApiResult<ThreadMessagesPage> =
        transport.channelMessages(threadId = threadId, limit = limit, beforeSeq = beforeSeq, afterSeq = afterSeq)
            .toChannelMessagesPage()

    override suspend fun gapCatchUpChannelMessages(threadId: String, limit: Int): ThreadApiResult<ThreadMessagesPage> =
        transport.gapCatchUpChannelMessages(threadId = threadId, limit = limit).toChannelMessagesPage()

    private suspend fun HomeNetworkResponse.toChannelMessagesPage(): ThreadApiResult<ThreadMessagesPage> =
        mapThreadBody(
                contractCode = "thread_messages_contract_mismatch",
                serializer = ChannelMessagesResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                ThreadMessagesPage(
                    messages = wire.messages,
                    canLoadMoreBefore = wire.historyLimited,
                    canLoadMoreAfter = null,
                )
            }

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): ThreadApiResult<ThreadMessagesPage> =
        transport.messageSync(channelId = channelId, sinceSeq = sinceSeq, limit = limit)
            .mapThreadBody(
                contractCode = "thread_sync_contract_mismatch",
                serializer = ListSerializer(WireMessage.serializer()),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                ThreadMessagesPage(
                    messages = wire,
                    canLoadMoreBefore = null,
                    canLoadMoreAfter = false,
                )
            }

    override suspend fun messageContext(
        messageId: String,
        channelId: String?
    ): ThreadApiResult<SharedMessagesPayload> {
        val response = transport.messageContext(messageId = messageId, channelId = channelId)
        if (!response.isSuccessful) {
            return ThreadApiResult.Failure(code = response.code, contractCode = "message_context_contract_mismatch")
        }
        val body =
            response.bodyText?.takeIf { it.isNotBlank() }
                ?: return ThreadApiResult.Failure(code = response.code, contractCode = "message_context_contract_mismatch")
        val payload =
            parseSharedMessageContext(
                bodyText = body,
                fallbackThreadId = ThreadId(channelId?.takeIf { it.isNotBlank() } ?: messageId),
                diagnostics = diagnosticsLogger
            )
        if (payload.messages.isEmpty()) {
            return ThreadApiResult.Failure(code = response.code, contractCode = "message_context_contract_mismatch")
        }
        return ThreadApiResult.Success(payload)
    }

    override suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String>,
        randomId: String?
    ): ThreadApiResult<ThreadMessagesPage> =
        transport.sendMessage(
            channelId = channelId,
            body = body,
            asTask = asTask,
            attachmentIds = attachmentIds,
            randomId = randomId
        ).mapThreadSendBody(
            contractCode = "thread_send_contract_mismatch",
            diagnosticsLogger = diagnosticsLogger
        )

    override suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): ThreadApiResult<List<PendingMentionActionExecuteResult>> =
        transport.executePendingMentionActions(
            action = action.canonicalPendingMentionActionVerb(),
            resolutionIds = resolutionIds
        )
            .mapThreadBody(
                contractCode = "thread_mention_action_contract_mismatch",
                serializer = MentionActionExecuteResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                wire.results.map { it.toDomain() }
            }

    override suspend fun attachmentUrl(
        priority: DataRequestPriority,
        attachmentId: String,
        disposition: String?
    ): ThreadApiResult<ThreadAttachmentOpenTarget> =
        transport.attachmentUrl(priority, attachmentId, disposition)
            .mapThreadBody(
                contractCode = "thread_attachment_url_contract_mismatch",
                serializer = ThreadAttachmentUrlResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                val url = wire.url.takeIf { it.isNotBlank() } ?: return@mapThreadBody null
                ThreadAttachmentOpenTarget(url = url, expiresAt = wire.expiresAt)
            }

    override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        transport.attachmentHtmlPreviewUrl(attachmentId)
            .mapThreadBody(
                contractCode = "thread_attachment_html_preview_url_contract_mismatch",
                serializer = ThreadAttachmentUrlResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                val url = wire.url.takeIf { it.isNotBlank() } ?: return@mapThreadBody null
                ThreadAttachmentOpenTarget(url = url, expiresAt = wire.expiresAt)
            }

    override suspend fun attachmentComments(attachmentId: String): ThreadApiResult<ThreadAttachmentCommentsPage> =
        transport.attachmentComments(attachmentId)
            .mapThreadBody(
                contractCode = "thread_attachment_comments_contract_mismatch",
                serializer = AttachmentCommentsResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                ThreadAttachmentCommentsPage(
                    comments = wire.comments.mapNotNull { it.toThreadAttachmentCommentItem() },
                    threadChannelId = wire.threadChannelId,
                    viewer = wire.viewer?.toThreadAttachmentCommentViewer()
                )
            }

    override suspend fun sendAttachmentComment(attachmentId: String, body: String): ThreadApiResult<Unit> =
        transport.sendAttachmentComment(attachmentId, body).mapThreadUnit()

    override suspend fun markChannelReadToSeq(channelId: String, seq: Long): ThreadApiResult<Unit> =
        transport.markChannelReadToSeq(channelId, seq).mapThreadUnit()

    override suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit> =
        transport.markChannelReadAll(channelId).mapThreadUnit()

    override suspend fun saveMessage(messageId: String): ThreadApiResult<Unit> =
        transport.saveMessage(messageId).mapThreadUnit()

    override suspend fun unsaveMessage(messageId: String): ThreadApiResult<Unit> =
        transport.unsaveMessage(messageId).mapThreadUnit()

    override suspend fun addReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> =
        transport.addReaction(messageId, emoji)
            .mapThreadBody(
                contractCode = "thread_reaction_contract_mismatch",
                serializer = MessageMutationResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                wire.toMessageMutationSummary()
            }

    override suspend fun removeReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> =
        transport.removeReaction(messageId, emoji)
            .mapThreadBody(
                contractCode = "thread_reaction_contract_mismatch",
                serializer = MessageMutationResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                wire.toMessageMutationSummary()
            }

    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult> =
        transport.createOrOpenThread(channelId, parentMessageId)
            .mapThreadBody(
                contractCode = "thread_create_contract_mismatch",
                serializer = CreateOrOpenThreadResponseDto.serializer(),
                diagnosticsLogger = diagnosticsLogger
            ) { wire ->
                val tid = wire.threadChannelId.takeIf { it.isNotBlank() } ?: return@mapThreadBody null
                ThreadActionResult(
                    threadChannelId = ChannelId(tid),
                    parentMessageId = wire.parentMessageId?.let(::MessageId),
                    changed = wire.created ?: false,
                    replyCount = wire.replyCount ?: 0,
                )
            }
}

internal class NetworkRaftThreadApi(
    private val networkClient: RaftNetworkClient
) : SharedThreadNetworkApi {
    override suspend fun channelMessages(
        threadId: String,
        limit: Int,
        beforeSeq: Long?,
        afterSeq: Long?
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "messages/channel/${threadId.trim()}",
            query = listOfNotNull(
                "limit" to limit.toString(),
                beforeSeq?.let { "before" to it.toString() },
                afterSeq?.let { "after" to it.toString() }
            )
        )

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "messages/sync",
            query =
                listOf(
                    "since_seq" to sinceSeq.toString(),
                    "limit" to limit.toString(),
                    "channel_id" to channelId.trim()
                )
        )

    override suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String>,
        randomId: String?
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "messages",
            method = "POST",
            bodyText =
                buildSharedThreadSendMessageBody(
                    channelId = channelId,
                    body = body,
                    attachmentIds = attachmentIds,
                    asTask = asTask,
                    randomId = randomId
                )
        )

    override suspend fun messageContext(messageId: String, channelId: String?): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Background,
            path = "messages/context/${messageId.trim()}",
            query = channelId?.takeIf { it.isNotBlank() }?.let { listOf("channelId" to it.trim()) } ?: emptyList()
        )

    override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "messages/mention-actions/execute",
            method = "POST",
            bodyText = buildPendingMentionActionExecuteBody(
                action = action.canonicalPendingMentionActionVerb(),
                resolutionIds = resolutionIds
            )
        )

    override suspend fun attachmentUrl(
        priority: DataRequestPriority,
        attachmentId: String,
        disposition: String?
    ): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "attachments/${attachmentId.trim()}/url",
            query = listOfNotNull(disposition?.trim()?.takeIf { it.isNotBlank() }?.let { "disposition" to it })
        )

    override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "attachments/${attachmentId.trim()}/html-preview-url"
        )

    override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "attachments/${attachmentId.trim()}/comments"
        )

    override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "attachments/${attachmentId.trim()}/comments",
            method = "POST",
            bodyText = threadApiJson.encodeToString(AttachmentCommentRequestBody(body))
        )

    override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse =
        networkClient.request(
            DataRequestPriority.Foreground,
            "channels/${channelId.trim()}/read",
            method = "POST",
            bodyText = threadApiJson.encodeToString(ThreadReadToSeqRequest(seq))
        )

    override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "channels/${channelId.trim()}/read-all", method = "POST")

    override suspend fun saveMessage(messageId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "channels/saved",
            method = "POST",
            bodyText = buildSharedSaveMessageBody(MessageId(messageId.trim()))
        )

    override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "channels/saved/${messageId.trim()}",
            method = "DELETE"
        )

    override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "messages/${messageId.trim()}/reactions",
            method = "POST",
            bodyText = buildSharedReactionBody(emoji)
        )

    override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "messages/${messageId.trim()}/reactions",
            method = "DELETE",
            bodyText = buildSharedReactionBody(emoji)
        )

    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "channels/${channelId.trim()}/threads",
            method = "POST",
            bodyText = buildSharedCreateOrOpenThreadBody(parentMessageId.trim())
        )
}

@Serializable
private data class ThreadReadToSeqRequest(val seq: Long)

internal sealed interface ThreadApiResult<out T> {
    data class Success<T>(val data: T) : ThreadApiResult<T>
    data class Failure(
        val code: Int,
        val errorBodyText: String? = null,
        val contractCode: String? = null
    ) : ThreadApiResult<Nothing>
}

internal data class ThreadAttachmentCommentsFailureProjection(
    val errorCode: String,
    val serverMessage: String?
)

internal fun ThreadApiResult.Failure.toThreadAttachmentCommentsFailureProjection():
    ThreadAttachmentCommentsFailureProjection {
    val envelope =
        errorBodyText
            ?.takeIf { it.isNotBlank() }
            ?.let { body ->
                runCatching {
                    threadApiJson.decodeFromString(ThreadErrorResponseDto.serializer(), body)
                }.getOrNull()
            }
    return ThreadAttachmentCommentsFailureProjection(
        errorCode =
            contractCode?.trim()?.takeIf { it.isNotBlank() }
                ?: envelope?.code?.trim()?.takeIf { it.isNotBlank() }
                ?: "http_$code",
        serverMessage = envelope?.error?.takeIf { it.isNotBlank() }
    )
}

internal data class ThreadMessagesPage(
    val messages: List<WireMessage>,
    val canLoadMoreBefore: Boolean?,
    val canLoadMoreAfter: Boolean?,
    val pendingMentionActions: List<PendingMentionAction> = emptyList()
) {
    val messageCount: Int
        get() = messages.size
}

/**
 * Task #59, the rule of botiverse/slock #10809, for a send response (the sender's own message):
 * a chat message that is not an assistant's delegated send reopens its DM, or for a thread
 * reply the parent DM. [fallbackChannelId] covers a message without `channelId`;
 * [parentConversationId] is the reply's parent conversation, null for a top-level send.
 */
internal fun ThreadMessagesPage.ownSentConversationIds(
    fallbackChannelId: String,
    parentConversationId: String?,
): Set<String> =
    messages
        .asSequence()
        .filter { message ->
            message.senderType == "user" &&
                message.messageType.parsedMessageTypeOrNull() == MessageType.Chat &&
                message.delegatedBy == null
        }
        .flatMap { message -> sequenceOf(message.channelId ?: fallbackChannelId, parentConversationId) }
        .filterNotNull()
        .filter { it.isNotBlank() }
        .toSet()

internal data class ThreadAttachmentOpenTarget(
    val url: String,
    val expiresAt: String?
)

internal data class ThreadAttachmentCommentsPage(
    val comments: List<ThreadAttachmentCommentItem>,
    val threadChannelId: String?,
    val viewer: ThreadAttachmentCommentViewer?
)

internal data class ThreadAttachmentCommentItem(
    val id: String,
    val channelId: String,
    val senderType: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val createdAt: String,
    val reactions: List<ThreadAttachmentCommentReaction>,
    val anchorLabel: String? = null
)

internal data class ThreadAttachmentCommentReaction(
    val emoji: String,
    val reactorType: String,
    val reactorId: String
)

internal data class ThreadAttachmentCommentViewer(
    val canComment: Boolean,
    val reason: String
)

private fun HomeNetworkResponse.toThreadApiFailure(): ThreadApiResult.Failure =
    ThreadApiResult.Failure(code = code, errorBodyText = errorBodyText)

private inline fun <Wire, T> HomeNetworkResponse.mapThreadBodySync(
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

private suspend inline fun <Wire, T> HomeNetworkResponse.mapThreadBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    diagnosticsLogger: (String) -> Unit = {},
    crossinline transform: (Wire) -> T?,
): ThreadApiResult<T> {
    val source = contractCode.removeSuffix("_contract_mismatch")
    val responseBytes = bodyText?.encodeToByteArray()?.size ?: 0
    diagnosticsLogger(
        "thread_network_body_received source=$source code=$code " +
            "responseBytes=$responseBytes hasBody=${bodyText != null}"
    )
    val parseStartedAt = TimeSource.Monotonic.markNow()
    diagnosticsLogger(
        "thread_parse_start source=$source responseBytes=$responseBytes dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
    )
    val result =
        withContext(RaftJsonParseDispatcher) {
            mapThreadBodySync(contractCode, serializer, transform)
        }
    val resultLabel =
        when (result) {
            is ThreadApiResult.Success -> "success"
            is ThreadApiResult.Failure -> "failure:${result.contractCode ?: result.code}"
        }
    val messageCount = (result as? ThreadApiResult.Success<*>)?.data.let { data ->
        (data as? ThreadMessagesPage)?.messageCount ?: 0
    }
    diagnosticsLogger(
        "thread_parse_result source=$source result=$resultLabel messages=$messageCount " +
            "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
    )
    return result
}

private suspend fun HomeNetworkResponse.mapThreadSendBody(
    contractCode: String,
    diagnosticsLogger: (String) -> Unit = {},
): ThreadApiResult<ThreadMessagesPage> {
    val source = contractCode.removeSuffix("_contract_mismatch")
    val responseBytes = bodyText?.encodeToByteArray()?.size ?: 0
    diagnosticsLogger(
        "thread_network_body_received source=$source code=$code " +
            "responseBytes=$responseBytes hasBody=${bodyText != null}"
    )
    val parseStartedAt = TimeSource.Monotonic.markNow()
    diagnosticsLogger(
        "thread_parse_start source=$source responseBytes=$responseBytes dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
    )
    val result =
        withContext(RaftJsonParseDispatcher) {
            mapThreadSendBodySync(contractCode)
        }
    val resultLabel =
        when (result) {
            is ThreadApiResult.Success -> "success"
            is ThreadApiResult.Failure -> "failure:${result.contractCode ?: result.code}"
        }
    val messageCount = (result as? ThreadApiResult.Success<ThreadMessagesPage>)?.data?.messageCount ?: 0
    diagnosticsLogger(
        "thread_parse_result source=$source result=$resultLabel messages=$messageCount " +
            "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
    )
    return result
}

private fun HomeNetworkResponse.mapThreadSendBodySync(contractCode: String): ThreadApiResult<ThreadMessagesPage> {
    if (!isSuccessful) return toThreadApiFailure()
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    val rawMessage = runCatching {
        threadApiJson.decodeFromString(WireMessage.serializer(), body)
    }.getOrNull()
    if (rawMessage != null && rawMessage.id.isNotBlank()) {
        return ThreadApiResult.Success(
            ThreadMessagesPage(
                messages = listOf(rawMessage),
                canLoadMoreBefore = null,
                canLoadMoreAfter = null,
            )
        )
    }
    val wrapped = runCatching {
        threadApiJson.decodeFromString(SendMessageResponseDto.serializer(), body)
    }.getOrNull()
    val message = wrapped?.message
    if (message == null || message.id.isBlank()) {
        return ThreadApiResult.Failure(code = code, contractCode = contractCode)
    }
    return ThreadApiResult.Success(
        ThreadMessagesPage(
            messages = listOf(message),
            canLoadMoreBefore = null,
            canLoadMoreAfter = null,
            pendingMentionActions = wrapped.pendingMentionActions.mapNotNull { it.toDomain() }
        )
    )
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
private data class AttachmentCommentsResponseDto(
    val comments: List<AttachmentCommentDto> = emptyList(),
    val threadChannelId: String? = null,
    val viewer: AttachmentCommentViewerDto? = null,
)

@Serializable
private data class ThreadErrorResponseDto(
    // Attachment-comment list/send and message-reaction failures use this
    // exact server envelope; aliases and plain text intentionally fail closed.
    val error: String? = null,
    val code: String? = null,
)

@Serializable
private data class AttachmentCommentDto(
    val id: String = "",
    val channelId: String = "",
    val senderType: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val content: String = "",
    val createdAt: String = "",
    val reactions: List<AttachmentCommentReactionDto> = emptyList(),
    val anchor: AttachmentCommentAnchorDto? = null,
)

@Serializable
private data class AttachmentCommentReactionDto(
    val emoji: String = "",
    val reactorType: String = "",
    val reactorId: String = "",
)

@Serializable
private data class AttachmentCommentAnchorDto(
    val type: String = "",
    val data: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
private data class AttachmentCommentViewerDto(
    val canComment: Boolean = true,
    val reason: String = "ok",
)

@Serializable
private data class CreateOrOpenThreadResponseDto(
    val threadChannelId: String,
    val parentMessageId: String? = null,
    val created: Boolean? = null,
    val replyCount: Int? = null,
)

@Serializable
private data class SendMessageResponseDto(
    val message: WireMessage? = null,
    val pendingMentionActions: List<PendingMentionActionDto> = emptyList()
)

@Serializable
private data class PendingMentionActionDto(
    val resolutionId: String? = null,
    val id: String? = null,
    val messageId: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val targetType: String? = null,
    val targetHandle: String? = null,
    val targetAvatarUrl: String? = null,
    val reason: String? = null,
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

@Serializable
private data class MentionActionExecuteResponseDto(
    val results: List<PendingMentionActionExecuteResultDto> = emptyList()
)

@Serializable
private data class PendingMentionActionExecuteResultDto(
    val resolutionId: String,
    val status: String,
    val reason: String? = null
)

// ── Request bodies ──

@Serializable
private data class AttachmentCommentRequestBody(val content: String)

private fun AttachmentCommentDto.toThreadAttachmentCommentItem(): ThreadAttachmentCommentItem? {
    val cleanId = id.takeIf { it.isNotBlank() } ?: return null
    return ThreadAttachmentCommentItem(
        id = cleanId,
        channelId = channelId,
        senderType = senderType,
        senderId = senderId,
        senderName = senderName.ifBlank { "Unknown" },
        content = content,
        createdAt = createdAt,
        reactions = reactions.map { it.toThreadAttachmentCommentReaction() },
        anchorLabel = anchor?.toAnchorLabel()
    )
}

private fun AttachmentCommentReactionDto.toThreadAttachmentCommentReaction(): ThreadAttachmentCommentReaction =
    ThreadAttachmentCommentReaction(
        emoji = emoji,
        reactorType = reactorType,
        reactorId = reactorId
    )

private fun AttachmentCommentViewerDto.toThreadAttachmentCommentViewer(): ThreadAttachmentCommentViewer =
    ThreadAttachmentCommentViewer(canComment = canComment, reason = reason)

// web `anchorLabel` (attachmentCommentAnchors.ts): a section is `§ <heading>`, a line range
// `L<start>–<end>`; other anchors keep their own label or quote.
private fun AttachmentCommentAnchorDto.toAnchorLabel(): String? {
    val json = data ?: return null
    when (type) {
        "md-section" -> {
            val title =
                json["headingTitle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: json["headingId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (title != null) return "§ $title".take(80)
        }
        "lines" -> {
            val start = json["start"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()
            val end = json["end"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: start
            if (start != null) return if (end == null || end == start) "L$start" else "L$start–$end"
        }
    }
    val label =
        json["label"]?.jsonPrimitive?.contentOrNull
            ?: json["quote"]?.jsonPrimitive?.contentOrNull
            ?: json["text"]?.jsonPrimitive?.contentOrNull
    return label?.takeIf { it.isNotBlank() }?.take(80)
}

@Serializable
data class ThreadSendMessageRequestBody(
    val channelId: String,
    val content: String,
    val attachmentIds: List<String> = emptyList(),
    val asTask: Boolean = false,
    val randomId: String? = null
)

@Serializable
private data class ThreadSendMessageRequestBodyWithoutRandomId(
    val channelId: String,
    val content: String,
    val attachmentIds: List<String> = emptyList(),
    val asTask: Boolean = false
)

@Serializable
private data class ThreadSendMessageRequestBodyWithRandomId(
    val channelId: String,
    val content: String,
    val attachmentIds: List<String> = emptyList(),
    val asTask: Boolean = false,
    val randomId: String
)

@Serializable
private data class PendingMentionActionExecuteRequestBody(
    val action: String,
    val resolutionIds: List<String>
)

internal fun buildSharedThreadSendMessageBody(
    channelId: String,
    body: String,
    attachmentIds: List<String> = emptyList(),
    asTask: Boolean = false,
    randomId: String? = null
): String =
    buildSharedThreadSendMessageBody(
        ThreadSendMessageRequestBody(
            channelId = channelId,
            content = body,
            attachmentIds = attachmentIds,
            asTask = asTask,
            randomId = randomId?.takeIf { it.isNotBlank() }
        )
    )

fun buildSharedThreadSendMessageBody(request: ThreadSendMessageRequestBody): String {
    val randomId = request.randomId?.takeIf { it.isNotBlank() }
    return if (randomId == null) {
        threadApiJson.encodeToString(
            ThreadSendMessageRequestBodyWithoutRandomId(
                channelId = request.channelId,
                content = request.content,
                attachmentIds = request.attachmentIds,
                asTask = request.asTask
            )
        )
    } else {
        threadApiJson.encodeToString(
            ThreadSendMessageRequestBodyWithRandomId(
                channelId = request.channelId,
                content = request.content,
                attachmentIds = request.attachmentIds,
                asTask = request.asTask,
                randomId = randomId
            )
        )
    }
}

internal fun buildPendingMentionActionExecuteBody(action: String, resolutionIds: List<String>): String =
    threadApiJson.encodeToString(
        PendingMentionActionExecuteRequestBody(
            action = action.canonicalPendingMentionActionVerb(),
            resolutionIds = resolutionIds
        )
    )

internal fun String.canonicalPendingMentionActionVerb(): String =
    when (trim()) {
        "invite" -> "add"
        else -> trim()
    }

internal fun buildSharedCreateOrOpenThreadBody(parentMessageId: String): String =
    threadApiJson.encodeToString(ParentMessageThreadRequestBody(parentMessageId))

private fun PendingMentionActionDto.toDomain(): PendingMentionAction? {
    val resolvedId = resolutionId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: return null
    return PendingMentionAction(
        resolutionId = resolvedId,
        messageId = messageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        channelId = channelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        channelName = channelName?.takeIf { it.isNotBlank() },
        targetType = targetType?.takeIf { it.isNotBlank() } ?: "unknown",
        targetHandle = targetHandle.orEmpty(),
        targetAvatarUrl = targetAvatarUrl.normalizedPendingMentionAvatarUrl(),
        reason = reason.orEmpty(),
        availableActions = availableActions.map { it.trim() }.filter { it.isNotBlank() },
        expiresAt = expiresAt?.takeIf { it.isNotBlank() }
    )
}

internal fun String?.normalizedPendingMentionAvatarUrl(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun PendingMentionActionExecuteResultDto.toDomain(): PendingMentionActionExecuteResult =
    PendingMentionActionExecuteResult(
        resolutionId = resolutionId,
        status = status,
        reason = reason
    )
