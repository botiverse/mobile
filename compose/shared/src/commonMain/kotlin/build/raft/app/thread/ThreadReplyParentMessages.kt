package build.raft.app

import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.StateFlow

/**
 * The reply-parent paths of [SharedThreadDataSource] (the stored or fetched parent message of a reply
 * thread, its origin resolution, and the parent and reply count an opener leaves behind), moved out of
 * it unchanged; the data source forwards to them. Holds no state: the parents live in the message store
 * passed in, and the reply-cache lock is the one [ThreadReplyCacheProvenanceOwner] owns.
 */
internal class ThreadReplyParentMessages(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val messageStore: SharedThreadMessageStore?,
    private val messageResolver: SharedThreadMessageStore,
    private val replyThreadPrewarm: ThreadReplyThreadPrewarm,
    private val diagnosticsLogger: (String) -> Unit,
    private val captureReplyCacheProducerContext: suspend () -> ReplyCacheProducerContext?,
    private val readReplyResolutionScope: suspend () -> ReplyResolutionScope?,
    private val fetchThreadSnapshot:
        suspend (
            fallbackCode: String,
            expectedProvenance: ReplyCacheProvenance,
            allowCapturedScopeResultAfterProvenanceChange: Boolean,
            call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        ) -> DataResult<ThreadSnapshot>,
) {
    private val replyCacheProvenanceLock: SynchronizedObject get() = provenanceOwner.replyCacheProvenanceLock
    private val replyCacheProvenance: StateFlow<ReplyCacheProvenance?> get() = provenanceOwner.replyCacheProvenanceState

    private fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenance(expected)

    private fun cacheReplyThreadResolution(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ) = replyThreadPrewarm.cacheReplyThreadResolution(namespaceId, parentChannelId, parentMessageId, threadChannelId)

    private suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        expectedProvenance: ReplyCacheProvenance,
        allowCapturedScopeResultAfterProvenanceChange: Boolean,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> =
        fetchThreadSnapshot(fallbackCode, expectedProvenance, allowCapturedScopeResultAfterProvenanceChange, call)

    suspend fun withCachedReplyParentCandidate(basePayload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        val context = captureReplyCacheProducerContext() ?: return basePayload
        return withCachedReplyParentCandidate(basePayload, context)
    }

    internal fun withCachedReplyParentCandidate(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext
    ): RaftThreadScreenPayload {
        if (basePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return basePayload
        if (basePayload.originMessage != null || basePayload.originPreview?.isNotBlank() == true) return basePayload
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return basePayload
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return basePayload
        if (!isCurrentReplyCacheProvenance(context.provenance)) return basePayload
        val session = context.session
        val messageStoreScopeId = context.provenance.messageStoreScopeId ?: return basePayload
        val parent =
            getCachedReplyParentMessage(
                messageStoreScopeId = messageStoreScopeId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return basePayload
        return basePayload.copy(
            originMessage = parent.toRaftThreadMessageItem(session.user.id),
            originParentMessageId = parentMessageId,
            originStatus = "loaded",
            originErrorCode = null
        )
    }

    suspend fun resolveReplyOrigin(
        basePayload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance? = null
    ): SharedThreadOriginResolution {
        if (basePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) {
            return SharedThreadOriginResolution.Missing
        }
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: return SharedThreadOriginResolution.Error("thread_parent_channel_missing")
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return SharedThreadOriginResolution.Error("thread_parent_message_missing")
        val replyResolutionScope =
            readReplyResolutionScope()
                ?: return SharedThreadOriginResolution.Error("thread_parent_context_session_missing")
        if (expectedProvenance != null && replyResolutionScope.provenance != expectedProvenance) {
            return SharedThreadOriginResolution.Error("thread_reply_cache_provenance_stale")
        }
        val session = replyResolutionScope.session
        val selected = when (
            val resolution = resolveReplyParentMessageFromStore(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                expectedProvenance = replyResolutionScope.provenance
            )
        ) {
            is SharedThreadMessageResolution.Loaded ->
                SharedThreadOriginResolution.Loaded(
                    resolution.message.toRaftThreadMessageItem(session.user.id)
                )
            SharedThreadMessageResolution.Missing -> SharedThreadOriginResolution.Missing
            is SharedThreadMessageResolution.Error -> SharedThreadOriginResolution.Error(resolution.code, resolution.hiddenTarget)
        }
        return if (isCurrentReplyCacheProvenance(replyResolutionScope.provenance)) {
            selected
        } else {
            SharedThreadOriginResolution.Error("thread_reply_cache_provenance_stale")
        }
    }

    fun rememberReplyParentCandidate(
        provenance: ReplyCacheProvenance,
        serverId: String?,
        parentChannelId: String?,
        parentMessage: RaftThreadMessageItem,
        threadChannelId: String?,
        /**
         * The reply count the opener shows for [parentMessage]. A timeline row carries no reply facts
         * ([withoutThreadReplyMetadata]), so without it the remembered parent says "0 replies" and the
         * thread counts only the replies it has loaded. A candidate: the thread's own load replaces it.
         */
        replyCount: Int = 0
    ): Boolean {
        val channelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return false
        if (parentMessage.id.isBlank()) return false
        val exactServerId = serverId?.takeIf { it.isNotBlank() } ?: provenance.serverId.takeIf { it.isNotBlank() }
        if (exactServerId == null || exactServerId != provenance.serverId) return false
        val projectedParent =
            if (parentMessage.needsReplyOriginContextHydration()) {
                null
            } else {
                parentMessage.toMessage(
                    threadId = ThreadId(channelId.value),
                    channelId = channelId,
                    diagnostics = diagnosticsLogger,
                ) ?: return false
            }
        val accepted =
            synchronized(replyCacheProvenanceLock) {
                if (replyCacheProvenance.value != provenance) return@synchronized false
                threadChannelId?.takeIf { it.isNotBlank() }?.let { resolvedThreadChannelId ->
                    cacheReplyThreadResolution(
                        namespaceId = provenance.namespaceId,
                        parentChannelId = channelId.value,
                        parentMessageId = parentMessage.id,
                        threadChannelId = resolvedThreadChannelId
                    )
                }
                projectedParent?.let { parent ->
                    val scopeId =
                        provenance.messageStoreScopeId
                            ?: provenance.serverId.takeIf { messageStore == null && it.isNotBlank() }
                            ?: return@synchronized false
                    val knownReplyCount =
                        maxOf(
                            parent.replyCount,
                            replyCount,
                            messageResolver.getScoped(scopeId, channelId, parent.id)?.replyCount ?: 0
                        )
                    messageResolver.upsertScoped(
                        scopeId = scopeId,
                        channelId = channelId,
                        message = if (knownReplyCount == parent.replyCount) parent else parent.copy(replyCount = knownReplyCount)
                    )
                }
                true
            }
        if (!accepted) return false
        diagnosticsLogger(
            "thread_parent_context_store_seed parentChannelId=${channelId.value} " +
                "parentMessageId=${parentMessage.id} threadChannelId=${threadChannelId.orEmpty()}"
        )
        return true
    }

    /**
     * A [ThreadUpdated] reached the open page: the remembered parent (what the next open of its
     * thread resolves its origin from) follows the server's count too. Events can arrive out of
     * order, so the count only grows here, as the reply-count facts do. A parent that is not
     * remembered stays unknown; its thread reads it when opened.
     */
    fun rememberReplyParentReplyCount(parentChannelId: String?, parentMessageId: String, replyCount: Int) {
        val channelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return
        val messageId = parentMessageId.takeIf { it.isNotBlank() }?.let(::MessageId) ?: return
        synchronized(replyCacheProvenanceLock) {
            val provenance = replyCacheProvenance.value ?: return@synchronized
            val scopeId =
                provenance.messageStoreScopeId
                    ?: provenance.serverId.takeIf { messageStore == null && it.isNotBlank() }
                    ?: return@synchronized
            val remembered = messageResolver.getScoped(scopeId, channelId, messageId) ?: return@synchronized
            if (remembered.replyCount < replyCount) {
                messageResolver.upsertScoped(scopeId, channelId, remembered.copy(replyCount = replyCount))
            }
        }
    }

    internal fun ThreadSnapshot.withCachedReplyParentMessage(
        basePayload: RaftThreadScreenPayload,
        messageStoreScopeId: String?
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
                conversation.kind == ConversationKind.Thread
        if (!isReplyThread) return this
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
                ?: return this
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentChannelId?.value
                ?: parentMessage?.channelId?.value
                ?: return this
        val parent =
            getCachedReplyParentMessage(
                messageStoreScopeId = messageStoreScopeId ?: return this,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return this
        return copy(parentMessage = parent)
    }

    internal suspend fun ThreadSnapshot.withReplyParentMessageFromContext(
        basePayload: RaftThreadScreenPayload,
        allowNetworkHydration: Boolean = true,
        replyResolutionScope: ReplyResolutionScope
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
                conversation.kind == ConversationKind.Thread
        if (!isReplyThread) return this
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
                ?: return this
        val existingParentMessage = parentMessage?.takeIf { it.id.value == parentMessageId }
        if (existingParentMessage != null && !existingParentMessage.needsReplyOriginContextHydration()) {
            return this
        }
        if (
            basePayload.originStatus == "loaded" &&
            basePayload.originMessage?.id == parentMessageId &&
            !basePayload.originMessage.needsReplyOriginContextHydration()
        ) {
            val originParentChannelId =
                basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                    ?: conversation.thread?.parentChannelId?.value
                    ?: parentMessage?.channelId?.value
                    ?: return this
            return copy(
                parentMessage =
                    basePayload.originMessage.toMessage(
                        threadId = ThreadId(originParentChannelId),
                        channelId = ChannelId(originParentChannelId),
                        diagnostics = diagnosticsLogger,
                    )
            )
        }
        val messageParentCandidate = messages.firstOrNull { message -> message.id.value == parentMessageId }
        if (messageParentCandidate != null && !messageParentCandidate.needsReplyOriginContextHydration()) {
            return this
        }
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentChannelId?.value
                ?: parentMessage?.channelId?.value
        if (!allowNetworkHydration) {
            return this
        }
        val resolvedParentChannelId = parentChannelId ?: return this
        val parent =
            getReplyParentMessageFromStore(
                parentChannelId = resolvedParentChannelId,
                parentMessageId = parentMessageId,
                messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                expectedProvenance = replyResolutionScope.provenance
            )
                ?: return this
        return copy(parentMessage = parent)
    }

    private suspend fun getReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): Message? =
        when (
            val resolution = resolveReplyParentMessageFromStore(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                requestPriority = requestPriority,
                messageStoreScopeId = messageStoreScopeId,
                expectedProvenance = expectedProvenance
            )
        ) {
            is SharedThreadMessageResolution.Loaded -> resolution.message
            SharedThreadMessageResolution.Missing,
            is SharedThreadMessageResolution.Error -> null
        }

    internal suspend fun resolveReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): SharedThreadMessageResolution {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        val cachedParent = messageStoreScopeId?.let { scopeId ->
            messageResolver.getScoped(scopeId, parentChannel, parentMessage)
        }
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
        suspend fun fetchParent(): SharedThreadMessageResolution {
            val startedAt = TimeSource.Monotonic.markNow()
            val contextResult =
                fetchThreadSnapshotFromRepository(
                    fallbackCode = "thread_parent_context",
                    expectedProvenance = expectedProvenance,
                    allowCapturedScopeResultAfterProvenanceChange = true
                ) { repository ->
                    repository.getMessageContext(parentMessage, parentChannel, requestPriority)
                }
            diagnosticsLogger(
                "thread_parent_context_parallel_result parentChannelId=$parentChannelId " +
                    "parentMessageId=$parentMessageId result=${contextResult.threadDiagnosticsResult()} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return when (contextResult) {
                is DataResult.Content -> {
                    val message =
                        contextResult.data.parentMessage?.takeIf { message -> message.id == parentMessage }
                            ?: contextResult.data.messages.firstOrNull { message -> message.id == parentMessage }
                    message
                        ?.let { resolved -> SharedThreadMessageResolution.Loaded(resolved) }
                        ?: SharedThreadMessageResolution.Missing
                }
                is DataResult.Empty -> SharedThreadMessageResolution.Missing
                is DataResult.Error ->
                    SharedThreadMessageResolution.Error(
                        contextResult.error.code,
                        contextResult.error.threadParentHiddenTargetOrNull(parentMessageId)
                    )
                is DataResult.NoPermission -> SharedThreadMessageResolution.Error(contextResult.blocker.code)
            }
        }
        val parent =
            if (messageStoreScopeId == null) {
                fetchParent()
            } else {
                messageResolver.resolveScoped(messageStoreScopeId, parentChannel, parentMessage, ::fetchParent)
            }
        if (cachedParent != null && parent is SharedThreadMessageResolution.Loaded && parent.message == cachedParent) {
            diagnosticsLogger(
                "thread_parent_context_store_hit parentChannelId=$parentChannelId parentMessageId=$parentMessageId"
            )
        }
        return when (parent) {
            is SharedThreadMessageResolution.Loaded -> {
                parent.message
                    .takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
                    ?.let { resolved -> SharedThreadMessageResolution.Loaded(resolved) }
                    ?: SharedThreadMessageResolution.Error("thread_parent_context_identity_mismatch")
            }
            SharedThreadMessageResolution.Missing -> parent
            is SharedThreadMessageResolution.Error -> parent
        }
    }

    private fun getCachedReplyParentMessage(
        messageStoreScopeId: String,
        parentChannelId: String,
        parentMessageId: String
    ): Message? {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        return messageResolver.getScoped(messageStoreScopeId, parentChannel, parentMessage)
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
    }

    internal fun ThreadSnapshot.rememberReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String,
        messageStoreScopeId: String?
    ): ThreadSnapshot {
        val parentChannel = ChannelId(parentChannelId)
        val targetMessageId = MessageId(parentMessageId)
        val candidate =
            parentMessage?.takeIf { message -> message.id == targetMessageId }
                ?: messages.firstOrNull { message -> message.id == targetMessageId }
                ?: return this
        if (candidate.matchesReplyOriginParent(parentChannel, targetMessageId)) {
            val scopeId = messageStoreScopeId ?: return this
            messageResolver.upsertScoped(scopeId, parentChannel, candidate)
        }
        return this
    }
}
