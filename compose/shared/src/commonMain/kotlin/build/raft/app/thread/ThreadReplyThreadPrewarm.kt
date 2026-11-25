package build.raft.app

import build.raft.app.SharedThreadDataSource.FocusedInitialContextOutcome
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.RawMessagePage
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * The reply-thread resolutions of [SharedThreadDataSource] (which thread stream a parent message
 * has, and opening it) and the Activity / unread-pill prewarm built on them, moved out of it
 * unchanged; the data source forwards to them. Sole owner of [replyThreadResolveCache]. Takes the
 * lock owned by [ThreadReplyCacheProvenanceOwner] at the same points as before.
 */
internal class ThreadReplyThreadPrewarm(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val payloadCache: SharedThreadPayloadStore,
    private val messageStore: SharedThreadMessageStore?,
    private val conversationRepository: ConversationRepository?,
    private val selectedServerStore: SelectedServerStore?,
    private val enteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader?,
    private val diagnosticsLogger: (String) -> Unit,
    private val readReplyResolutionScope: suspend () -> ReplyResolutionScope?,
    private val durableHeadEpochOrNull: (ReplyCacheProvenance) -> Long?,
    private val cachedPayloadIfCurrent: (String, ReplyCacheProvenance) -> RaftThreadScreenPayload?,
    private val seedAuthorProfiles: (String?, RaftThreadScreenPayload, String) -> Unit,
    private val fetchInitialReplyThreadPayload:
        suspend (
            StoredAuthSession,
            RaftThreadScreenPayload,
            DataRequestPriority,
            ReplyResolutionScope,
            ((String?, RawMessagePage?) -> Unit)?,
        ) -> DataResult<RaftThreadScreenPayload>,
    private val fetchFocusedInitialThreadPayload:
        suspend (
            StoredAuthSession,
            RaftThreadScreenPayload,
            ThreadId,
            ConversationAccessCandidate?,
            ReplyResolutionScope,
            DataRequestPriority,
        ) -> FocusedInitialContextOutcome,
    private val persistReplyThreadPrewarmPage:
        suspend (RaftThreadScreenPayload, ReplyCacheProducerContext, String?, RawMessagePage?, Long, Long?, Long?) -> Unit,
) {
    private val replyCacheProvenanceLock: SynchronizedObject get() = provenanceOwner.replyCacheProvenanceLock
    private val replyCacheProvenance: StateFlow<ReplyCacheProvenance?> get() = provenanceOwner.replyCacheProvenanceState

    private fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenance(expected)

    private fun seedThreadAuthorProfiles(serverId: String?, payload: RaftThreadScreenPayload, source: String) =
        seedAuthorProfiles(serverId, payload, source)

    private suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        requestPriority: DataRequestPriority,
        replyResolutionScope: ReplyResolutionScope,
        onRawReplyPage: ((threadChannelId: String?, page: RawMessagePage?) -> Unit)?,
    ): DataResult<RaftThreadScreenPayload> =
        fetchInitialReplyThreadPayload(session, basePayload, requestPriority, replyResolutionScope, onRawReplyPage)

    private suspend fun fetchFocusedInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        routeThreadId: ThreadId,
        accessFallback: ConversationAccessCandidate?,
        replyResolutionScope: ReplyResolutionScope,
        requestPriority: DataRequestPriority,
    ): FocusedInitialContextOutcome =
        fetchFocusedInitialThreadPayload(session, basePayload, routeThreadId, accessFallback, replyResolutionScope, requestPriority)

    private suspend fun persistReplyThreadPrewarmPageIfCurrent(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
        resolvedThreadChannelId: String?,
        rawPage: RawMessagePage?,
        expectedCommitGeneration: Long,
        headEpochAtRequest: Long?,
        revAtRequest: Long?,
    ) = persistReplyThreadPrewarmPage(
        basePayload,
        context,
        resolvedThreadChannelId,
        rawPage,
        expectedCommitGeneration,
        headEpochAtRequest,
        revAtRequest,
    )

    internal val replyThreadResolveCache = mutableMapOf<ReplyThreadResolveKey, String>()

    internal fun cachedReplyThreadStreamId(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String
    ): String? {
        namespaceId ?: return null
        val key = ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)
        return replyThreadResolveCache[key]
            ?: messageStore?.getReplyThreadStream(namespaceId, parentChannelId, parentMessageId)
    }

    internal fun RaftThreadScreenPayload.matchesCachedReplyThreadStreamResolution(
        namespaceId: String?
    ): Boolean {
        val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
        val parentMessage =
            parentMessageId?.takeIf { it.isNotBlank() }
                ?: originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return false
        val routeStream =
            streamChannelId?.takeIf { it.isNotBlank() }
                ?: sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return false
        return cachedReplyThreadStreamId(namespaceId, parentChannel, parentMessage) == routeStream
    }

    internal fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != provenance) return@synchronized false
            cacheReplyThreadResolution(
                provenance.namespaceId,
                parentChannelId,
                parentMessageId,
                threadChannelId
            )
            true
        }

    internal fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        payload: RaftThreadScreenPayload
    ): Boolean {
        val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return true
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return true
        val threadChannelId =
            payload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return true
        return cacheReplyThreadResolution(
            provenance,
            parentChannelId,
            parentMessageId,
            threadChannelId
        )
    }

    internal fun cacheReplyThreadResolution(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ) {
        namespaceId ?: return
        if (parentChannelId.isBlank() || parentMessageId.isBlank() || threadChannelId.isBlank()) return
        if (parentChannelId == threadChannelId) return
        replyThreadResolveCache[ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)] = threadChannelId
        messageStore?.upsertReplyThreadStream(namespaceId, parentChannelId, parentMessageId, threadChannelId)
    }

    /**
     * The unread-thread pill's prewarm (artin): [prewarmReplyThread] for a reply thread of the
     * current server, whose id the caller (a channel page) does not hold itself.
     */
    suspend fun prewarmReplyThreadOnCurrentServer(
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
    ): DataResult<Unit> {
        val serverId =
            readReplyResolutionScope()?.provenance?.serverId?.takeIf { it.isNotBlank() }
                ?: return authSessionRequiredResult()
        return prewarmReplyThread(
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = serverId,
                        parentChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        threadChannelId = threadChannelId,
                    )
            )
        )
    }

    suspend fun prewarmReplyThread(
        marker: ActivityReplyThreadPrewarmMarker
    ): DataResult<Unit> {
        val key = marker.key
        if (key.isConversation) return prewarmActivityConversation(key)
        if (
            key.serverId.isBlank() ||
            key.parentChannelId.isBlank() ||
            key.parentMessageId.isBlank() ||
            key.threadChannelId.isBlank()
        ) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_identity_missing", retryable = false))
        }
        val replyResolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = replyResolutionScope.session
        val payloadOwnerScopeId =
            replyResolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val selection = selectedServerStore
            ?: return DataResult.Error(ScreenError(code = "thread_prewarm_selection_unavailable", retryable = false))
        if (!selection.isSelected(key.serverId)) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_server_changed", retryable = false))
        }
        val basePayload =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = key.threadChannelId,
                streamChannelId = key.threadChannelId,
                parentChannelId = key.parentChannelId,
                parentMessageId = key.parentMessageId,
                originParentMessageId = key.parentMessageId
            )
        // Task #14b: the reply page this prewarm fetched, kept for the durable write below, and the thread
        // scope's commit generation read BEFORE the fetch (a write landing after it makes the page stale).
        var fetchedPage: Pair<String?, RawMessagePage?>? = null
        val durableCommitGeneration =
            replyResolutionScope.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?.let { partition -> enteredScopeSnapshotLoader?.replyThreadCommitGeneration(partition, key.threadChannelId) }
        val durableHeadEpoch = durableHeadEpochOrNull(replyResolutionScope.provenance)
        // Message storage Phase 2b: the write revision before the fetch too — a realtime loose write bumps no commit
        // generation, but its revision is newer than the page, so the page never overwrites it.
        val durableRevAtRequest =
            replyResolutionScope.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?.let { partition -> enteredScopeSnapshotLoader?.currentLocalRev(partition) }
        val result =
            payloadCache.prewarm(
                serverId = key.serverId,
                threadId = key.threadChannelId,
                ownerScopeId = payloadOwnerScopeId
            ) {
                val fetched =
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = basePayload,
                        requestPriority = DataRequestPriority.Background,
                        replyResolutionScope = replyResolutionScope,
                        onRawReplyPage =
                            if (durableCommitGeneration == null) {
                                null
                            } else {
                                { threadChannelId, page -> fetchedPage = threadChannelId to page }
                            }
                    )
                val payload =
                    when (fetched) {
                        is DataResult.Content -> fetched.data
                        is DataResult.Empty ->
                            if (fetched.reason == EmptyReason.NoMessages) {
                                basePayload.toEmptyReplyThreadPayload()
                            } else {
                                return@prewarm fetched
                            }
                        is DataResult.Error -> return@prewarm fetched
                        is DataResult.NoPermission -> return@prewarm fetched
                    }
                DataResult.Content(payload)
            }
        (result as? DataResult.Content)?.data?.let { payload ->
            if (!cacheReplyThreadResolution(replyResolutionScope.provenance, payload)) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            fetchedPage?.let { (threadChannelId, page) ->
                persistReplyThreadPrewarmPageIfCurrent(
                    basePayload = basePayload,
                    context = ReplyCacheProducerContext(session, replyResolutionScope.provenance),
                    resolvedThreadChannelId = threadChannelId,
                    rawPage = page,
                    expectedCommitGeneration = durableCommitGeneration ?: return@let,
                    headEpochAtRequest = durableHeadEpoch,
                    revAtRequest = durableRevAtRequest,
                )
            }
        }
        return when (result) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * artin #111: warm a channel / DM row of the Activity viewport. Fetches, at Background priority, the
     * exact focused `messages/context` (+ `channels/:id`) the Activity open of that row makes, into the
     * same app-scope payload cache and single-flight prewarm slot the open reads
     * ([SharedThreadPayloadStore.prewarm] / [SharedThreadPayloadStore.joinOrConsumePrewarmWithOutcome]).
     * Skips the request when the cache already holds this conversation's current content including the
     * anchor. The open still validates the warmed payload against its own route before using it.
     */
    private suspend fun prewarmActivityConversation(key: ActivityReplyThreadPrewarmKey): DataResult<Unit> {
        fun identityMissing() =
            DataResult.Error(ScreenError(code = "thread_prewarm_identity_missing", retryable = false))
        val surfaceKind =
            key.conversationSurfaceKind
                ?.takeIf { it == RaftThreadSurfaceKind.Channel || it == RaftThreadSurfaceKind.DirectMessage }
                ?: return identityMissing()
        val anchorMessageId = key.anchorMessageId?.takeIf { it.isNotBlank() } ?: return identityMissing()
        if (key.serverId.isBlank() || key.threadChannelId.isBlank()) return identityMissing()
        val resolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val payloadOwnerScopeId =
            resolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val selection = selectedServerStore
            ?: return DataResult.Error(ScreenError(code = "thread_prewarm_selection_unavailable", retryable = false))
        if (!selection.isSelected(key.serverId) || resolutionScope.provenance.serverId != key.serverId) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_server_changed", retryable = false))
        }
        val cached = cachedPayloadIfCurrent(key.threadChannelId, resolutionScope.provenance)
        if (cached != null && cached.messages.any { message -> message.id == anchorMessageId }) {
            diagnosticsLogger(
                "thread_conversation_prewarm_skip reason=cached_current threadId=${key.threadChannelId} " +
                    "surface=$surfaceKind anchor=$anchorMessageId"
            )
            return DataResult.Content(Unit)
        }
        val isDirectMessage = surfaceKind == RaftThreadSurfaceKind.DirectMessage
        // Mirrors the route payload of a non-task channel / DM open (ThreadRoutePayload), so the warmed
        // payload carries the same route-derived header flags as the open's own answer would.
        val basePayload =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = surfaceKind,
                channelKind = surfaceKind,
                sourceThreadId = key.threadChannelId,
                streamChannelId = key.threadChannelId,
                memberScopedMentions = isDirectMessage,
                focusedMessageId = anchorMessageId,
                selectedTab = "chat",
                navigationLabel = "Back",
                showModeTabs = true,
                showMembersAction = !isDirectMessage,
                showSettingsAction = !isDirectMessage,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
            )
        diagnosticsLogger(
            "thread_conversation_prewarm_start threadId=${key.threadChannelId} surface=$surfaceKind " +
                "anchor=$anchorMessageId priority=background"
        )
        val result =
            payloadCache.prewarm(
                serverId = key.serverId,
                threadId = key.threadChannelId,
                ownerScopeId = payloadOwnerScopeId
            ) {
                if (!isCurrentReplyCacheProvenance(resolutionScope.provenance)) {
                    return@prewarm staleReplyCacheProvenanceResult()
                }
                when (
                    val outcome =
                        fetchFocusedInitialThreadPayloadFromRepository(
                            session = session,
                            basePayload = basePayload,
                            routeThreadId = ThreadId(key.threadChannelId),
                            accessFallback = null,
                            replyResolutionScope = resolutionScope,
                            requestPriority = DataRequestPriority.Background,
                        )
                ) {
                    is FocusedInitialContextOutcome.Loaded -> outcome.result
                    // The context answered for another conversation (or not at all): nothing to warm.
                    else -> DataResult.Error(ScreenError(code = "thread_prewarm_context_mismatch", retryable = false))
                }
            }
        diagnosticsLogger(
            "thread_conversation_prewarm_result threadId=${key.threadChannelId} surface=$surfaceKind " +
                "result=${result.threadDiagnosticsResult()}"
        )
        (result as? DataResult.Content)?.data?.let { payload ->
            seedThreadAuthorProfiles(session.currentServerId, payload, source = "conversation_prewarm")
        }
        return when (result) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    internal suspend fun resolveReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> {
        val repository = conversationRepository ?: return DataResult.Content(basePayload)
        if (!basePayload.needsReplyThreadStreamResolution()) {
            return DataResult.Content(basePayload)
        }
        return createOrOpenReplyThreadStream(basePayload, repository, replyResolutionScope)
    }

    internal suspend fun createOrOpenReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        repository: ConversationRepository? = conversationRepository,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> {
        val resolvedRepository =
            repository
                ?: return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
        val replyResolutionNamespaceId = replyResolutionScope.namespaceId
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        cachedReplyThreadStreamId(replyResolutionNamespaceId, parentChannelId, parentMessageId)?.let { threadChannelId ->
            return DataResult.Content(
                basePayload.withResolvedReplyThreadAction(
                    ThreadActionResult(
                        threadChannelId = ChannelId(threadChannelId),
                        changed = false,
                        parentMessageId = MessageId(parentMessageId),
                        replyCount = basePayload.originMessage?.replyCount ?: 0
                    )
                )
            )
        }
        val actionResult =
            try {
                resolvedRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessageId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true, traceId = null))
            }
        return when (actionResult) {
            is DataResult.Content -> {
                if (!cacheReplyThreadResolution(
                        replyResolutionScope.provenance,
                        parentChannelId,
                        parentMessageId,
                        actionResult.data.threadChannelId.value
                    )
                ) {
                    return DataResult.Error(
                        ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                    )
                }
                DataResult.Content(basePayload.withResolvedReplyThreadAction(actionResult.data))
            }
            is DataResult.Empty -> actionResult
            is DataResult.Error -> actionResult
            is DataResult.NoPermission -> actionResult
        }
    }
}
