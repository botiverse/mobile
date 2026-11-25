package build.raft.app

import build.raft.app.SharedThreadDataSource.FocusedInitialContextOutcome
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.map
import build.raft.app.data.withInboxFetchReason
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ThreadDurableEnteredPage
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lower fetch paths of [SharedThreadDataSource] (an older / newer / gap page, the initial, focused and
 * reply-thread snapshots, and the repository call under them with its stale-producer checks), moved out of it
 * unchanged; the data source forwards to them. Holds no state: the caches, the access flights and the
 * provenance belong to the owners passed in.
 */
internal class ThreadConversationFetch(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val payloadCache: SharedThreadPayloadStore,
    private val durableCacheGateway: ThreadDurableCacheGateway,
    private val replyThreadPrewarm: ThreadReplyThreadPrewarm,
    private val replyParentMessages: ThreadReplyParentMessages,
    private val conversationRepository: ConversationRepository?,
    private val storedConversationDetails: build.raft.app.data.StoredConversationDetails?,
    private val canonicalConversationIdentityPublisher:
        suspend (ReplyCacheProvenance, build.raft.app.model.ConversationSummary) -> Boolean,
    private val replyOriginHydrationGraceMillis: Long,
    private val diagnosticsLogger: (String) -> Unit,
    private val readReplyResolutionScope: suspend () -> ReplyResolutionScope?,
    private val threadPageSize: (serverId: String?) -> Int,
    private val replyThreadAccessFlightKeyOf: (ReplyResolutionScope, RaftThreadScreenPayload) -> SharedReplyThreadAccessFlightKey?,
    private val seedAuthorProfiles: (serverId: String?, payload: RaftThreadScreenPayload, source: String) -> Unit,
    private val replyThreadAccessValidatedHook: () -> (suspend () -> Unit)?,
) {
    private val onReplyThreadAccessValidatedForTest: (suspend () -> Unit)? get() = replyThreadAccessValidatedHook()

    private fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenance(expected)

    private fun durableHeadEpochOrNull(provenance: ReplyCacheProvenance): Long? =
        durableCacheGateway.durableHeadEpochOrNull(provenance)

    private suspend fun durableLocalRevOrNull(provenance: ReplyCacheProvenance): Long? =
        durableCacheGateway.durableLocalRevOrNull(provenance)

    private fun replyThreadAccessFlightKey(
        resolutionScope: ReplyResolutionScope,
        basePayload: RaftThreadScreenPayload
    ): SharedReplyThreadAccessFlightKey? = replyThreadAccessFlightKeyOf(resolutionScope, basePayload)

    private fun seedThreadAuthorProfiles(
        serverId: String?,
        payload: RaftThreadScreenPayload,
        source: String
    ) = seedAuthorProfiles(serverId, payload, source)

    private suspend fun createOrOpenReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> =
        replyThreadPrewarm.createOrOpenReplyThreadStream(basePayload, conversationRepository, replyResolutionScope)

    private suspend fun replyThreadAccessAuthorityStillCurrent(
        expectedKey: SharedReplyThreadAccessFlightKey,
        expectedProvenance: ReplyCacheProvenance,
        basePayload: RaftThreadScreenPayload
    ): Boolean {
        val currentScope = readReplyResolutionScope() ?: return false
        return currentScope.provenance == expectedProvenance &&
            replyThreadAccessFlightKey(currentScope, basePayload) == expectedKey
    }

    internal suspend fun fetchThreadPage(
        basePayload: RaftThreadScreenPayload,
        source: String,
        pageSizeOverride: Int? = null,
        repositoryCall: suspend (ConversationRepository, ThreadId, Int) -> DataResult<ThreadSnapshot>,
        call: suspend (threadId: String, pageSize: Int) -> ThreadApiResult<ThreadMessagesPage>
    ): DataResult<SharedThreadPageFetchResult> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val pageSize = pageSizeOverride ?: threadPageSize(session.currentServerId)
        diagnosticsLogger(
            "thread_page_size source=$source server=${session.currentServerId.orEmpty()} pageSize=$pageSize"
        )
        var rawPage = RawMessagePageWitness.None
        var rawServerRowCount = 0
        // Phase 3b: read before the request (with the identity): a horizonReset during it voids the page's head claim.
        val headEpochAtRequest = durableHeadEpochOrNull(resolutionScope.provenance)
        val revAtRequest = durableLocalRevOrNull(resolutionScope.provenance)
        val result =
            if (conversationRepository != null) {
                fetchThreadPayloadFromRepository(
                    session = session,
                    expectedProvenance = resolutionScope.provenance,
                    fallbackCode = "thread_${source}_network",
                    call = { repository -> repositoryCall(repository, ThreadId(threadId), pageSize) },
                    transform = { snapshot ->
                        rawPage = snapshot.rawMessagePage
                        rawServerRowCount = snapshot.rawMessagePage.page?.messages?.size ?: snapshot.rawMessageCount
                        snapshot.toRaftThreadPayload(basePayload, session.user.id)
                    }
                )
            } else {
                val response =
                    try {
                        call(threadId, pageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_${source}_network", retryable = true, traceId = null))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_${source}_network")
                    }
                rawServerRowCount = page.messages.size
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = basePayload,
                        page = page,
                        session = session,
                        diagnostics = diagnosticsLogger,
                    )
                )
            }
        return when (result) {
            is DataResult.Content -> {
                seedThreadAuthorProfiles(session.currentServerId, result.data, source = source)
                DataResult.Content(
                    SharedThreadPageFetchResult(
                        payload = result.data,
                        requestedPageSize = pageSize,
                        rawServerRowCount = rawServerRowCount,
                        durablePage =
                            rawPage.page?.let { page ->
                                SharedThreadDurablePage(
                                    context = ReplyCacheProducerContext(session, resolutionScope.provenance),
                                    rawPage = page,
                                    headEpochAtRequest = headEpochAtRequest,
                                    revAtRequest = revAtRequest,
                                )
                            }
                    )
                )
            }
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    internal suspend fun fetchThreadPayloadFromRepository(
        session: StoredAuthSession,
        expectedProvenance: ReplyCacheProvenance,
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        transform: (ThreadSnapshot) -> RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> {
        val result =
            fetchThreadSnapshotFromRepository(
                fallbackCode = fallbackCode,
                expectedProvenance = expectedProvenance,
                call = call
            )
        return result.map { snapshot ->
            transform(snapshot)
                .asLoadedEmptyReplyThreadPayload()
        }
    }

    internal suspend fun fetchInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        threadId: ThreadId,
        replyResolutionScope: ReplyResolutionScope,
        includeFollowedSummaries: Boolean = false,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null
    ): DataResult<RaftThreadScreenPayload> {
        val focusedContextOutcome =
            if (includeFollowedSummaries) {
                FocusedInitialContextOutcome.NotAttemptedOrTransient
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = threadId,
                    accessFallback = null,
                    replyResolutionScope = replyResolutionScope,
                    onFocusedContextPage = onFocusedContextPage
                )
            }
        if (focusedContextOutcome is FocusedInitialContextOutcome.Loaded) {
            return focusedContextOutcome.result
        }
        val focusMissing = focusedContextOutcome is FocusedInitialContextOutcome.TargetMissing
        val fallbackBase =
            if (focusMissing) {
                basePayload.copy(focusedMessageId = null, focusedMessageRequestNonce = 0)
            } else {
                basePayload
            }
        val initialResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_network",
                expectedProvenance = replyResolutionScope.provenance,
                loadedConversationId = threadId.value,
            ) { repository ->
                val pageSize = threadPageSize(session.currentServerId)
                diagnosticsLogger?.invoke(
                    "thread_page_size source=initial_repository server=${session.currentServerId.orEmpty()} " +
                        "pageSize=$pageSize"
                )
                withInboxFetchReason(if (includeFollowedSummaries) InboxFetchReason.Initial else null) {
                    repository.getThread(
                        threadId = threadId,
                        includeFollowedSummaries = includeFollowedSummaries,
                        limit = pageSize,
                        captureRawMessagePage = onEnteredPage != null
                    )
                }
            }
        val initialData = initialResult as? DataResult.Content
        // Message storage Phase 2b: the page this request fetched is the durable write (with the channel detail the
        // same load fetched: its kind names the scope).
        initialData?.data?.let { snapshot ->
            val raw = snapshot.rawMessagePage.page ?: return@let
            onEnteredPage?.invoke(
                raw.toEnteredPage(
                    channelId = threadId.value,
                    channelType =
                        snapshot.canonicalConversationDetail
                            ?.takeIf { detail -> detail.channelId?.value == null || detail.channelId?.value == threadId.value }
                            ?.channelType,
                )
            )
        }
        // If the route's stream hint is untrusted (stream != parent channel) and the
        // thread-channel snapshot's reported parent pair differs from the route,
        // fall back to the resolve path which POSTs createOrOpenThread to
        // reconcile. This is the v0.2 defense-in-depth: the snapshot may not
        // carry parent metadata at all (treated as trust), but if it does and
        // disagrees with the canonical route parent pair, we don't show it.
        return when {
            initialData != null &&
                fallbackBase.hasUntrustedReplyThreadStreamHint() &&
                !initialData.data.parentPairMatchesRoute(fallbackBase) ->
                fetchResolvedReplyThreadSnapshot(
                    session = session,
                    basePayload = fallbackBase,
                    replyResolutionScope = replyResolutionScope
                ).map { (resolvedBase, snapshot) ->
                    (snapshot
                        ?.withReplyParentMessageFromContext(resolvedBase, replyResolutionScope = replyResolutionScope)
                        ?.toRaftThreadPayload(resolvedBase, session.user.id)
                        ?: resolvedBase.toEmptyReplyThreadPayload())
                        .let { if (focusMissing) it.copy(focusedTargetMissing = true) else it }
                }
            initialData != null ->
                DataResult.Content(
                    initialData.data
                        .withReplyParentMessageFromContext(fallbackBase, replyResolutionScope = replyResolutionScope)
                        .toRaftThreadPayload(fallbackBase, session.user.id)
                        .let { if (focusMissing) it.copy(focusedTargetMissing = true) else it }
                )
            else -> initialResult as DataResult<RaftThreadScreenPayload>
        }
    }

    internal suspend fun fetchFocusedInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        routeThreadId: ThreadId,
        accessFallback: ConversationAccessCandidate?,
        replyResolutionScope: ReplyResolutionScope,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        /** artin #111: the Activity conversation prewarm asks at Background; every open stays Foreground. */
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
    ): FocusedInitialContextOutcome {
        val focusedMessageId = basePayload.focusedMessageId?.takeIf { it.isNotBlank() }
            ?: return FocusedInitialContextOutcome.NotAttemptedOrTransient
        val replyParentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
        if (basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && focusedMessageId == replyParentMessageId) {
            // The parent message lives in the parent channel, not the thread stream: requesting
            // `messages/context/<parentMessageId>?channelId=<threadChannelId>` always 404s. Fall
            // through to `resolveReplyParentMessageFromStore` + `getThread` while keeping focus.
            return FocusedInitialContextOutcome.NotAttemptedOrTransient
        }
        val focusedContextChannelId = basePayload.focusedMessageContextChannelIdOrNull()
        // Phase 3b: read before the request: a horizonReset during it voids the page's head claim.
        val headEpochAtRequest = durableHeadEpochOrNull(replyResolutionScope.provenance)
        val revAtRequest = durableLocalRevOrNull(replyResolutionScope.provenance)
        val contextResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_focused_context",
                expectedProvenance = replyResolutionScope.provenance
            ) { repository ->
                // The route names the conversation, so its header detail is read beside the context.
                repository.getMessageContextInConversation(
                    messageId = MessageId(focusedMessageId),
                    channelId = focusedContextChannelId,
                    expectedConversationId = ChannelId(routeThreadId.value),
                    priority = requestPriority,
                )
            }
        val context =
            when (contextResult) {
                is DataResult.Content -> contextResult.data
                is DataResult.Error -> {
                    if (isFinalFocusedMessageNotFoundError(contextResult.error)) {
                        val cause = contextResult.error.serverCode ?: contextResult.error.code
                        diagnosticsLogger(
                            "thread_focused_context_missing scope=${routeThreadId.value} " +
                                "anchor=$focusedMessageId cause=$cause quiet=true"
                        )
                        return FocusedInitialContextOutcome.TargetMissing(cause)
                    }
                    return FocusedInitialContextOutcome.NotAttemptedOrTransient
                }
                else -> return FocusedInitialContextOutcome.NotAttemptedOrTransient
            }
        if (!context.matchesFocusedThreadRoute(routeThreadId, focusedMessageId)) {
            return FocusedInitialContextOutcome.NotAttemptedOrTransient
        }
        // Task #14 part C: the context page is a fact source too — hand it to the durable writer (the
        // caller decides when; the writer decides whether it joins the stored window).
        onFocusedContextPage?.let { sink ->
            val rawPage = context.rawMessagePage.page
            if (rawPage == null) {
                diagnosticsLogger(
                    "canonical_v2_context_page server=${replyResolutionScope.provenance.serverId} " +
                        "scope=${routeThreadId.value} anchor=$focusedMessageId outcome=skipped reason=no_raw_page facts=0"
                )
            } else {
                sink(
                    SharedThreadDurableContextPage(
                        context = ReplyCacheProducerContext(session, replyResolutionScope.provenance),
                        routeIdentity = basePayload.resolveThreadRouteIdentity(),
                        replyParent = basePayload.durableReplyParentOrNull(),
                        anchorMessageId = focusedMessageId,
                        rawPage = rawPage,
                        hasOlder = context.canLoadMoreBefore,
                        hasNewer = context.canLoadMoreAfter,
                        headEpochAtRequest = headEpochAtRequest,
                        revAtRequest = revAtRequest,
                    )
                )
            }
        }
        return FocusedInitialContextOutcome.Loaded(
            DataResult.Content(
                context
                    .withCachedReplyParentMessage(
                        basePayload = basePayload,
                        messageStoreScopeId = replyResolutionScope.messageStoreScopeId
                    )
                    .withConversationAccessFallback(accessFallback)
                    .toRaftThreadPayload(basePayload, session.user.id)
            )
        )
    }

    private fun ThreadSnapshot.withCachedReplyParentMessage(
        basePayload: RaftThreadScreenPayload,
        messageStoreScopeId: String?
    ): ThreadSnapshot =
        with(replyParentMessages) { this@withCachedReplyParentMessage.withCachedReplyParentMessage(basePayload, messageStoreScopeId) }

    private suspend fun ThreadSnapshot.withReplyParentMessageFromContext(
        basePayload: RaftThreadScreenPayload,
        allowNetworkHydration: Boolean = true,
        replyResolutionScope: ReplyResolutionScope
    ): ThreadSnapshot =
        with(replyParentMessages) {
            this@withReplyParentMessageFromContext.withReplyParentMessageFromContext(
                basePayload,
                allowNetworkHydration,
                replyResolutionScope,
            )
        }

    internal suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        includeFollowedSummaries: Boolean = false,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        replyResolutionScope: ReplyResolutionScope,
        replaceReplyAccessFailure: Boolean = false,
        /**
         * Task #14b: when set, the reply page's raw `messages` array is requested and handed here with
         * the thread channel id the server resolved (both `null`-able: a joined access flight that did not
         * capture it, or a snapshot without its stream id), before the payload is built.
         */
        onRawReplyPage: ((threadChannelId: String?, page: RawMessagePage?) -> Unit)? = null,
        /** Task #14 part C: receives the anchor context page of a focused open (see [SharedThreadDurableContextPage]). */
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null
    ): DataResult<RaftThreadScreenPayload> = coroutineScope {
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val routeThreadId =
            basePayload.streamChannelId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
                ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        val focusedContextOutcome =
            if (includeFollowedSummaries || routeThreadId == null) {
                FocusedInitialContextOutcome.NotAttemptedOrTransient
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = routeThreadId,
                    accessFallback = null,
                    replyResolutionScope = replyResolutionScope,
                    onFocusedContextPage = onFocusedContextPage
                )
            }
        if (focusedContextOutcome is FocusedInitialContextOutcome.Loaded) {
            return@coroutineScope focusedContextOutcome.result
        }
        val focusMissing = focusedContextOutcome is FocusedInitialContextOutcome.TargetMissing
        val effectiveBase =
            if (focusMissing) {
                basePayload.copy(focusedMessageId = null, focusedMessageRequestNonce = 0)
            } else {
                basePayload
            }
        val parentContextStartedAt = TimeSource.Monotonic.markNow()
        val parentContextDeferred =
            async {
                resolveReplyParentMessageFromStore(
                    parentChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    requestPriority = requestPriority,
                    messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                    expectedProvenance = replyResolutionScope.provenance
                )
            }
        val accessFlightKey = replyThreadAccessFlightKey(replyResolutionScope, basePayload)
        // Message storage Phase 2b: set only when THIS caller's request ran. A joined access flight (another
        // caller's request, possibly sent before this caller took its durable ticket) never hands its page over.
        var ownFetch = false
        suspend fun fetchInitialSnapshot(): DataResult<ThreadSnapshot> {
            ownFetch = true
            if (accessFlightKey != null &&
                !replyThreadAccessAuthorityStillCurrent(
                    expectedKey = accessFlightKey,
                    expectedProvenance = replyResolutionScope.provenance,
                    basePayload = basePayload
                )
            ) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            val result =
                fetchThreadSnapshotFromRepository(
                    fallbackCode = "thread_network_reply",
                    expectedProvenance = replyResolutionScope.provenance
                ) { repository ->
                    val pageSize = threadPageSize(session.currentServerId)
                    diagnosticsLogger(
                        "thread_page_size source=initial_reply server=${session.currentServerId.orEmpty()} " +
                            "pageSize=$pageSize"
                    )
                    repository.getReplyThread(
                        parentChannelId = ChannelId(parentChannelId),
                        parentMessageId = MessageId(parentMessageId),
                        includeFollowedSummaries = includeFollowedSummaries,
                        limit = pageSize,
                        priority = requestPriority,
                        captureRawMessagePage = onRawReplyPage != null
                    )
                }
            if (accessFlightKey != null &&
                !replyThreadAccessAuthorityStillCurrent(
                    expectedKey = accessFlightKey,
                    expectedProvenance = replyResolutionScope.provenance,
                    basePayload = basePayload
                )
            ) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            return result
        }
        val initialResult =
            if (accessFlightKey == null) {
                fetchInitialSnapshot()
            } else {
                payloadCache.joinOrStartReplyThreadAccessFlight(
                    key = accessFlightKey,
                    replaceFailure = replaceReplyAccessFailure,
                    fetch = ::fetchInitialSnapshot
                )
            }
        return@coroutineScope when (initialResult) {
            is DataResult.Content -> {
                onReplyThreadAccessValidatedForTest?.invoke()
                onRawReplyPage?.invoke(
                    initialResult.data.conversation.thread?.streamChannelId?.value?.takeIf { it.isNotBlank() },
                    initialResult.data.rawMessagePage.page?.takeIf { ownFetch }
                )
                val hasReplyContent = initialResult.data.hasRenderableReplyThreadMessages(basePayload)
                val parentContextRemainingGraceMillis =
                    (replyOriginHydrationGraceMillis -
                        parentContextStartedAt.elapsedNow().inWholeMilliseconds)
                        .coerceAtLeast(0L)
                val parentResolution =
                    if (parentContextDeferred.isCompleted) {
                        parentContextDeferred.await()
                    } else {
                        withTimeoutOrNull(parentContextRemainingGraceMillis) {
                            parentContextDeferred.await()
                        }
                    }
                // A grace timeout only means that the canonical parent lookup has not
                // completed yet. It is not evidence that the parent message is absent.
                // The scoped message-store producer may still complete after this
                // caller is cancelled, and the visible route will hydrate that result.
                val parentContextTimedOut = parentResolution == null
                if (parentContextTimedOut) {
                    parentContextDeferred.cancel()
                    diagnosticsLogger(
                        "thread_parent_context_grace_expired parentChannelId=$parentChannelId " +
                            "parentMessageId=$parentMessageId " +
                            "graceMs=$replyOriginHydrationGraceMillis " +
                            "hasReplyContent=$hasReplyContent"
                    )
                }
                val parentMessage =
                    (parentResolution as? SharedThreadMessageResolution.Loaded)?.message
                val resolvedThreadId =
                    initialResult.data.conversation.thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.channelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.threadId.value.takeIf { it.isNotBlank() }
                        ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                        ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                val resolvedBase =
                    if (resolvedThreadId == null) {
                        effectiveBase
                    } else {
                        effectiveBase.copy(
                            sourceThreadId = resolvedThreadId,
                            streamChannelId = resolvedThreadId
                        )
                    }
                val originResolvedBase =
                    when (parentResolution) {
                        is SharedThreadMessageResolution.Loaded -> resolvedBase
                        SharedThreadMessageResolution.Missing ->
                            resolvedBase.copy(
                                originStatus = "missing",
                                originErrorCode = null
                            )
                        is SharedThreadMessageResolution.Error ->
                            resolvedBase.copy(
                                originStatus = "error",
                                originErrorCode = parentResolution.code,
                                originHiddenTarget = parentResolution.hiddenTarget
                            )
                        null ->
                            resolvedBase.copy(
                                originStatus = "loading",
                                originErrorCode = null
                            )
                    }
                val payload =
                    initialResult.data
                        .withReplyParentMessage(parentChannelId, parentMessageId, parentMessage)
                        .withReplyThreadReplyCountOnParent()
                        .rememberReplyParentMessage(
                            parentChannelId,
                            parentMessageId,
                            replyResolutionScope.messageStoreScopeId
                        )
                        .withReplyParentMessageFromContext(
                            basePayload = originResolvedBase,
                            allowNetworkHydration = false,
                            replyResolutionScope = replyResolutionScope
                        )
                        .toRaftThreadPayload(originResolvedBase, session.user.id)
                        .let { if (focusMissing) it.copy(focusedTargetMissing = true) else it }
                DataResult.Content(
                    if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && payload.messages.isEmpty()) {
                        payload.toEmptyReplyThreadPayload()
                    } else {
                        payload
                    }
                )
            }
            is DataResult.Empty -> {
                parentContextDeferred?.cancel()
                initialResult
            }
            is DataResult.Error -> {
                parentContextDeferred?.cancel()
                initialResult
            }
            is DataResult.NoPermission -> {
                parentContextDeferred?.cancel()
                initialResult
            }
        }
    }

    private suspend fun resolveReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): SharedThreadMessageResolution =
        replyParentMessages.resolveReplyParentMessageFromStore(
            parentChannelId,
            parentMessageId,
            requestPriority,
            messageStoreScopeId,
            expectedProvenance,
        )

    private fun ThreadSnapshot.rememberReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String,
        messageStoreScopeId: String?
    ): ThreadSnapshot =
        with(replyParentMessages) {
            this@rememberReplyParentMessage.rememberReplyParentMessage(parentChannelId, parentMessageId, messageStoreScopeId)
        }

    private suspend fun fetchResolvedReplyThreadSnapshot(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<Pair<RaftThreadScreenPayload, ThreadSnapshot?>> {
        val resolvedBasePayload =
            when (
                val resolved =
                    createOrOpenReplyThreadStream(
                        basePayload = basePayload,
                        replyResolutionScope = replyResolutionScope
                    )
            ) {
                is DataResult.Content -> resolved.data
                is DataResult.Empty -> return resolved
                is DataResult.Error -> return resolved
                is DataResult.NoPermission -> return resolved
            }
        val resolvedThreadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val resolvedResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_network",
                expectedProvenance = replyResolutionScope.provenance
            ) { repository ->
                val pageSize = threadPageSize(session.currentServerId)
                diagnosticsLogger(
                    "thread_page_size source=resolved_reply server=${session.currentServerId.orEmpty()} " +
                        "pageSize=$pageSize"
                )
                repository.getThread(
                    threadId = ThreadId(resolvedThreadId),
                    includeFollowedSummaries = false,
                    limit = pageSize
                )
            }
        return when (resolvedResult) {
            is DataResult.Content ->
                DataResult.Content(resolvedBasePayload to resolvedResult.data)
            is DataResult.Empty ->
                if (resolvedResult.reason == EmptyReason.NoMessages) {
                    DataResult.Content(resolvedBasePayload to null)
                } else {
                    resolvedResult
                }
            is DataResult.Error -> resolvedResult
            is DataResult.NoPermission -> resolvedResult
        }
    }

    internal suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        expectedProvenance: ReplyCacheProvenance,
        allowCapturedScopeResultAfterProvenanceChange: Boolean = false,
        /** The conversation this request loads: gone (404 / lost permission) = its stored header detail goes. */
        loadedConversationId: String? = null,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
        // Offline-first header: the stored detail is fenced from now (the request's start), not its answer.
        val detailFetchStartedAt = storedConversationDetails?.fetchStarted()
        val result =
            try {
                call(repository)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = fallbackCode, retryable = true, traceId = null))
            }
        val detail =
            (result as? DataResult.Content)
                ?.data
                ?.canonicalConversationDetail
                ?.takeIf { candidate ->
                    candidate.membershipKnown &&
                        !candidate.channelType.equals("thread", ignoreCase = true) &&
                        candidate.kind != ConversationKind.Thread
                }
        if (detail != null) {
            if (!isCurrentReplyCacheProvenance(expectedProvenance)) {
                diagnosticsLogger(
                    "conversation_directory_canonical_drop reason=stale_producer " +
                        "serverId=${expectedProvenance.serverId} channelId=${detail.channelId?.value.orEmpty()}"
                )
            } else {
                try {
                    val published = canonicalConversationIdentityPublisher(expectedProvenance, detail)
                    if (published) {
                        storedConversationDetails?.recordCanonical(
                            owner = expectedProvenance.storedConversationDetailOwner(),
                            detail = detail,
                            startedAt = detailFetchStartedAt,
                        )
                    }
                    diagnosticsLogger(
                        "conversation_directory_canonical_publish " +
                            "serverId=${expectedProvenance.serverId} channelId=${detail.channelId?.value.orEmpty()} " +
                            "accepted=$published"
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    diagnosticsLogger(
                        "conversation_directory_canonical_publish_failed " +
                            "serverId=${expectedProvenance.serverId} error=${throwable::class.simpleName.orEmpty()}"
                    )
                }
            }
        }
        val goneConversationId = loadedConversationId?.trim()?.takeIf { it.isNotEmpty() && result.isConversationGone() }
        if (goneConversationId != null && storedConversationDetails != null && isCurrentReplyCacheProvenance(expectedProvenance)) {
            // Deleted / no longer visible: its name and topic must not come back on a cold open.
            storedConversationDetails.remove(expectedProvenance.storedConversationDetailOwner(), goneConversationId)
        }
        if (
            !allowCapturedScopeResultAfterProvenanceChange &&
            !isCurrentReplyCacheProvenance(expectedProvenance)
        ) {
            return staleReplyCacheProvenanceResult()
        }
        return result
    }
}
