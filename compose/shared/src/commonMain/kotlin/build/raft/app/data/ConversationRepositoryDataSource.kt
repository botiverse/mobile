package build.raft.app.data

import build.raft.app.projectionRejectingDiagnosticsLogger
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.Message
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.conversationSafeDisplayTitleOrNull
import build.raft.app.model.headerIdentityIds
import build.raft.app.model.nameIsBlankOrIdentity
import build.raft.app.model.parentChannelIdOrNull
import build.raft.app.model.parentMessageIdOrNull
import build.raft.app.model.threadIdentity
import build.raft.app.model.topLevelThreadId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlin.time.TimeSource

private val conversationRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal interface AuthorityBoundAttachmentUploader {
    suspend fun uploadAttachmentsBound(
        request: AttachmentUploadRequest,
        expectedAuthority: SlockExpectedAuthBinding
    ): DataResult<AttachmentUploadResult>
}

internal class RealConversationAuthorityBoundAttachmentUploader(
    private val repository: RealConversationRepository
) : AuthorityBoundAttachmentUploader {
    override suspend fun uploadAttachmentsBound(
        request: AttachmentUploadRequest,
        expectedAuthority: SlockExpectedAuthBinding
    ): DataResult<AttachmentUploadResult> =
        repository.uploadAttachmentsBound(request, expectedAuthority)
}

internal fun interface ProgressiveConversationAttachmentUploader {
    suspend fun uploadAttachmentsWithProgress(
        request: AttachmentUploadRequest,
        onProgress: (build.raft.app.network.SlockTransferProgress) -> Unit
    ): DataResult<AttachmentUploadResult>
}

private fun DataResult<*>.conversationDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

private suspend fun SlockNetworkClient.channelMessages(
    priority: DataRequestPriority,
    channelId: ChannelId,
    limit: Int,
    beforeSeq: Long? = null,
    afterSeq: Long? = null,
    serverIdOverride: ServerId? = null,
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "messages/channel/${channelId.value}",
        serverIdOverride = serverIdOverride,
        query =
            listOfNotNull(
                "limit" to limit.toString(),
                beforeSeq?.let { "before" to it.toString() },
                afterSeq?.let { "after" to it.toString() }
            )
    )

private suspend fun SlockNetworkClient.messageContext(
    priority: DataRequestPriority,
    messageId: MessageId,
    channelId: ChannelId? = null,
    serverIdOverride: ServerId? = null,
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "messages/context/${messageId.value}",
        query = listOfNotNull(channelId?.let { "channelId" to it.value }),
        serverIdOverride = serverIdOverride,
    )

private suspend fun SlockNetworkClient.messageSync(
    priority: DataRequestPriority,
    sinceSeq: Long,
    limit: Int,
    channelId: ChannelId? = null,
    serverIdOverride: ServerId? = null,
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "messages/sync",
        serverIdOverride = serverIdOverride,
        query =
            listOfNotNull(
                "since_seq" to sinceSeq.toString(),
                "limit" to limit.toString(),
                channelId?.let { "channel_id" to it.value }
            )
    )

private suspend fun SlockNetworkClient.channelThreads(
    priority: DataRequestPriority,
    channelId: ChannelId,
    limit: Int? = null,
    parentMessageIds: List<MessageId>? = null,
    serverIdOverride: ServerId? = null,
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "channels/${channelId.value}/threads",
        serverIdOverride = serverIdOverride,
        query =
            listOfNotNull(
                limit?.let { "limit" to it.toString() },
                parentMessageIds
                    ?.asSequence()
                    ?.map { it.value }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.joinToString(",")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "parentMessageIds" to it }
            )
    )

private suspend fun SlockNetworkClient.followedThreads(
    priority: DataRequestPriority,
    limit: Int,
    serverId: ServerId? = null
): HomeNetworkResponse =
    request(
        priority,
        "channels/threads/followed",
        query = listOf("limit" to limit.toString()),
        serverIdOverride = serverId
    )

private suspend fun SlockNetworkClient.createOrOpenThread(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String,
    serverIdOverride: ServerId? = null,
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "channels/${channelId.value}/threads",
        method = "POST",
        bodyText = bodyText,
        serverIdOverride = serverIdOverride,
    )

private suspend fun SlockNetworkClient.followThread(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/threads/follow", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.unfollowThread(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/threads/unfollow", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.markThreadDone(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/threads/done", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.markThreadUndone(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/threads/undone", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.attachmentUrl(
    priority: DataRequestPriority,
    attachmentId: AttachmentId
): HomeNetworkResponse =
    request(priority, "attachments/${attachmentId.value}/url")

private suspend fun SlockNetworkClient.attachmentUploadCapabilities(
    priority: DataRequestPriority
): HomeNetworkResponse =
    request(priority, "attachments/upload-capabilities")

private suspend fun SlockNetworkClient.channelFiles(
    priority: DataRequestPriority,
    channelId: ChannelId,
    limit: Int,
    cursor: String?
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "channels/${channelId.value}/files",
        query = listOfNotNull("limit" to limit.toString(), cursor?.takeIf { it.isNotBlank() }?.let { "cursor" to it })
    )

private suspend fun SlockNetworkClient.sendMessage(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "messages", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.executePendingMentionActions(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "messages/mention-actions/execute", method = "POST", bodyText = bodyText)

class RealConversationRepository private constructor(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val routeServerIdProvider: (() -> String?)? = null,
    private val warningLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
    private val routeEnvironmentIdProvider: (() -> String?)? = null,
    /** Non-null only for the repository produced by [boundTo].  Direct upload sessions are
     * reservation-bearing mutations and must not be reachable through a selector-backed facade. */
    private val directSessionAuthority: SlockExpectedAuthBinding? = null,
) : ConversationRepository, ProgressiveConversationAttachmentUploader {
    /**
     * Upper bound the targeted reply-summary join waits for the account-wide followed-thread
     * list (task #1142). Channel summaries are the authority for reply rows; the followed list
     * only decorates them with `isThreadFollowed`. On accounts following many threads that list
     * is multi-megabyte and a slow link never delivered it before the user left, so the join
     * never ran and an anchored channel page showed no reply rows at all. Tests shrink this.
     */
    internal var followedSummariesJoinBudgetMillis: Long = FOLLOWED_SUMMARIES_JOIN_BUDGET_MILLIS

    /** Public compatibility constructor for ordinary selector-backed repositories. */
    constructor(
        sessionStore: SlockAuthSessionStore,
        networkClient: SlockNetworkClient,
        routeServerIdProvider: (() -> String?)? = null,
        warningLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
        routeEnvironmentIdProvider: (() -> String?)? = null,
    ) : this(
        sessionStore = sessionStore,
        networkClient = networkClient,
        routeServerIdProvider = routeServerIdProvider,
        warningLogger = warningLogger,
        routeEnvironmentIdProvider = routeEnvironmentIdProvider,
        directSessionAuthority = null,
    )

    /**
     * Build a repository pinned to one transient route-authority witness.  The returned instance
     * shares the same auth store but every request (including direct-session compensation) goes
     * through the bound network-client seam.  Callers must capture this once and reuse it for the
     * complete operation; constructing a fresh unbound repository for cleanup would reopen the
     * account/server race this seam is intended to close.
     */
    internal fun boundTo(expectedAuthority: SlockExpectedAuthBinding): RealConversationRepository? {
        if (networkClient !is AuthorityBoundSlockNetworkClient) return null
        if (
            expectedAuthority.accountId.trim().isBlank() ||
            expectedAuthority.accessToken.isBlank() ||
            expectedAuthority.refreshToken.isBlank() ||
            expectedAuthority.serverId.trim().isBlank() ||
            expectedAuthority.environmentId.normalizedEnvironmentId().isBlank()
        ) {
            return null
        }
        return RealConversationRepository(
            sessionStore = sessionStore,
            networkClient = networkClient.boundTo(expectedAuthority),
            // A bound operation must not re-read a mutable route-server selector.  Pin the
            // repository context to the witness as well as the network client; otherwise a
            // remount could make currentContext() validate one server while the transport sends
            // another.
            routeServerIdProvider = { expectedAuthority.serverId },
            routeEnvironmentIdProvider = { expectedAuthority.environmentId },
            warningLogger = warningLogger,
            directSessionAuthority = expectedAuthority,
        )
    }

    /**
     * Capture one bound repository/coordinator for the complete Gallery direct-session lifecycle.
     * Keeping this factory next to [boundTo] makes it difficult for a caller to accidentally use
     * an unbound repository for capability admission or compensation.
     */
    internal fun directAttachmentUploadOperation(
        expectedAuthority: SlockExpectedAuthBinding,
        diagnosticsLogger: (String) -> Unit = {},
    ): DirectAttachmentUploadOperation? =
        boundTo(expectedAuthority)?.let { boundRepository ->
            DirectAttachmentUploadOperation(
                authority = expectedAuthority,
                coordinator = DirectAttachmentUploadCoordinator(
                    repository = boundRepository,
                    diagnosticsLogger = diagnosticsLogger,
                )
            )
        }

    override suspend fun getConversationDetail(
        channelId: ChannelId,
        priority: DataRequestPriority,
    ): DataResult<ConversationSummary> {
        val context = currentContext() ?: return authRequired()
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.request(
                    priority = priority,
                    path = "channels/${channelId.value}",
                    serverIdOverride = context.server.id,
                )
            },
            source = "conversation_detail",
            mapper = { bodyText ->
                parseSlockChannelDetail(bodyText, warningLogger)?.toConversationSummary()
                    ?: throw IllegalStateException("Conversation detail response missing channel")
            },
        )
    }

    override suspend fun getLatestChannelMessage(
        channelId: ChannelId,
        priority: DataRequestPriority,
    ): DataResult<Message> {
        val context = currentContext() ?: return authRequired()
        val result =
            executeConversation(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    networkClient.channelMessages(
                        priority = priority,
                        channelId = channelId,
                        limit = 1,
                        serverIdOverride = context.server.id,
                    )
                },
                emptyWhen = { it.messages.isEmpty() },
                source = "messages_channel_latest",
                mapper = { bodyText ->
                    parseSharedChannelMessages(
                        bodyText = bodyText,
                        fallbackThreadId = ThreadId(channelId.value),
                        diagnostics = warningLogger,
                    )
                }
            )
        return when (result) {
            is DataResult.Content -> {
                val latest = result.data.messages.firstOrNull()
                if (latest != null) {
                    DataResult.Content(latest)
                } else {
                    DataResult.Empty(EmptyReason.NoMessages)
                }
            }
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    override suspend fun getAttachmentUploadCapabilities(): DataResult<AttachmentUploadCapabilities> {
        currentContext() ?: return authRequired()
        return executeConversation(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { networkClient.attachmentUploadCapabilities(DataRequestPriority.Foreground) },
            source = "attachment_upload_capabilities",
            bodyEventFields = ::attachmentUploadCapabilitiesBodyTelemetry,
            mapper = ::parseSharedAttachmentUploadCapabilities
        )
    }

    override suspend fun createAttachmentUploadSession(
        request: AttachmentUploadSessionCreateRequest
    ): DataResult<AttachmentUploadSessionCreateResult> {
        if (directSessionAuthority == null) return directSessionAuthorityRequired()
        val context = currentContext() ?: return authRequired()
        if (request.channelId.value.isBlank() || request.filename.isBlank() || request.mimeType.isBlank() ||
            request.sizeBytes <= 0L || request.clientRequestId.isBlank()
        ) {
            return DataResult.Error(ScreenError("attachment_upload_session_invalid_request", retryable = false))
        }
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.createAttachmentUploadSession(
                    priority = DataRequestPriority.Foreground,
                    request = request,
                    serverIdOverride = context.server.id,
                )
            },
            source = "attachment_upload_session_create",
            mapper = ::parseSharedAttachmentUploadSessionCreate,
        )
    }

    override suspend fun putAttachmentUploadBytes(
        upload: AttachmentUploadSessionPut,
        bytes: ByteArray,
        onUploadProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)?,
    ): DataResult<Unit> {
        if (directSessionAuthority == null) return directSessionAuthorityRequired()
        currentContext() ?: return authRequired()
        if (bytes.isEmpty()) {
            return DataResult.Error(ScreenError("attachment_upload_session_empty_body", retryable = false))
        }
        return executeAttachmentUploadUnit(
            source = "attachment_upload_session_put_bytes",
            block = {
                networkClient.putAttachmentUploadBytes(
                    priority = DataRequestPriority.Foreground,
                    upload = upload,
                    bytes = bytes,
                    onUploadProgress = onUploadProgress,
                )
            }
        )
    }

    override suspend fun putAttachmentUploadFile(
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        onUploadProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)?,
    ): DataResult<Unit> {
        if (directSessionAuthority == null) return directSessionAuthorityRequired()
        currentContext() ?: return authRequired()
        if (file.fileRef.isNullOrBlank() && file.bytes.isEmpty()) {
            return DataResult.Error(ScreenError("attachment_upload_session_empty_body", retryable = false))
        }
        return executeAttachmentUploadUnit(
            source = "attachment_upload_session_put_file",
            block = {
                networkClient.putAttachmentUploadFile(
                    priority = DataRequestPriority.Foreground,
                    upload = upload,
                    file = file,
                    onUploadProgress = onUploadProgress,
                )
            }
        )
    }

    override suspend fun prepareAttachmentUploadFile(
        file: PlatformFile,
    ): DataResult<PlatformFile> {
        if (directSessionAuthority == null) return directSessionAuthorityRequired()
        currentContext() ?: return authRequired()
        val normalizedRef = file.fileRef?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedRef != null && file.bytes.isNotEmpty()) {
            return DataResult.Error(
                ScreenError("attachment_file_input_conflict", retryable = false)
            )
        }
        val preparation = networkClient.prepareAttachmentUploadFile(
            file.copy(fileRef = normalizedRef)
        )
        return when (preparation) {
            is SlockPlatformFilePreparationResult.Ready -> {
                val size = preparation.sizeBytes
                if (size <= 0L) {
                    DataResult.Error(ScreenError("attachment_size_unavailable", retryable = false))
                } else {
                    DataResult.Content(
                        file.copy(
                            fileRef = normalizedRef,
                            sizeBytes = size,
                        )
                    )
                }
            }
            is SlockPlatformFilePreparationResult.Rejected ->
                if (preparation.code == "auth_authority_changed") {
                    DataResult.Error(ScreenError("auth_authority_changed", retryable = true))
                } else {
                    DataResult.Error(
                        ScreenError(
                            code = preparation.code.ifBlank { "attachment_size_unavailable" },
                            retryable = preparation.retryable,
                        )
                    )
                }
        }
    }

    override suspend fun completeAttachmentUploadSession(
        uploadId: String
    ): DataResult<AttachmentUploadSessionCompleteResult> {
        if (directSessionAuthority == null) return directSessionAuthorityRequired()
        val context = currentContext() ?: return authRequired()
        val normalizedId = uploadId.trim()
        if (normalizedId.isBlank()) {
            return DataResult.Error(ScreenError("attachment_upload_session_invalid_request", retryable = false))
        }
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.completeAttachmentUploadSession(
                    priority = DataRequestPriority.Foreground,
                    uploadId = normalizedId,
                    serverIdOverride = context.server.id,
                )
            },
            source = "attachment_upload_session_complete",
            mapper = ::parseSharedAttachmentUploadSessionComplete,
        )
    }

    override suspend fun cancelAttachmentUploadSession(
        uploadId: String
    ): DataResult<AttachmentUploadSessionView> {
        if (directSessionAuthority == null) return directSessionAuthorityRequired()
        val context = currentContext() ?: return authRequired()
        val normalizedId = uploadId.trim()
        if (normalizedId.isBlank()) {
            return DataResult.Error(ScreenError("attachment_upload_session_invalid_request", retryable = false))
        }
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.cancelAttachmentUploadSession(
                    priority = DataRequestPriority.Foreground,
                    uploadId = normalizedId,
                    serverIdOverride = context.server.id,
                )
            },
            source = "attachment_upload_session_cancel",
            mapper = ::parseSharedAttachmentUploadSessionView,
        )
    }

    override suspend fun statusAttachmentUploadSession(
        uploadId: String
    ): DataResult<AttachmentUploadSessionView> {
        if (directSessionAuthority == null) return directSessionAuthorityRequired()
        val context = currentContext() ?: return authRequired()
        val normalizedId = uploadId.trim()
        if (normalizedId.isBlank()) {
            return DataResult.Error(ScreenError("attachment_upload_session_invalid_request", retryable = false))
        }
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.statusAttachmentUploadSession(
                    priority = DataRequestPriority.Foreground,
                    uploadId = normalizedId,
                    serverIdOverride = context.server.id,
                )
            },
            source = "attachment_upload_session_status",
            mapper = ::parseSharedAttachmentUploadSessionView,
        )
    }
    override suspend fun getThread(
        threadId: ThreadId,
        includeFollowedSummaries: Boolean,
        limit: Int
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        val messageLimit = limit.coerceAtLeast(1)
        return loadChannelMessages(
            context = context,
            threadId = threadId,
            messageLimit = messageLimit,
            block = {
                networkClient.channelMessages(
                    DataRequestPriority.Foreground,
                    ChannelId(threadId.value),
                    messageLimit,
                    serverIdOverride = context.server.id,
                )
            },
            source = "messages_channel",
            includeChannelSummaries = includeFollowedSummaries,
            includeFollowedSummaries = includeFollowedSummaries,
            hydrateConversationDetail = true,
        )
    }

    override suspend fun getThreadReplySummaries(
        threadId: ThreadId,
        parentMessageIds: List<MessageId>
    ): DataResult<ThreadReplySummariesSnapshot> = coroutineScope {
        val context = currentContext() ?: return@coroutineScope authRequired()
        val visibleParentMessageIds = parentMessageIds.distinct()
        if (visibleParentMessageIds.isEmpty()) {
            return@coroutineScope DataResult.Content(ThreadReplySummariesSnapshot(threads = emptyList()))
        }
        val loadStartedAt = TimeSource.Monotonic.markNow()
        warningLogger(
            "thread_summary_load_start threadId=${threadId.value} " +
                "parentMessageIds=${visibleParentMessageIds.size}"
        )
        val channelSummariesDeferred =
            async {
                executeConversation(
                    emptyReason = EmptyReason.NoInboxItems,
                    block = {
                        networkClient.channelThreads(
                            priority = DataRequestPriority.Visible,
                            channelId = ChannelId(threadId.value),
                            parentMessageIds = visibleParentMessageIds,
                            serverIdOverride = context.server.id,
                        )
                    },
                    emptyWhen = { false },
                    source = "thread_summary_channel_threads",
                    mapper = { bodyText -> parseChannelThreadListSnapshotFromBody(bodyText, context) }
                )
            }
        val followedSummariesDeferred =
            async {
                executeConversation(
                    emptyReason = EmptyReason.NoInboxItems,
                    block = {
                        networkClient.followedThreads(
                            DataRequestPriority.Visible,
                            MESSAGE_PAGE_LIMIT,
                            context.server.id,
                        )
                    },
                    emptyWhen = { false },
                    source = "thread_summary_followed_threads",
                    mapper = { bodyText -> parseFollowedThreadListSnapshotFromBody(bodyText, context) }
                )
            }
        val channelSummaries = channelSummariesDeferred.await()
        // Task #1142: never let the account-wide followed list gate the channel summaries.
        val followedSummaries =
            withTimeoutOrNull(followedSummariesJoinBudgetMillis) { followedSummariesDeferred.await() }
        if (followedSummaries == null) {
            followedSummariesDeferred.cancel()
            warningLogger(
                "thread_summary_followed_budget_exceeded threadId=${threadId.value} " +
                    "budgetMs=$followedSummariesJoinBudgetMillis"
            )
        }
        val channelThreads =
            (channelSummaries as? DataResult.Content)
                ?.data
                ?.threads
                .orEmpty()
        val followedForChannel =
            (followedSummaries as? DataResult.Content)
                ?.data
                ?.threads
                ?.filter { it.parentChannelId?.value == threadId.value }
                .orEmpty()
        val followedThreadKeys = followedForChannel.mapTo(mutableSetOf()) { it.key }
        val mergedThreads = (channelThreads + followedForChannel).distinctBy { it.key }
        warningLogger(
            "thread_summary_load_result threadId=${threadId.value} " +
                "channelSummaries=${channelThreads.size} followedSummaries=${followedForChannel.size} " +
                "followedList=${if (followedSummaries == null) "budget_exceeded" else "ok"} " +
                "mergedSummaries=${mergedThreads.size} durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
        )
        if (channelSummaries is DataResult.Error && followedSummaries !is DataResult.Content) {
            DataResult.Error(channelSummaries.error)
        } else {
            DataResult.Content(
                ThreadReplySummariesSnapshot(
                    threads = mergedThreads,
                    followedThreadKeys = followedThreadKeys
                )
            )
        }
    }

    override suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean,
        limit: Int
    ): DataResult<ThreadSnapshot> =
        getReplyThread(
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            includeFollowedSummaries = includeFollowedSummaries,
            limit = limit,
            priority = DataRequestPriority.Foreground
        )

    override suspend fun getReplyThread(
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        includeFollowedSummaries: Boolean,
        limit: Int,
        priority: DataRequestPriority
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        val messageLimit = limit.coerceAtLeast(1)
        return loadReplyThread(
            context = context,
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            messageLimit = messageLimit,
            priority = priority
        )
    }

    override suspend fun getMessageContext(messageId: MessageId, channelId: ChannelId?): DataResult<ThreadSnapshot> =
        getMessageContext(messageId, channelId, DataRequestPriority.Foreground)

    override suspend fun getMessageContext(
        messageId: MessageId,
        channelId: ChannelId?,
        priority: DataRequestPriority
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        var canonicalTargetKind: String? = null
        val result = executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.messageContext(
                    priority = priority,
                    messageId = messageId,
                    channelId = channelId,
                    serverIdOverride = context.server.id,
                )
            },
            emptyWhen = { it.messages.isEmpty() && it.parentMessage == null },
            source = "messages_context",
            mapper = { bodyText ->
                val fallbackThreadId = ThreadId(messageId.value)
                val payload = parseSharedMessageContext(bodyText, fallbackThreadId, warningLogger)
                canonicalTargetKind = payload.canonicalTarget?.kind
                val resolvedThreadId = payload.channelId?.let { ThreadId(it.value) } ?: fallbackThreadId
                buildSharedThreadSnapshot(
                    payload = payload,
                    threadId = resolvedThreadId,
                    canLoadMoreBefore = payload.hasOlder,
                    canLoadMoreAfter = payload.hasNewer,
                    highlightedMessageId = payload.targetMessageId ?: messageId,
                    diagnostics = warningLogger,
                )
            }
        )
        val snapshot = (result as? DataResult.Content)?.data ?: return result
        val detailChannelId = snapshot.conversation.channelId ?: channelId ?: return result
        val detail = loadConversationDetail(detailChannelId, priority, context.server.id)
        return DataResult.Content(
            snapshot.withConversationDetail(
                detail = detail,
                canonicalReplyThread = canonicalTargetKind == KuiklyThreadSurfaceKind.ReplyThread
            )
        )
    }

    private suspend fun loadConversationDetail(
        channelId: ChannelId,
        priority: DataRequestPriority,
        serverId: ServerId,
    ): ConversationSummary? {
        val response =
            try {
                networkClient.request(
                    priority = priority,
                    path = "channels/${channelId.value}",
                    serverIdOverride = serverId,
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                warningLogger(
                    "conversation_detail_unavailable channelId=${channelId.value} " +
                        "exception=${exception::class.simpleName}"
                )
                return null
            }
        if (!response.isSuccessful) {
            warningLogger("conversation_detail_unavailable channelId=${channelId.value} status=${response.code}")
            return null
        }
        val detail = parseSlockChannelDetail(response.bodyText.orEmpty(), warningLogger)?.toConversationSummary()
        if (detail == null) {
            warningLogger("conversation_detail_parse_failed channelId=${channelId.value}")
        }
        return detail
    }

    override suspend fun loadOlderMessages(
        threadId: ThreadId,
        beforeSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        val messageLimit = limit.coerceAtLeast(1)
        return loadChannelMessages(
            context = context,
            threadId = threadId,
            messageLimit = messageLimit,
            block = {
                networkClient.channelMessages(
                    DataRequestPriority.Visible,
                    ChannelId(threadId.value),
                    messageLimit,
                    beforeSeq = beforeSeq,
                    serverIdOverride = context.server.id,
                )
            },
            source = "messages_channel_older",
            includeChannelSummaries = false,
            captureRawMessagePage = true,
        )
    }

    override suspend fun loadLatestMessages(
        threadId: ThreadId,
        limit: Int
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        val messageLimit = limit.coerceAtLeast(1)
        return loadChannelMessages(
            context = context,
            threadId = threadId,
            messageLimit = messageLimit,
            block = {
                networkClient.channelMessages(
                    DataRequestPriority.Visible,
                    ChannelId(threadId.value),
                    messageLimit,
                    serverIdOverride = context.server.id,
                )
            },
            source = "messages_channel_latest",
            includeChannelSummaries = false,
            captureRawMessagePage = true,
        )
    }

    override suspend fun loadNewerMessages(
        threadId: ThreadId,
        afterSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        val messageLimit = limit.coerceAtLeast(1)
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.channelMessages(
                    DataRequestPriority.Visible,
                    ChannelId(threadId.value),
                    messageLimit,
                    afterSeq = afterSeq,
                    serverIdOverride = context.server.id,
                )
            },
            emptyWhen = { it.messages.isEmpty() },
            source = "messages_channel_newer",
            mapper = { bodyText ->
                val payload = parseSharedChannelMessages(bodyText, threadId, warningLogger)
                buildSharedThreadSnapshot(
                    payload = payload,
                    threadId = threadId,
                    canLoadMoreAfter = payload.messages.size >= messageLimit,
                    diagnostics = warningLogger,
                ).copy(rawMessagePage = rawMessagePageWitness(bodyText, messageLimit))
            }
        )
    }

    override suspend fun syncNewerMessages(
        threadId: ThreadId,
        sinceSeq: Long,
        limit: Int
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        val messageLimit = limit.coerceAtLeast(1)
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.messageSync(
                    DataRequestPriority.Visible,
                    sinceSeq = sinceSeq,
                    limit = messageLimit,
                    channelId = ChannelId(threadId.value),
                    serverIdOverride = context.server.id,
                )
            },
            emptyWhen = { it.messages.isEmpty() },
            source = "messages_sync",
            mapper = { bodyText ->
                val payload = parseSharedSyncMessages(bodyText, threadId, warningLogger)
                buildSharedThreadSnapshot(
                    payload = payload,
                    threadId = threadId,
                    canLoadMoreAfter = payload.messages.size >= messageLimit,
                    diagnostics = warningLogger,
                )
            }
        )
    }

    override suspend fun getChannelThreads(
        channelId: ChannelId,
        limit: Int
    ): DataResult<ThreadListSnapshot> {
        val context = currentContext() ?: return authRequired()
        return executeConversation(
            emptyReason = EmptyReason.NoInboxItems,
            block = {
                networkClient.channelThreads(
                    DataRequestPriority.Visible,
                    channelId,
                    limit,
                    serverIdOverride = context.server.id,
                )
            },
            emptyWhen = { it.threads.isEmpty() },
            source = "channel_threads",
            mapper = { bodyText -> parseChannelThreadListSnapshotFromBody(bodyText, context) }
        )
    }

    override suspend fun getFollowedThreads(
        serverId: ServerId,
        limit: Int
    ): DataResult<ThreadListSnapshot> {
        val context = sessionStore.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return executeConversation(
            emptyReason = EmptyReason.NoInboxItems,
            block = { networkClient.followedThreads(DataRequestPriority.Visible, limit, context.server.id) },
            emptyWhen = { it.threads.isEmpty() },
            source = "followed_threads",
            mapper = { bodyText -> parseFollowedThreadListSnapshotFromBody(bodyText, context) }
        )
    }

    override suspend fun createOrOpenThread(
        channelId: ChannelId,
        parentMessageId: MessageId
    ): DataResult<ThreadActionResult> {
        val context = currentContext() ?: return authRequired()
        return threadAction(
            fallbackThreadChannelId = null,
            fallbackParentMessageId = parentMessageId,
            source = "thread_create_or_open"
        ) {
            networkClient.createOrOpenThread(
                DataRequestPriority.Foreground,
                channelId,
                parentMessageId.toParentMessageThreadBody(),
                serverIdOverride = context.server.id,
            )
        }
    }

    override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> =
        threadAction(
            fallbackThreadChannelId = null,
            fallbackParentMessageId = parentMessageId,
            source = "thread_follow_parent"
        ) {
            networkClient.followThread(DataRequestPriority.Foreground, parentMessageId.toParentMessageThreadBody())
        }

    override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        threadAction(threadChannelId, source = "thread_unfollow") {
            networkClient.unfollowThread(DataRequestPriority.Foreground, threadChannelId.toThreadActionBody())
        }

    override suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        threadAction(threadChannelId, source = "thread_done") {
            networkClient.markThreadDone(DataRequestPriority.Foreground, threadChannelId.toThreadActionBody())
        }

    override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        threadAction(threadChannelId, source = "thread_undone") {
            networkClient.markThreadUndone(DataRequestPriority.Foreground, threadChannelId.toThreadActionBody())
        }

    override suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult> =
        uploadAttachmentsInternal(request, onProgress = null)

    override suspend fun uploadAttachmentsWithProgress(
        request: AttachmentUploadRequest,
        onProgress: (build.raft.app.network.SlockTransferProgress) -> Unit
    ): DataResult<AttachmentUploadResult> =
        uploadAttachmentsInternal(request, onProgress)

    private suspend fun uploadAttachmentsInternal(
        request: AttachmentUploadRequest,
        onProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)?
    ): DataResult<AttachmentUploadResult> {
        currentContext() ?: return authRequired()
        if (request.files.isEmpty()) return DataResult.Empty(EmptyReason.NoMessages)
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                val files = request.files.map { it.toMultipartFile() }
                val progressive = networkClient as? ProgressiveAttachmentNetworkClient
                if (onProgress != null && progressive != null) {
                    progressive.uploadAttachmentsWithProgress(
                        priority = DataRequestPriority.Foreground,
                        channelId = request.channelId,
                        files = files,
                        onProgress = onProgress
                    )
                } else {
                    networkClient.uploadAttachments(
                        priority = DataRequestPriority.Foreground,
                        channelId = request.channelId,
                        files = files
                    )
                }
            },
            source = "attachments_upload",
            mapper = { bodyText -> parseSharedAttachmentUploadResult(bodyText) },
            emptyWhen = { it.attachments.isEmpty() }
        )
    }

    internal suspend fun uploadAttachmentsBound(
        request: AttachmentUploadRequest,
        expectedAuthority: SlockExpectedAuthBinding
    ): DataResult<AttachmentUploadResult> {
        val context = currentContext() ?: return authRequired()
        if (
            !context.session.matchesExpectedAuthority(
                expected = expectedAuthority,
                effectiveServerId = context.server.id.value,
                effectiveEnvironmentId = routeEnvironmentIdProvider?.invoke(),
            )
        ) {
            return DataResult.Error(ScreenError("external_share_authority_changed", retryable = true))
        }
        if (request.files.isEmpty()) return DataResult.Empty(EmptyReason.NoMessages)
        val boundNetwork = networkClient as? AuthorityBoundSlockNetworkClient
            ?: return DataResult.Error(ScreenError("attachment_upload_authority_binding_unsupported", retryable = false))
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                boundNetwork.uploadAttachmentsBound(
                    priority = DataRequestPriority.Foreground,
                    channelId = request.channelId,
                    files = request.files.map { it.toMultipartFile() },
                    expectedAuthority = expectedAuthority
                )
            },
            source = "attachments_upload_bound",
            mapper = { bodyText -> parseSharedAttachmentUploadResult(bodyText) },
            emptyWhen = { it.attachments.isEmpty() }
        )
    }

    override suspend fun getAttachmentOpenTarget(attachmentId: AttachmentId): DataResult<AttachmentOpenTarget> {
        currentContext() ?: return authRequired()
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = { networkClient.attachmentUrl(DataRequestPriority.Foreground, attachmentId) },
            source = "attachment_open_url",
            mapper = { bodyText ->
                parseSharedAttachmentOpenTarget(bodyText, attachmentId)
                    ?: throw IllegalStateException("Attachment URL response missing target")
            },
            emptyWhen = { it.url.isBlank() }
        )
    }

    override suspend fun getChannelFiles(
        channelId: ChannelId,
        limit: Int,
        cursor: String?
    ): DataResult<ChannelFilesSnapshot> {
        currentContext() ?: return authRequired()
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = { networkClient.channelFiles(DataRequestPriority.Visible, channelId, limit, cursor) },
            source = "channel_files",
            mapper = { bodyText -> parseSharedChannelFilesSnapshot(bodyText, channelId) },
            emptyWhen = { it.items.isEmpty() }
        )
    }

    override suspend fun sendMessage(
        channelId: ChannelId,
        body: String,
        attachmentIds: List<AttachmentId>,
        asTask: Boolean,
        randomId: String?
    ): DataResult<SendMessageResult> {
        val context = currentContext() ?: return authRequired()
        if (body.isBlank() && attachmentIds.isEmpty()) return DataResult.Empty(EmptyReason.NoMessages)
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.sendMessage(
                    DataRequestPriority.Foreground,
                    buildSharedThreadSendMessageBody(
                        channelId = channelId.value,
                        body = body,
                        attachmentIds = attachmentIds.map { it.value },
                        asTask = asTask,
                        randomId = randomId
                    )
                )
            },
            source = "messages_send",
            mapper = { bodyText ->
                val fallbackThreadId = topLevelThreadId(channelId)
                val sentPayload = parseSharedSentMessagePayload(bodyText, fallbackThreadId, warningLogger)
                if (sentPayload.projectionRejected) {
                    error("sent message projection rejected")
                }
                val message =
                    sentPayload.message
                        ?: Message(
                            id = MessageId("message-sent"),
                            threadId = fallbackThreadId,
                            channelId = channelId,
                            author = context.session.user.toMemberProfile(),
                            timestampLabel = "",
                            body = body,
                            deliveryState = MessageDeliveryState.Sent
                        )
                SendMessageResult(message, pendingMentionActions = sentPayload.pendingMentionActions)
            }
        )
    }

    override suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): DataResult<List<PendingMentionActionExecuteResult>> {
        currentContext() ?: return authRequired()
        val normalizedAction =
            when (action.trim().lowercase()) {
                "invite", "add" -> "invite"
                "notify", "notify_only" -> "notify"
                else -> return DataResult.Error(ScreenError(code = "mention_action_unsupported", retryable = false))
            }
        val normalizedResolutionIds = resolutionIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (normalizedResolutionIds.isEmpty()) return DataResult.Empty(EmptyReason.NoMessages)
        return executeConversation(
            emptyReason = EmptyReason.NoMessages,
            block = {
                networkClient.executePendingMentionActions(
                    DataRequestPriority.Foreground,
                    buildPendingMentionActionExecuteBody(
                        action = normalizedAction,
                        resolutionIds = normalizedResolutionIds
                    )
                )
            },
            source = "mention_actions_execute",
            mapper = { bodyText ->
                parsePendingMentionActionExecuteResults(bodyText)
            },
            emptyWhen = { it.isEmpty() }
        )
    }

    private suspend fun loadReplyThread(
        context: NetworkServerContext,
        parentChannelId: ChannelId,
        parentMessageId: MessageId,
        messageLimit: Int,
        priority: DataRequestPriority,
        source: String = "messages_channel_reply_thread"
    ): DataResult<ThreadSnapshot> = coroutineScope {
        val loadStartedAt = TimeSource.Monotonic.markNow()
        warningLogger(
            "reply_thread_load_start source=$source parentChannelId=${parentChannelId.value} " +
                "parentMessageId=${parentMessageId.value} messageLimit=$messageLimit"
        )
        val createOrOpen =
            threadAction(
                fallbackThreadChannelId = null,
                fallbackParentMessageId = parentMessageId,
                source = "thread_create_or_open"
            ) {
                networkClient.createOrOpenThread(
                    priority,
                    parentChannelId,
                    parentMessageId.toParentMessageThreadBody(),
                    serverIdOverride = context.server.id,
                )
            }
        if (createOrOpen !is DataResult.Content) {
            warningLogger(
                "reply_thread_load_result source=$source " +
                    "result=${createOrOpen.conversationDiagnosticsResult()} " +
                    "parentChannelId=${parentChannelId.value} parentMessageId=${parentMessageId.value} " +
                    "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            return@coroutineScope when (createOrOpen) {
                is DataResult.Empty -> createOrOpen
                is DataResult.Error -> createOrOpen
                is DataResult.NoPermission -> createOrOpen
                is DataResult.Content -> error("unreachable reply thread create/open content branch")
            }
        }
        val resolvedThreadChannelId = createOrOpen.data.threadChannelId
        val detailDeferred =
            async {
                loadConversationDetail(
                    channelId = parentChannelId,
                    priority = priority,
                    serverId = context.server.id,
                )
            }
        val messagesResult =
            executeConversation(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    networkClient.channelMessages(
                        priority,
                        resolvedThreadChannelId,
                        messageLimit,
                        serverIdOverride = context.server.id,
                    )
                },
                emptyWhen = { it.messages.isEmpty() },
                source = source,
                mapper = { bodyText ->
                    parseSharedChannelMessages(
                        bodyText,
                        ThreadId(resolvedThreadChannelId.value),
                        warningLogger,
                    )
                }
            )
        val rawMessages: List<Message> =
            (messagesResult as? DataResult.Content)?.data?.messages.orEmpty()
        val snapshot =
            buildSharedThreadSnapshot(
                payload = SharedMessagesPayload(
                    messages = rawMessages,
                    hasOlder = (messagesResult as? DataResult.Content)?.data?.hasOlder ?: false,
                    hasNewer = (messagesResult as? DataResult.Content)?.data?.hasNewer ?: false
                ),
                threadId = ThreadId(resolvedThreadChannelId.value),
                canLoadMoreBefore = rawMessages.size >= messageLimit,
                diagnostics = warningLogger,
            ).withReplyThreadConversation(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                streamChannelId = resolvedThreadChannelId
            ).withConversationDetail(
                detail = detailDeferred.await(),
                canonicalReplyThread = true,
            )
        warningLogger(
            "reply_thread_load_result source=$source " +
                "result=${messagesResult.conversationDiagnosticsResult()} " +
                "messages=${rawMessages.size} tasks=${snapshot.tasks.size} " +
                "resolvedThreadChannelId=${resolvedThreadChannelId.value} " +
                "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
        )
        when {
            messagesResult is DataResult.Content -> DataResult.Content(snapshot)
            messagesResult is DataResult.Empty &&
                messagesResult.reason == EmptyReason.NoMessages -> DataResult.Content(snapshot)
            else -> messagesResult as DataResult<ThreadSnapshot>
        }
    }

    private suspend fun loadChannelMessages(
        context: NetworkServerContext,
        threadId: ThreadId,
        messageLimit: Int,
        block: suspend () -> HomeNetworkResponse,
        source: String,
        includeChannelSummaries: Boolean = false,
        includeFollowedSummaries: Boolean = false,
        hydrateConversationDetail: Boolean = false,
        /** Task #168: only the older-page and gap catch-up fetches carry the raw page to the durable writer. */
        captureRawMessagePage: Boolean = false,
    ): DataResult<ThreadSnapshot> = coroutineScope {
        val loadStartedAt = TimeSource.Monotonic.markNow()
        warningLogger(
            "thread_load_start source=$source threadId=${threadId.value} " +
                "messageLimit=$messageLimit includeChannelSummaries=$includeChannelSummaries " +
                "includeFollowedSummaries=$includeFollowedSummaries"
        )
        val messagesDeferred =
            async {
                val branchStartedAt = TimeSource.Monotonic.markNow()
                val result = executeConversation(
                    emptyReason = EmptyReason.NoMessages,
                    block = block,
                    emptyWhen = { it.messages.isEmpty() },
                    source = source,
                    mapper = { bodyText ->
                        val payload = parseSharedChannelMessages(bodyText, threadId, warningLogger)
                        buildSharedThreadSnapshot(
                            payload = payload,
                            threadId = threadId,
                            canLoadMoreBefore = payload.messages.size >= messageLimit,
                            diagnostics = warningLogger,
                        ).let { snapshot ->
                            if (captureRawMessagePage) {
                                snapshot.copy(rawMessagePage = rawMessagePageWitness(bodyText, messageLimit))
                            } else {
                                snapshot
                            }
                        }
                    }
                )
                warningLogger(
                    "thread_load_branch source=$source branch=messages " +
                        "result=${result.conversationDiagnosticsResult()} " +
                        "messages=${(result as? DataResult.Content)?.data?.messages?.size ?: 0} " +
                        "tasks=${(result as? DataResult.Content)?.data?.tasks?.size ?: 0} " +
                        "durationMs=${branchStartedAt.elapsedNow().inWholeMilliseconds}"
                )
                result
            }
        val conversationDetailDeferred =
            if (hydrateConversationDetail) {
                async {
                    loadConversationDetail(
                        channelId = ChannelId(threadId.value),
                        priority = DataRequestPriority.Foreground,
                        serverId = context.server.id,
                    )
                }
            } else {
                null
            }
        val messagesResult = messagesDeferred.await()
        val visibleParentMessageIds =
            (messagesResult as? DataResult.Content)
                ?.data
                ?.messages
                ?.map { it.id }
                .orEmpty()
        val embeddedSummariesAvailable =
            (messagesResult as? DataResult.Content)
                ?.data
                ?.replySummariesAuthority == ThreadReplySummariesAuthority.Embedded
        val shouldFetchTargetedSummaries = includeChannelSummaries
        val threadSummariesDeferred =
            if (shouldFetchTargetedSummaries && visibleParentMessageIds.isNotEmpty()) {
                async {
                    val branchStartedAt = TimeSource.Monotonic.markNow()
                    val result = executeConversation(
                        emptyReason = EmptyReason.NoInboxItems,
                        block = {
                            networkClient.channelThreads(
                                priority = DataRequestPriority.Visible,
                                channelId = ChannelId(threadId.value),
                                parentMessageIds = visibleParentMessageIds,
                                serverIdOverride = context.server.id,
                            )
                        },
                        emptyWhen = { false },
                        source = "${source}_thread_summaries",
                        mapper = { bodyText -> parseChannelThreadListSnapshotFromBody(bodyText, context) }
                    )
                    warningLogger(
                        "thread_load_branch source=$source branch=channel_thread_summaries " +
                            "result=${result.conversationDiagnosticsResult()} " +
                            "parentMessageIds=${visibleParentMessageIds.size} " +
                            "threads=${(result as? DataResult.Content)?.data?.threads?.size ?: 0} " +
                            "durationMs=${branchStartedAt.elapsedNow().inWholeMilliseconds}"
                    )
                    result
                }
            } else {
                val reason =
                    when {
                        visibleParentMessageIds.isEmpty() -> "no_visible_messages"
                        embeddedSummariesAvailable -> "embedded"
                        else -> "disabled"
                    }
                warningLogger(
                    "thread_load_branch source=$source branch=channel_thread_summaries " +
                        "result=skipped:$reason parentMessageIds=${visibleParentMessageIds.size} threads=0 durationMs=0"
                )
                null
            }
        val followedSummariesDeferred =
            if (includeFollowedSummaries) {
                async {
                    val branchStartedAt = TimeSource.Monotonic.markNow()
                    val result = executeConversation(
                        emptyReason = EmptyReason.NoInboxItems,
                        block = {
                            networkClient.followedThreads(
                                DataRequestPriority.Visible,
                                MESSAGE_PAGE_LIMIT,
                                context.server.id,
                            )
                        },
                        emptyWhen = { false },
                        source = "${source}_followed_summaries",
                        mapper = { bodyText -> parseFollowedThreadListSnapshotFromBody(bodyText, context) }
                    )
                    warningLogger(
                        "thread_load_branch source=$source branch=followed_thread_summaries " +
                            "result=${result.conversationDiagnosticsResult()} " +
                            "threads=${(result as? DataResult.Content)?.data?.threads?.size ?: 0} " +
                            "durationMs=${branchStartedAt.elapsedNow().inWholeMilliseconds}"
                    )
                    result
                }
            } else {
                warningLogger(
                    "thread_load_branch source=$source branch=followed_thread_summaries " +
                        "result=skipped:disabled threads=0 durationMs=0"
                )
                null
            }
        val threadSummaries = threadSummariesDeferred?.await()
        val followedSummaries = followedSummariesDeferred?.await()
        val canonicalDetail = conversationDetailDeferred?.await()
        if (
            messagesResult is DataResult.Empty &&
            messagesResult.reason == EmptyReason.NoMessages &&
            canonicalDetail != null
        ) {
            warningLogger(
                "thread_load_result source=$source threadId=${threadId.value} " +
                    "result=content:canonical_empty messages=0 tasks=0 channelSummaries=0 " +
                    "followedSummaries=0 mergedSummaries=0 " +
                    "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            DataResult.Content(
                ThreadSnapshot(
                    conversation = canonicalDetail,
                    canonicalConversationDetail = canonicalDetail,
                    messages = emptyList(),
                )
            )
        } else if (messagesResult !is DataResult.Content) {
            warningLogger(
                "thread_load_result source=$source threadId=${threadId.value} " +
                    "result=${messagesResult.conversationDiagnosticsResult()} messages=0 tasks=0 " +
                    "channelSummaries=${(threadSummaries as? DataResult.Content)?.data?.threads?.size ?: 0} " +
                    "followedSummaries=${(followedSummaries as? DataResult.Content)?.data?.threads?.size ?: 0} " +
                    "mergedSummaries=0 durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            messagesResult
        } else {
            val messagesSnapshot =
                messagesResult.data.withConversationDetail(
                    detail = canonicalDetail,
                    canonicalReplyThread = false,
                )
            val channelThreads = if (threadSummaries is DataResult.Content) threadSummaries.data.threads else emptyList()
            val followedForChannel =
                if (followedSummaries is DataResult.Content) {
                    followedSummaries.data.threads.filter { it.parentChannelId?.value == threadId.value }
                } else {
                    emptyList()
                }
            val followedThreadKeys = followedForChannel.map { it.key }.toSet()
            val mergedThreads = (channelThreads + followedForChannel).distinctBy { it.key }
            warningLogger(
                "thread_load_result source=$source threadId=${threadId.value} " +
                    "result=${messagesResult.conversationDiagnosticsResult()} " +
                    "messages=${messagesResult.data.messages.size} tasks=${messagesResult.data.tasks.size} " +
                    "channelSummaries=${channelThreads.size} followedSummaries=${followedForChannel.size} " +
                    "mergedSummaries=${mergedThreads.size} " +
                    "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            if (threadSummaries is DataResult.Error && followedSummaries is DataResult.Error) {
                warningLogger(
                    "thread_summaries_error source=$source " +
                        "channel_code=${threadSummaries.error.code} " +
                        "followed_code=${followedSummaries.error.code}"
                )
                if (
                    messagesResult.data.replySummariesAuthority ==
                    ThreadReplySummariesAuthority.EmbeddedInvalid
                ) {
                    DataResult.Content(
                        messagesSnapshot.copy(
                            replySummariesAuthority = ThreadReplySummariesAuthority.Unavailable,
                        )
                    )
                } else {
                    DataResult.Content(messagesSnapshot)
                }
            } else {
                warningLogger(
                    "thread_summaries_loaded source=$source " +
                        "channel=${channelThreads.size} followed_filtered=${followedForChannel.size} " +
                        "merged=${mergedThreads.size} " +
                        "candidateIds=${mergedThreads.take(3).joinToString { t ->
                            "${t.channelId.value.take(8)}/" +
                                "${t.threadChannelId?.value?.take(8) ?: "null"}/" +
                                (t.parentMessageId?.value?.take(8) ?: "null")
                        }}"
                )
                val authority =
                    when {
                        threadSummaries is DataResult.Content -> ThreadReplySummariesAuthority.Targeted
                        messagesSnapshot.replySummariesAuthority == ThreadReplySummariesAuthority.Embedded ->
                            ThreadReplySummariesAuthority.Embedded
                        else -> ThreadReplySummariesAuthority.Unavailable
                    }
                val authoritativeThreads =
                    if (authority == ThreadReplySummariesAuthority.Targeted) {
                        mergedThreads
                    } else {
                        messagesSnapshot.replySummaries.threads
                    }
                DataResult.Content(
                    messagesSnapshot.withReplySummaries(
                        threads = authoritativeThreads,
                        followedThreadKeys = followedThreadKeys,
                        authority = authority,
                        logger = warningLogger,
                    )
                )
            }
        }
    }

    private suspend fun threadAction(
        threadChannelId: ChannelId,
        source: String,
        block: suspend () -> HomeNetworkResponse
    ): DataResult<ThreadActionResult> =
        threadAction(
            fallbackThreadChannelId = threadChannelId,
            fallbackParentMessageId = null,
            source = source,
            block = block
        )

    private suspend fun threadAction(
        fallbackThreadChannelId: ChannelId?,
        fallbackParentMessageId: MessageId?,
        source: String,
        block: suspend () -> HomeNetworkResponse
    ): DataResult<ThreadActionResult> {
        currentContext() ?: return authRequired()
        return executeConversation(
            emptyReason = EmptyReason.NoInboxItems,
            block = block,
            source = source,
            mapper = { bodyText ->
                parseSharedThreadActionResult(
                    bodyText = bodyText,
                    fallbackThreadChannelId = fallbackThreadChannelId,
                    fallbackParentMessageId = fallbackParentMessageId
                ) ?: error("thread action response missing thread channel id")
            }
        )
    }

    private suspend fun <T> executeConversation(
        emptyReason: EmptyReason,
        block: suspend () -> HomeNetworkResponse,
        emptyWhen: (T) -> Boolean = { false },
        source: String? = null,
        bodyEventFields: ((String) -> String)? = null,
        mapper: (String) -> T
    ): DataResult<T> =
        executeConversationWithDiagnostics(
            emptyReason = emptyReason,
            block = block,
            emptyWhen = emptyWhen,
            source = source,
            diagnosticsLogger = warningLogger,
            bodyEventFields = bodyEventFields,
            mapper = mapper
        )

    /**
     * Binary presigned PUTs intentionally have no JSON response body.  Treat any HTTP 2xx as
     * success, while preserving the normal conversation error mapping for every other status.
     * The signed URL is never included in the diagnostic source or error text.
     */
    private suspend fun executeAttachmentUploadUnit(
        source: String,
        block: suspend () -> HomeNetworkResponse,
    ): DataResult<Unit> {
        return try {
            val response = block()
            // If-None-Match:* returns 412 when the object is already present. Treat that as a
            // successful PUT so the caller can complete the same session instead of creating
            // another reservation.
            if (response.isSuccessful || response.code == 412) {
                warningLogger(
                    "conversation_binary_result source=$source code=${response.code} " +
                        "result=content"
                )
                DataResult.Content(Unit)
            } else {
                response.toConversationDataError()
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger(
                "conversation_binary_failed source=$source exception=${exception::class.simpleName}"
            )
            DataResult.Error(ScreenError(code = "network_request_failed", retryable = true))
        }
    }

    private suspend fun currentContext(): NetworkServerContext? {
        val session = sessionStore.readSession() ?: return null
        val routeServerId = routeServerIdProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
        val sessionServerId = session.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
        val selectedId = (routeServerId ?: sessionServerId)
            ?.takeIf { candidate ->
                session.servers.isEmpty() || session.servers.any { it.id.trim() == candidate }
            }
            ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
    }

    private companion object {
        const val MESSAGE_PAGE_LIMIT = 50
        const val FOLLOWED_SUMMARIES_JOIN_BUDGET_MILLIS = 1_500L
        const val SYNC_PAGE_LIMIT = 200
    }
}

private data class NetworkServerContext(val session: StoredAuthSession, val server: build.raft.app.model.ServerSummary)

private fun StoredAuthSession.toNetworkServerContext(serverId: ServerId): NetworkServerContext? {
    val requestedId = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val server = servers.firstOrNull { it.id.trim() == requestedId }
        ?.toServerSummary()
        ?.let { summary ->
            // Keep the context's identity canonical even when a legacy persisted session carried
            // surrounding whitespace.  Every downstream request and owner key uses this value.
            summary.copy(id = ServerId(summary.id.value.trim()))
        }
        ?: return null
    return NetworkServerContext(session = this, server = server)
}

private fun parseFollowedThreadListSnapshotFromBody(
    bodyText: String,
    context: NetworkServerContext
): ThreadListSnapshot {
    val parsed =
        parseFollowedThreadListResponse(bodyText)
            ?: throw IllegalStateException("Thread list response missing threads")
    return ThreadListSnapshot(
        server = context.server,
        threads = parsed.items,
        totalCount = parsed.totalCount,
        hasMore = parsed.hasMore,
        nextOffset = parsed.nextOffset
    )
}

private fun parseChannelThreadListSnapshotFromBody(
    bodyText: String,
    context: NetworkServerContext
): ThreadListSnapshot {
    val parsed =
        parseChannelThreadSummaryRecord(bodyText)
            ?: throw IllegalStateException("Channel thread summary response missing record")
    return ThreadListSnapshot(
        server = context.server,
        threads = parsed.items,
        totalCount = parsed.totalCount,
        hasMore = parsed.hasMore,
        nextOffset = parsed.nextOffset
    )
}

private fun buildSharedThreadSnapshot(
    payload: SharedMessagesPayload,
    threadId: ThreadId,
    canLoadMoreBefore: Boolean = false,
    canLoadMoreAfter: Boolean = false,
    highlightedMessageId: MessageId? = null,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): ThreadSnapshot {
    val highlighted = highlightedMessageId?.let { id -> payload.messages.firstOrNull { it.id == id } }
    val baseConversation =
        buildDefaultConversation(threadId, threadId.value)
            .copy(archived = payload.channelArchived)
    val conversation =
        if (
            payload.canonicalTarget?.kind == KuiklyThreadSurfaceKind.ReplyThread &&
            payload.canonicalTarget.channelId != null &&
            payload.canonicalTarget.threadParentMessageId != null
        ) {
            baseConversation.copy(
                threadId = threadIdentity(payload.canonicalTarget.channelId, payload.canonicalTarget.threadParentMessageId),
                channelId = payload.canonicalTarget.channelId,
                thread =
                    ThreadConversation(
                        parentChannelId = payload.canonicalTarget.channelId,
                        parentMessageId = payload.canonicalTarget.threadParentMessageId,
                        streamChannelId = payload.canonicalTarget.threadChannelId,
                        parentChannelKind = baseConversation.channelType.normalizedThreadChannelKind()
                    ),
                kind = ConversationKind.Thread
            )
        } else if (highlighted != null) {
            baseConversation.withThreadAnchor(
                parentMessage = highlighted,
                streamChannelId = ChannelId(threadId.value)
            )
        } else {
            baseConversation
        }
    return ThreadSnapshot(
        conversation = conversation,
        messages = payload.messages,
        replySummaries = payload.threadSummaries,
        replySummariesAuthority = payload.replySummariesAuthority,
        parentMessage = highlighted,
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        historyLimited = payload.historyLimited,
        highlightedMessageId = highlightedMessageId
    ).withReplySummaries(
        threads = payload.threadSummaries.threads,
        followedThreadKeys = payload.threadSummaries.followedThreadKeys,
        authority = payload.replySummariesAuthority,
        logger = diagnostics,
    )
}

/**
 * Task #168: the raw `messages` array of a paging response, for the durable window extender. A
 * body that is not an object with a `messages` array yields no witness (nothing will be persisted);
 * the rendered snapshot is unaffected either way.
 */
private fun rawMessagePageWitness(bodyText: String, requestedLimit: Int): RawMessagePageWitness {
    val messages =
        runCatching { rawMessagePageJson.parseToJsonElement(bodyText) as? JsonObject }
            .getOrNull()
            ?.get("messages") as? JsonArray
            ?: return RawMessagePageWitness.None
    return RawMessagePageWitness(RawMessagePage(messages = messages, requestedLimit = requestedLimit))
}

private val rawMessagePageJson = Json { ignoreUnknownKeys = true }

private fun ThreadSnapshot.withConversationDetail(
    detail: ConversationSummary?,
    canonicalReplyThread: Boolean
): ThreadSnapshot {
    if (detail == null) return this
    val snapshotChannelId = conversation.channelId?.value ?: return this
    val detailChannelId = detail.channelId?.value ?: return this
    if (snapshotChannelId != detailChannelId) return this
    if (canonicalReplyThread) {
        return copy(
            canonicalConversationDetail = detail,
            conversation =
                conversation.copy(
                    name = detail.name,
                    subtitle = conversation.subtitle ?: detail.subtitle
                )
        )
    }
    return copy(
        canonicalConversationDetail = detail,
        conversation =
            detail.copy(
                lastMessageId = conversation.lastMessageId ?: detail.lastMessageId,
                lastMessageSeq = conversation.lastMessageSeq ?: detail.lastMessageSeq,
                firstUnreadMessageId = conversation.firstUnreadMessageId ?: detail.firstUnreadMessageId
            )
    )
}

private fun buildDefaultConversation(threadId: ThreadId, name: String): ConversationSummary {
    val parentChannelId = threadId.parentChannelIdOrNull()
    val parentMessageId = threadId.parentMessageIdOrNull()
    return ConversationSummary(
        threadId = threadId,
        channelId = parentChannelId ?: ChannelId(threadId.value),
        thread =
            if (parentChannelId != null && parentMessageId != null) {
                ThreadConversation(
                    parentChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    streamChannelId = null
                )
            } else {
                null
            },
        kind =
            if (parentChannelId != null && parentMessageId != null) {
                ConversationKind.Thread
            } else {
                ConversationKind.Channel
            },
        name = name
    )
}

private fun ThreadSnapshot.withReplySummaries(
    threads: List<InboxItem>,
    followedThreadKeys: Set<String> = emptySet(),
    authority: ThreadReplySummariesAuthority = ThreadReplySummariesAuthority.Targeted,
    logger: (String) -> Unit
): ThreadSnapshot {
    if (threads.isEmpty()) {
        return if (replySummariesAuthority == authority) this else copy(replySummariesAuthority = authority)
    }
    val summariesByParentMessageId =
        threads
            .asSequence()
            .mapNotNull { thread -> thread.parentMessageId?.let { it.value to thread } }
            .toMap()
    val summariesByThreadChannelId =
        threads
            .asSequence()
            .flatMap { thread ->
                sequenceOf(
                    thread.threadChannelId?.value?.let { it to thread },
                    thread.channelId.value to thread
                ).filterNotNull()
            }
            .toMap()
    var joinMissCount = 0
    var firstJoinMissMessageId: MessageId? = null
    var firstJoinMissThreadChannelId: ChannelId? = null
    val updatedMessages =
        messages.map { message ->
            val summary =
                summariesByParentMessageId[message.id.value]
                    ?: message.threadChannelId?.value?.let(summariesByThreadChannelId::get)
                    ?: threads.firstOrNull { thread -> thread.matchesParentMessage(message.id) }
            if (summary == null) {
                if (message.replyCount == 0) {
                    joinMissCount += 1
                    if (firstJoinMissMessageId == null) {
                        firstJoinMissMessageId = message.id
                        firstJoinMissThreadChannelId = message.threadChannelId
                    }
                }
                return@map message
            }
            val summaryThreadChannelId = summary.threadChannelId ?: summary.channelId
            logger(
                "summary_join parentMessageId=${message.id.value} " +
                    "channelId=${message.channelId?.value.orEmpty()} " +
                    "threadChannelId=${summaryThreadChannelId.value} " +
                    "replyCount=${maxOf(message.replyCount, summary.replyCount)} " +
                    "unreadCount=${summary.unreadCount} " +
                    "firstUnreadMessageId=${summary.firstUnreadMessageId?.value ?: "null"} " +
                    "source=channel_threads"
            )
            message.copy(
                threadChannelId = message.threadChannelId ?: summaryThreadChannelId,
                replyCount = summary.replyCount.coerceAtLeast(0),
                unreadReplyCount = maxOf(message.unreadReplyCount, summary.unreadCount),
                firstUnreadReplyMessageId = message.firstUnreadReplyMessageId ?: summary.firstUnreadMessageId,
                isThreadFollowed = message.isThreadFollowed || summary.key in followedThreadKeys
            )
        }
    if (joinMissCount > 0) {
        val candidates =
            threads.take(2).joinToString { thread ->
                "ch=${thread.channelId.value.take(8)} " +
                    "pMsgId=${thread.parentMessageId?.value?.take(8) ?: "null"}"
            }
        logger(
            "summary_join_miss_count count=$joinMissCount " +
                "firstMsgId=${firstJoinMissMessageId?.value?.take(8).orEmpty()} " +
                "firstThreadCh=${firstJoinMissThreadChannelId?.value?.take(8) ?: "null"} " +
                "threadCandidates=[$candidates] source=channel_threads"
        )
    }
    return copy(
        messages = updatedMessages,
        replySummaries =
            ThreadReplySummariesSnapshot(
                threads = threads,
                followedThreadKeys = followedThreadKeys,
            ),
        replySummariesAuthority = authority,
    )
}

private fun InboxItem.matchesParentMessage(messageId: MessageId): Boolean {
    val candidateIds =
        listOfNotNull(
            parentMessageId?.value,
            lastMessageId?.value,
            latestActivityMessageId?.value,
            parentMessagePreview?.extractSlockMessageReferenceId(),
            latestActivityPreview?.extractSlockMessageReferenceId(),
            lastMessagePreview?.extractSlockMessageReferenceId(),
            threadChannelId?.value,
            channelId.value,
            key.removePrefix("thread:").takeIf { it != key }
        )
    return candidateIds.any { candidate -> messageId.value.matchesThreadSummaryId(candidate) }
}

private fun String.extractSlockMessageReferenceId(): String? =
    Regex("#[A-Za-z0-9_-]+:([A-Fa-f0-9]{8})(?:[A-Fa-f0-9-]{0,28})?")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)

private fun String.matchesThreadSummaryId(candidate: String): Boolean {
    if (equals(candidate, ignoreCase = true)) return true
    val parentShortId = take(8)
    val candidateShortId = candidate.take(8)
    return parentShortId.length == 8 &&
        candidateShortId.length == 8 &&
        parentShortId.equals(candidateShortId, ignoreCase = true)
}

private fun ConversationSummary.withThreadAnchor(
    parentMessage: Message?,
    streamChannelId: ChannelId,
    parentChannelName: String? = null
): ConversationSummary {
    fun ConversationSummary.withParentNameFallback(): ConversationSummary {
        val fallbackName =
            parentChannelName
                ?.let { candidate -> conversationSafeDisplayTitleOrNull(candidate, headerIdentityIds()) }
                ?.takeIf { nameIsBlankOrIdentity() }
                ?: return this
        return copy(name = fallbackName)
    }
    if (kind == ConversationKind.Thread && thread?.streamChannelId == null) {
        return copy(thread = thread?.copy(streamChannelId = streamChannelId)).withParentNameFallback()
    }
    val parentChannelId = parentMessage?.channelId ?: return withParentNameFallback()
    return copy(
        threadId = threadIdentity(parentChannelId, parentMessage.id),
        channelId = parentChannelId,
        thread =
            ThreadConversation(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessage.id,
                streamChannelId = parentMessage.threadChannelId ?: streamChannelId,
                parentChannelKind = channelType.normalizedThreadChannelKind()
            ),
        kind = ConversationKind.Thread
    ).withParentNameFallback()
}

private fun ThreadSnapshot.withReplyThreadConversation(
    parentChannelId: ChannelId,
    parentMessageId: MessageId,
    streamChannelId: ChannelId
): ThreadSnapshot =
    copy(
        conversation =
            conversation.copy(
                channelId = parentChannelId,
                kind = ConversationKind.Thread,
                thread =
                    ThreadConversation(
                        parentChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        streamChannelId = streamChannelId,
                        parentChannelKind = conversation.thread?.parentChannelKind
                    )
            )
    )

@Serializable
data class ThreadChannelActionRequestBody(
    val threadChannelId: String,
)

@Serializable
data class ParentMessageThreadRequestBody(val parentMessageId: String)

private fun ChannelId.toThreadActionBody(): String =
    conversationRepositoryJson.encodeToString(
        ThreadChannelActionRequestBody(threadChannelId = value)
    )

private fun MessageId.toParentMessageThreadBody(): String =
    conversationRepositoryJson.encodeToString(ParentMessageThreadRequestBody(parentMessageId = value))

private fun PlatformFile.toMultipartFile(): MultipartFile =
    MultipartFile(
        name = "files",
        fileName = fileName,
        mimeType = mimeType ?: "application/octet-stream",
        bytes = bytes,
        fileRef = fileRef,
        sizeBytes = sizeBytes
    )

private fun attachmentUploadCapabilitiesBodyTelemetry(body: String): String =
    runCatching {
        val capabilities = parseSharedAttachmentUploadCapabilities(body)
        " maxBytes=${capabilities.maxBytes} " +
            "directUploadThresholdBytes=${capabilities.directUploadThresholdBytes ?: "null"} " +
            "directUploadEnabled=${capabilities.directUploadEnabled}"
    }.getOrElse {
        " attachmentUploadCapabilitiesParseError=true"
    }

private suspend fun <T> executeConversationWithDiagnostics(
    emptyReason: EmptyReason,
    block: suspend () -> HomeNetworkResponse,
    emptyWhen: (T) -> Boolean = { false },
    source: String? = null,
    diagnosticsLogger: (String) -> Unit = {},
    bodyEventFields: ((String) -> String)? = null,
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code
        if (!response.isSuccessful) return response.toConversationDataError()
        val body = response.bodyText.orEmpty()
        if (body.isBlank()) return DataResult.Empty(emptyReason)
        val sourceLabel = source ?: "conversation"
        val responseBytes = body.encodeToByteArray().size
        diagnosticsLogger(
            "conversation_network_body_received source=$sourceLabel code=${response.code} " +
                "responseBytes=$responseBytes hasBody=${response.bodyText != null}" +
                (bodyEventFields?.invoke(body) ?: "")
        )
        val parseStartedAt = TimeSource.Monotonic.markNow()
        diagnosticsLogger(
            "conversation_parse_start source=$sourceLabel responseBytes=$responseBytes " +
                "dispatcher=$SLOCK_JSON_PARSE_DISPATCHER_LABEL"
        )
        val result =
            withContext(SlockJsonParseDispatcher) {
                mapper(body)
            }
        val dataResult = if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
        diagnosticsLogger(
            "conversation_parse_result source=$sourceLabel result=${dataResult.conversationDiagnosticsResult()} " +
                "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} " +
                "dispatcher=$SLOCK_JSON_PARSE_DISPATCHER_LABEL"
        )
        dataResult
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        if (source != null) {
            diagnosticsLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
            )
        }
        val recoverySessionIds =
            (exception as? AttachmentUploadSessionCreateParseException)
                ?.recoveryUploadId
                ?.let(::setOf)
                .orEmpty()
        DataResult.Error(
            ScreenError(
                code = "network_parse_failed",
                retryable = true,
                recoverySessionIds = recoverySessionIds,
            )
        )
    }
}

private fun <T> HomeNetworkResponse.toConversationDataError(): DataResult<T> {
    val serverMessage = conversationRouteErrorTextOrNull()
    val serverCode = conversationRouteStructuredErrorCodeOrNull()
    return when (code) {
        0 ->
            DataResult.Error(
                ScreenError(
                    code = errorBodyText.toConversationTransportErrorCode(),
                    retryable = true,
                    serverCode = serverCode,
                )
            )
        401 -> authRequired(serverMessage)
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    requiredCapability = "server_access",
                    serverMessage = serverMessage
                )
            )
        in 500..599 ->
            DataResult.Error(
                ScreenError(
                    code = "server_unavailable",
                    retryable = true,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                )
            )
        413 ->
            DataResult.Error(
                ScreenError(
                    code = conversationRouteStructuredErrorCodeOrNull() ?: "attachment_too_large",
                    retryable = false,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = "http_$code",
                    retryable = code != 404,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                )
            )
    }
}

@Serializable
private data class ConversationRouteErrorDto(val error: String? = null)

@Serializable
private data class ConversationRouteStructuredErrorDto(
    val code: String? = null,
    val errorCode: String? = null
)

private fun HomeNetworkResponse.conversationRouteStructuredErrorCodeOrNull(): String? {
    val payload =
        bodyText?.takeIf { it.isNotBlank() }
            ?: errorBodyText?.takeIf { it.isNotBlank() }
            ?: return null
    val dto =
        runCatching {
            conversationRepositoryJson.decodeFromString<ConversationRouteStructuredErrorDto>(payload)
        }.getOrNull()
    return (dto?.code ?: dto?.errorCode)?.trim()?.takeIf { it.isNotBlank() }
}

private fun HomeNetworkResponse.conversationRouteErrorTextOrNull(): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        conversationRepositoryJson.decodeFromString(ConversationRouteErrorDto.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun String?.toConversationTransportErrorCode(): String {
    val text = orEmpty().lowercase()
    return when {
        "unable to resolve host" in text ||
            "unknown host" in text ||
            "could not resolve host" in text ||
            "no address associated with hostname" in text ||
            "nodename nor servname" in text -> "network_dns_failed"
        "timeout" in text || "timed out" in text -> "network_timeout"
        "failed to connect" in text ||
            "connection refused" in text ||
            "connection reset" in text -> "network_connect_failed"
        else -> "network_unavailable"
    }
}

private fun authRequired(): DataResult.NoPermission =
    authSessionRequiredResult()

private fun authRequired(serverMessage: String?): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage)

private fun directSessionAuthorityRequired(): DataResult.Error =
    DataResult.Error(
        ScreenError(
            code = "attachment_upload_authority_binding_required",
            retryable = false,
        )
    )
