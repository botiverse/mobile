package build.raft.app

import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.RaftThreadApi
import build.raft.app.data.RawMessagePage
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ThreadDurableEnteredPage
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The fetch entry points of [SharedThreadDataSource] (opening a conversation and its canonical refetch, the
 * followed-thread summaries, older / newer / gap pages, opening a reply thread, a web link or a message's
 * context), moved out of it unchanged; the data source forwards to them. Holds no state: the caches and the
 * provenance belong to the owners passed in.
 */
internal class ThreadPayloadFetch(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val payloadCache: SharedThreadPayloadStore,
    private val replyThreadPrewarm: ThreadReplyThreadPrewarm,
    private val conversationAccessResolution: ThreadConversationAccessResolution,
    private val conversationFetch: ThreadConversationFetch,
    private val api: RaftThreadApi,
    private val conversationRepository: ConversationRepository?,
    private val messageStore: SharedThreadMessageStore?,
    private val diagnosticsLogger: (String) -> Unit,
    private val readReplyResolutionScope: suspend () -> ReplyResolutionScope?,
    private val captureReplyCacheProducerContext: suspend () -> ReplyCacheProducerContext?,
    private val threadPageSize: (serverId: String?) -> Int,
    private val cacheCanonicalPayloadOf:
        suspend (RaftThreadScreenPayload, String?, ReplyCacheProvenance?) -> RaftThreadScreenPayload,
    private val seedAuthorProfiles: (serverId: String?, payload: RaftThreadScreenPayload, source: String) -> Unit,
) {
    private fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenance(expected)

    private fun isCurrentReplyCacheProvenanceForLocalSend(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenanceForLocalSend(expected)

    private fun currentReplyCacheProvenanceForLocalSend(
        expected: ReplyCacheProvenance
    ): ReplyCacheProvenance? = provenanceOwner.currentReplyCacheProvenanceForLocalSend(expected)

    private fun cachePayload(
        payload: RaftThreadScreenPayload,
        serverId: String,
        expectedGeneration: SharedThreadFreshnessGeneration?,
        ownerScopeId: String,
        expectedProvenance: ReplyCacheProvenance? = null
    ): Boolean = provenanceOwner.cachePayload(payload, serverId, expectedGeneration, ownerScopeId, expectedProvenance)

    private suspend fun cacheCanonicalPayload(
        payload: RaftThreadScreenPayload,
        expectedOwnerScopeId: String?
    ): RaftThreadScreenPayload = cacheCanonicalPayloadOf(payload, expectedOwnerScopeId, null)

    private suspend fun replyAccessResolutionOwnerStillCurrent(
        expected: ReplyResolutionScope
    ): Boolean = conversationAccessResolution.replyAccessResolutionOwnerStillCurrent(expected)

    private fun cachedReplyThreadStreamId(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String
    ): String? = replyThreadPrewarm.cachedReplyThreadStreamId(namespaceId, parentChannelId, parentMessageId)

    private fun RaftThreadScreenPayload.matchesCachedReplyThreadStreamResolution(
        namespaceId: String?
    ): Boolean =
        with(replyThreadPrewarm) {
            this@matchesCachedReplyThreadStreamResolution.matchesCachedReplyThreadStreamResolution(namespaceId)
        }

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ): Boolean =
        replyThreadPrewarm.cacheReplyThreadResolution(provenance, parentChannelId, parentMessageId, threadChannelId)

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        payload: RaftThreadScreenPayload
    ): Boolean = replyThreadPrewarm.cacheReplyThreadResolution(provenance, payload)

    private suspend fun resolveReplyThreadStream(
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> =
        replyThreadPrewarm.resolveReplyThreadStream(basePayload, replyResolutionScope)

    private fun seedThreadAuthorProfiles(
        serverId: String?,
        payload: RaftThreadScreenPayload,
        source: String
    ) = seedAuthorProfiles(serverId, payload, source)

    private suspend fun fetchThreadPage(
        basePayload: RaftThreadScreenPayload,
        source: String,
        pageSizeOverride: Int? = null,
        repositoryCall: suspend (ConversationRepository, ThreadId, Int) -> DataResult<ThreadSnapshot>,
        call: suspend (threadId: String, pageSize: Int) -> ThreadApiResult<ThreadMessagesPage>
    ): DataResult<SharedThreadPageFetchResult> =
        conversationFetch.fetchThreadPage(basePayload, source, pageSizeOverride, repositoryCall, call)

    private suspend fun fetchThreadPayloadFromRepository(
        session: StoredAuthSession,
        expectedProvenance: ReplyCacheProvenance,
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        transform: (ThreadSnapshot) -> RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> =
        conversationFetch.fetchThreadPayloadFromRepository(session, expectedProvenance, fallbackCode, call, transform)

    private suspend fun fetchInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        threadId: ThreadId,
        replyResolutionScope: ReplyResolutionScope,
        includeFollowedSummaries: Boolean = false,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null
    ): DataResult<RaftThreadScreenPayload> =
        conversationFetch.fetchInitialThreadPayloadFromRepository(
            session,
            basePayload,
            threadId,
            replyResolutionScope,
            includeFollowedSummaries,
            onFocusedContextPage,
            onEnteredPage,
        )

    private suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: RaftThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope,
        replaceReplyAccessFailure: Boolean,
        onRawReplyPage: ((threadChannelId: String?, page: RawMessagePage?) -> Unit)?,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)?
    ): DataResult<RaftThreadScreenPayload> =
        conversationFetch.fetchInitialReplyThreadPayloadFromRepository(
            session = session,
            basePayload = basePayload,
            replyResolutionScope = replyResolutionScope,
            replaceReplyAccessFailure = replaceReplyAccessFailure,
            onRawReplyPage = onRawReplyPage,
            onFocusedContextPage = onFocusedContextPage,
        )

    suspend fun fetchThreadPayload(
        basePayload: RaftThreadScreenPayload,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false,
        replaceReplyAccessFailure: Boolean = false
    ): DataResult<RaftThreadScreenPayload> =
        fetchThreadPayloadWithResolutionScope(
            basePayload = basePayload,
            persistResult = persistResult,
            reusePrewarm = reusePrewarm,
            replyResolutionScope = null,
            replaceReplyAccessFailure = replaceReplyAccessFailure
        )

    /**
     * Runs a visible Store refresh under the exact owner captured before the refresh began.
     * Re-sampling here could turn an A-owned Store request into a B-owned network request while
     * the surrounding Store generation still belongs to A.
     */
    internal suspend fun fetchThreadPayloadForContext(
        basePayload: RaftThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false,
        replaceReplyAccessFailure: Boolean = false,
        /** Task #14 part C: receives the anchor context page of a focused open, for the durable writer. */
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        /** Message storage Phase 2b: receives the request's own latest page, for the durable write-through. */
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null,
        /**
         * Receives the first canonical result whose freshness generation moved while it was fetched
         * (it is refetched; see [SharedThreadStaleCanonicalRefetchMaxExtraRounds]). Its content is not
         * final, but its conversation access is server truth the caller may act on right away.
         */
        onSupersededCanonicalResult: (suspend (DataResult<RaftThreadScreenPayload>) -> Unit)? = null,
        /**
         * Called when the stale-generation refetch hit its cap: the returned result is superseded
         * content (not cached here) that the caller must not present as final.
         */
        onCanonicalRefetchCapped: (() -> Unit)? = null,
        /**
         * artin #110: asked with the message ids when only live message events moved the freshness
         * generation during the request. True when the caller holds every one of those events for
         * this conversation and folds them onto the answer itself; the answer is then returned as is
         * (not cached under a generation it does not match) instead of being fetched again.
         */
        foldsRealtimeIntoSupersededResult: ((Set<String>) -> Boolean)? = null,
    ): DataResult<RaftThreadScreenPayload> {
        if (!isCurrentReplyCacheProvenanceForLocalSend(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return fetchThreadPayloadWithResolutionScope(
            basePayload = basePayload,
            persistResult = persistResult,
            reusePrewarm = reusePrewarm,
            replyResolutionScope =
                ReplyResolutionScope(
                    session = expectedContext.session,
                    provenance = expectedContext.provenance,
                    sharedMessageStore = messageStore != null,
                ),
            replaceReplyAccessFailure = replaceReplyAccessFailure,
            onFocusedContextPage = onFocusedContextPage,
            onEnteredPage = onEnteredPage,
            onSupersededCanonicalResult = onSupersededCanonicalResult,
            onCanonicalRefetchCapped = onCanonicalRefetchCapped,
            foldsRealtimeIntoSupersededResult = foldsRealtimeIntoSupersededResult,
        )
    }

    private suspend fun fetchThreadPayloadWithResolutionScope(
        basePayload: RaftThreadScreenPayload,
        persistResult: Boolean,
        reusePrewarm: Boolean,
        replyResolutionScope: ReplyResolutionScope?,
        replaceReplyAccessFailure: Boolean = false,
        onFocusedContextPage: ((SharedThreadDurableContextPage) -> Unit)? = null,
        onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? = null,
        onSupersededCanonicalResult: (suspend (DataResult<RaftThreadScreenPayload>) -> Unit)? = null,
        onCanonicalRefetchCapped: (() -> Unit)? = null,
        foldsRealtimeIntoSupersededResult: ((Set<String>) -> Boolean)? = null,
    ): DataResult<RaftThreadScreenPayload> {
        val resolutionScope = replyResolutionScope ?: readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val replyResolutionNamespaceId = resolutionScope.namespaceId
        val payloadOwnerScopeId =
            resolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val shouldFetchReplyByParent =
            conversationRepository != null &&
                basePayload.shouldFetchReplyThreadByParentPair() &&
                !basePayload.matchesCachedReplyThreadStreamResolution(replyResolutionNamespaceId)
        val resolvedBasePayload =
            if (shouldFetchReplyByParent) {
                basePayload
            } else {
                when (val resolved = resolveReplyThreadStream(basePayload, resolutionScope)) {
                    is DataResult.Content -> resolved.data
                    is DataResult.Empty -> return resolved
                    is DataResult.Error -> return resolved
                    is DataResult.NoPermission -> return resolved
                }
            }
        val threadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
        var failedPrewarm = false
        if (reusePrewarm && threadId != null) {
            val serverId = session.currentServerId.orEmpty()
            fun logReuse(outcome: String, reason: String) =
                diagnosticsLogger(
                    "thread_refresh_reuse_prewarm threadId=$threadId outcome=$outcome reason=$reason"
                )
            if (payloadCache.promoteQueuedPrewarm(serverId, threadId, payloadOwnerScopeId)) {
                diagnosticsLogger?.invoke(
                    "thread_prewarm_promote threadId=$threadId from=background_queue to=foreground"
                )
                logReuse("own_fetch", "prewarm_queued_promoted")
                failedPrewarm = true
            } else {
                val joined = payloadCache.joinOrConsumePrewarmWithOutcome(serverId, threadId, payloadOwnerScopeId)
                when (val warmed = joined.result?.takeUnlessWarmedConversationMisses(resolvedBasePayload)) {
                    is DataResult.Content -> {
                        if (!cacheReplyThreadResolution(resolutionScope.provenance, warmed.data)) {
                            return staleReplyCacheProvenanceResult()
                        }
                        logReuse(joined.outcome.diagnosticsName, "prewarm_content")
                        seedThreadAuthorProfiles(session.currentServerId, warmed.data, source = "prewarm")
                        return warmed
                    }
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> {
                        logReuse("own_fetch", "prewarm_failed_${joined.outcome.diagnosticsName}")
                        failedPrewarm = true
                    }
                    // JoinTimedOut leaves failedPrewarm false on purpose: the timed-out prewarm was detached
                    // (it can no longer commit), so there is nothing to take over; the open fetches itself.
                    // A warmed channel / DM page that does not answer this route (see
                    // takeUnlessWarmedConversationMisses) lands here too: it stays a first-frame candidate only.
                    null ->
                        logReuse(
                            "own_fetch",
                            if (joined.result is DataResult.Content) "prewarm_route_mismatch" else joined.outcome.diagnosticsName
                        )
                }
            }
        }
        suspend fun fetchCanonicalPayload(): DataResult<RaftThreadScreenPayload> =
            if (conversationRepository != null) {
                if (shouldFetchReplyByParent) {
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload,
                        replyResolutionScope = resolutionScope,
                        replaceReplyAccessFailure = replaceReplyAccessFailure,
                        onRawReplyPage =
                            onEnteredPage?.let { sink ->
                                { threadChannelId, page ->
                                    if (threadChannelId != null && page != null) {
                                        sink(page.toEnteredPage(threadChannelId, channelType = null))
                                    }
                                }
                            },
                        onFocusedContextPage = onFocusedContextPage
                    )
                } else {
                    val resolvedThreadId =
                        threadId
                            ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false, traceId = null))
                    fetchInitialThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload,
                        threadId = ThreadId(resolvedThreadId),
                        replyResolutionScope = resolutionScope,
                        onFocusedContextPage = onFocusedContextPage,
                        onEnteredPage = onEnteredPage
                    )
                }
            } else {
                val resolvedThreadId =
                    threadId
                        ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false, traceId = null))
                val response =
                    try {
                        val pageSize = threadPageSize(session.currentServerId)
                        diagnosticsLogger(
                            "thread_page_size source=initial_api server=${session.currentServerId.orEmpty()} " +
                                "pageSize=$pageSize"
                        )
                        api.channelMessages(resolvedThreadId, limit = pageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_network", retryable = true, traceId = null))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_network")
                    }
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = resolvedBasePayload,
                        page = page,
                        session = session,
                        diagnostics = diagnosticsLogger,
                    )
                )
            }
        var canonicalRequestGeneration: SharedThreadFreshnessGeneration? = null
        suspend fun fetchCanonicalPayloadForCurrentGeneration(): DataResult<RaftThreadScreenPayload> {
            val resolvedThreadId = threadId ?: return fetchCanonicalPayload()
            var extraRound = 0
            while (true) {
                if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                    return staleReplyCacheProvenanceResult()
                }
                val generation =
                    payloadCache.captureGeneration(
                        session.currentServerId.orEmpty(),
                        resolvedThreadId,
                        payloadOwnerScopeId
                    )
                val result = fetchCanonicalPayload()
                if (payloadCache.freshnessLedger.isCurrent(generation)) {
                    canonicalRequestGeneration = generation
                    return result
                }
                if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                    return staleReplyCacheProvenanceResult()
                }
                // Hands 7e198cab: say what moved the generation, so a refresh that never settles
                // names its cause (an Activity summary, a server-wide invalidation, a removal, ...).
                val supersededBy =
                    payloadCache.captureGeneration(
                        session.currentServerId.orEmpty(),
                        resolvedThreadId,
                        payloadOwnerScopeId
                    )
                // artin #110: only live events the page also received moved it (web merges its answer
                // with the socket events in one store): keep this answer, the caller folds them onto it.
                val realtimeMessageIds =
                    if (foldsRealtimeIntoSupersededResult != null) {
                        payloadCache.freshnessLedger.realtimeMessageIdsSince(generation)
                    } else {
                        null
                    }
                if (
                    foldsRealtimeIntoSupersededResult != null &&
                    realtimeMessageIds != null &&
                    foldsRealtimeIntoSupersededResult(realtimeMessageIds)
                ) {
                    diagnosticsLogger(
                        "thread_canonical_superseded_merged threadId=$resolvedThreadId round=${extraRound + 1} " +
                            "cause=${supersededBy.cause}"
                    )
                    return result
                }
                diagnosticsLogger(
                    "thread_canonical_superseded threadId=$resolvedThreadId round=${extraRound + 1} " +
                        "cause=${supersededBy.cause}"
                )
                if (extraRound == 0) {
                    // The content may miss what moved the generation; the access it carries does not.
                    onSupersededCanonicalResult?.invoke(result)
                }
                if (extraRound >= SharedThreadStaleCanonicalRefetchMaxExtraRounds) {
                    // Stop refetching. canonicalRequestGeneration stays null, so this superseded
                    // result is neither cached nor recorded under a generation it does not match, and
                    // the caller is told it is not final (a delete / edit that landed mid-fetch may be
                    // missing from it); the store keeps its visible content and refreshes once more.
                    diagnosticsLogger(
                        "thread_canonical_refetch_capped threadId=$resolvedThreadId rounds=${extraRound + 1}"
                    )
                    onCanonicalRefetchCapped?.invoke()
                    return result
                }
                extraRound++
                delay(SharedThreadStaleCanonicalRefetchBackoffMillis * extraRound)
            }
        }
        val payloadResult =
            if (failedPrewarm && threadId != null) {
                payloadCache.takeOverFailedPrewarm(
                    serverId = session.currentServerId.orEmpty(),
                    threadId = threadId,
                    ownerScopeId = payloadOwnerScopeId,
                    fetch = {
                        if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                            staleReplyCacheProvenanceResult()
                        } else {
                            fetchCanonicalPayload()
                        }
                    }
                )
            } else {
                fetchCanonicalPayloadForCurrentGeneration()
            }
        val payload =
            when (payloadResult) {
                is DataResult.Content -> payloadResult.data
                is DataResult.Empty ->
                    if (resolvedBasePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                        payloadResult.reason == EmptyReason.NoMessages
                    ) {
                        resolvedBasePayload.toEmptyReplyThreadPayload()
                    } else {
                        return payloadResult
                    }
                is DataResult.Error -> return payloadResult
                is DataResult.NoPermission -> return payloadResult
            }
                .let { canonicalPayload ->
                    canonicalPayload.cacheThreadId()?.let { resolvedThreadId ->
                        payloadCache.applyReplyCountFacts(
                            serverId = session.currentServerId.orEmpty(),
                            threadId = resolvedThreadId,
                            environmentId = resolutionScope.provenance.environmentId,
                            principalId = session.user.id,
                            payload = canonicalPayload
                        )
                    } ?: canonicalPayload
                }
        if (!replyAccessResolutionOwnerStillCurrent(resolutionScope)) {
            // The page that asked is no longer current (server switch, token rotation, or account
            // change). The response is still canonical data of the server it was requested from:
            // keep it in that server's timeline cache unless the account/environment changed
            // (Tenny rule 1; [cachePayload] applies the account fence and the generation fence).
            if (persistResult && canonicalRequestGeneration != null) {
                cachePayload(
                    payload = payload,
                    serverId = resolutionScope.provenance.serverId,
                    expectedGeneration = canonicalRequestGeneration,
                    ownerScopeId = payloadOwnerScopeId,
                    expectedProvenance = resolutionScope.provenance
                )
            }
            return staleReplyCacheProvenanceResult()
        }
        val cacheProvenance =
            currentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)
                ?: return staleReplyCacheProvenanceResult()
        if (!cacheReplyThreadResolution(cacheProvenance, payload)) {
            return staleReplyCacheProvenanceResult()
        }
        if (canonicalRequestGeneration != null) {
            payloadCache.recordCanonicalSuccess(canonicalRequestGeneration, payloadOwnerScopeId)
        }
        if (persistResult && canonicalRequestGeneration != null) {
            cachePayload(
                payload = payload,
                serverId = session.currentServerId.orEmpty(),
                expectedGeneration = canonicalRequestGeneration,
                ownerScopeId = payloadOwnerScopeId
            )
        }
        seedThreadAuthorProfiles(session.currentServerId, payload, source = "initial")
        return DataResult.Content(payload)
    }

    suspend fun fetchWebLinkThreadPayload(
        basePayload: RaftThreadScreenPayload,
        target: RaftThreadWebLinkTarget
    ): DataResult<RaftThreadScreenPayload> {
        val messageId = target.messageId?.takeIf { it.isNotBlank() }
        if (messageId == null || conversationRepository == null) {
            return fetchThreadPayload(basePayload)
        }
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val contextResult =
            fetchThreadPayloadFromRepository(
                session = session,
                expectedProvenance = resolutionScope.provenance,
                fallbackCode = "thread_weblink_context",
                call = { repository -> repository.getMessageContext(MessageId(messageId)) },
                transform = { snapshot -> snapshot.toRaftThreadPayload(basePayload, session.user.id) }
            )
        return when (contextResult) {
            is DataResult.Content -> contextResult
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> fetchThreadPayload(basePayload)
        }
    }

    suspend fun fetchFollowedThreadPayload(
        basePayload: RaftThreadScreenPayload,
        onSummaries: (ThreadReplySummariesSnapshot, TimeSource.Monotonic.ValueTimeMark) -> Unit = { _, _ -> },
    ): DataResult<RaftThreadScreenPayload> {
        return fetchFollowedThreadPayloadForContext(
            basePayload = basePayload,
            expectedContext = null,
            onSummaries = onSummaries,
        )
    }

    internal suspend fun fetchFollowedThreadPayloadForContext(
        basePayload: RaftThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext?,
        onSummaries: (ThreadReplySummariesSnapshot, TimeSource.Monotonic.ValueTimeMark) -> Unit = { _, _ -> },
    ): DataResult<RaftThreadScreenPayload> {
        if (expectedContext != null && !isCurrentReplyCacheProvenance(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        if (conversationRepository == null) return DataResult.Content(basePayload)
        if (basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            return DataResult.Content(basePayload)
        }
        val context = expectedContext ?: captureReplyCacheProducerContext()
            ?: return authSessionRequiredResult()
        if (!isCurrentReplyCacheProvenance(context.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val resolvedThreadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        // Only server messages can be thread parents. A local send row (sending, or failed with
        // Retry) carries a `local-send-…` id, and the server refuses the whole request when one id
        // is not a message id (400 `thread_parent_message_ids_invalid`, slock `channels.ts`
        // `parseThreadSummaryParentMessageIds`). A refresh that runs while a send is in flight (the
        // catch-up after a new message backfills the summaries) then got only the followed list,
        // which has no reply previews (artin, #wg-mobile-sync: inline replies gone after a send).
        val parentMessageIds =
            basePayload.messages
                .filterNot { message -> message.hasLocalSendState() }
                .map { MessageId(it.id) }
        // Mark the instant the canonical summaries request is issued, not when
        // the response lands: facts recorded by realtime events that arrive
        // while this request is in flight must stay ahead of its (older)
        // server-side snapshot.
        val summariesRequestedAt = TimeSource.Monotonic.markNow()
        val result = conversationRepository
            .getThreadReplySummaries(
                threadId = ThreadId(resolvedThreadId),
                parentMessageIds = parentMessageIds
            )
        if (!isCurrentReplyCacheProvenance(context.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return when (result) {
            is DataResult.Content -> {
                onSummaries(result.data, summariesRequestedAt)
                if (!isCurrentReplyCacheProvenance(context.provenance)) {
                    staleReplyCacheProvenanceResult()
                } else {
                    // `withReplySummaries` unconditionally overwrites each
                    // message's replyCount with the canonical snapshot. Realtime
                    // reply-count facts recorded after the request was issued
                    // (newer observedAt) must still win, so re-apply the fact
                    // ledger before publishing the payload.
                    val projected = basePayload.withReplySummaries(result.data)
                    DataResult.Content(
                        payloadCache.applyReplyCountFacts(
                            serverId = context.provenance.serverId,
                            threadId = resolvedThreadId,
                            environmentId = context.provenance.environmentId,
                            principalId = context.provenance.principalId,
                            payload = projected
                        )
                    )
                }
            }
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun fetchOlderThreadPayload(
        basePayload: RaftThreadScreenPayload,
        beforeSeq: Long
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "older",
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadOlderMessages(threadId, beforeSeq, pageSize)
            },
            call = { threadId, pageSize ->
                api.channelMessages(threadId, limit = pageSize, beforeSeq = beforeSeq)
            }
        )

    suspend fun fetchNewerThreadPayload(
        basePayload: RaftThreadScreenPayload,
        afterSeq: Long
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "newer",
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadNewerMessages(threadId, afterSeq, pageSize)
            },
            call = { threadId, pageSize ->
                api.channelMessages(threadId, limit = pageSize, afterSeq = afterSeq)
            }
        )

    /**
     * Task #168 step 1: one step of the gap catch-up (disconnect / resume / in-foreground realtime gap).
     *
     * An uncursored `messages/channel/{id}?limit=` latest page: full nodes, the same shape the enter
     * snapshot persists (the `/messages/sync` delta rows lack `randomId` and can never reach the
     * durable V2 cache, and the delta has no completeness signal). [wide] = `false` asks for the thread
     * page size (the cheap common case), `true` for [SharedThreadGapCatchUpPageLimit] (the enter
     * snapshot's page). Whether a page joins the timeline is decided by the caller from an explicit
     * fact (does the page contain the anchor message), never by seq arithmetic.
     */
    suspend fun fetchGapCatchUpLatestPage(
        basePayload: RaftThreadScreenPayload,
        wide: Boolean,
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = if (wide) "gap_catchup_wide" else "gap_catchup",
            pageSizeOverride = SharedThreadGapCatchUpPageLimit.takeIf { wide },
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadLatestMessages(threadId, pageSize)
            },
            call = { threadId, pageSize ->
                api.gapCatchUpChannelMessages(threadId, limit = pageSize)
            }
        )

    suspend fun createOrOpenReplyThread(
        basePayload: RaftThreadScreenPayload,
        parentMessage: RaftThreadMessageItem
    ): DataResult<RaftThreadScreenPayload> {
        val replyResolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val replyResolutionNamespaceId = replyResolutionScope.namespaceId
        val parentChannelId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        cachedReplyThreadStreamId(replyResolutionNamespaceId, parentChannelId, parentMessage.id)?.let { threadChannelId ->
            return loadResolvedReplyThread(
                basePayload = basePayload,
                parentMessage = parentMessage,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessage.id,
                threadChannelId = threadChannelId,
                replyCount = parentMessage.replyCount,
                replyResolutionScope = replyResolutionScope
            )
        }
        val actionResult =
            if (conversationRepository != null) {
                try {
                    conversationRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessage.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true, traceId = null))
                }
            } else {
                val response =
                    try {
                        api.createOrOpenThread(parentChannelId, parentMessage.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true, traceId = null))
                    }
                when (response) {
                    is ThreadApiResult.Success -> DataResult.Content(response.data)
                    is ThreadApiResult.Failure -> return response.toThreadDataError("thread_reply_network")
                }
            }
        val action =
            when (actionResult) {
                is DataResult.Content -> actionResult.data
                is DataResult.Empty -> {
                    return DataResult.Empty(EmptyReason.NoMessages)
                }
                is DataResult.Error -> return actionResult
                is DataResult.NoPermission -> return actionResult
            }
        val threadChannelId = action.threadChannelId.value
        val resolvedParentMessageId = action.parentMessageId?.value ?: parentMessage.id
        if (!cacheReplyThreadResolution(
                replyResolutionScope.provenance,
                parentChannelId,
                resolvedParentMessageId,
                threadChannelId
            )
        ) {
            return DataResult.Error(
                ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
            )
        }
        return loadResolvedReplyThread(
            basePayload = basePayload,
            parentMessage = parentMessage,
            parentChannelId = parentChannelId,
            parentMessageId = resolvedParentMessageId,
            threadChannelId = threadChannelId,
            replyCount = action.replyCount,
            replyResolutionScope = replyResolutionScope
        )
    }

    private suspend fun loadResolvedReplyThread(
        basePayload: RaftThreadScreenPayload,
        parentMessage: RaftThreadMessageItem,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
        replyCount: Int,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<RaftThreadScreenPayload> {
        val parentSurfaceKind =
            basePayload.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: when {
                    basePayload.surfaceKind == RaftThreadSurfaceKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                    basePayload.channelKind.equals("dm", ignoreCase = true) -> RaftThreadSurfaceKind.DirectMessage
                    basePayload.memberScopedMentions && basePayload.parentChannelId.isNullOrBlank() -> RaftThreadSurfaceKind.DirectMessage
                    else -> RaftThreadSurfaceKind.Channel
                }
        val resolvedOrigin =
            parentMessage.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(parentMessage.replyCount, replyCount)
            )
        val replyBase =
            basePayload.copy(
                instanceId = "shared-shell-thread-$threadChannelId",
                title = ThreadReplyPageTitle,
                subtitle = basePayload.replyThreadHeaderSubtitle(),
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = threadChannelId,
                streamChannelId = threadChannelId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                parentSurfaceKind = parentSurfaceKind,
                parentNavigationHint = basePayload.parentNavigationHint,
                navigationLabel = "Close",
                selectedTab = "chat",
                showModeTabs = false,
                showOpenInChannelAction = true,
                focusedMessageId = basePayload.focusedMessageId,
                originMessage = resolvedOrigin,
                originParentMessageId = parentMessageId,
                originStatus = "loaded",
                messages = emptyList(),
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                emptyTitle = ThreadReplyEmptyTitle,
                emptyDetail = ThreadReplyEmptyDetail,
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = parentMessage.id,
                        enabled = basePayload.canSend,
                        mentionChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        streamChannelId = threadChannelId
                    )
            )
        return when (
            val loaded =
                fetchThreadPayloadWithResolutionScope(
                    basePayload = replyBase,
                    persistResult = false,
                    reusePrewarm = false,
                    replyResolutionScope = replyResolutionScope
                )
        ) {
            is DataResult.Content -> {
                val payload =
                    loaded.data.copy(
                        surfaceKind = replyBase.surfaceKind,
                        streamChannelId = replyBase.streamChannelId,
                        parentChannelId = replyBase.parentChannelId,
                        parentMessageId = replyBase.parentMessageId,
                        parentSurfaceKind = replyBase.parentSurfaceKind,
                        focusedMessageId = loaded.data.focusedMessageId ?: replyBase.focusedMessageId,
                        focusedMessageRequestNonce = replyBase.focusedMessageRequestNonce,
                        composer = loaded.data.composer.takeUnless { it.isDefaultThreadComposer() } ?: replyBase.composer,
                        originMessage = loaded.data.originMessage ?: resolvedOrigin,
                        originParentMessageId = loaded.data.originParentMessageId ?: parentMessageId,
                        originStatus = if (loaded.data.originMessage != null) loaded.data.originStatus else "loaded"
                    ).asLoadedEmptyReplyThreadPayload()
                val selected =
                    cacheCanonicalPayload(
                        payload,
                        replyResolutionScope.provenance.threadPayloadOwnerScopeId
                    )
                DataResult.Content(selected)
            }
            is DataResult.Empty -> {
                val payload = replyBase.toEmptyReplyThreadPayload()
                val selected =
                    cacheCanonicalPayload(
                        payload,
                        replyResolutionScope.provenance.threadPayloadOwnerScopeId
                    )
                DataResult.Content(selected)
            }
            is DataResult.Error -> loaded
            is DataResult.NoPermission -> loaded
        }
    }

    suspend fun openMessageContext(
        messageId: String,
        basePayload: RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> {
        val trimmedMessageId = messageId.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        return fetchThreadPayloadFromRepository(
            session = session,
            expectedProvenance = resolutionScope.provenance,
            fallbackCode = "thread_parent_context_network",
            call = { repository -> repository.getMessageContext(MessageId(trimmedMessageId)) },
            transform = { snapshot ->
                snapshot.toRaftThreadPayload(basePayload, session.user.id)
                    .copy(showOpenInChannelAction = false)
            }
        )
    }

    suspend fun openCommentHost(
        hostMessageId: String,
        basePayload: RaftThreadScreenPayload
    ): DataResult<RaftThreadScreenPayload> {
        val streamThreadId = basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        if (basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && streamThreadId != null) {
            return fetchThreadPayload(basePayload, persistResult = false)
        }
        return openMessageContext(hostMessageId, basePayload)
    }
}
