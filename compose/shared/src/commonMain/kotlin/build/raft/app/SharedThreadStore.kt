package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.toConversationSummary
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.CreateAgentBody
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.buildCreateAgentBody
import build.raft.app.data.buildExecuteActionBody
import build.raft.app.data.buildMarkAgentActionExecutedBody
import build.raft.app.data.buildMarkChannelActionExecutedBody
import build.raft.app.data.buildMarkChannelMembersActionExecutedBody
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.ChannelMemberActionResult
import build.raft.app.data.ConversationRepository
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ReadReceiptResult
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.MemberRepository
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MessageReactionSummary
import build.raft.app.data.MviStaleToken
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockIntegrationsActionsPushApiClient
import build.raft.app.data.SavedStatusRepository
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SlockThreadApi
import build.raft.app.data.SlockThreadApiClient
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.ThreadCandidateProofGate
import build.raft.app.data.ThreadIdentityProof
import build.raft.app.data.ThreadRouteId
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.data.map
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadRouteId
import build.raft.app.data.withExecutedAgent
import build.raft.app.data.withExecutedChannel
import build.raft.app.data.withExecutedChannelMembers
import build.raft.app.data.realtime.RealtimeVisibleStreamGapPolicy
import build.raft.app.data.realtime.RealtimeVisibleStreamGapRequest
import build.raft.app.model.AgentStatus
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageCommentHostSource
import build.raft.app.model.MessageCommentRef
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageType
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.formatSlockTimestampLabel
import build.raft.app.model.messageStreamChannelId
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.model.safeDisplayNameOrNull
import build.raft.app.model.threadIdentity
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private data class ReplyThreadResolveKey(
    val parentChannelId: String,
    val parentMessageId: String
)

private data class SharedThreadPayloadCacheKey(
    val serverId: String,
    val threadId: String
)

private const val SharedThreadPrewarmMaxConcurrency = 2
private const val SharedThreadPrewarmStartIntervalMillis = 100L
private const val SharedThreadZeroReplyOriginHydrationGraceMillis = 300L

private data class PendingReactionRealtimeGuard(
    val messageId: String,
    val emoji: String,
    val selected: Boolean
)

internal data class SharedThreadMessageKey(
    val channelId: ChannelId,
    val messageId: MessageId
)

internal class SharedThreadMessageStore(
    private val limit: Int = 256
) {
    private val messages = LinkedHashMap<SharedThreadMessageKey, Message>()
    private val replyThreadStreams = LinkedHashMap<ReplyThreadResolveKey, String>()
    private val inFlight = mutableMapOf<SharedThreadMessageKey, Deferred<Message?>>()
    private val inFlightMutex = Mutex()

    fun get(channelId: ChannelId, messageId: MessageId): Message? =
        messages[SharedThreadMessageKey(channelId, messageId)]

    fun upsert(channelId: ChannelId, message: Message): Message {
        val key = SharedThreadMessageKey(channelId, message.id)
        val existing = messages[key]
        val selected =
            if (existing != null && !existing.needsSharedThreadMessageStoreHydration() && message.needsSharedThreadMessageStoreHydration()) {
                existing
            } else {
                message
            }
        messages.remove(key)
        messages[key] = selected
        trimToLimit()
        return selected
    }

    fun getReplyThreadStream(parentChannelId: String, parentMessageId: String): String? =
        replyThreadStreams[ReplyThreadResolveKey(parentChannelId, parentMessageId)]

    fun upsertReplyThreadStream(parentChannelId: String, parentMessageId: String, threadChannelId: String) {
        if (parentChannelId.isBlank() || parentMessageId.isBlank() || threadChannelId.isBlank()) return
        val key = ReplyThreadResolveKey(parentChannelId, parentMessageId)
        replyThreadStreams.remove(key)
        replyThreadStreams[key] = threadChannelId
        while (replyThreadStreams.size > limit) {
            val firstKey = replyThreadStreams.keys.firstOrNull() ?: return
            replyThreadStreams.remove(firstKey)
        }
    }

    suspend fun getOrFetch(
        channelId: ChannelId,
        messageId: MessageId,
        fetch: suspend () -> Message?
    ): Message? = coroutineScope {
        get(channelId, messageId)
            ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
            ?.let { message -> return@coroutineScope message }
        val key = SharedThreadMessageKey(channelId, messageId)
        val deferred =
            inFlightMutex.withLock {
                get(channelId, messageId)
                    ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
                    ?.let { message -> return@coroutineScope message }
                inFlight[key] ?: async {
                    fetch()
                        ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
                        ?.let { message -> upsert(channelId, message) }
                }.also { inFlight[key] = it }
            }
        try {
            deferred.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlight[key] === deferred) {
                    inFlight.remove(key)
                }
            }
        }
    }

    internal fun clearForTest() {
        messages.clear()
        replyThreadStreams.clear()
        inFlight.clear()
    }

    private fun trimToLimit() {
        while (messages.size > limit) {
            val firstKey = messages.keys.firstOrNull() ?: return
            messages.remove(firstKey)
        }
    }
}

/**
 * App-scope first-frame cache shared by Activity prewarm and visible Thread stores.
 *
 * Route payloads remain identity/intent-only. Background prewarm writes the same
 * canonical payload candidate that [SharedThreadStore] reads on start, so opening
 * a warmed reply thread does not need a second route-owned content model.
 */
internal class SharedThreadPayloadStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    maxConcurrentPrewarms: Int = SharedThreadPrewarmMaxConcurrency,
    private val prewarmStartIntervalMillis: Long = SharedThreadPrewarmStartIntervalMillis
) {
    private val payloads = MutableStateFlow<Map<SharedThreadPayloadCacheKey, KuiklyThreadScreenPayload>>(emptyMap())
    private val prewarmMutex = Mutex()
    private val prewarmPool = Semaphore(maxConcurrentPrewarms.coerceAtLeast(1))
    private val prewarmStartMutex = Mutex()
    private val prewarmInFlight = mutableMapOf<SharedThreadPayloadCacheKey, Deferred<DataResult<KuiklyThreadScreenPayload>>>()
    private val prewarmReady = mutableSetOf<SharedThreadPayloadCacheKey>()
    private var lastPrewarmStartedAt: TimeMark? = null

    fun get(serverId: String, threadId: String): KuiklyThreadScreenPayload? =
        payloads.value[SharedThreadPayloadCacheKey(serverId, threadId)]
            ?: serverId.takeIf { it.isNotBlank() }
                ?.let { payloads.value[SharedThreadPayloadCacheKey("", threadId)] }

    fun put(serverId: String, threadId: String, payload: KuiklyThreadScreenPayload) {
        payloads.update { current -> current + (SharedThreadPayloadCacheKey(serverId, threadId) to payload) }
    }

    fun remove(serverId: String, threadId: String) {
        val key = SharedThreadPayloadCacheKey(serverId, threadId)
        val legacyKey = SharedThreadPayloadCacheKey("", threadId)
        payloads.update { current ->
            current - key - legacyKey
        }
    }

    fun values(serverId: String): Collection<KuiklyThreadScreenPayload> =
        payloads.value.filterKeys { key -> key.serverId == serverId || key.serverId.isBlank() }.values

    suspend fun prewarm(
        serverId: String,
        threadId: String,
        fetch: suspend () -> DataResult<KuiklyThreadScreenPayload>
    ): DataResult<KuiklyThreadScreenPayload> = coroutineScope {
        val key = SharedThreadPayloadCacheKey(serverId, threadId)
        val cached =
            prewarmMutex.withLock {
                if (key in prewarmReady) payloads.value[key] else null
            }
        if (cached != null) return@coroutineScope DataResult.Content(cached)

        var created: Deferred<DataResult<KuiklyThreadScreenPayload>>? = null
        val deferred =
            prewarmMutex.withLock {
                prewarmInFlight[key]
                    ?: scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val result =
                                prewarmPool.withPermit {
                                    awaitPrewarmStartRateLimit()
                                    fetch()
                                }
                            if (result is DataResult.Content) {
                                prewarmMutex.withLock { prewarmReady += key }
                            }
                            result
                        } finally {
                            val currentJob = currentCoroutineContext()[Job]
                            prewarmMutex.withLock {
                                if (prewarmInFlight[key] === currentJob) {
                                    prewarmInFlight.remove(key)
                                }
                            }
                        }
                    }.also { candidate ->
                        prewarmInFlight[key] = candidate
                        created = candidate
                    }
            }
        created?.start()
        deferred.await()
    }

    suspend fun joinOrConsumePrewarm(serverId: String, threadId: String): DataResult<KuiklyThreadScreenPayload>? {
        val key = SharedThreadPayloadCacheKey(serverId, threadId)
        val lookup =
            prewarmMutex.withLock {
                val cached =
                    if (prewarmReady.remove(key)) {
                        payloads.value[key]
                    } else {
                        null
                    }
                SharedThreadPrewarmLookup(cached = cached, inFlight = prewarmInFlight[key])
            }
        lookup.cached?.let { return DataResult.Content(it) }
        return lookup.inFlight?.await()
    }

    private suspend fun awaitPrewarmStartRateLimit() {
        if (prewarmStartIntervalMillis <= 0L) return
        prewarmStartMutex.withLock {
            val elapsedMillis = lastPrewarmStartedAt?.elapsedNow()?.inWholeMilliseconds
            val remainingMillis =
                elapsedMillis
                    ?.let { elapsed -> (prewarmStartIntervalMillis - elapsed).coerceAtLeast(0L) }
                    ?: 0L
            if (remainingMillis > 0L) {
                delay(remainingMillis)
            }
            lastPrewarmStartedAt = TimeSource.Monotonic.markNow()
        }
    }

    internal fun clearForTest() {
        prewarmInFlight.values.forEach { it.cancel() }
        prewarmInFlight.clear()
        prewarmReady.clear()
        lastPrewarmStartedAt = null
        payloads.value = emptyMap()
    }
}

private data class SharedThreadPrewarmLookup(
    val cached: KuiklyThreadScreenPayload?,
    val inFlight: Deferred<DataResult<KuiklyThreadScreenPayload>>?
)

private fun Message.needsSharedThreadMessageStoreHydration(): Boolean =
    body.isBlank() && attachments.isEmpty()

private fun Message.matchesSharedThreadMessageStoreKey(
    channelId: ChannelId,
    messageId: MessageId
): Boolean =
    id == messageId &&
        !needsSharedThreadMessageStoreHydration() &&
        (this.channelId == channelId || threadId == ThreadId(channelId.value))

internal data class MarkdownThreadRefTarget(
    val parentMessageId: String,
    val focusedMessageId: String? = null,
    val threadChannelId: String? = null
)

internal class SharedThreadDataSource(
    private val api: SlockThreadApi,
    private val sessionStore: SlockAuthSessionStore,
    private val selectedServerStore: SelectedServerStore? = null,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val conversationRepository: ConversationRepository? = null,
    private val channelRepository: ChannelRepository? = null,
    private val messageStore: SharedThreadMessageStore? = null,
    payloadStore: SharedThreadPayloadStore? = null,
    private val agentsApiClient: SlockAgentsApiClient? = null,
    private val actionCardsApiClient: SlockIntegrationsActionsPushApiClient? = null,
    private val savedStatusRepository: SavedStatusRepository? = null,
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
    /**
     * Task #43: shared create-agent coordinator used by the action-card
     * path. Mirrors the field on `SharedMembersDataSource`; the actual
     * body construction + API call lives in
     * [build.raft.app.data.SharedCreateAgentCoordinator] so the
     * Members page and Thread action card share the same code path.
     */
    private val createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator? = null
) {
    /**
     * Public accessor for the [build.raft.app.data.SharedCreateAgentCoordinator]
     * field. Used by callers (e.g. `SettingsHomePage` which constructs
     * `SharedThreadDataSource` and forwards to `SharedThreadRouteContent`)
     * that need the coordinator to wire up the action-card create-agent
     * dialog. Returns null when the data source was constructed without
     * a coordinator; in that case the create-agent action card falls back
     * to the legacy name/description/machineId-only path.
     */
    val createAgentCoordinatorOrNull: build.raft.app.data.SharedCreateAgentCoordinator?
        get() = createAgentCoordinator

    constructor(
        networkApi: SharedThreadNetworkApi,
        sessionStore: SlockAuthSessionStore,
        selectedServerStore: SelectedServerStore? = null,
        memberRepository: MemberRepository? = null,
        serverMembersStore: SharedServerMembersStore? = null,
        conversationRepository: ConversationRepository? = null,
        channelRepository: ChannelRepository? = null,
        messageStore: SharedThreadMessageStore? = null,
        payloadStore: SharedThreadPayloadStore? = null,
        agentsApiClient: SlockAgentsApiClient? = null,
        actionCardsApiClient: SlockIntegrationsActionsPushApiClient? = null,
        savedStatusRepository: SavedStatusRepository? = null,
        createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator? = null,
        diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger
    ) : this(
        api = SlockThreadApiClient(networkApi, diagnosticsLogger),
        sessionStore = sessionStore,
        selectedServerStore = selectedServerStore,
        memberRepository = memberRepository,
        serverMembersStore = serverMembersStore,
        conversationRepository = conversationRepository,
        channelRepository = channelRepository,
        messageStore = messageStore,
        payloadStore = payloadStore,
        agentsApiClient = agentsApiClient,
        actionCardsApiClient = actionCardsApiClient,
        savedStatusRepository = savedStatusRepository,
        diagnosticsLogger = diagnosticsLogger,
        createAgentCoordinator = createAgentCoordinator
    )

    private val payloadCache = payloadStore ?: SharedThreadPayloadStore()
    private val messageResolver = messageStore ?: SharedThreadMessageStore()
    private val replyThreadResolveCache = mutableMapOf<ReplyThreadResolveKey, String>()
    private val cacheServerId = MutableStateFlow("")

    suspend fun readSession(): StoredAuthSession? =
        sessionStore.readSession()?.also { session ->
            cacheServerId.value = session.currentServerId.orEmpty()
        }

    fun cachePayload(
        payload: KuiklyThreadScreenPayload,
        serverId: String = cacheServerId.value
    ) {
        val threadId = payload.resolveThreadRouteIdentity().timelineChannelId ?: return
        val cacheablePayload =
            payload
                .withoutTransientLocalSendingMessages()
                .withOriginReplyCountAtLeastLoadedReplies()
                .asLoadedEmptyReplyThreadPayload()
        if (cacheablePayload.hasRenderableThreadContent()) {
            payloadCache.put(
                serverId,
                threadId,
                cacheablePayload.copy(
                    status = if (cacheablePayload.isLoadedEmptyReplyThread()) "empty" else "content",
                    backgroundRefreshing = false,
                    errorCode = null
                )
            )
        } else {
            payloadCache.remove(serverId, threadId)
        }
    }

    fun cachedPayload(threadId: String): KuiklyThreadScreenPayload? =
        payloadCache.get(cacheServerId.value, threadId)

    // Reserved for UNRECOVERABLE scenarios only (task #562 boundary):
    // permission/visibility loss, epoch/server switches, identity proof
    // failures. Ordinary realtime traffic warms the cache with the live
    // store's merged snapshot instead (see applyRealtimeEvent). Narrow by
    // design: removes exactly one thread's candidate; never touches durable
    // stores, the message resolver, or the active screen's in-memory state.
    fun invalidateCachedPayload(payload: KuiklyThreadScreenPayload) {
        val threadId = payload.resolveThreadRouteIdentity().timelineChannelId ?: return
        payloadCache.remove(cacheServerId.value, threadId)
    }

    suspend fun withCachedReplyParentCandidate(basePayload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        if (basePayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return basePayload
        if (basePayload.originMessage != null || basePayload.originPreview?.isNotBlank() == true) return basePayload
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return basePayload
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return basePayload
        val parent =
            getCachedReplyParentMessage(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return basePayload
        val currentUserId = readSession()?.user?.id
        return basePayload.copy(
            originMessage = parent.toKuiklyThreadMessageItem(currentUserId),
            originParentMessageId = parentMessageId,
            originStatus = "loading",
            originErrorCode = null
        )
    }

    fun rememberReplyParentCandidate(
        parentChannelId: String?,
        parentMessage: KuiklyThreadMessageItem,
        threadChannelId: String?
    ) {
        val channelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return
        if (parentMessage.id.isBlank()) return
        threadChannelId?.takeIf { it.isNotBlank() }?.let { resolvedThreadChannelId ->
            cacheReplyThreadResolution(channelId.value, parentMessage.id, resolvedThreadChannelId)
        }
        if (parentMessage.needsReplyOriginContextHydration()) return
        val projectedParent =
            parentMessage.toMessage(
                    threadId = ThreadId(channelId.value),
                    channelId = channelId,
                    diagnostics = diagnosticsLogger,
                ) ?: return
        messageResolver.upsert(channelId = channelId, message = projectedParent)
        diagnosticsLogger(
            "thread_parent_context_store_seed parentChannelId=${channelId.value} " +
                "parentMessageId=${parentMessage.id} threadChannelId=${threadChannelId.orEmpty()}"
        )
    }

    suspend fun withConversationAccessFallback(basePayload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val session = readSession() ?: return basePayload
        return basePayload.withConversationAccessFallback(resolveConversationAccessFallback(basePayload, session))
    }

    suspend fun withKnownMarkdownChannelNames(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val session = readSession() ?: return payload
        return payload.withKnownMarkdownChannelNames(session)
    }

    suspend fun homeConversationContextResolution(
        payload: KuiklyThreadScreenPayload
    ): ThreadHomeContextResolution {
        val session = readSession() ?: return ThreadHomeContextResolution(payload = payload)
        val home = currentHomeSnapshot(session) ?: return ThreadHomeContextResolution(payload = payload)
        val accessFallback = resolveConversationAccessFallback(payload, home)
        return ThreadHomeContextResolution(
            payload =
                payload
                    .withConversationAccessFallback(accessFallback)
                    .withKnownMarkdownChannelNames(home),
            accessCandidate = accessFallback
        )
    }

    suspend fun withHomeConversationContext(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
        homeConversationContextResolution(payload).payload

    private fun cachedReplyThreadStreamId(parentChannelId: String, parentMessageId: String): String? =
        replyThreadResolveCache[ReplyThreadResolveKey(parentChannelId, parentMessageId)]
            ?: messageStore?.getReplyThreadStream(parentChannelId, parentMessageId)

    private fun KuiklyThreadScreenPayload.matchesCachedReplyThreadStreamResolution(): Boolean {
        val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
        val parentMessage =
            parentMessageId?.takeIf { it.isNotBlank() }
                ?: originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return false
        val routeStream =
            streamChannelId?.takeIf { it.isNotBlank() }
                ?: sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return false
        return cachedReplyThreadStreamId(parentChannel, parentMessage) == routeStream
    }

    private fun cachedMarkdownThreadRefTarget(parentChannelId: String, threadShortId: String): MarkdownThreadRefTarget? {
        val shortId = threadShortId.trim().lowercase()
        if (parentChannelId.isBlank() || shortId.isBlank()) return null
        val matches =
            payloadCache.values(cacheServerId.value)
                .mapNotNull { payload ->
                    val parentMessageId =
                        payload.parentMessageId?.takeIf { it.isNotBlank() }
                            ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                    if (
                        payload.parentChannelId != parentChannelId ||
                        !parentMessageId.lowercase().startsWith(shortId)
                    ) {
                        return@mapNotNull null
                    }
                    MarkdownThreadRefTarget(
                        parentMessageId = parentMessageId,
                        threadChannelId =
                            payload.streamChannelId?.takeIf { it.isNotBlank() }
                                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                    )
                }
                .distinctBy { it.parentMessageId }
        return matches.singleOrNull()
    }

    private fun cacheReplyThreadResolution(payload: KuiklyThreadScreenPayload) {
        val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return
        val threadChannelId =
            payload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return
        cacheReplyThreadResolution(parentChannelId, parentMessageId, threadChannelId)
    }

    private fun cacheReplyThreadResolution(
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ) {
        if (parentChannelId.isBlank() || parentMessageId.isBlank() || threadChannelId.isBlank()) return
        if (parentChannelId == threadChannelId) return
        replyThreadResolveCache[ReplyThreadResolveKey(parentChannelId, parentMessageId)] = threadChannelId
        messageStore?.upsertReplyThreadStream(parentChannelId, parentMessageId, threadChannelId)
    }

    suspend fun joinChannel(channelId: ChannelId) =
        channelRepository?.joinChannel(channelId)
            ?: DataResult.Error(ScreenError("channel_repository_unavailable", retryable = false))

    suspend fun cacheCanonicalPayload(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        cachePayload(payload)
        return payload
    }

    suspend fun cacheLocalSendFailure(
        basePayload: KuiklyThreadScreenPayload,
        localMessage: KuiklyThreadMessageItem,
        errorCode: String?,
        errorMessage: String?
    ): KuiklyThreadScreenPayload {
        val failedPayload =
            basePayload
                .withLocalSendStarted(localMessage)
                .withLocalSendFailure(localMessage.id, errorCode, errorMessage)
        cachePayload(failedPayload)
        return failedPayload
    }

    suspend fun cacheLocalSendSuccess(
        basePayload: KuiklyThreadScreenPayload,
        localMessage: KuiklyThreadMessageItem,
        sentPage: ThreadMessagesPage
    ): KuiklyThreadScreenPayload {
        if (sentPage.messages.isEmpty()) return basePayload
        val pendingPayload = basePayload.withLocalSendStarted(localMessage)
        val appended =
            appendSharedThreadSentMessageFromPage(
                basePayload = pendingPayload,
                page = sentPage,
                session = readSession(),
                trustedReplyThreadChannelId = localMessage.pendingSendContext?.streamChannelId,
                diagnostics = diagnosticsLogger,
            )
        val replaced =
            appended
                .withoutLocalSendMessage(localMessage.id)
                .preserveStableTimelineFrom(pendingPayload)
        return cacheCanonicalPayload(replaced)
    }

    suspend fun commitAgentCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        return commitAgentCreateActionCardInternal(messageId, card)
    }

    /**
     * Task #43: action-card create-agent path that goes through the shared
     * [SharedCreateAgentCoordinator]. The dialog collects a full
     * [SharedCreateAgentDialogSubmit] (name, description, machineId,
     * runtimeConfig, external); the coordinator owns the body construction
     * and the API call. The action card is just the metadata that gets
     * marked executed on success.
     *
     * Replaces the legacy `commitAgentCreateActionCard(messageId, card)`
     * which only used name/description/machineId from the action card.
     */
    suspend fun commitAgentCreateActionCardFromDialog(
        messageId: String,
        card: ThreadActionCard,
        submit: SharedCreateAgentDialogSubmit
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val coordinator = createAgentCoordinator
            ?: return DataResult.Error(ScreenError(code = "action_card_coordinator_missing", retryable = false))

        val created = coordinator.createAgent(submit.toCreateAgentRequest())
        return when (created) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: submit.name
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    suspend fun commitThreadActionCard(
        payload: KuiklyThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        return when {
            card.type.equals("agent:create", ignoreCase = true) ->
                DataResult.Error(ScreenError(code = "action_card_agent_create_dialog_required", retryable = false))
            card.type.equals("channel:create", ignoreCase = true) ->
                commitChannelCreateActionCard(messageId, card)
            card.type.equals("channel:add_member", ignoreCase = true) ->
                commitChannelAddMembersActionCard(payload, messageId, card)
            card.type.equals("integration:approve_agent_login", ignoreCase = true) ->
                executeInlineThreadActionCard(messageId, card)
            else ->
                DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
    }

    private suspend fun executeInlineThreadActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (val result = actionsApi.executeAction(MessageId(messageId), buildExecuteActionBody(expectedState = "prepared"))) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.actionCard
                        ?: card.copy(
                            state = "executed",
                            resultKind = "agent-integration-login",
                            resultId = card.agentId,
                            resultName = card.clientName,
                            executedByUserName = card.executedByUserName
                        )
                )
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_execute_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun commitAgentCreateActionCardInternal(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val agentName = card.name.trim()
        if (agentName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_agent_name_missing", retryable = false))
        }
        val agentsApi = agentsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_agents_api_missing", retryable = false))
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))

        return when (
            val created =
                agentsApi.createAgent(
                    buildCreateAgentBody(
                        CreateAgentBody(
                            name = agentName.removePrefix("@"),
                            description = card.description?.takeIf { it.isNotBlank() },
                            machineId = card.requiredComputer?.takeIf { it.isNotBlank() }
                                ?: card.suggestedComputer?.takeIf { it.isNotBlank() }
                        )
                    )
                )
        ) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: agentName.removePrefix("@")
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val channelName = card.name.trim()
        if (channelName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_channel_name_missing", retryable = false))
        }
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (
            val created =
                repository.createChannel(
                    CreateChannelRequest(
                        name = channelName,
                        description = card.description?.trim()?.takeIf { it.isNotBlank() },
                        visibility = card.visibility?.trim()?.takeIf { it.isNotBlank() } ?: "public",
                        agentMemberIds = card.initialAgents.cleanActionCardIds(),
                        userMemberIds = card.initialHumans.cleanActionCardIds()
                    )
                )
        ) {
            is DataResult.Content -> {
                val channel = created.data.channel
                val createdChannelId = channel.channelId?.value ?: channel.threadId.value
                val createdChannelName = channel.name.takeIf { it.isNotBlank() } ?: channelName
                val executedCard = card.withExecutedChannel(id = createdChannelId, name = createdChannelName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body =
                        buildMarkChannelActionExecutedBody(
                            channelId = createdChannelId,
                            channelName = createdChannelName
                        ),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "channel_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelAddMembersActionCard(
        payload: KuiklyThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:add_member", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val targetChannelId =
            card.channel?.trim()?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_missing", retryable = false))
        val humanIds = card.humans.cleanActionCardIds()
        val agentIds = card.agents.cleanActionCardIds()
        if (humanIds.isEmpty() && agentIds.isEmpty()) {
            return DataResult.Error(ScreenError(code = "action_card_members_missing", retryable = false))
        }
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_member_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val channelId = ChannelId(targetChannelId)
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val addedHumans = mutableListOf<String>()
        val addedAgents = mutableListOf<String>()
        var firstFailure: DataResult<ChannelMemberActionResult>? = null

        for (humanId in humanIds) {
            val request = AddChannelMemberRequest(channelId = channelId, memberId = MemberId(humanId), kind = MemberKind.Human)
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.addChannelMember(serverId, request, DataRequestPriority.Foreground)
                } else {
                    repository.addChannelMember(DataRequestPriority.Foreground, request)
                }
            if (result is DataResult.Content) {
                addedHumans += humanId
            } else if (firstFailure == null) {
                firstFailure = result
            }
        }
        for (agentId in agentIds) {
            val request = AddChannelMemberRequest(channelId = channelId, memberId = MemberId(agentId), kind = MemberKind.Agent)
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.addChannelMember(serverId, request, DataRequestPriority.Foreground)
                } else {
                    repository.addChannelMember(DataRequestPriority.Foreground, request)
                }
            if (result is DataResult.Content) {
                addedAgents += agentId
            } else if (firstFailure == null) {
                firstFailure = result
            }
        }
        if (addedHumans.isEmpty() && addedAgents.isEmpty()) {
            return when (val failure = firstFailure) {
                is DataResult.Empty -> DataResult.Empty(failure.reason)
                is DataResult.Error -> failure
                is DataResult.NoPermission -> failure
                else -> DataResult.Error(ScreenError(code = "channel_members_add_failed", retryable = true))
            }
        }
        val channelName =
            payload.title.takeIf { targetChannelId == payload.sourceThreadId && it.isNotBlank() }
                ?: card.resultChannelName?.takeIf { it.isNotBlank() }
                ?: targetChannelId
        val executedCard =
            card.withExecutedChannelMembers(
                channelId = targetChannelId,
                channelName = channelName,
                addedHumanIds = addedHumans,
                addedAgentIds = addedAgents
            )
        return markThreadActionCardExecuted(
            actionsApi = actionsApi,
            messageId = messageId,
            body =
                buildMarkChannelMembersActionExecutedBody(
                    channelId = targetChannelId,
                    channelName = channelName,
                    addedHumanIds = addedHumans,
                    addedAgentIds = addedAgents
                ),
            executedCard = executedCard
        )
    }

    private suspend fun markThreadActionCardExecuted(
        actionsApi: SlockIntegrationsActionsPushApiClient,
        messageId: String,
        body: String,
        executedCard: ThreadActionCard
    ): DataResult<ThreadActionCard> =
        when (val result = actionsApi.markActionExecuted(MessageId(messageId), body)) {
            is DataResult.Content -> DataResult.Content(executedCard)
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_mark_executed_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }

    suspend fun fetchThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false
    ): DataResult<KuiklyThreadScreenPayload> {
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val shouldFetchReplyByParent =
            conversationRepository != null &&
                basePayload.shouldFetchReplyThreadByParentPair() &&
                !basePayload.matchesCachedReplyThreadStreamResolution()
        val resolvedBasePayload =
            if (shouldFetchReplyByParent) {
                basePayload
            } else {
                when (val resolved = resolveReplyThreadStream(basePayload)) {
                    is DataResult.Content -> resolved.data
                    is DataResult.Empty -> return resolved
                    is DataResult.Error -> return resolved
                    is DataResult.NoPermission -> return resolved
                }
            }
        val threadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
        if (reusePrewarm && threadId != null) {
            when (val warmed = payloadCache.joinOrConsumePrewarm(session.currentServerId.orEmpty(), threadId)) {
                is DataResult.Content -> {
                    cacheReplyThreadResolution(warmed.data)
                    return warmed
                }
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission,
                null -> Unit
            }
        }
        val payloadResult =
            if (conversationRepository != null) {
                if (shouldFetchReplyByParent) {
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload
                    )
                } else {
                    val resolvedThreadId =
                        threadId
                            ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
                    fetchInitialThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload,
                        threadId = ThreadId(resolvedThreadId)
                    )
                }
            } else {
                val resolvedThreadId =
                    threadId
                        ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
                val response =
                    try {
                        api.channelMessages(resolvedThreadId, limit = SharedThreadPaging.initialPageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_network", retryable = true))
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
        val payload =
            when (payloadResult) {
                is DataResult.Content -> payloadResult.data
                is DataResult.Empty ->
                    if (resolvedBasePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                        payloadResult.reason == EmptyReason.NoMessages
                    ) {
                        resolvedBasePayload.toEmptyReplyThreadPayload()
                    } else {
                        return payloadResult
                    }
                is DataResult.Error -> return payloadResult
                is DataResult.NoPermission -> return payloadResult
            }
        cacheReplyThreadResolution(payload)
        if (persistResult) {
            cachePayload(payload)
        }
        return DataResult.Content(payload)
    }

    suspend fun prewarmReplyThread(
        marker: ActivityReplyThreadPrewarmMarker
    ): DataResult<Unit> {
        val key = marker.key
        if (
            key.serverId.isBlank() ||
            key.parentChannelId.isBlank() ||
            key.parentMessageId.isBlank() ||
            key.threadChannelId.isBlank()
        ) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_identity_missing", retryable = false))
        }
        val session = readSession() ?: return authSessionRequiredResult()
        val selection = selectedServerStore
            ?: return DataResult.Error(ScreenError(code = "thread_prewarm_selection_unavailable", retryable = false))
        if (!selection.isSelected(key.serverId)) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_server_changed", retryable = false))
        }
        val basePayload =
            KuiklyThreadScreenPayload(
                status = "loading",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                channelKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = key.threadChannelId,
                streamChannelId = key.threadChannelId,
                parentChannelId = key.parentChannelId,
                parentMessageId = key.parentMessageId,
                originParentMessageId = key.parentMessageId
            )
        val result =
            payloadCache.prewarm(key.serverId, key.threadChannelId) {
                val fetched =
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = basePayload,
                        requestPriority = DataRequestPriority.Background
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
                cacheReplyThreadResolution(payload)
                cachePayload(payload, key.serverId)
                DataResult.Content(payload)
            }
        return when (result) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun resolveReplyThreadStream(
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository = conversationRepository ?: return DataResult.Content(basePayload)
        if (!basePayload.needsReplyThreadStreamResolution()) {
            return DataResult.Content(basePayload)
        }
        return createOrOpenReplyThreadStream(basePayload, repository)
    }

    private suspend fun createOrOpenReplyThreadStream(
        basePayload: KuiklyThreadScreenPayload,
        repository: ConversationRepository? = conversationRepository
    ): DataResult<KuiklyThreadScreenPayload> {
        val resolvedRepository =
            repository
                ?: return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        cachedReplyThreadStreamId(parentChannelId, parentMessageId)?.let { threadChannelId ->
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
                return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
            }
        return when (actionResult) {
            is DataResult.Content -> {
                cacheReplyThreadResolution(parentChannelId, parentMessageId, actionResult.data.threadChannelId.value)
                DataResult.Content(basePayload.withResolvedReplyThreadAction(actionResult.data))
            }
            is DataResult.Empty -> actionResult
            is DataResult.Error -> actionResult
            is DataResult.NoPermission -> actionResult
        }
    }

    suspend fun fetchWebLinkThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        target: SlockThreadWebLinkTarget
    ): DataResult<KuiklyThreadScreenPayload> {
        val messageId = target.messageId?.takeIf { it.isNotBlank() }
        if (messageId == null || conversationRepository == null) {
            return fetchThreadPayload(basePayload)
        }
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val contextResult =
            fetchThreadPayloadFromRepository(
                session = session,
                fallbackCode = "thread_weblink_context",
                call = { repository -> repository.getMessageContext(MessageId(messageId)) },
                transform = { snapshot -> snapshot.toKuiklyThreadPayload(basePayload, session.user.id) }
            )
        return when (contextResult) {
            is DataResult.Content -> contextResult
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> fetchThreadPayload(basePayload)
        }
    }

    suspend fun enrichSavedStatus(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val session = readSession() ?: return payload
        return payload.withSavedStatus(session)
    }

    suspend fun fetchFollowedThreadPayload(
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        if (conversationRepository == null) return DataResult.Content(basePayload)
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val result =
            if (basePayload.shouldResolveReplyThreadByParentPair()) {
                fetchInitialReplyThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    includeFollowedSummaries = true
                )
            } else {
                val resolvedThreadId =
                    basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                        ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                        ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
                fetchInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    threadId = ThreadId(resolvedThreadId),
                    includeFollowedSummaries = true
                )
            }
        return result
    }

    /**
     * Outcome of an attempt to enrich the current thread payload with
     * fresh member / candidate data. The tri-state lets the caller
     * distinguish a real "no change" success from a transient failure
     * (network / repository exception, NoPermission, Error) so that
     * idempotent LoadMembers can decide whether to enter Loaded or
     * Error.
     */
    internal sealed class MembersEnrichOutcome {
        data class Updated(val payload: KuiklyThreadScreenPayload) : MembersEnrichOutcome()
        data object Unchanged : MembersEnrichOutcome()
        data class Failed(val reason: String) : MembersEnrichOutcome()
    }

    suspend fun enrichThreadMembers(payload: KuiklyThreadScreenPayload): MembersEnrichOutcome {
        val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return MembersEnrichOutcome.Unchanged
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val store = serverMembersStore
        val repository = memberRepository
        val channelMembers =
            try {
                if (store != null && serverId != null) {
                    store.loadChannelMembers(serverId, ChannelId(threadId), DataRequestPriority.Visible)
                } else {
                    repository?.getChannelMembers(DataRequestPriority.Visible, ChannelId(threadId))
                        ?: return MembersEnrichOutcome.Unchanged
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                return MembersEnrichOutcome.Failed("getChannelMembers threw: ${t::class.simpleName}")
            }
        if (channelMembers !is DataResult.Content) {
            // Reached only for Empty / NoPermission / Error. Treat
            // Empty as success-no-data (Loaded), NoPermission / Error
            // as transient failure so the next modal open can retry.
            return when (val result = channelMembers) {
                is DataResult.Empty -> MembersEnrichOutcome.Unchanged
                is DataResult.NoPermission ->
                    MembersEnrichOutcome.Failed("getChannelMembers: NoPermission")
                is DataResult.Error ->
                    MembersEnrichOutcome.Failed("getChannelMembers: Error(${result.error.code})")
                is DataResult.Content -> error("unreachable: filtered by !is DataResult.Content above")
            }
        }
        val serverMembers = serverId?.let { serverMembersStore?.currentServerMembers(it) }
        val members =
            channelMembers.data.members
                .distinctBy { it.memberKey }
        val memberKeys = members.map { it.memberKey }.toSet()
        val candidates =
            if (serverMembers == null) {
                payload.memberCandidates
            } else {
                serverMembers
                    .filterNot { it.memberKey in memberKeys }
                    .map { it.toKuiklyThreadMemberItem(serverId) }
            }
        return MembersEnrichOutcome.Updated(
            payload.copy(
                membersCount = members.size,
                members = members.map { it.toKuiklyThreadMemberItem(serverId) },
                memberCandidates = candidates,
                canAddMembers = channelMembers.data.canAddMembers
            )
        )
    }

    suspend fun addChannelMember(
        payload: KuiklyThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.strictMemberKindOrNull(
                source = "thread_add_member",
                contract = MemberKindWireContract.Projected,
            )
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val request =
            AddChannelMemberRequest(
                channelId = channelId,
                memberId = cleanMemberId,
                kind = memberKind
            )
        return when (
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.addChannelMember(
                        serverId = serverId,
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                } else {
                    repository.addChannelMember(DataRequestPriority.Foreground, request)
                }
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    refreshMembersAfterMutation(
                        payload.copy(addMemberStatus = "idle", addMemberErrorCode = null)
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun markdownChannelLaunchPayload(
        currentPayload: KuiklyThreadScreenPayload,
        channelLabel: String
    ): KuiklyThreadScreenPayload? {
        val session = readSession() ?: return null
        val home = currentHomeSnapshot(session) ?: return null
        return buildMarkdownChannelLaunchPayload(
            currentPayload = currentPayload,
            home = home,
            channelLabel = channelLabel
        )
    }

    suspend fun markdownThreadRefLaunchPayload(
        currentPayload: KuiklyThreadScreenPayload,
        channelLabel: String,
        threadShortId: String?
    ): DataResult<KuiklyThreadScreenPayload> {
        val shortId =
            threadShortId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val home =
            currentHomeSnapshot(session)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val channel =
            resolveMarkdownChannelConversation(home, channelLabel)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val parentChannelId =
            channel.resolveThreadRouteIdentity().timelineChannelId
                ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val localTarget =
            currentPayload.markdownThreadRefTarget(parentChannelId, shortId)
                ?: cachedMarkdownThreadRefTarget(parentChannelId, shortId)
        val target =
            localTarget
                ?: when (
                    val result =
                        fetchThreadSnapshotFromRepository(
                            fallbackCode = "thread_markdown_ref_context_network",
                            call = { repository ->
                                repository.getMessageContext(MessageId(shortId), ChannelId(parentChannelId))
                            }
                        )
                ) {
                    is DataResult.Content -> result.data.markdownThreadRefTarget(parentChannelId, shortId)
                    is DataResult.Empty -> return result
                    is DataResult.Error -> return result
                    is DataResult.NoPermission -> return result
                }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = channelLabel,
                threadShortId = shortId,
                parentMessageId = target.parentMessageId,
                focusedMessageId = target.focusedMessageId,
                threadChannelId = target.threadChannelId
            )
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        target.threadChannelId
            ?.takeIf { it.isNotBlank() }
            ?.let { cacheReplyThreadResolution(parentChannelId, target.parentMessageId, it) }
        return DataResult.Content(payload)
    }

    suspend fun removeChannelMember(
        payload: KuiklyThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.strictMemberKindOrNull(
                source = "thread_remove_member",
                contract = MemberKindWireContract.Projected,
            )
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val request =
            RemoveChannelMemberRequest(
                channelId = channelId,
                memberId = cleanMemberId,
                kind = memberKind
            )
        return when (
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.removeChannelMember(
                        serverId = serverId,
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                } else {
                    repository.removeChannelMember(DataRequestPriority.Foreground, request)
                }
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    refreshMembersAfterMutation(
                        payload.copy(
                            removeMemberStatus = "idle",
                            removingMemberId = null,
                            removeMemberErrorCode = null
                        )
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun leaveChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.leaveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun archiveChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.archiveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun deleteChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.deleteChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun refreshMembersAfterMutation(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        return when (val outcome = enrichThreadMembers(payload)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> outcome.payload
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged,
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> payload
        }
    }

    suspend fun fetchOlderThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        beforeSeq: Long
    ): DataResult<KuiklyThreadScreenPayload> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "older",
            repositoryCall = { repository, threadId -> repository.loadOlderMessages(threadId, beforeSeq, SharedThreadPaging.pageSize) },
            call = { threadId -> api.channelMessages(threadId, limit = SharedThreadPaging.pageSize, beforeSeq = beforeSeq) }
        )

    suspend fun fetchNewerThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        afterSeq: Long
    ): DataResult<KuiklyThreadScreenPayload> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "newer",
            repositoryCall = { repository, threadId -> repository.loadNewerMessages(threadId, afterSeq, SharedThreadPaging.pageSize) },
            call = { threadId -> api.channelMessages(threadId, limit = SharedThreadPaging.pageSize, afterSeq = afterSeq) }
        )

    suspend fun fetchRealtimeGapThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        sinceSeq: Long
    ): DataResult<KuiklyThreadScreenPayload> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "realtime_gap",
            repositoryCall = { repository, threadId -> repository.syncNewerMessages(threadId, sinceSeq, SharedThreadPaging.pageSize) },
            call = { threadId -> api.messageSync(threadId, sinceSeq = sinceSeq, limit = SharedThreadPaging.pageSize) }
        )

    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String> = emptyList(),
        randomId: String? = null
    ): ThreadApiResult<ThreadMessagesPage> =
        api.sendMessage(channelId = channelId, body = body, asTask = asTask, attachmentIds = attachmentIds, randomId = randomId)

    suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): ThreadApiResult<List<PendingMentionActionExecuteResult>> =
        api.executePendingMentionActions(action = action, resolutionIds = resolutionIds)

    suspend fun uploadAttachments(
        channelId: String,
        files: List<PlatformFile>
    ): DataResult<AttachmentUploadResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "attachment_upload_unavailable", retryable = false))
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "missing_channel_id", retryable = false))
        }
        if (files.isEmpty()) {
            return DataResult.Error(ScreenError(code = "attachment_read_failed", retryable = false))
        }
        return repository.uploadAttachments(
            AttachmentUploadRequest(
                channelId = ChannelId(channelId),
                files = files
            )
        )
    }

    suspend fun attachmentUrl(attachmentId: String, disposition: String? = null): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentUrl(attachmentId, disposition)

    suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentHtmlPreviewUrl(attachmentId)

    suspend fun fetchFilesPayload(
        basePayload: KuiklyThreadScreenPayload,
        cursor: String? = null,
        currentPayload: KuiklyThreadFilesPayload? = null
    ): DataResult<KuiklyThreadFilesPayload> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_files_unavailable", retryable = false))
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.getChannelFiles(ChannelId(threadId), cursor = cursor)) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.toKuiklyThreadFilesPayload(
                        basePayload = basePayload,
                        currentPayload = currentPayload,
                        append = cursor != null
                    )
                )
            is DataResult.Empty ->
                DataResult.Content(
                    currentPayload
                        ?.takeIf { cursor != null && it.items.isNotEmpty() }
                        ?.copy(status = "content", hasMore = false, loadingMore = false, nextCursor = null, errorCode = null)
                        ?: KuiklyThreadFilesPayload(
                            instanceId = basePayload.instanceId,
                            status = "empty",
                            items = emptyList(),
                            hasMore = false,
                            loadingMore = false,
                            nextCursor = null
                        )
                )
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun saveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        val response =
            try {
                api.saveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_save_network", retryable = true))
        }
        return response.toUnitDataResult("thread_save_network")
    }

    suspend fun unsaveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        val response =
            try {
                api.unsaveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_unsave_network", retryable = true))
            }
        return response.toUnitDataResult("thread_unsave_network")
    }

    suspend fun setMessageReaction(messageId: String, emoji: String, selected: Boolean): DataResult<MessageMutationSummary> {
        val cleanMessageId = messageId.trim()
        val cleanEmoji = emoji.trim()
        if (cleanMessageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        if (cleanEmoji.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_reaction_missing", retryable = false))
        }
        val response =
            try {
                if (selected) {
                    api.addReaction(cleanMessageId, cleanEmoji)
                } else {
                    api.removeReaction(cleanMessageId, cleanEmoji)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reaction_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_reaction_network")
        }
    }

    suspend fun loadAttachmentComments(attachmentId: String): DataResult<ThreadAttachmentCommentsPage> {
        val cleanAttachmentId = attachmentId.trim()
        if (cleanAttachmentId.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_id_missing", retryable = false))
        }
        val response =
            try {
                api.attachmentComments(cleanAttachmentId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comments_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comments_network")
        }
    }

    suspend fun sendAttachmentComment(attachmentId: String, body: String): DataResult<Unit> {
        val cleanAttachmentId = attachmentId.trim()
        val cleanBody = body.trim()
        if (cleanAttachmentId.isBlank() || cleanBody.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_comment_missing", retryable = false))
        }
        val response =
            try {
                api.sendAttachmentComment(cleanAttachmentId, cleanBody)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comment_send_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(Unit)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comment_send_network")
        }
    }

    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): DataResult<TaskSummary?> {
        if (taskId.isBlank()) {
            return DataResult.Error(ScreenError(code = "task_id_missing", retryable = false))
        }
        val response =
            try {
                api.updateTaskStatus(taskId, status)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_task_status_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_task_status_network")
        }
    }

    suspend fun convertMessageToTask(messageId: String): DataResult<TaskSummary> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        val response =
            try {
                api.convertMessageToTask(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_task_convert_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success ->
                response.data?.let { DataResult.Content(it) }
                    ?: DataResult.Error(ScreenError(code = "thread_task_convert_contract_mismatch", retryable = false))
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_task_convert_network")
        }
    }

    suspend fun createOrOpenReplyThread(
        basePayload: KuiklyThreadScreenPayload,
        parentMessage: KuiklyThreadMessageItem
    ): DataResult<KuiklyThreadScreenPayload> {
        val parentChannelId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        cachedReplyThreadStreamId(parentChannelId, parentMessage.id)?.let { threadChannelId ->
            return loadResolvedReplyThread(
                basePayload = basePayload,
                parentMessage = parentMessage,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessage.id,
                threadChannelId = threadChannelId,
                replyCount = parentMessage.replyCount
            )
        }
        val actionResult =
            if (conversationRepository != null) {
                try {
                    conversationRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessage.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
                }
            } else {
                val response =
                    try {
                        api.createOrOpenThread(parentChannelId, parentMessage.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
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
        cacheReplyThreadResolution(parentChannelId, resolvedParentMessageId, threadChannelId)
        return loadResolvedReplyThread(
            basePayload = basePayload,
            parentMessage = parentMessage,
            parentChannelId = parentChannelId,
            parentMessageId = resolvedParentMessageId,
            threadChannelId = threadChannelId,
            replyCount = action.replyCount
        )
    }

    suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_follow_network", retryable = true))
        return try {
            repository.followThread(parentMessageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "thread_follow_network", retryable = true))
        }
    }

    suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_unfollow_network", retryable = true))
        return try {
            repository.unfollowThread(threadChannelId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "thread_unfollow_network", retryable = true))
        }
    }

    private suspend fun loadResolvedReplyThread(
        basePayload: KuiklyThreadScreenPayload,
        parentMessage: KuiklyThreadMessageItem,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
        replyCount: Int
    ): DataResult<KuiklyThreadScreenPayload> {
        val parentSurfaceKind =
            basePayload.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: when {
                    basePayload.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
                    basePayload.channelKind.equals("dm", ignoreCase = true) -> KuiklyThreadSurfaceKind.DirectMessage
                    basePayload.memberScopedMentions && basePayload.parentChannelId.isNullOrBlank() -> KuiklyThreadSurfaceKind.DirectMessage
                    else -> KuiklyThreadSurfaceKind.Channel
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
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = threadChannelId,
                streamChannelId = threadChannelId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                parentSurfaceKind = parentSurfaceKind,
                parentNavigationHint = basePayload.parentNavigationHint,
                navigationLabel = "Close",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
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
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = parentMessage.id,
                        enabled = basePayload.canSend,
                        mentionChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        streamChannelId = threadChannelId
                    )
            )
        return when (val loaded = fetchThreadPayload(replyBase, persistResult = false)) {
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
                    cacheCanonicalPayload(payload)
                DataResult.Content(selected)
            }
            is DataResult.Empty -> {
                val payload = replyBase.toEmptyReplyThreadPayload()
                val selected =
                    cacheCanonicalPayload(payload)
                DataResult.Content(selected)
            }
            is DataResult.Error -> loaded
            is DataResult.NoPermission -> loaded
        }
    }

    suspend fun markVisibleChannelRead(channelId: String, latestSeq: Long?): DataResult<ReadReceiptResult> {
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        }
        val response =
            try {
                if (latestSeq != null && latestSeq > 0L) {
                    api.markChannelReadToSeq(channelId, latestSeq)
                } else {
                    api.markChannelReadAll(channelId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_read_receipt_network", retryable = true))
            }
        return if (response is ThreadApiResult.Success) {
            DataResult.Content(
                ReadReceiptResult(
                    channelId = ChannelId(channelId),
                    seq = latestSeq?.takeIf { it > 0L },
                    changed = true
                )
            )
        } else {
            (response as ThreadApiResult.Failure).toThreadDataError("thread_read_receipt_network")
        }
    }

    suspend fun openMessageContext(
        messageId: String,
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val trimmedMessageId = messageId.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        return fetchThreadPayloadFromRepository(
            session = session,
            fallbackCode = "thread_parent_context_network",
            call = { repository -> repository.getMessageContext(MessageId(trimmedMessageId)) },
            transform = { snapshot ->
                snapshot.toKuiklyThreadPayload(basePayload, session.user.id)
                    .copy(showOpenInChannelAction = false)
            }
        )
    }

    suspend fun openCommentHost(
        hostMessageId: String,
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val streamThreadId = basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        if (basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread && streamThreadId != null) {
            return fetchThreadPayload(basePayload, persistResult = false)
        }
        return openMessageContext(hostMessageId, basePayload)
    }

    private suspend fun fetchThreadPage(
        basePayload: KuiklyThreadScreenPayload,
        source: String,
        repositoryCall: suspend (ConversationRepository, ThreadId) -> DataResult<ThreadSnapshot>,
        call: suspend (threadId: String) -> ThreadApiResult<ThreadMessagesPage>
    ): DataResult<KuiklyThreadScreenPayload> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val session =
            readSession()
                ?: return authSessionRequiredResult()
        val result =
            if (conversationRepository != null) {
                fetchThreadPayloadFromRepository(
                    session = session,
                    fallbackCode = "thread_${source}_network",
                    call = { repository -> repositoryCall(repository, ThreadId(threadId)) },
                    transform = { snapshot -> snapshot.toKuiklyThreadPayload(basePayload, session.user.id) }
                )
            } else {
                val response =
                    try {
                        call(threadId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_${source}_network", retryable = true))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_${source}_network")
                    }
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
            is DataResult.Content -> DataResult.Content(result.data)
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun fetchThreadPayloadFromRepository(
        session: StoredAuthSession,
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        transform: (ThreadSnapshot) -> KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val result = fetchThreadSnapshotFromRepository(fallbackCode, call)
        return result.map { snapshot ->
            transform(snapshot)
                .asLoadedEmptyReplyThreadPayload()
        }
    }

    private suspend fun fetchInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        threadId: ThreadId,
        includeFollowedSummaries: Boolean = false
    ): DataResult<KuiklyThreadScreenPayload> {
        val focusedContext =
            if (includeFollowedSummaries) {
                null
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = threadId,
                    accessFallback = null
                )
            }
        if (focusedContext != null) {
            return focusedContext
        }
        val initialResult =
            fetchThreadSnapshotFromRepository("thread_network") { repository ->
                repository.getThread(
                    threadId = threadId,
                    includeFollowedSummaries = includeFollowedSummaries,
                    limit = SharedThreadPaging.initialPageSize
                )
            }
        val initialData = initialResult as? DataResult.Content
        // If the route's stream hint is untrusted (stream != parent channel) and the
        // thread-channel snapshot's reported parent pair differs from the route,
        // fall back to the resolve path which POSTs createOrOpenThread to
        // reconcile. This is the v0.2 defense-in-depth: the snapshot may not
        // carry parent metadata at all (treated as trust), but if it does and
        // disagrees with the canonical route parent pair, we don't show it.
        return when {
            initialData != null &&
                basePayload.hasUntrustedReplyThreadStreamHint() &&
                !initialData.data.parentPairMatchesRoute(basePayload) ->
                fetchResolvedReplyThreadSnapshot(basePayload).map { (resolvedBase, snapshot) ->
                    snapshot
                        ?.withReplyParentMessageFromContext(resolvedBase)
                        ?.toKuiklyThreadPayload(resolvedBase, session.user.id)
                        ?: resolvedBase.toEmptyReplyThreadPayload()
                }
            initialData != null ->
                DataResult.Content(
                    initialData.data
                        .withReplyParentMessageFromContext(basePayload)
                        .toKuiklyThreadPayload(basePayload, session.user.id)
                )
            else -> initialResult as DataResult<KuiklyThreadScreenPayload>
        }
    }

    private suspend fun fetchFocusedInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        routeThreadId: ThreadId,
        accessFallback: ConversationAccessCandidate?
    ): DataResult<KuiklyThreadScreenPayload>? {
        val focusedMessageId = basePayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return null
        val focusedContextChannelId = basePayload.focusedMessageContextChannelIdOrNull()
        val contextResult =
            fetchThreadSnapshotFromRepository("thread_focused_context") { repository ->
                repository.getMessageContext(MessageId(focusedMessageId), focusedContextChannelId)
            }
        val context = (contextResult as? DataResult.Content)?.data ?: return null
        if (!context.matchesFocusedThreadRoute(routeThreadId, focusedMessageId)) {
            return null
        }
        return DataResult.Content(
            context
                .withCachedReplyParentMessage(basePayload)
                .withConversationAccessFallback(accessFallback)
                .toKuiklyThreadPayload(basePayload, session.user.id)
        )
    }

    private fun ThreadSnapshot.withCachedReplyParentMessage(
        basePayload: KuiklyThreadScreenPayload
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
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
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return this
        return copy(parentMessage = parent)
    }

    private suspend fun ThreadSnapshot.withReplyParentMessageFromContext(
        basePayload: KuiklyThreadScreenPayload,
        allowNetworkHydration: Boolean = true
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
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
                parentMessageId = parentMessageId
            )
                ?: return this
        return copy(parentMessage = parent)
    }

    private suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        includeFollowedSummaries: Boolean = false,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground
    ): DataResult<KuiklyThreadScreenPayload> = coroutineScope {
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val routeThreadId =
            basePayload.streamChannelId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
                ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        val focusedContext =
            if (includeFollowedSummaries || routeThreadId == null) {
                null
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = routeThreadId,
                    accessFallback = null
                )
            }
        if (focusedContext != null) {
            return@coroutineScope focusedContext
        }
        val parentContextDeferred =
            async {
                getReplyParentMessageFromStore(
                    parentChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    requestPriority = requestPriority
                )
            }
        val initialResult =
            fetchThreadSnapshotFromRepository("thread_network_reply") { repository ->
                repository.getReplyThread(
                    parentChannelId = ChannelId(parentChannelId),
                    parentMessageId = MessageId(parentMessageId),
                    includeFollowedSummaries = includeFollowedSummaries,
                    limit = SharedThreadPaging.initialPageSize,
                    priority = requestPriority
                )
            }
        return@coroutineScope when (initialResult) {
            is DataResult.Content -> {
                val hasReplyContent = initialResult.data.hasRenderableReplyThreadMessages(basePayload)
                val parentMessage =
                    if (hasReplyContent) {
                        parentContextDeferred.await()
                    } else {
                        withTimeoutOrNull(SharedThreadZeroReplyOriginHydrationGraceMillis) {
                            parentContextDeferred.await()
                        }
                    }
                val parentContextTimedOut = !hasReplyContent && !parentContextDeferred.isCompleted
                if (parentContextTimedOut) {
                    parentContextDeferred.cancel()
                    diagnosticsLogger(
                        "thread_parent_context_grace_expired parentChannelId=$parentChannelId " +
                            "parentMessageId=$parentMessageId " +
                            "graceMs=$SharedThreadZeroReplyOriginHydrationGraceMillis"
                    )
                }
                val resolvedThreadId =
                    initialResult.data.conversation.thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.channelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.threadId.value.takeIf { it.isNotBlank() }
                        ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                        ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                val resolvedBase =
                    if (resolvedThreadId == null) {
                        basePayload
                    } else {
                        basePayload.copy(
                            sourceThreadId = resolvedThreadId,
                            streamChannelId = resolvedThreadId
                        )
                    }
                val originResolvedBase =
                    if (parentMessage == null) {
                        resolvedBase.copy(
                            originStatus = "missing",
                            originErrorCode = null
                        )
                    } else {
                        resolvedBase
                    }
                val payload =
                    initialResult.data
                        .withReplyParentMessage(parentChannelId, parentMessageId, parentMessage)
                        .rememberReplyParentMessage(parentChannelId, parentMessageId)
                        .withReplyParentMessageFromContext(
                            basePayload = originResolvedBase,
                            allowNetworkHydration = false
                        )
                        .toKuiklyThreadPayload(originResolvedBase, session.user.id)
                DataResult.Content(
                    if (payload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread && payload.messages.isEmpty()) {
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

    private suspend fun getReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground
    ): Message? {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        val cachedParent = messageResolver.get(parentChannel, parentMessage)
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
        val parent =
            messageResolver.getOrFetch(parentChannel, parentMessage) {
                val startedAt = TimeSource.Monotonic.markNow()
                val contextResult =
                    fetchThreadSnapshotFromRepository("thread_parent_context") { repository ->
                        repository.getMessageContext(parentMessage, parentChannel, requestPriority)
                    }
                diagnosticsLogger(
                    "thread_parent_context_parallel_result parentChannelId=$parentChannelId " +
                        "parentMessageId=$parentMessageId result=${contextResult.threadDiagnosticsResult()} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                val context = (contextResult as? DataResult.Content)?.data ?: return@getOrFetch null
                context.parentMessage?.takeIf { message -> message.id == parentMessage }
                    ?: context.messages.firstOrNull { message -> message.id == parentMessage }
            }
        if (cachedParent != null && parent == cachedParent) {
            diagnosticsLogger(
                "thread_parent_context_store_hit parentChannelId=$parentChannelId parentMessageId=$parentMessageId"
            )
        }
        return parent?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
    }

    private fun getCachedReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String
    ): Message? {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        return messageResolver.get(parentChannel, parentMessage)
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
    }

    private fun ThreadSnapshot.withReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String,
        parentMessage: Message?
    ): ThreadSnapshot {
        val parentChannel = ChannelId(parentChannelId)
        val targetMessageId = MessageId(parentMessageId)
        val existingParent =
            this.parentMessage?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
                ?: messages.firstOrNull { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
        if (existingParent != null) {
            return copy(parentMessage = existingParent)
        }
        return parentMessage
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
            ?.let { parent -> copy(parentMessage = parent) }
            ?: this
    }

    private fun ThreadSnapshot.rememberReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String
    ): ThreadSnapshot {
        val parentChannel = ChannelId(parentChannelId)
        val targetMessageId = MessageId(parentMessageId)
        val candidate =
            parentMessage?.takeIf { message -> message.id == targetMessageId }
                ?: messages.firstOrNull { message -> message.id == targetMessageId }
                ?: return this
        if (candidate.matchesReplyOriginParent(parentChannel, targetMessageId)) {
            messageResolver.upsert(parentChannel, candidate)
        }
        return this
    }

    private fun Message.needsReplyOriginContextHydration(): Boolean =
        body.isBlank() && attachments.isEmpty()

    private fun KuiklyThreadMessageItem.needsReplyOriginContextHydration(): Boolean =
        body.isBlank() && attachments.isEmpty()

    private fun Message.matchesReplyOriginParent(
        parentChannelId: ChannelId,
        parentMessageId: MessageId
    ): Boolean =
        id == parentMessageId &&
            !needsReplyOriginContextHydration() &&
            (channelId == parentChannelId || threadId == ThreadId(parentChannelId.value))

    private fun ThreadSnapshot.hasRenderableReplyThreadMessages(
        basePayload: KuiklyThreadScreenPayload
    ): Boolean {
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
        val replyThreadContentChannelId =
            basePayload.copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId =
                    basePayload.sourceThreadId
                        ?: conversation.thread?.streamChannelId?.value
                        ?: conversation.channelId?.value
                        ?: conversation.threadId.value,
                streamChannelId =
                    basePayload.streamChannelId
                        ?: conversation.thread?.streamChannelId?.value
                        ?: conversation.channelId?.value
                        ?: conversation.threadId.value
            ).replyThreadContentChannelId()
        return messages.any { message ->
            message.id.value != parentMessageId &&
                (replyThreadContentChannelId == null || message.belongsToReplyThreadContent(replyThreadContentChannelId))
        }
    }

    private suspend fun fetchResolvedReplyThreadSnapshot(
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<Pair<KuiklyThreadScreenPayload, ThreadSnapshot?>> {
        val resolvedBasePayload =
            when (val resolved = createOrOpenReplyThreadStream(basePayload)) {
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
            fetchThreadSnapshotFromRepository("thread_network") { repository ->
                repository.getThread(
                    threadId = ThreadId(resolvedThreadId),
                    includeFollowedSummaries = false,
                    limit = SharedThreadPaging.initialPageSize
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

    private fun KuiklyThreadScreenPayload.toEmptyReplyThreadPayload(): KuiklyThreadScreenPayload =
        copy(
            status = "empty",
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            emptyTitle = ThreadReplyEmptyTitle,
            emptyDetail = ThreadReplyEmptyDetail
        )

    private suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
        val result =
            try {
                call(repository)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
            }
        return result
    }

    private suspend fun resolveConversationAccessFallback(
        basePayload: KuiklyThreadScreenPayload,
        session: StoredAuthSession
    ): ConversationAccessCandidate? {
        val home = currentHomeSnapshot(session) ?: return null
        return resolveConversationAccessFallback(basePayload, home)
    }

    private fun resolveConversationAccessFallback(
        basePayload: KuiklyThreadScreenPayload,
        home: HomeSnapshot
    ): ConversationAccessCandidate? {
        val lookupIds = basePayload.conversationAccessFallbackIds()
        if (lookupIds.isEmpty()) return null
        return home.toSharedConversationDirectoryState()
            .let { directory -> lookupIds.firstNotNullOfOrNull(directory::resolve) }
            ?.toConversationAccessCandidate()
            ?.takeIf { candidate -> basePayload.acceptsInitialConversationAccessCandidate(candidate) }
    }

    private suspend fun KuiklyThreadScreenPayload.withKnownMarkdownChannelNames(
        session: StoredAuthSession
    ): KuiklyThreadScreenPayload {
        val home = currentHomeSnapshot(session) ?: return this
        return withKnownMarkdownChannelNames(home)
    }

    private fun KuiklyThreadScreenPayload.withKnownMarkdownChannelNames(
        home: HomeSnapshot
    ): KuiklyThreadScreenPayload {
        val names =
            home.channels
                .mapNotNull { conversation -> conversation.name.trim().takeIf { it.isNotBlank() } }
                .distinctBy { it.lowercase() }
        return if (names.isEmpty() || names == knownMarkdownChannelNames) {
            this
        } else {
            copy(knownMarkdownChannelNames = names)
        }
    }

    private suspend fun currentHomeSnapshot(session: StoredAuthSession): HomeSnapshot? {
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
        val repository = channelRepository ?: return null
        return (repository.getHome(serverId) as? DataResult.Content)?.data
    }

    private suspend fun KuiklyThreadScreenPayload.withSavedStatus(
        session: StoredAuthSession
    ): KuiklyThreadScreenPayload {
        val repository = savedStatusRepository ?: return this
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return this
        val messageIds =
            (messages.map { it.id } + listOfNotNull(originMessage?.id))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map(::MessageId)
        if (messageIds.isEmpty()) return this
        val result =
            try {
                repository.checkSavedMessages(serverId, messageIds)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger(
                    "thread_saved_status_refresh_failed errorType=${throwable.safeThreadDiagnosticsType()}"
                )
                return this
            }
        return when (result) {
            is DataResult.Content -> withSavedMessageIds(result.data.savedIds.map { it.value }.toSet())
            is DataResult.Empty -> this
            is DataResult.Error -> {
                diagnosticsLogger("thread_saved_status_refresh_failed result=error code=${result.error.code}")
                this
            }
            is DataResult.NoPermission -> {
                diagnosticsLogger("thread_saved_status_refresh_failed result=no_permission code=${result.blocker.code}")
                this
            }
        }
    }
}

private fun ThreadApiResult.Failure.toThreadDataError(fallbackCode: String): DataResult<Nothing> =
    when (code) {
        // Permission loss must surface as NoPermission, not a transient error:
        // downstream treats NoPermission as unrecoverable (e.g. task #562 drops
        // the first-frame cache candidate) while plain errors keep warm state.
        401 ->
            DataResult.NoPermission(
                PermissionBlocker(code = "auth_required", requiredCapability = "auth_session")
            )
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = contractCode ?: "thread_forbidden",
                    requiredCapability = "thread_access"
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = contractCode ?: errorBodyText?.takeIf { it.isNotBlank() } ?: fallbackCode,
                    retryable = contractCode == null
                )
            )
    }

private fun ThreadApiResult<Unit>.toUnitDataResult(fallbackCode: String): DataResult<Unit> =
    when (this) {
        is ThreadApiResult.Success -> DataResult.Content(Unit)
        is ThreadApiResult.Failure -> toThreadDataError(fallbackCode)
    }

internal object SharedThreadPaging {
    const val initialPageSize = 50
    const val pageSize = initialPageSize
}
private const val SharedThreadFirstFrameDelayMillis = 16L
private const val SharedThreadInitialLoadDeadlineMillis = 10_000L
private const val ThreadVisibleReadCoalesceWindowMillis = 2_000L
private const val ThreadInitialAccessLookupTimeoutMillis = 1_000L

internal data class SharedThreadState(
    val payload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    val timelineSlice: ThreadTimelineSlice = payload.toThreadTimelineSlice(),
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata> = payload.toThreadReplyMetadataMap(),
    val highlightedMentions: Set<String> = emptySet(),
    val accessResolution: ThreadAccessResolution = ThreadAccessResolution.Loading,
    val canonicalAccessResolved: Boolean = false,
    val initialHomeAccessLookupComplete: Boolean = false,
    val initialHomeAccessCandidate: ConversationAccessCandidate? = null,
    val membersLoadStatus: LoadMembersStatus = LoadMembersStatus.Idle,
    /**
     * Key (typically source thread id) the [membersLoadStatus] is bound to.
     * Switching threads resets the status to Idle for the new key so we
     * never reuse a previous thread's "Loaded" verdict.
     */
    val membersLoadKey: String? = null
)

internal enum class ThreadAccessResolution {
    Loading,
    Resolved,
    Error,
    NoPermission
}

internal data class ThreadHomeContextResolution(
    val payload: KuiklyThreadScreenPayload,
    val accessCandidate: ConversationAccessCandidate? = null
)

/**
 * UI-owned projection that keeps access/composer facts and timeline load facts
 * in the same StateFlow emission. Collecting these fields independently can
 * briefly pair a resolved membership payload with a still-loading timeline (or
 * vice versa), which makes the footer and LazyColumn change on separate frames.
 */
internal data class SharedThreadRenderSlice(
    val payload: KuiklyThreadScreenPayload,
    val timelineSlice: ThreadTimelineSlice,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    val highlightedMentions: Set<String>,
    val accessResolution: ThreadAccessResolution
)

private fun SharedThreadState.toRenderSlice(): SharedThreadRenderSlice =
    SharedThreadRenderSlice(
        payload = payload.withoutThreadTimelineContent(),
        timelineSlice = timelineSlice,
        replyMetadataByParentId = replyMetadataByParentId,
        highlightedMentions = highlightedMentions,
        accessResolution = accessResolution
    )

/**
 * Per-thread members load status. Keyed by [SharedThreadState.membersLoadKey]
 * so that switching to another thread resets the loaded state instead of
 * re-using the previous thread's "Loaded" status.
 */
internal enum class LoadMembersStatus { Idle, InFlight, Loaded, Error }

private fun SharedThreadState.withSynchronizedTimelineSlice(): SharedThreadState {
    val nextTimelineSlice = payload.toThreadTimelineSlice()
    val nextReplyMetadataByParentId = payload.toThreadReplyMetadataMap()
    val timelineChanged = timelineSlice != nextTimelineSlice
    val replyMetadataChanged = replyMetadataByParentId != nextReplyMetadataByParentId
    return if (!timelineChanged && !replyMetadataChanged) {
        this
    } else {
        copy(
            timelineSlice = nextTimelineSlice,
            replyMetadataByParentId = nextReplyMetadataByParentId
        )
    }
}

private data class ThreadVisibleReadMarker(
    val channelId: String,
    val latestMessageId: String,
    val latestSeq: Long?
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data class ApplyRouteIntent(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent
    data class AppendSentPage(val page: ThreadMessagesPage) : SharedThreadIntent
    data class ApplyLocalSendStarted(val message: KuiklyThreadMessageItem) : SharedThreadIntent
    data class ApplyLocalSendFailure(
        val messageId: String,
        val errorCode: String? = null,
        val errorMessage: String? = null
    ) : SharedThreadIntent
    data class CancelLocalSend(val messageId: String) : SharedThreadIntent
    data class DismissLocalFailedSend(val messageId: String) : SharedThreadIntent
    data class ReplaceLocalSendWithSentPage(val messageId: String, val page: ThreadMessagesPage) : SharedThreadIntent
    data object PrepareForLocalSend : SharedThreadIntent
    data class ConsumeComposerSeedForSend(
        val sentBody: String,
        val sentAttachmentIds: Set<String>
    ) : SharedThreadIntent
    data class ApplyMessageReactionSelection(
        val messageId: String,
        val emoji: String,
        val selected: Boolean
    ) : SharedThreadIntent
    data class ApplyMessageReactions(
        val messageId: String,
        val reactions: List<KuiklyThreadReactionItem>
    ) : SharedThreadIntent
    data class ApplyReplyThreadRead(
        val parentMessageId: String,
        val threadChannelId: String? = null
    ) : SharedThreadIntent
    data class ApplyMessageThreadFollowed(
        val parentMessageId: String,
        val threadChannelId: String? = null,
        val followed: Boolean,
        val replyCount: Int = 0
    ) : SharedThreadIntent
    data class ApplyMessageMutation(val summary: MessageMutationSummary) : SharedThreadIntent
    data class ApplyActionCard(val messageId: String, val card: ThreadActionCard) : SharedThreadIntent
    data class ApplyMessageSaved(val messageId: String, val saved: Boolean) : SharedThreadIntent
    data class ApplyChannelJoined(val channelId: String) : SharedThreadIntent
    data class SelectTab(val tab: String) : SharedThreadIntent
    data class FocusMessage(val messageId: String, val switchToTab: String? = null) : SharedThreadIntent
    data class ApplyMembersPatch(
        val membersCount: Int,
        val members: List<KuiklyThreadMemberItem>,
        val memberCandidates: List<KuiklyThreadMemberItem>,
        val canAddMembers: Boolean,
        val addMemberStatus: String = "idle",
        val addMemberErrorCode: String? = null,
        val removeMemberStatus: String = "idle",
        val removingMemberId: String? = null,
        val removeMemberErrorCode: String? = null
    ) : SharedThreadIntent
    data class SetAddMemberStatus(val status: String, val errorCode: String? = null) : SharedThreadIntent
    data class SetRemoveMemberStatus(
        val status: String,
        val removingMemberId: String?,
        val errorCode: String? = null
    ) : SharedThreadIntent
    data class SetPagingAvailability(
        val canLoadMoreBefore: Boolean,
        val canLoadMoreAfter: Boolean
    ) : SharedThreadIntent
    data class ApplyRealtimeEvent(val event: RealtimeEvent, val activeReplyParentMessageId: MessageId? = null) :
        SharedThreadIntent
    data object MarkVisibleRead : SharedThreadIntent
    data object LoadMembers : SharedThreadIntent
    data object LoadOlder : SharedThreadIntent
    data object LoadNewer : SharedThreadIntent
    data object RefreshLatestAfterVisibleMaxSeq : SharedThreadIntent
    data class RecoverRealtimeGap(val serverSeq: Long) : SharedThreadIntent
}

internal class SharedThreadStore(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    private val initialLoadDeadlineMillis: Long = SharedThreadInitialLoadDeadlineMillis,
    initialPayload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload()
) {
    private val _state = MutableStateFlow(SharedThreadState(payload = initialPayload))
    val state: StateFlow<SharedThreadState> = _state.asStateFlow()
    fun payloadFlow(): Flow<KuiklyThreadScreenPayload> =
        state
            .map { threadState -> threadState.payload.withoutThreadTimelineContent() }
            .distinctUntilChanged()

    fun renderSliceFlow(): Flow<SharedThreadRenderSlice> =
        state
            .map { threadState -> threadState.toRenderSlice() }
            .distinctUntilChanged()

    fun timelineSliceFlow(): Flow<ThreadTimelineSlice> =
        state
            .map { threadState -> threadState.timelineSlice }
            .distinctUntilChanged()

    fun replyMetadataFlow(): Flow<Map<String, ThreadReplyMetadata>> =
        state
            .map { threadState -> threadState.replyMetadataByParentId }
            .distinctUntilChanged()

    fun highlightedMentionsFlow(): Flow<Set<String>> =
        state
            .map { threadState -> threadState.highlightedMentions }
            .distinctUntilChanged()

    private var loadJob: Job? = null
    private var secondaryTimelineRefreshJob: Job? = null
    private var initialAccessResolutionJob: Job? = null
    private var homeContextHydrationJob: Job? = null
    private var backgroundMembersHydrationJob: Job? = null
    private var readInFlightMarker: ThreadVisibleReadMarker? = null
    private var pendingReadMarker: ThreadVisibleReadMarker? = null
    private var lastCompletedReadMarker: ThreadVisibleReadMarker? = null
    private var lastCompletedReadAt: TimeMark? = null
    private var lastRoutePayload: KuiklyThreadScreenPayload? = null
    private var routeStaleToken = MviStaleToken.Initial
    private var routeStaleTokenValue = MviStaleToken.Initial.value
    private val initialAccessStateMutex = Mutex()
    private val pendingReactionRealtimeGuards = mutableMapOf<String, PendingReactionRealtimeGuard>()

    fun dispatch(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start,
            SharedThreadIntent.Refresh -> {
                loadJob?.cancel()
                if (intent is SharedThreadIntent.Start) {
                    initialAccessResolutionJob?.cancel()
                    initialAccessResolutionJob = null
                }
                secondaryTimelineRefreshJob?.cancel()
                secondaryTimelineRefreshJob = null
                homeContextHydrationJob?.cancel()
                homeContextHydrationJob = null
                backgroundMembersHydrationJob?.cancel()
                backgroundMembersHydrationJob = null
                pendingReactionRealtimeGuards.clear()
                loadJob =
                    scope.launch {
                        accept(intent)
                    }
            }
            is SharedThreadIntent.AppendSentPage,
            is SharedThreadIntent.ApplyRouteIntent,
            is SharedThreadIntent.ApplyLocalSendStarted,
            is SharedThreadIntent.ApplyLocalSendFailure,
            is SharedThreadIntent.CancelLocalSend,
            is SharedThreadIntent.DismissLocalFailedSend,
            is SharedThreadIntent.ReplaceLocalSendWithSentPage,
            SharedThreadIntent.PrepareForLocalSend,
            is SharedThreadIntent.ConsumeComposerSeedForSend,
            is SharedThreadIntent.ApplyMessageReactionSelection,
            is SharedThreadIntent.ApplyMessageReactions,
            is SharedThreadIntent.ApplyReplyThreadRead,
            is SharedThreadIntent.ApplyMessageThreadFollowed,
            is SharedThreadIntent.ApplyMessageMutation,
            is SharedThreadIntent.ApplyActionCard,
            is SharedThreadIntent.ApplyMessageSaved,
            is SharedThreadIntent.ApplyChannelJoined,
            is SharedThreadIntent.SelectTab,
            is SharedThreadIntent.FocusMessage,
            is SharedThreadIntent.ApplyMembersPatch,
            is SharedThreadIntent.SetAddMemberStatus,
            is SharedThreadIntent.SetRemoveMemberStatus,
            is SharedThreadIntent.SetPagingAvailability ->
                scope.launch { accept(intent) }
            is SharedThreadIntent.ApplyRealtimeEvent -> {
                scope.launch {
                    applyRealtimeEvent(
                        event = intent.event,
                        activeReplyParentMessageId = intent.activeReplyParentMessageId
                    )
                }
            }
            SharedThreadIntent.MarkVisibleRead -> {
                scope.launch { markVisibleRead() }
            }
            SharedThreadIntent.LoadMembers -> {
                scope.launch { loadMembers() }
            }
            SharedThreadIntent.LoadOlder -> {
                scope.launch { loadOlder() }
            }
            SharedThreadIntent.LoadNewer -> {
                scope.launch { loadNewer() }
            }
            SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq -> {
                scope.launch { refreshLatestAfterVisibleMaxSeq() }
            }
            is SharedThreadIntent.RecoverRealtimeGap -> {
                scope.launch { recoverRealtimeGap(intent.serverSeq) }
            }
        }
    }

    suspend fun accept(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start -> start(intent.payload)
            is SharedThreadIntent.ApplyRouteIntent -> applyRouteIntent(intent.payload)
            SharedThreadIntent.Refresh -> refresh(lastRoutePayload ?: _state.value.payload, advanceRouteStaleToken())
            is SharedThreadIntent.AppendSentPage -> appendSentPage(intent.page)
            is SharedThreadIntent.ApplyLocalSendStarted -> applyLocalSendStarted(intent.message)
            is SharedThreadIntent.ApplyLocalSendFailure ->
                applyLocalSendFailure(intent.messageId, intent.errorCode, intent.errorMessage)
            is SharedThreadIntent.CancelLocalSend -> cancelLocalSend(intent.messageId)
            is SharedThreadIntent.DismissLocalFailedSend -> dismissLocalFailedSend(intent.messageId)
            is SharedThreadIntent.ReplaceLocalSendWithSentPage -> replaceLocalSendWithSentPage(intent.messageId, intent.page)
            SharedThreadIntent.PrepareForLocalSend -> prepareForLocalSend()
            is SharedThreadIntent.ConsumeComposerSeedForSend ->
                consumeComposerSeedForSend(intent.sentBody, intent.sentAttachmentIds)
            is SharedThreadIntent.ApplyMessageReactionSelection ->
                applyMessageReactionSelection(intent.messageId, intent.emoji, intent.selected)
            is SharedThreadIntent.ApplyMessageReactions ->
                applyMessageReactions(intent.messageId, intent.reactions)
            is SharedThreadIntent.ApplyReplyThreadRead ->
                applyReplyThreadRead(intent.parentMessageId, intent.threadChannelId)
            is SharedThreadIntent.ApplyMessageThreadFollowed ->
                applyMessageThreadFollowed(intent.parentMessageId, intent.threadChannelId, intent.followed, intent.replyCount)
            is SharedThreadIntent.ApplyMessageMutation -> applyMessageMutation(intent.summary)
            is SharedThreadIntent.ApplyActionCard -> applyActionCard(intent.messageId, intent.card)
            is SharedThreadIntent.ApplyMessageSaved -> applyMessageSaved(intent.messageId, intent.saved)
            is SharedThreadIntent.ApplyChannelJoined -> applyChannelJoined(intent.channelId)
            is SharedThreadIntent.SelectTab -> selectTab(intent.tab)
            is SharedThreadIntent.FocusMessage -> focusMessage(intent.messageId, intent.switchToTab)
            is SharedThreadIntent.ApplyMembersPatch -> applyMembersPatch(intent)
            is SharedThreadIntent.SetAddMemberStatus -> setAddMemberStatus(intent.status, intent.errorCode)
            is SharedThreadIntent.SetRemoveMemberStatus ->
                setRemoveMemberStatus(intent.status, intent.removingMemberId, intent.errorCode)
            is SharedThreadIntent.SetPagingAvailability ->
                setPagingAvailability(intent.canLoadMoreBefore, intent.canLoadMoreAfter)
            is SharedThreadIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(
                    event = intent.event,
                    activeReplyParentMessageId = intent.activeReplyParentMessageId
                )
            SharedThreadIntent.MarkVisibleRead -> markVisibleRead()
            SharedThreadIntent.LoadMembers -> loadMembers()
            SharedThreadIntent.LoadOlder -> loadOlder()
            SharedThreadIntent.LoadNewer -> loadNewer()
            SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq -> refreshLatestAfterVisibleMaxSeq()
            is SharedThreadIntent.RecoverRealtimeGap -> recoverRealtimeGap(intent.serverSeq)
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob = null
        initialAccessResolutionJob?.cancel()
        initialAccessResolutionJob = null
        homeContextHydrationJob?.cancel()
        homeContextHydrationJob = null
        backgroundMembersHydrationJob?.cancel()
        backgroundMembersHydrationJob = null
        lastRoutePayload = null
        advanceRouteStaleToken()
        readInFlightMarker = null
        pendingReadMarker = null
        lastCompletedReadMarker = null
        lastCompletedReadAt = null
    }

    private suspend fun start(initialPayload: KuiklyThreadScreenPayload) {
        val session = dataSource.readSession()
        val routeCandidatePayload = initialPayload.withoutRouteCandidateContent()
        val routeContextPayload =
            dataSource
                .withCachedReplyParentCandidate(routeCandidatePayload)
                .withoutTransientLocalSendingMessages()
        val staleToken = advanceRouteStaleToken()
        lastRoutePayload = routeContextPayload
        val routeIdentity = routeContextPayload.resolveThreadRouteIdentity()
        val threadId = routeIdentity.timelineKey
        val cached =
            threadId
                ?.let(dataSource::cachedPayload)
                ?.takeIf { routeIdentity.canRenderTimelineCandidates }
                ?.takeIf { it.isUsableCachedPayloadForRoute(routeContextPayload) }
                ?.withRouteContext(routeContextPayload)
        val current =
            if (routeIdentity.canRenderTimelineCandidates) {
                _state.value.payload.takeIf {
                    it.isUsableCachedPayloadForRoute(routeContextPayload) && it.hasRenderableThreadContent()
                }
            } else {
                null
            }
        diagnosticsLogger?.invoke(
            "thread_start threadId=${threadId.orEmpty()} cachedMemory=${cached != null || current != null} " +
                "status=${routeContextPayload.status} messages=${routeContextPayload.messages.size} " +
                routeContextPayload.originDiagnostics()
        )
        val visiblePayload =
            cached
                ?: current
                ?: if (threadId == null) {
                    routeContextPayload
                } else {
                    routeContextPayload.copy(status = "loading", errorCode = null)
                }
        diagnosticsLogger?.invoke(
            "thread_visible_payload threadId=${threadId.orEmpty()} status=${visiblePayload.status} " +
                "messages=${visiblePayload.messages.size} " + visiblePayload.originDiagnostics()
        )
        initialAccessStateMutex.withLock {
            reduceState(
                SharedThreadState(
                    payload = visiblePayload,
                    highlightedMentions = emptySet(),
                    accessResolution = ThreadAccessResolution.Loading,
                    canonicalAccessResolved = false,
                    initialHomeAccessLookupComplete = false,
                    initialHomeAccessCandidate = null
                )
            )
        }
        delay(SharedThreadFirstFrameDelayMillis)
        if (
            staleTokenStillCurrent(staleToken, "thread_start", "highlight_mentions", routeContextPayload) &&
            (threadId == null || _state.value.payload.sourceThreadId == threadId)
        ) {
            reduceState(_state.value.copy(highlightedMentions = session.threadSelfMentionNames()))
        }
        refresh(routeContextPayload, staleToken, reusePrewarm = true)
    }

    private fun applyRouteIntent(routePayload: KuiklyThreadScreenPayload) {
        val routeCandidatePayload = routePayload.withoutRouteCandidateContent()
        val currentPayload = _state.value.payload
        val currentIdentity = currentPayload.resolveThreadRouteIdentity()
        val incomingIdentity = routeCandidatePayload.resolveThreadRouteIdentity()
        if (!currentIdentity.canRetainThreadPageFor(incomingIdentity)) {
            diagnosticsLogger?.invoke(
                "thread_route_intent_drop reason=route_changed " +
                    "current=${currentIdentity.instanceKey} incoming=${incomingIdentity.instanceKey}"
            )
            return
        }
        // Same-identity intent updates (tab/focus/nonce) do not invalidate the
        // in-flight canonical content/access generation. Content projection
        // deliberately does not own those intent fields, so the latest intent
        // remains intact when the current generation completes.
        lastRoutePayload =
            (lastRoutePayload ?: currentPayload)
                .withRouteIntentFrom(routeCandidatePayload)
                .withoutTransientLocalSendingMessages()
        reducePayloadPatch { currentPayload ->
            currentPayload.withRouteIntentFrom(routeCandidatePayload)
        }
    }

    private suspend fun refresh(
        basePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken = routeStaleToken,
        reusePrewarm: Boolean = false
    ) {
        val refreshStartedAt = TimeSource.Monotonic.markNow()
        var refreshStartState = _state.value
        val resolvingInitialAccessAtStart =
            refreshStartState.accessResolution != ThreadAccessResolution.Resolved
        if (resolvingInitialAccessAtStart) {
            initialAccessStateMutex.withLock {
                val latest = _state.value
                reduceState(
                    latest.copy(
                        accessResolution = ThreadAccessResolution.Loading,
                        canonicalAccessResolved = false,
                        initialHomeAccessLookupComplete = false,
                        initialHomeAccessCandidate = null
                    )
                )
                refreshStartState = _state.value
            }
            launchInitialAccessResolution(basePayload, staleToken)
        }
        val existing = refreshStartState.payload
        if (!routeStillCurrent(basePayload, "thread_refresh", "start", existing)) {
            return
        }
        val hasRenderableContent = existing.hasRenderableThreadContent()
        diagnosticsLogger?.invoke(
            "thread_refresh_start threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "existingStatus=${existing.status} existingMessages=${existing.messages.size} " +
                "hasRenderableContent=$hasRenderableContent"
        )
        val loadingBase =
            if (hasRenderableContent) {
                existing.withRouteContext(basePayload).copy(backgroundRefreshing = true)
            } else {
                basePayload.copy(status = "loading", backgroundRefreshing = false, errorCode = null)
            }
        diagnosticsLogger?.invoke(
            "thread_loading_patch threadId=${basePayload.sourceThreadId.orEmpty()} status=${loadingBase.status} " +
                "messages=${loadingBase.messages.size} " + loadingBase.originDiagnostics()
        )
        reducePayloadPatch { currentPayload ->
            if (!routeStillCurrent(basePayload, "thread_refresh", "loading_apply", currentPayload)) {
                return@reducePayloadPatch currentPayload
            }
            currentPayload.withThreadContentProjectionFrom(loadingBase)
        }
        if (!hasRenderableContent) {
            delay(SharedThreadFirstFrameDelayMillis)
        }
        val result =
            if (hasRenderableContent) {
                dataSource.fetchThreadPayload(
                    basePayload = basePayload,
                    persistResult = true,
                    reusePrewarm = reusePrewarm
                )
            } else {
                coroutineScope {
                    val deadlineJob =
                        launch {
                            delay(initialLoadDeadlineMillis.coerceAtLeast(0L))
                            val current = _state.value.payload
                            if (
                                staleTokenStillCurrent(
                                    staleToken,
                                    "thread_initial_load_deadline",
                                    "apply",
                                    basePayload,
                                    current
                                ) &&
                                routeStillCurrent(basePayload, "thread_initial_load_deadline", "apply", current) &&
                                current.status == "loading" &&
                                !current.hasRenderableThreadContent()
                            ) {
                                val emitted = reducePayloadPatch { currentPayload ->
                                    if (
                                        !routeStillCurrent(
                                            basePayload,
                                            "thread_initial_load_deadline",
                                            "reduce",
                                            currentPayload
                                        ) ||
                                        currentPayload.status != "loading" ||
                                        currentPayload.hasRenderableThreadContent()
                                    ) {
                                        return@reducePayloadPatch currentPayload
                                    }
                                    currentPayload.asRefreshFailed("thread_initial_load_timeout")
                                }
                                if (emitted) {
                                    diagnosticsLogger?.invoke(
                                        "thread_initial_load_deadline threadId=${basePayload.sourceThreadId.orEmpty()} " +
                                            "deadlineMs=${initialLoadDeadlineMillis.coerceAtLeast(0L)} result=retryable_error"
                                    )
                                }
                            }
                        }
                    try {
                        dataSource.fetchThreadPayload(
                            basePayload = basePayload,
                            persistResult = true,
                            reusePrewarm = reusePrewarm
                        )
                    } finally {
                        deadlineJob.cancel()
                    }
                }
            }
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(staleToken, "thread_refresh", "after_fetch", basePayload, latest) ||
            !routeStillCurrent(basePayload, "thread_refresh", "after_fetch", latest)
        ) {
            diagnosticsLogger?.invoke(
                "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                    "result=${result.threadDiagnosticsResult()} ignored=true reason=stale_route " +
                    "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        val visiblePayload =
            (result as? DataResult.Content)
                ?.data
                ?.withRouteContext(basePayload)
                ?.preserveStableTimelineFrom(latest)
                ?.asLoadedEmptyReplyThreadPayload()
        val nextPayload =
            when (result) {
                is DataResult.Content ->
                    visiblePayload ?: result.data.withRouteContext(basePayload).asLoadedEmptyReplyThreadPayload()
                is DataResult.Empty ->
                    if (result.reason == EmptyReason.NoMessages) {
                        latest.asRefreshEmpty(basePayload)
                    } else {
                        latest.asRefreshFailed("empty_thread")
                    }
                is DataResult.Error ->
                    latest.asRefreshFailed(result.error.code)
                is DataResult.NoPermission -> {
                    // Canonical fetch says we can no longer read this thread:
                    // the cached first-frame candidate is unrecoverable-stale
                    // (permission/visibility change) — drop it. Transient
                    // network errors deliberately keep the warm cache.
                    dataSource.invalidateCachedPayload(basePayload)
                    latest.asRefreshNoPermission(basePayload, result.blocker.code)
                }
            }.copy(backgroundRefreshing = false)
        initialAccessStateMutex.withLock {
            val currentState = _state.value
            val currentPayload = currentState.payload
            if (
                staleTokenStillCurrent(staleToken, "thread_refresh", "access_apply", basePayload, currentPayload) &&
                routeStillCurrent(basePayload, "thread_refresh", "apply", currentPayload)
            ) {
                val resolvingInitialAccess = currentState.accessResolution != ThreadAccessResolution.Resolved
                val projectedPayload =
                    currentPayload
                        .withThreadContentProjectionFrom(nextPayload)
                        .let { payload ->
                            if (
                                resolvingInitialAccess &&
                                result is DataResult.Content &&
                                payload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread
                            ) {
                                payload.withCanonicalConversationAccessFrom(nextPayload)
                            } else {
                                payload
                            }
                        }
                        .withInitialHomeConversationAccessFallback(currentState.initialHomeAccessCandidate)
                val canonicalAccessResolved =
                    currentState.canonicalAccessResolved || (resolvingInitialAccess && result is DataResult.Content)
                val projectedAccessResolution =
                    currentState.accessResolution.afterRefreshResult(
                        result = result,
                        canonicalAccessResolved = canonicalAccessResolved,
                        homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                        homeAccessCandidate = currentState.initialHomeAccessCandidate,
                        requiresParentAccessCandidate =
                            projectedPayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
                    )
                val didEmit =
                    reduceState(
                        currentState.copy(
                            payload = projectedPayload,
                            accessResolution = projectedAccessResolution,
                            canonicalAccessResolved = canonicalAccessResolved
                        )
                    )
                if (didEmit && visiblePayload != null) {
                    dataSource.cachePayload(projectedPayload)
                }
            }
        }
        diagnosticsLogger?.invoke(
            "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "result=${result.threadDiagnosticsResult()} messages=${nextPayload.messages.size} " +
                "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds} " +
                nextPayload.originDiagnostics()
        )
        if (!resolvingInitialAccessAtStart) {
            launchBackgroundHomeContextHydration(basePayload)
        }
        if (visiblePayload != null &&
            nextPayload.hasRenderableThreadContent() &&
            !basePayload.needsReplyThreadStreamResolution()
        ) {
            launchSecondaryTimelineRefresh(basePayload, staleToken)
        }
        if (nextPayload.hasRenderableThreadContent()) {
            launchBackgroundMembersHydration(basePayload)
        }
    }

    private fun launchInitialAccessResolution(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        initialAccessResolutionJob?.cancel()
        initialAccessResolutionJob =
            scope.launch {
                val homeResolution =
                    withTimeoutOrNull(ThreadInitialAccessLookupTimeoutMillis) {
                        try {
                            dataSource.homeConversationContextResolution(routePayload)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            null
                        }
                    }
                val candidate = homeResolution?.accessCandidate
                initialAccessStateMutex.withLock {
                    val currentState = _state.value
                    val currentPayload = currentState.payload
                    if (
                        !staleTokenStillCurrent(
                            staleToken,
                            "thread_access_resolution",
                            "apply",
                            routePayload,
                            currentPayload
                        ) ||
                        !routeStillCurrent(routePayload, "thread_access_resolution", "apply", currentPayload)
                    ) {
                        return@withLock
                    }
                    val projectedPayload =
                        currentPayload
                            .let { payload ->
                                homeResolution?.payload?.let { homePayload ->
                                    payload.withHomeContextProjectionFrom(homePayload)
                                } ?: payload
                            }
                            .withInitialHomeConversationAccessFallback(candidate)
                    val nextResolution =
                        when {
                            currentState.accessResolution == ThreadAccessResolution.NoPermission ->
                                ThreadAccessResolution.NoPermission
                            currentState.accessResolution == ThreadAccessResolution.Error ->
                                ThreadAccessResolution.Error
                            projectedPayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                                currentState.canonicalAccessResolved &&
                                candidate != null ->
                                ThreadAccessResolution.Resolved
                            projectedPayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                                currentState.canonicalAccessResolved ->
                                ThreadAccessResolution.Error
                            currentState.canonicalAccessResolved ->
                                ThreadAccessResolution.Resolved
                            candidate != null && currentPayload.status == "empty" ->
                                ThreadAccessResolution.Resolved
                            currentPayload.status == "error" ->
                                ThreadAccessResolution.Error
                            currentPayload.status == "empty" ->
                                ThreadAccessResolution.Error
                            else -> ThreadAccessResolution.Loading
                        }
                    reduceState(
                        currentState.copy(
                            payload = projectedPayload,
                            accessResolution = nextResolution,
                            initialHomeAccessLookupComplete = true,
                            initialHomeAccessCandidate = candidate
                        )
                    )
                }
            }
    }

    private fun launchBackgroundHomeContextHydration(routePayload: KuiklyThreadScreenPayload) {
        val staleToken = routeStaleToken
        homeContextHydrationJob?.cancel()
        homeContextHydrationJob =
            scope.launch {
                val hydratedPayload =
                    try {
                        dataSource.withHomeConversationContext(routePayload)
                            .withoutTransientLocalSendingMessages()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return@launch
                    }
                if (hydratedPayload == routePayload) {
                    return@launch
                }
                if (!staleTokenStillCurrent(staleToken, "thread_home_context", "after_fetch", routePayload)) {
                    return@launch
                }
                val emitted =
                    reducePayloadPatch { currentPayload ->
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_home_context",
                                "apply",
                                routePayload,
                                currentPayload
                            ) ||
                            !routeStillCurrent(routePayload, "thread_home_context", "apply", currentPayload)
                        ) {
                            return@reducePayloadPatch currentPayload
                        }
                        currentPayload.withHomeContextProjectionFrom(hydratedPayload)
                    }
                if (emitted) {
                    diagnosticsLogger?.invoke(
                        "thread_home_context_applied threadId=${routePayload.sourceThreadId.orEmpty()} " +
                            "knownMarkdownChannels=${hydratedPayload.knownMarkdownChannelNames.size} " +
                            "accessMutation=false"
                    )
                }
            }
    }

    private fun launchSecondaryTimelineRefresh(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob =
            scope.launch {
                refreshSecondaryTimelineData(routePayload, staleToken)
            }
    }

    private fun launchBackgroundMembersHydration(routePayload: KuiklyThreadScreenPayload) {
        backgroundMembersHydrationJob?.cancel()
        backgroundMembersHydrationJob =
            scope.launch {
                val latest = _state.value.payload
                if (!routeStillCurrent(routePayload, "thread_members_background_load", "start", latest)) {
                    return@launch
                }
                if (!latest.hasRenderableThreadContent()) {
                    return@launch
                }
                loadMembers(force = true)
            }
    }

    private suspend fun refreshSecondaryTimelineData(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        val beforeFetch = _state.value.payload
        if (!routeStillCurrent(routePayload, "thread_secondary_refresh", "start", beforeFetch)) {
            return
        }
        if (!beforeFetch.hasRenderableThreadContent()) {
            return
        }
        val followedStartedAt = TimeSource.Monotonic.markNow()
        val followedResult = dataSource.fetchFollowedThreadPayload(beforeFetch)
        diagnosticsLogger?.invoke(
            "thread_secondary_followed_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "result=${followedResult.threadDiagnosticsResult()} " +
                "durationMs=${followedStartedAt.elapsedNow().inWholeMilliseconds}"
        )
        val followedPayload =
            when (followedResult) {
                is DataResult.Content -> followedResult.data
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> beforeFetch
            }
        val enriched = dataSource.enrichSavedStatus(followedPayload)
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(staleToken, "thread_secondary_refresh", "after_fetch", routePayload, latest) ||
            !routeStillCurrent(routePayload, "thread_secondary_refresh", "after_fetch", latest)
        ) {
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "ignored=true reason=stale_route durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        val patch =
            enriched
                .withRouteContext(routePayload)
                .preserveStableTimelineFrom(latest)
        val emitted =
            reducePayloadPatchAndCache(cachePayload = true) { currentPayload ->
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_secondary_refresh",
                        "apply",
                        routePayload,
                        currentPayload
                    ) ||
                    !routeStillCurrent(routePayload, "thread_secondary_refresh", "apply", currentPayload)
                ) {
                    return@reducePayloadPatchAndCache currentPayload
                }
                currentPayload.withThreadContentProjectionFrom(patch)
            }
        diagnosticsLogger?.invoke(
            "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "emitted=$emitted messages=${patch.messages.size} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
    }

    /**
     * Load the current thread's members, with idempotency guards.
     *
     * Behaviour:
     * - No-op when the current source thread is missing.
     * - No-op when the bound status is [LoadMembersStatus.InFlight] for
     *   the current thread key (concurrent taps coalesce to one request).
     * - No-op when the bound status is [LoadMembersStatus.Loaded] for the
     *   current thread key, unless [force] is true.
     * - Resets to [LoadMembersStatus.Idle] when the bound key changes
     *   (switching threads) so a previous thread's "Loaded" verdict
     *   never leaks into the next thread.
     */
    private suspend fun loadMembers(force: Boolean = false) {
        val membersStartedAt = TimeSource.Monotonic.markNow()
        val existing = _state.value.payload
        val basePayload = existing
        val threadKey = existing.sourceThreadId.orEmpty()
        if (threadKey.isBlank()) return
        val currentState = _state.value
        if (currentState.membersLoadKey != threadKey) {
            // Switched threads: reset status so the previous verdict does not leak.
            _state.value = currentState.copy(
                membersLoadKey = threadKey,
                membersLoadStatus = LoadMembersStatus.Idle
            )
        }
        val resolvedStatus = _state.value.membersLoadStatus
        if (!force && resolvedStatus == LoadMembersStatus.InFlight) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=in_flight"
            )
            return
        }
        if (!force && resolvedStatus == LoadMembersStatus.Loaded) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=already_loaded"
            )
            return
        }
        diagnosticsLogger?.invoke(
            "thread_members_load_start threadId=$threadKey " +
                "existingMembers=${existing.members.size} existingCandidates=${existing.memberCandidates.size}"
        )
        _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.InFlight)
        when (val outcome = dataSource.enrichThreadMembers(existing)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                reducePayloadPatch(cachePayload = true) { currentPayload ->
                    if (!routeStillCurrent(basePayload, "thread_members_load", "apply", currentPayload)) {
                        return@reducePayloadPatch currentPayload
                    }
                    currentPayload.withMembersPatchFrom(outcome.payload)
                }
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=changed members=${outcome.payload.members.size} " +
                        "candidates=${outcome.payload.memberCandidates.size} canAddMembers=${outcome.payload.canAddMembers} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=unchanged members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                // Transient failure: keep status as Error so the next
                // modal open (or Refresh) can retry. Do not reduce payload.
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Error)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=failed reason=${outcome.reason} " +
                        "members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
        }
    }

    private suspend fun applyRealtimeEvent(
        event: RealtimeEvent,
        activeReplyParentMessageId: MessageId? = null
    ) {
        val existing = _state.value.payload
        val realtimeGapServerSeq =
            existing.realtimeNewerGapServerSeq(
                event = event,
                activeReplyParentMessageId = activeReplyParentMessageId
            )
        val rawMerged =
            existing.applyRealtimeEvent(
                event = event,
                currentUserId = dataSource.readSession()?.user?.id,
                activeReplyParentMessageId = activeReplyParentMessageId,
                shouldPreserveReactionsForRealtime = { message, currentUserId ->
                    shouldPreserveReactionsForRealtime(message, currentUserId)
                }
            )
        val rawReplyCountGap =
            rawMerged.realtimeReplyCountGap(
                event = event,
                previous = existing,
                activeReplyParentMessageId = activeReplyParentMessageId
            )
        val merged = rawMerged
        if (realtimeGapServerSeq != null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_gap_detected threadId=${existing.sourceThreadId.orEmpty()} " +
                    "visibleMaxSeq=${existing.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L} " +
                    "serverSeq=$realtimeGapServerSeq"
            )
            recoverRealtimeGap(realtimeGapServerSeq)
            return
        }
        if (rawMerged === existing) {
            if (rawReplyCountGap != null) {
                diagnosticsLogger?.invoke(
                    "thread_realtime_reply_count_gap_detected threadId=${existing.sourceThreadId.orEmpty()} " +
                        "visibleMaxSeq=${existing.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L} " +
                        "loadedReplies=${existing.loadedReplyTimelineCount()} " +
                        "replyCount=${rawReplyCountGap.replyCount}"
                )
                recoverRealtimeGapFromVisibleTail(rawReplyCountGap)
                return
            }
            diagnosticsLogger?.invoke(
                "thread_realtime_apply_result threadId=${existing.sourceThreadId.orEmpty()} " +
                    "source=none emitted=false messages=${existing.messages.size} reason=no_projection"
            )
            return
        }
        val stableSelected =
            merged.preserveStableTimelineFrom(
                previous = existing,
                preserveSelectedReactions = false
            )
        // task #562 (agreed with KMP-专家/HanXin, adjusting #231's A'): the
        // first-frame candidate is a read-only SNAPSHOT of the live store's
        // merged state — a single merge algorithm, written through after the
        // reduce. Every open still forces a refresh, so any snapshot drift
        // lives only in the first-frame window and is corrected by the
        // canonical fetch through this same reducer. Raw events are never
        // written; durable local-store never rides this path.
        val emitted =
            reducePayloadPatch(cachePayload = true) { currentPayload ->
                if (!routeStillCurrent(existing, "thread_realtime_event", "apply", currentPayload)) {
                    return@reducePayloadPatch currentPayload
                }
                currentPayload.withThreadContentProjectionFrom(stableSelected)
            }
        diagnosticsLogger?.invoke(
            "thread_realtime_apply_result threadId=${existing.sourceThreadId.orEmpty()} " +
                "source=direct emitted=$emitted messages=${stableSelected.messages.size} cacheWarmed=true"
        )
        val replyCountGap =
            stableSelected.realtimeReplyCountGap(
                event = event,
                previous = existing,
                activeReplyParentMessageId = activeReplyParentMessageId
            )
        if (replyCountGap != null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_reply_count_gap_detected threadId=${stableSelected.sourceThreadId.orEmpty()} " +
                    "visibleMaxSeq=${stableSelected.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L} " +
                    "loadedReplies=${stableSelected.loadedReplyTimelineCount()} " +
                    "replyCount=${replyCountGap.replyCount}"
            )
            recoverRealtimeGapFromVisibleTail(replyCountGap)
        }
    }

    private data class ReplyCountRealtimeGap(
        val replyCount: Int
    )

    private fun KuiklyThreadScreenPayload.realtimeReplyCountGap(
        event: RealtimeEvent,
        previous: KuiklyThreadScreenPayload,
        activeReplyParentMessageId: MessageId? = null
    ): ReplyCountRealtimeGap? {
        if (event !is ThreadUpdated) return null
        if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
        if (canLoadMoreAfter || newerMessagesStatus == "loading") return null
        val visibleMaxSeq = messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
        if (visibleMaxSeq <= 0L) return null
        val replyThreadContentChannelId = replyThreadContentChannelId() ?: return null
        val originParentId = originParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
        val matchesThread =
            event.threadChannelId.value == replyThreadContentChannelId &&
                (
                    originParentId == null ||
                        event.parentMessageId == originParentId ||
                        event.parentMessageId == activeReplyParentMessageId
                    )
        if (!matchesThread) return null
        val loadedReplies = loadedReplyTimelineCount()
        val previousLoadedReplies = previous.loadedReplyTimelineCount()
        val loadedReplyDelta = (loadedReplies - previousLoadedReplies).coerceAtLeast(0)
        val previousReplyCount =
            previous.knownReplyCountFor(event, activeReplyParentMessageId)
                ?: previousLoadedReplies
        val replyDelta = event.replyCount - previousReplyCount
        if (replyDelta <= 0 || loadedReplyDelta >= replyDelta) return null
        return ReplyCountRealtimeGap(replyCount = event.replyCount)
    }

    private fun KuiklyThreadScreenPayload.loadedReplyTimelineCount(): Int =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            val parentId = originParentMessageId?.takeIf { it.isNotBlank() }
            messages.count { message -> message.id != parentId }
        } else {
            messages.size
        }

    private fun KuiklyThreadScreenPayload.knownReplyCountFor(
        event: ThreadUpdated,
        activeReplyParentMessageId: MessageId?
    ): Int? {
        val parentIds =
            listOfNotNull(
                originParentMessageId?.takeIf { it.isNotBlank() },
                parentMessageId?.takeIf { it.isNotBlank() },
                activeReplyParentMessageId?.value,
                event.parentMessageId.value
            ).toSet()
        return (listOfNotNull(originMessage) + messages)
            .filter { message -> message.id in parentIds }
            .map { message -> message.replyCount }
            .maxOrNull()
    }

    private suspend fun markVisibleRead() {
        val marker = _state.value.payload.visibleReadMarker() ?: return
        if (marker.wasRecentlyMarkedRead()) {
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=recently_marked latest=${marker.latestMessageId}"
            )
            return
        }
        if (readInFlightMarker != null) {
            pendingReadMarker = marker
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=in_flight latest=${marker.latestMessageId}"
            )
            return
        }

        var nextMarker: ThreadVisibleReadMarker? = marker
        while (nextMarker != null) {
            val current = nextMarker
            pendingReadMarker = null
            readInFlightMarker = current
            val startedAt = TimeSource.Monotonic.markNow()
            diagnosticsLogger?.invoke(
                "thread_read_start channelId=${current.channelId} latest=${current.latestMessageId}"
            )
            try {
                val result = dataSource.markVisibleChannelRead(current.channelId, current.latestSeq)
                if (result is DataResult.Content) {
                    lastCompletedReadMarker = current
                    lastCompletedReadAt = TimeSource.Monotonic.markNow()
                }
                diagnosticsLogger?.invoke(
                    "thread_read_result channelId=${current.channelId} result=${result.threadDiagnosticsResult()} " +
                        "mode=${if (current.latestSeq != null && current.latestSeq > 0L) "seq" else "all"} " +
                        "seq=${current.latestSeq ?: -1} latest=${current.latestMessageId} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            } finally {
                readInFlightMarker = null
            }
            val pending = pendingReadMarker
            nextMarker =
                if (pending != null && pending != current && pending != lastCompletedReadMarker) {
                    pending
                } else {
                    pendingReadMarker = null
                    null
                }
        }
    }

    private fun ThreadVisibleReadMarker.wasRecentlyMarkedRead(): Boolean {
        val completed = lastCompletedReadMarker ?: return false
        if (completed.channelId != channelId) return false
        if (completed == this) return true
        val completedAt = lastCompletedReadAt ?: return false
        val ageMs = completedAt.elapsedNow().inWholeMilliseconds
        return ageMs in 0L until ThreadVisibleReadCoalesceWindowMillis
    }

    private suspend fun loadOlder() {
        val existing = _state.value.payload
        if (!existing.canLoadMoreBefore || existing.olderMessagesStatus == "loading") return
        val beforeSeq = existing.messages.mapNotNull { it.seq }.minOrNull()
        if (beforeSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(olderMessagesStatus = "error", errorCode = "missing_before_seq")
            }
            return
        }
        reducePayloadPatch { currentPayload -> currentPayload.copy(olderMessagesStatus = "loading", errorCode = null) }
        val result = dataSource.fetchOlderThreadPayload(existing, beforeSeq)
        val latest = _state.value.payload
        if (!routeStillCurrent(existing, "thread_page", "older_after_fetch", latest)) {
            return
        }
        val nextPayload =
            when (result) {
                is DataResult.Content -> {
                    val merged =
                        latest
                        .mergePagedMessages(result.data.messages, before = true)
                        .preserveStableTimelineFrom(latest)
                    dataSource
                        .cacheCanonicalPayload(merged)
                        .withRouteContext(existing)
                        .preserveStableTimelineFrom(latest)
                }
                is DataResult.Empty ->
                    latest.copy(canLoadMoreBefore = false, olderMessagesStatus = "idle")
                is DataResult.Error ->
                    latest.copy(olderMessagesStatus = "error", errorCode = result.error.code)
                is DataResult.NoPermission ->
                    latest.copy(olderMessagesStatus = "error", errorCode = result.blocker.code)
            }
        reducePayloadPatch { currentPayload ->
            if (!routeStillCurrent(existing, "thread_page", "older_apply", currentPayload)) {
                return@reducePayloadPatch currentPayload
            }
            currentPayload.withThreadContentProjectionFrom(nextPayload)
        }
    }

    private suspend fun loadNewer() {
        val existing = _state.value.payload
        if (!existing.canLoadMoreAfter || existing.newerMessagesStatus == "loading") return
        val afterSeq = existing.messages.mapNotNull { it.seq }.maxOrNull()
        if (afterSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(newerMessagesStatus = "error", errorCode = "missing_after_seq")
            }
            return
        }
        reducePayloadPatch { currentPayload -> currentPayload.copy(newerMessagesStatus = "loading", errorCode = null) }
        val result = dataSource.fetchNewerThreadPayload(existing, afterSeq)
        val latest = _state.value.payload
        if (!routeStillCurrent(existing, "thread_page", "newer_after_fetch", latest)) {
            return
        }
        val nextPayload =
            when (result) {
                is DataResult.Content -> {
                    val merged =
                        latest
                        .mergePagedMessages(result.data.messages, before = false)
                        .preserveStableTimelineFrom(latest)
                    dataSource
                        .cacheCanonicalPayload(merged)
                        .withRouteContext(existing)
                        .preserveStableTimelineFrom(latest)
                }
                is DataResult.Empty ->
                    latest.copy(canLoadMoreAfter = false, newerMessagesStatus = "idle")
                is DataResult.Error ->
                    latest.copy(newerMessagesStatus = "error", errorCode = result.error.code)
                is DataResult.NoPermission ->
                    latest.copy(newerMessagesStatus = "error", errorCode = result.blocker.code)
            }
        reducePayloadPatch { currentPayload ->
            if (!routeStillCurrent(existing, "thread_page", "newer_apply", currentPayload)) {
                return@reducePayloadPatch currentPayload
            }
            currentPayload.withThreadContentProjectionFrom(nextPayload)
        }
    }

    private suspend fun recoverRealtimeGap(serverSeq: Long) {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
        var nextRequest =
            RealtimeVisibleStreamGapPolicy.syncRequest(
                channelId = streamChannelId,
                visibleMaxSeq = initial.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L,
                serverSeq = serverSeq,
                loading = initial.newerMessagesStatus == "loading"
            ) ?: return
        recoverRealtimeGap(initial, nextRequest, staleToken)
    }

    private suspend fun recoverRealtimeGapFromVisibleTail(replyCountGap: ReplyCountRealtimeGap) {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
                ?: return
        val sinceSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()?.takeIf { it > 0L } ?: return
        if (initial.newerMessagesStatus == "loading") return
        recoverRealtimeGap(
            initial = initial,
            initialRequest =
                RealtimeVisibleStreamGapRequest(
                    channelId = streamChannelId,
                    sinceSeq = sinceSeq,
                    serverSeq = sinceSeq + replyCountGap.replyCount.toLong().coerceAtLeast(1L)
            ),
            staleToken = staleToken
        )
    }

    private suspend fun refreshLatestAfterVisibleMaxSeq() {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
                ?: return
        val sinceSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()?.takeIf { it > 0L } ?: return
        if (initial.newerMessagesStatus == "loading") return
        recoverRealtimeGap(
            initial = initial,
            initialRequest =
                RealtimeVisibleStreamGapRequest(
                    channelId = streamChannelId,
                    sinceSeq = sinceSeq,
                    serverSeq = sinceSeq + 1L
                ),
            staleToken = staleToken
        )
    }

    private suspend fun recoverRealtimeGap(
        initial: KuiklyThreadScreenPayload,
        initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken
    ) {
        var nextRequest = initialRequest
        reducePayloadPatch { currentPayload -> currentPayload.copy(newerMessagesStatus = "loading", errorCode = null) }
        while (true) {
            val result = dataSource.fetchRealtimeGapThreadPayload(_state.value.payload, nextRequest.sinceSeq)
            val latest = _state.value.payload
            if (
                !staleTokenStillCurrent(staleToken, "thread_realtime_gap", "after_fetch", initial, latest) ||
                !routeStillCurrent(initial, "thread_realtime_gap", "after_fetch", latest)
            ) {
                return
            }
            val nextPayload =
                when (result) {
                    is DataResult.Content -> {
                        val pageMessages = result.data.messages
                        val merged =
                            latest
                                .mergePagedMessages(pageMessages, before = false)
                                .preserveStableTimelineFrom(latest)
                        val selected =
                            dataSource
                                .cacheCanonicalPayload(merged)
                                .withRouteContext(initial)
                                .preserveStableTimelineFrom(latest)
                        reducePayloadPatch { currentPayload ->
                            if (
                                !staleTokenStillCurrent(
                                    staleToken,
                                    "thread_realtime_gap",
                                    "apply",
                                    initial,
                                    currentPayload
                                ) ||
                                !routeStillCurrent(initial, "thread_realtime_gap", "apply", currentPayload)
                            ) {
                                return@reducePayloadPatch currentPayload
                            }
                            currentPayload.withThreadContentProjectionFrom(selected)
                        }
                        val nextSinceSeq = selected.messages.mapNotNull { it.seq }.maxOrNull() ?: nextRequest.sinceSeq
                        val fetchedFullPage = pageMessages.size >= SharedThreadPaging.pageSize
                        if (!fetchedFullPage || nextSinceSeq <= nextRequest.sinceSeq) {
                            return
                        }
                        nextRequest =
                            nextRequest.copy(
                                sinceSeq = nextSinceSeq
                            )
                        null
                    }
                    is DataResult.Empty ->
                        latest.copy(canLoadMoreAfter = false, newerMessagesStatus = "idle")
                    is DataResult.Error ->
                        latest.copy(newerMessagesStatus = "error", errorCode = result.error.code)
                    is DataResult.NoPermission ->
                        latest.copy(newerMessagesStatus = "error", errorCode = result.blocker.code)
                }
            if (nextPayload != null) {
                reducePayloadPatch { currentPayload ->
                    if (
                        !staleTokenStillCurrent(staleToken, "thread_realtime_gap", "apply", initial, currentPayload) ||
                        !routeStillCurrent(initial, "thread_realtime_gap", "apply", currentPayload)
                    ) {
                        return@reducePayloadPatch currentPayload
                    }
                    currentPayload.withThreadContentProjectionFrom(nextPayload)
                }
                return
            }
        }
    }

    private suspend fun appendSentPage(page: ThreadMessagesPage) {
        if (page.messages.isEmpty()) return
        val currentPayload = _state.value.payload
        val appended =
            appendSharedThreadSentMessageFromPage(
                basePayload = currentPayload,
                page = page,
                session = dataSource.readSession(),
                diagnostics = diagnosticsLogger ?: strictProjectionDiagnosticsLogger,
            )
        val appendedCanonicalMessages =
            appended.messages.filter { appendedMessage ->
                currentPayload.messages.none { currentMessage -> currentMessage.id == appendedMessage.id }
            }
        val deduped =
            appended
                .copy(messages = appended.messages.absorbingCanonicalSendEchoes(appendedCanonicalMessages))
                .preserveStableTimelineFrom(currentPayload)
        val selected =
            dataSource
                .cacheCanonicalPayload(deduped)
                .withRouteContext(currentPayload)
                .preserveStableTimelineFrom(currentPayload)
        reducePayloadPatch { payload ->
            if (!routeStillCurrent(currentPayload, "thread_send", "append_apply", payload)) {
                return@reducePayloadPatch payload
            }
            payload.withThreadContentProjectionFrom(selected)
        }
    }

    private fun applyLocalSendStarted(message: KuiklyThreadMessageItem) {
        reducePayloadPatch { payload ->
            payload.withLocalSendStarted(message)
        }
    }

    private suspend fun applyLocalSendFailure(messageId: String, errorCode: String?, errorMessage: String?) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withLocalSendFailure(messageId, errorCode, errorMessage)
        }
    }

    private suspend fun dismissLocalFailedSend(messageId: String) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withoutLocalFailedSendMessage(messageId)
        }
    }

    private suspend fun cancelLocalSend(messageId: String) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withoutLocalSendMessage(messageId)
        }
    }

    private suspend fun replaceLocalSendWithSentPage(messageId: String, page: ThreadMessagesPage) {
        if (page.messages.isEmpty()) return
        val currentPayload = _state.value.payload
        val appended =
            appendSharedThreadSentMessageFromPage(
                basePayload = currentPayload,
                page = page,
                session = dataSource.readSession(),
                trustedReplyThreadChannelId =
                    currentPayload
                        .messages
                        .firstOrNull { it.id == messageId }
                        ?.pendingSendContext
                        ?.streamChannelId,
                diagnostics = diagnosticsLogger ?: strictProjectionDiagnosticsLogger,
            )
        val replaced =
            appended
                .withoutLocalSendMessage(messageId)
                .preserveStableTimelineFrom(currentPayload)
        val selected =
            dataSource
                .cacheCanonicalPayload(replaced)
                .withRouteContext(currentPayload)
                .preserveStableTimelineFrom(currentPayload)
        reducePayloadPatch { payload ->
            if (!routeStillCurrent(currentPayload, "thread_send", "replace_apply", payload)) {
                return@reducePayloadPatch payload
            }
            payload.withThreadContentProjectionFrom(selected)
        }
    }

    private fun applyMessageReactionSelection(messageId: String, emoji: String, selected: Boolean) {
        val guard = PendingReactionRealtimeGuard(
            messageId = messageId.trim(),
            emoji = emoji,
            selected = selected
        )
        if (guard.messageId.isNotBlank() && guard.emoji.isNotBlank()) {
            pendingReactionRealtimeGuards[guard.key] = guard
        }
        reducePayloadPatch { payload ->
            payload.withMessageReaction(messageId, emoji, selected)
        }
    }

    private fun applyMessageReactions(messageId: String, reactions: List<KuiklyThreadReactionItem>) {
        clearReactionRealtimeGuards(messageId)
        reducePayloadPatch { payload ->
            payload.withMessageReactions(messageId, reactions)
        }
    }

    private fun applyReplyThreadRead(parentMessageId: String, threadChannelId: String?) {
        reducePayloadPatch(cachePayload = true) { payload ->
            payload.withReplyThreadRead(parentMessageId, threadChannelId)
        }
    }

    private suspend fun applyMessageThreadFollowed(
        parentMessageId: String,
        threadChannelId: String?,
        followed: Boolean,
        replyCount: Int
    ) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withMessageThreadFollowed(parentMessageId, threadChannelId, followed, replyCount)
        }
    }

    private suspend fun applyMessageMutation(summary: MessageMutationSummary) {
        val session = dataSource.readSession()
        val currentUserId = session?.user?.id
        val currentUserName = session?.user?.bestDisplayName
        confirmReactionRealtimeGuards(summary, currentUserId)
        val currentPayload = _state.value.payload
        val canonicalPayload = currentPayload.withMessageMutationSummary(summary, currentUserId, currentUserName)
        val selected =
            dataSource
                .cacheCanonicalPayload(canonicalPayload)
                .withRouteContext(currentPayload)
                .preserveStableTimelineFrom(currentPayload, preserveSelectedReactions = false)
        reducePayloadPatch { payload ->
            if (!routeStillCurrent(currentPayload, "thread_reaction", "mutation_apply", payload)) {
                return@reducePayloadPatch payload
            }
            payload.withThreadContentProjectionFrom(selected)
        }
    }

    private fun shouldPreserveReactionsForRealtime(message: Message, currentUserId: String?): Boolean {
        val messageId = message.id.value
        if (message.reactions == null || pendingReactionRealtimeGuards.none { it.value.messageId == messageId }) {
            return false
        }
        val incomingReactions = message.reactions.toRealtimeThreadReactionItems(currentUserId).orEmpty()
        var preserveReactions = false
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == messageId }
            .forEach { guard ->
                val incomingSelected = incomingReactions.selectedForEmoji(guard.emoji)
                if (incomingSelected == guard.selected) {
                    pendingReactionRealtimeGuards.remove(guard.key)
                } else {
                    preserveReactions = true
                    pendingReactionRealtimeGuards.remove(guard.key)
                    diagnosticsLogger?.invoke(
                        "thread_realtime_reaction_frame_stale messageId=$messageId emoji=${guard.emoji} " +
                            "expectedSelected=${guard.selected} incomingSelected=$incomingSelected"
                    )
                }
            }
        return preserveReactions
    }

    private fun confirmReactionRealtimeGuards(summary: MessageMutationSummary, currentUserId: String?) {
        val messageId = summary.messageId.trim()
        if (messageId.isBlank()) return
        val canonicalReactions = summary.reactions.toThreadReactionItems(currentUserId)
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == messageId }
            .forEach { guard ->
                if (canonicalReactions.selectedForEmoji(guard.emoji) != guard.selected) {
                    pendingReactionRealtimeGuards.remove(guard.key)
                }
            }
    }

    private fun clearReactionRealtimeGuards(messageId: String) {
        val cleanMessageId = messageId.trim()
        if (cleanMessageId.isBlank()) return
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == cleanMessageId }
            .forEach { guard -> pendingReactionRealtimeGuards.remove(guard.key) }
    }

    private suspend fun applyActionCard(messageId: String, card: ThreadActionCard) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withActionCard(messageId, card) }
    }

    private suspend fun applyMessageSaved(messageId: String, saved: Boolean) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withMessageSaved(messageId, saved) }
    }

    private suspend fun applyChannelJoined(channelId: String) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            if (payload.joinChannelTargetId()?.value != channelId) {
                payload
            } else {
                payload.copy(
                    joined = true,
                    canSend = true,
                    composer = payload.composer.copy(enabled = true)
                )
            }
        }
    }

    private suspend fun applyMembersPatch(patch: SharedThreadIntent.ApplyMembersPatch) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.copy(
                membersCount = patch.membersCount,
                members = patch.members,
                memberCandidates = patch.memberCandidates,
                canAddMembers = patch.canAddMembers,
                addMemberStatus = patch.addMemberStatus,
                addMemberErrorCode = patch.addMemberErrorCode,
                removeMemberStatus = patch.removeMemberStatus,
                removingMemberId = patch.removingMemberId,
                removeMemberErrorCode = patch.removeMemberErrorCode
            )
        }
    }

    private fun setAddMemberStatus(status: String, errorCode: String?) {
        reducePayloadPatch {
            it.copy(addMemberStatus = status, addMemberErrorCode = errorCode)
        }
    }

    private fun setRemoveMemberStatus(status: String, removingMemberId: String?, errorCode: String?) {
        reducePayloadPatch {
            it.copy(
                removeMemberStatus = status,
                removingMemberId = removingMemberId,
                removeMemberErrorCode = errorCode
            )
        }
    }

    private fun setPagingAvailability(canLoadMoreBefore: Boolean, canLoadMoreAfter: Boolean) {
        reducePayloadPatch {
            it.copy(
                canLoadMoreBefore = canLoadMoreBefore,
                canLoadMoreAfter = canLoadMoreAfter
            )
        }
    }

    private fun selectTab(tab: String) {
        val normalizedTab = tab.takeIf { it.isNotBlank() } ?: return
        reducePayloadPatch { it.copy(selectedTab = normalizedTab) }
    }

    private fun focusMessage(messageId: String, switchToTab: String?) {
        val normalizedMessageId = messageId.takeIf { it.isNotBlank() } ?: return
        reducePayloadPatch {
            it.copy(
                selectedTab = switchToTab?.takeIf { tab -> tab.isNotBlank() } ?: it.selectedTab,
                focusedMessageId = normalizedMessageId
            )
        }
    }

    private fun prepareForLocalSend() {
        reducePayloadPatch {
            it.copy(
                focusedMessageId = null,
                canLoadMoreAfter = false,
                newerMessagesStatus = "idle"
            )
        }
    }

    private fun consumeComposerSeedForSend(sentBody: String, sentAttachmentIds: Set<String>) {
        reducePayloadPatch {
            it.consumeComposerSeedForSend(
                sentBody = sentBody,
                sentAttachmentIds = sentAttachmentIds
            )
        }
    }

    private fun reduceState(nextState: SharedThreadState): Boolean {
        val previousState = _state.value
        val synchronizedState = nextState.withSynchronizedTimelineSlice()
        val emitted = synchronizedState != previousState
        if (!emitted) return false
        _state.value = synchronizedState
        return true
    }

    private suspend fun reducePayloadPatchAndCache(
        cachePayload: Boolean = false,
        patch: (KuiklyThreadScreenPayload) -> KuiklyThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload) {
            dataSource.cachePayload(nextPayload)
        }
        return emitted
    }

    private fun advanceRouteStaleToken(): MviStaleToken {
        routeStaleTokenValue += 1L
        routeStaleToken = MviStaleToken(routeStaleTokenValue)
        return routeStaleToken
    }

    private fun staleTokenStillCurrent(
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: KuiklyThreadScreenPayload,
        currentPayload: KuiklyThreadScreenPayload = _state.value.payload
    ): Boolean {
        if (staleToken.isCurrent(routeStaleToken)) {
            return true
        }
        diagnosticsLogger?.invoke(
            "${source}_stale_drop threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "currentThreadId=${currentPayload.sourceThreadId.orEmpty()} stage=$stage " +
                "reason=stale_token token=${staleToken.value} currentToken=${routeStaleToken.value}"
        )
        return false
    }

    private fun routeStillCurrent(
        routePayload: KuiklyThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: KuiklyThreadScreenPayload = _state.value.payload
    ): Boolean {
        if (currentPayload.isUsableCachedPayloadForRoute(routePayload)) {
            return true
        }
        diagnosticsLogger?.invoke(
            "${source}_stale_drop threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "currentThreadId=${currentPayload.sourceThreadId.orEmpty()} stage=$stage " +
                "routeSurface=${routePayload.surfaceKind} currentSurface=${currentPayload.surfaceKind} " +
                "routeParentMessageId=${routePayload.parentMessageId.orEmpty()} " +
                "currentParentMessageId=${currentPayload.parentMessageId.orEmpty()}"
        )
        return false
    }

    private fun reducePayloadPatch(
        cachePayload: Boolean = false,
        patch: (KuiklyThreadScreenPayload) -> KuiklyThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload) {
            dataSource.cachePayload(nextPayload)
        }
        return emitted
    }
}

internal class SharedThreadViewModel(
    private val dataSource: SharedThreadDataSource,
    private val initialPayload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    private val diagnosticsLogger: ((String) -> Unit)? = null
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadStore(
                dataSource = dataSource,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger,
                initialPayload = initialPayload
            )
        }
    private val store: SharedThreadStore by storeDelegate
    val state: StateFlow<SharedThreadState>
        get() = store.state
    val currentPayload: KuiklyThreadScreenPayload
        get() = store.state.value.payload.withoutThreadTimelineContent()
    val currentTimelineSlice: ThreadTimelineSlice
        get() = store.state.value.timelineSlice
    val currentReplyMetadataByParentId: Map<String, ThreadReplyMetadata>
        get() = store.state.value.replyMetadataByParentId
    val currentHighlightedMentions: Set<String>
        get() = store.state.value.highlightedMentions
    val currentRenderSlice: SharedThreadRenderSlice
        get() = store.state.value.toRenderSlice()

    fun payloadFlow(): Flow<KuiklyThreadScreenPayload> = store.payloadFlow()

    fun renderSliceFlow(): Flow<SharedThreadRenderSlice> = store.renderSliceFlow()

    fun timelineSliceFlow(): Flow<ThreadTimelineSlice> = store.timelineSliceFlow()

    fun replyMetadataFlow(): Flow<Map<String, ThreadReplyMetadata>> = store.replyMetadataFlow()

    fun highlightedMentionsFlow(): Flow<Set<String>> = store.highlightedMentionsFlow()

    fun dispatch(intent: SharedThreadIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

private fun KuiklyThreadScreenPayload.asRefreshFailed(code: String): KuiklyThreadScreenPayload =
    if (hasRenderableThreadContent()) {
        copy(status = "content", backgroundRefreshing = false, errorCode = code)
    } else {
        copy(status = "error", backgroundRefreshing = false, errorCode = code)
    }

private fun KuiklyThreadScreenPayload.asRefreshNoPermission(
    base: KuiklyThreadScreenPayload,
    code: String
): KuiklyThreadScreenPayload =
    withRouteContext(base)
        .withoutThreadTimelineContent()
        .copy(
            status = "no_permission",
            backgroundRefreshing = false,
            errorCode = code,
            focusedMessageId = base.focusedMessageId,
            focusedMessageRequestNonce = base.focusedMessageRequestNonce
        )

private fun KuiklyThreadScreenPayload.asRefreshEmpty(base: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    withRouteContext(base).copy(
        status = "empty",
        backgroundRefreshing = false,
        errorCode = null,
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        olderMessagesStatus = "idle",
        newerMessagesStatus = "idle",
        emptyTitle = "No messages yet",
        emptyDetail = "This conversation has no messages yet."
    )

private fun KuiklyThreadScreenPayload.asLoadedEmptyReplyThreadPayload(): KuiklyThreadScreenPayload =
    if (isLoadedEmptyReplyThread()) {
        copy(
            status = "empty",
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            emptyTitle = ThreadReplyEmptyTitle,
            emptyDetail = ThreadReplyEmptyDetail
        )
    } else {
        this
    }

private fun KuiklyThreadScreenPayload.isLoadedEmptyReplyThread(): Boolean =
    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        messages.isEmpty() &&
        originStatus != "none" &&
        originStatus != "loading"

private fun DataResult<*>.threadDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

private fun KuiklyThreadScreenPayload.originDiagnostics(): String =
    "surface=$surfaceKind parentChannelId=${parentChannelId.orEmpty()} " +
        "parentMessageId=${parentMessageId.orEmpty()} originParentMessageId=${originParentMessageId.orEmpty()} " +
        "originStatus=$originStatus hasOriginMessage=${originMessage != null} " +
        "hasOriginPreview=${!originPreview.isNullOrBlank()}"

private fun KuiklyThreadScreenPayload.withRouteContext(base: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    if (hasResolvedReplyThreadStreamFrom(base)) {
        copy(
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            focusedMessageId = base.focusedMessageId,
            focusedMessageRequestNonce = base.focusedMessageRequestNonce,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            compactConsecutiveMessages = base.compactConsecutiveMessages,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            knownMarkdownChannelNames =
                base.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
            emptyTitle = if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) emptyTitle else base.emptyTitle,
            emptyDetail = if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) emptyDetail else base.emptyDetail
        )
    } else {
        copy(
            instanceId = base.instanceId.ifBlank { instanceId },
            sourceThreadId = base.sourceThreadId ?: sourceThreadId,
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            focusedMessageId = base.focusedMessageId,
            focusedMessageRequestNonce = base.focusedMessageRequestNonce,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            compactConsecutiveMessages = base.compactConsecutiveMessages,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            knownMarkdownChannelNames =
                base.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
            composer = base.composer.takeUnless { it.isDefaultThreadComposer() } ?: composer,
            emptyTitle = base.emptyTitle,
            emptyDetail = base.emptyDetail
        )
    }

private fun KuiklyThreadScreenPayload.withRouteIntentFrom(routePayload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
    val resolvedIdentityPayload =
        if (routePayload.resolveThreadRouteIdentity().canRenderTimelineCandidates) {
            withResolvedReplyThreadRouteIntentFrom(routePayload)
        } else {
            this
        }
    return resolvedIdentityPayload.copy(
        navigationLabel = routePayload.navigationLabel,
        selectedTab = routePayload.selectedTab,
        showModeTabs = routePayload.showModeTabs,
        focusedMessageId = routePayload.focusedMessageId,
        focusedMessageRequestNonce = routePayload.focusedMessageRequestNonce,
        alignOwnMessagesRight = routePayload.alignOwnMessagesRight,
        compactConsecutiveMessages = routePayload.compactConsecutiveMessages,
        localOffsetMinutes = routePayload.localOffsetMinutes,
        todayEpochDay = routePayload.todayEpochDay ?: todayEpochDay,
        nowEpochMillis = routePayload.nowEpochMillis ?: nowEpochMillis,
        timeFormat = routePayload.timeFormat,
        knownMarkdownChannelNames =
            routePayload.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
        composer =
            composer.copy(
                syncNonce = maxOf(composer.syncNonce, routePayload.composer.syncNonce),
                focusRequestNonce = maxOf(composer.focusRequestNonce, routePayload.composer.focusRequestNonce)
            )
    )
}

private fun KuiklyThreadScreenPayload.withHomeContextProjectionFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload {
    val knownNames =
        patch.knownMarkdownChannelNames.takeIf { it.isNotEmpty() && it != knownMarkdownChannelNames }
            ?: knownMarkdownChannelNames
    // Access is resolved exactly once by launchInitialAccessResolution and is
    // emitted atomically with ThreadAccessResolution.Resolved. Background Home
    // enrichment must never flip an already-mounted Composer into Join later.
    return copy(knownMarkdownChannelNames = knownNames)
}

private fun KuiklyThreadScreenPayload.withResolvedReplyThreadRouteIntentFrom(
    routePayload: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    if (routePayload.hasResolvedReplyThreadStreamFrom(this)) {
        copy(
            sourceThreadId = routePayload.sourceThreadId,
            streamChannelId = routePayload.streamChannelId,
            parentChannelId = routePayload.parentChannelId,
            parentMessageId = routePayload.parentMessageId,
            composer =
                composer.copy(
                    streamChannelId =
                        routePayload.composer.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: routePayload.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: routePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                )
        )
    } else {
        this
    }

internal fun buildMarkdownChannelLaunchPayload(
    currentPayload: KuiklyThreadScreenPayload,
    home: HomeSnapshot,
    channelLabel: String
): KuiklyThreadScreenPayload? {
    val channel = resolveMarkdownChannelConversation(home, channelLabel) ?: return null
    val conversation = channel.toThreadLaunchConversationIdentity()
    val streamChannelId = conversation.threadStreamChannelId() ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation,
        timeContext = currentPayload.toSlockTimeContext(),
        alignOwnMessagesRight = currentPayload.alignOwnMessagesRight,
        compactConsecutiveMessages = currentPayload.compactConsecutiveMessages,
        instanceIdOverride = "shared-shell-thread-$streamChannelId"
    )?.toInitialPayloadOrNull("shared-shell-thread-$streamChannelId")
}

internal fun buildMarkdownThreadRefLaunchPayload(
    currentPayload: KuiklyThreadScreenPayload,
    home: HomeSnapshot,
    channelLabel: String,
    threadShortId: String,
    parentMessageId: String,
    focusedMessageId: String? = null,
    threadChannelId: String? = null
): KuiklyThreadScreenPayload? {
    val channel = resolveMarkdownChannelConversation(home, channelLabel) ?: return null
    val routeIdentity = channel.resolveThreadRouteIdentity()
    val parentChannelId =
        routeIdentity.timelineChannelId
            ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
            ?: return null
    val cleanParentMessageId = parentMessageId.takeIf { it.isNotBlank() } ?: return null
    val cleanThreadChannelId = threadChannelId?.takeIf { it.isNotBlank() }
    val instanceId =
        cleanThreadChannelId?.let { "shared-shell-thread-$it" }
            ?: "shared-shell-thread-$parentChannelId-$threadShortId"
    val conversation =
        buildThreadLaunchConversationIdentity(
            kind = ConversationKind.Thread,
            channelId = cleanThreadChannelId?.let(::ChannelId) ?: ChannelId(parentChannelId),
            threadChannelId = cleanThreadChannelId?.let(::ChannelId),
            parentChannelId = ChannelId(parentChannelId),
            parentMessageId = MessageId(cleanParentMessageId),
            parentSurfaceKind = channel.kind.threadParentSurfaceKindOrNull(),
            parentChannelKind = routeIdentity.routeKind
        )
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation,
        timeContext = currentPayload.toSlockTimeContext(),
        anchorMessageId = focusedMessageId?.takeIf { it.isNotBlank() },
        alignOwnMessagesRight = currentPayload.alignOwnMessagesRight,
        compactConsecutiveMessages = currentPayload.compactConsecutiveMessages,
        instanceIdOverride = instanceId
    )?.toInitialPayloadOrNull(instanceId)
}

private fun resolveMarkdownChannelConversation(home: HomeSnapshot, channelLabel: String): ConversationSummary? {
    val key = channelLabel.markdownChannelLookupKey()
    if (key.isBlank()) return null
    val matches =
        home.channels
            .map { it.toConversationSummary() }
            .filter { it.name.markdownChannelLookupKey() == key }
            .distinctBy { it.messageStreamChannelId().value }
    return matches.singleOrNull()
}

private fun String.markdownChannelLookupKey(): String =
    trim()
        .removePrefix("#")
        .substringBefore(":")
        .trim()
        .lowercase()

private fun KuiklyThreadScreenPayload.markdownThreadRefTarget(
    parentChannelId: String,
    threadShortId: String
): MarkdownThreadRefTarget? {
    val shortId = threadShortId.trim().lowercase()
    if (parentChannelId.isBlank() || shortId.isBlank()) return null
    val routeIsParentChannel =
        surfaceKind != KuiklyThreadSurfaceKind.ReplyThread &&
            (sourceThreadId == parentChannelId || streamChannelId == parentChannelId)
    val originIsParentMessage =
        this.parentChannelId == parentChannelId &&
            originMessage != null
    val candidateMessages =
        (if (routeIsParentChannel) messages else emptyList()) +
            if (originIsParentMessage) listOfNotNull(originMessage) else emptyList()
    val candidates =
        candidateMessages
            .filter { message ->
                message.id.lowercase().startsWith(shortId)
            }
            .map { message ->
                MarkdownThreadRefTarget(
                    parentMessageId = message.id,
                    threadChannelId = message.threadChannelId
                )
            }
            .distinctBy { it.parentMessageId }
    return candidates.singleOrNull()
}

private fun ThreadSnapshot.markdownThreadRefTarget(
    parentChannelId: String,
    threadShortId: String
): MarkdownThreadRefTarget? {
    val expectedShortId = threadShortId.trim().lowercase()
    val routeThread = conversation.thread
    if (
        conversation.kind == ConversationKind.Thread &&
        routeThread != null &&
        routeThread.parentChannelId.value == parentChannelId &&
        routeThread.parentMessageId.value.isNotBlank()
    ) {
        return MarkdownThreadRefTarget(
            parentMessageId = routeThread.parentMessageId.value,
            focusedMessageId =
                highlightedMessageId
                    ?.value
                    ?.takeIf { it.isNotBlank() && it != routeThread.parentMessageId.value },
            threadChannelId = routeThread.streamChannelId?.value
        )
    }
    val targetMessageId =
        highlightedMessageId
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: parentMessage
                ?.id
                ?.value
                ?.takeIf { it.isNotBlank() }
            ?: messages
                .firstOrNull { message ->
                    message.channelId?.value == parentChannelId &&
                        message.id.value.lowercase().startsWith(expectedShortId)
                }
                ?.id
                ?.value
    return targetMessageId?.let { MarkdownThreadRefTarget(parentMessageId = it) }
}

private fun KuiklyThreadScreenPayload.withoutRouteCandidateContent(): KuiklyThreadScreenPayload =
    if (!resolveThreadRouteIdentity().canRenderTimelineCandidates) {
        val originCandidate = validatedUnresolvedReplyOriginCandidate()
        copy(
            status = if (hasRenderableThreadContent() && status != "error") "loading" else status,
            backgroundRefreshing = false,
            messages = emptyList(),
            originMessage = originCandidate,
            originPreview = if (originCandidate != null) originPreview else null,
            originStatus = if (originCandidate != null) originStatus else "none",
            originErrorCode = null,
            tasks = emptyList(),
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            members = emptyList(),
            memberCandidates = emptyList(),
            settingsActions = emptyList()
        )
    } else {
        copy(
            status = "loading",
            backgroundRefreshing = false,
            messages = emptyList(),
            originMessage = null,
            originPreview = null,
            originStatus = "none",
            originErrorCode = null,
            tasks = emptyList(),
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            showComposer = true,
            joined = true,
            canSend = true,
            membersCount = 0,
            members = emptyList(),
            memberCandidates = emptyList(),
            settingsActions = emptyList()
        ).withLaunchHeaderContext()
    }

private fun KuiklyThreadScreenPayload.validatedUnresolvedReplyOriginCandidate(): KuiklyThreadMessageItem? {
    val expectedParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
    if (sourceThreadId?.isNotBlank() == true || streamChannelId?.isNotBlank() == true) return null
    return originMessage?.takeIf { message -> message.id == expectedParentMessageId }
}

private fun KuiklyThreadScreenPayload.needsReplyThreadStreamResolution(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        parentMessage.isNotBlank() &&
        (streamCandidate == null || streamCandidate == parentChannel)
}

private fun KuiklyThreadScreenPayload.shouldFetchReplyThreadByParentPair(): Boolean =
    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        parentChannelId?.isNotBlank() == true &&
        (parentMessageId?.isNotBlank() == true || originParentMessageId?.isNotBlank() == true)

private fun KuiklyThreadScreenPayload.shouldResolveReplyThreadByParentPair(): Boolean =
    shouldFetchReplyThreadByParentPair() &&
        needsReplyThreadStreamResolution()

private fun KuiklyThreadScreenPayload.hasUntrustedReplyThreadStreamHint(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        streamCandidate != parentChannel
}

private fun ThreadSnapshot.parentPairMatchesRoute(base: KuiklyThreadScreenPayload): Boolean {
    val expectedParentChannelId = base.parentChannelId?.takeIf { it.isNotBlank() } ?: return true
    val expectedParentMessageId =
        base.parentMessageId?.takeIf { it.isNotBlank() }
            ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return true
    val thread = conversation.thread
    if (thread != null) {
        return thread.parentChannelId.value == expectedParentChannelId &&
            thread.parentMessageId.value == expectedParentMessageId
    }
    val parent = parentMessage ?: return true
    return parent.channelId?.value == expectedParentChannelId &&
        parent.id.value == expectedParentMessageId
}

private fun KuiklyThreadScreenPayload.hasResolvedReplyThreadStreamFrom(base: KuiklyThreadScreenPayload): Boolean {
    val source = sourceThreadId?.takeIf { it.isNotBlank() } ?: return false
    val baseSource =
        base.streamChannelId?.takeIf { it.isNotBlank() }
            ?: base.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: base.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        base.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        source != baseSource
}

private fun KuiklyThreadScreenPayload.withResolvedReplyThreadAction(
    action: ThreadActionResult
): KuiklyThreadScreenPayload {
    val threadChannelId = action.threadChannelId.value.takeIf { it.isNotBlank() } ?: return this
    val resolvedParentMessageId =
        action.parentMessageId?.value?.takeIf { it.isNotBlank() }
            ?: parentMessageId
            ?: originParentMessageId
    return copy(
        instanceId = "shared-shell-thread-$threadChannelId",
        sourceThreadId = threadChannelId,
        streamChannelId = threadChannelId,
        parentMessageId = resolvedParentMessageId,
        originParentMessageId = resolvedParentMessageId ?: originParentMessageId,
        originMessage =
            originMessage?.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(originMessage.replyCount, action.replyCount)
            ),
        composer =
            composer.copy(
                parentMessageId = resolvedParentMessageId ?: composer.parentMessageId,
                streamChannelId = threadChannelId
            )
    )
}

internal fun KuiklyThreadScreenPayload.preserveStableTimelineFrom(
    previous: KuiklyThreadScreenPayload,
    preserveSelectedReactions: Boolean = true
): KuiklyThreadScreenPayload {
    val visibleWindowMessages =
        if (
            (canLoadMoreBefore || previous.canLoadMoreBefore) &&
            messages.shouldRetainExpandedTimelineWindowFrom(previous.messages)
        ) {
            messages.mergeIntoExpandedTimelineWindow(previous.messages)
        } else {
            messages
        }
    val timelineMessages =
        if (visibleWindowMessages.isEmpty()) {
            visibleWindowMessages.withRetainedTransientLocalSendingMessagesFrom(previous.messages)
        } else {
            visibleWindowMessages
        }
    val stableMessages =
        timelineMessages
            .withRetainedLocalFailedMessagesFrom(previous.messages)
            .withSelectedReactionsFrom(previous.messages, preserveSelectedReactions)
            .preserveStableItemsFrom(previous.messages) { item -> item.id }
    val stableStatus =
        if (stableMessages.any { it.isTransientLocalSendingMessage() } && status == "empty") {
            "content"
        } else {
            status
        }
    val preservePreviousOrigin = shouldPreserveLoadedOriginFrom(previous)
    val stableOrigin =
        when {
            preservePreviousOrigin -> previous.originMessage
            originMessage != null && originMessage == previous.originMessage -> previous.originMessage
            else -> originMessage
        }
    val reactionStableOrigin =
        stableOrigin?.withSelectedReactionsFrom(previous.originMessage, preserveSelectedReactions)
    val stableTasks =
        tasks
            .preserveStableItemsFrom(previous.tasks) { item -> item.id }
            .withReferencedThreadTasksFrom(
                previous = previous.tasks,
                messages = stableMessages,
                originMessage = reactionStableOrigin
            )
    val stableOriginParentMessageId =
        if (preservePreviousOrigin) {
            previous.originParentMessageId ?: previous.originMessage?.id ?: originParentMessageId
        } else {
            originParentMessageId
        }
    val stableOriginPreview =
        if (preservePreviousOrigin) previous.originPreview else originPreview
    val stableOriginStatus =
        if (preservePreviousOrigin) previous.originStatus.takeIf { it == "loaded" } ?: "loaded" else originStatus
    val stableOriginErrorCode =
        if (preservePreviousOrigin) previous.originErrorCode else originErrorCode
    if (
        stableMessages === messages &&
        stableTasks === tasks &&
        reactionStableOrigin === originMessage &&
        stableOriginParentMessageId == originParentMessageId &&
        stableOriginPreview == originPreview &&
        stableOriginStatus == originStatus &&
        stableOriginErrorCode == originErrorCode &&
        stableStatus == status
    ) {
        return this
    }
    return copy(
        status = stableStatus,
        messages = stableMessages,
        originMessage = reactionStableOrigin,
        originPreview = stableOriginPreview,
        originParentMessageId = stableOriginParentMessageId,
        originStatus = stableOriginStatus,
        originErrorCode = stableOriginErrorCode,
        tasks = stableTasks
    )
}

private fun List<KuiklyThreadMessageItem>.shouldRetainExpandedTimelineWindowFrom(
    previous: List<KuiklyThreadMessageItem>
): Boolean {
    if (isEmpty() || previous.size <= size) return false
    val previousIds = previous.map { it.id }
    val previousIdSet = previousIds.toSet()
    val existingIncomingIds = map { it.id }.filter { it in previousIdSet }
    if (existingIncomingIds.isEmpty()) return false
    if (
        previous.size >= SharedThreadPaging.initialPageSize &&
        existingIncomingIds.size < SharedThreadPaging.initialPageSize / 2
    ) {
        return false
    }
    return existingIncomingIds == previousIds.takeLast(existingIncomingIds.size)
}

private fun List<KuiklyThreadMessageItem>.mergeIntoExpandedTimelineWindow(
    previous: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    val incomingById = associateBy { it.id }
    val previousIds = previous.mapTo(mutableSetOf()) { it.id }
    return (
        previous.map { message -> incomingById[message.id] ?: message } +
            filterNot { message -> message.id in previousIds }
        ).sortedByThreadTimeline()
}

private fun KuiklyThreadScreenPayload.shouldPreserveLoadedOriginFrom(
    previous: KuiklyThreadScreenPayload
): Boolean {
    val previousOrigin = previous.originMessage ?: return false
    if (originMessage != null) return false
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread || previous.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
        return false
    }
    if (originStatus != "missing" && originStatus != "loading") return false
    val incomingOriginId =
        originParentMessageId?.takeIf { it.isNotBlank() }
            ?: parentMessageId?.takeIf { it.isNotBlank() }
            ?: return false
    val previousOriginId =
        previous.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: previous.parentMessageId?.takeIf { it.isNotBlank() }
            ?: previousOrigin.id.takeIf { it.isNotBlank() }
    return previousOriginId == incomingOriginId && previousOrigin.id == incomingOriginId
}

private inline fun <T, K> List<T>.preserveStableItemsFrom(
    previous: List<T>,
    keySelector: (T) -> K
): List<T> {
    if (isEmpty() || previous.isEmpty()) return this
    val previousByKey = previous.associateBy(keySelector)
    var reusedAnyReference = false
    val stableItems =
        map { incoming ->
            val previousItem = previousByKey[keySelector(incoming)]
            if (previousItem != null && previousItem == incoming) {
                reusedAnyReference = reusedAnyReference || previousItem !== incoming
                previousItem
            } else {
                incoming
            }
        }
    val fullyMatchesPrevious =
        stableItems.size == previous.size &&
            stableItems.indices.all { index -> stableItems[index] === previous[index] }
    return when {
        fullyMatchesPrevious -> previous
        reusedAnyReference -> stableItems
        else -> this
    }
}

private fun List<KuiklyThreadMessageItem>.withSelectedReactionsFrom(
    previous: List<KuiklyThreadMessageItem>,
    enabled: Boolean
): List<KuiklyThreadMessageItem> {
    if (!enabled || isEmpty() || previous.isEmpty()) return this
    val previousById = previous.associateBy { it.id }
    var changed = false
    val merged =
        map { message ->
            val previousMessage = previousById[message.id] ?: return@map message
            val nextMessage = message.withSelectedReactionsFrom(previousMessage, enabled = true)
            changed = changed || nextMessage !== message
            nextMessage
        }
    return if (changed) merged else this
}

private fun KuiklyThreadMessageItem.withSelectedReactionsFrom(
    previous: KuiklyThreadMessageItem?,
    enabled: Boolean
): KuiklyThreadMessageItem {
    if (!enabled || previous == null) return this
    val selectedPrevious = previous.reactions.filter { it.selected && it.count > 0 }
    if (selectedPrevious.isEmpty()) return this
    val nextReactions = reactions.withSelectedReactionsFrom(selectedPrevious)
    val orderedReactions = nextReactions.orderedLike(previous.reactions)
    return if (orderedReactions === reactions) this else copy(reactions = orderedReactions)
}

private fun List<KuiklyThreadReactionItem>.withSelectedReactionsFrom(
    selectedPrevious: List<KuiklyThreadReactionItem>
): List<KuiklyThreadReactionItem> {
    var changed = false
    val byEmoji = associateBy { it.emoji }.toMutableMap()
    selectedPrevious.forEach { previousReaction ->
        val incoming = byEmoji[previousReaction.emoji]
        if (incoming == null || !incoming.selected || incoming.count <= 0) {
            byEmoji[previousReaction.emoji] =
                incoming?.copy(
                    count = maxOf(incoming.count, previousReaction.count),
                    selected = true,
                    reactorIds = (incoming.reactorIds + previousReaction.reactorIds).distinct(),
                    reactorNames = (incoming.reactorNames + previousReaction.reactorNames).distinct()
                )
                    ?: previousReaction
            changed = true
        }
    }
    return if (changed) byEmoji.values.toList() else this
}

private fun List<KuiklyThreadReactionItem>.orderedLike(
    previous: List<KuiklyThreadReactionItem>
): List<KuiklyThreadReactionItem> {
    if (size <= 1 || previous.isEmpty()) return this
    val previousOrder = previous.map { it.emoji }.filter { it.isNotBlank() }.distinct()
    if (previousOrder.isEmpty()) return this
    val currentByEmoji = associateBy { it.emoji }
    val ordered =
        buildList(size) {
            previousOrder.forEach { emoji ->
                currentByEmoji[emoji]?.let(::add)
            }
            this@orderedLike.forEach { reaction ->
                if (reaction.emoji !in previousOrder) add(reaction)
            }
        }
    return if (ordered == this) this else ordered
}

private fun List<KuiklyThreadTaskItem>.withReferencedThreadTasksFrom(
    previous: List<KuiklyThreadTaskItem>,
    messages: List<KuiklyThreadMessageItem>,
    originMessage: KuiklyThreadMessageItem?
): List<KuiklyThreadTaskItem> {
    if (previous.isEmpty()) return this
    val referencedMessages = messages + listOfNotNull(originMessage)
    if (referencedMessages.none { it.hasThreadTaskReference() }) return this
    val existingIds = mapNotNull { item -> item.id.takeIf { it.isNotBlank() } }.toSet()
    val existingNumbers = mapNotNull { item -> item.taskNumber }.toSet()
    val retained =
        previous.filter { item ->
            val taskNumberIsSuperseded = item.taskNumber?.let { it in existingNumbers } == true
            item.matchesAnyThreadTaskReference(referencedMessages) &&
                item.id.takeIf { it.isNotBlank() } !in existingIds &&
                !taskNumberIsSuperseded
        }
    if (retained.isEmpty()) return this
    return this + retained
}

private fun KuiklyThreadMessageItem.hasThreadTaskReference(): Boolean =
    taskId?.isNotBlank() == true || taskNumber != null

private fun KuiklyThreadTaskItem.matchesAnyThreadTaskReference(
    messages: List<KuiklyThreadMessageItem>
): Boolean =
    messages.any { message ->
        val taskIdMatches =
            id.isNotBlank() &&
                message.taskId?.takeIf { it.isNotBlank() } == id
        val taskNumberMatches =
            taskNumber != null &&
                message.taskNumber == taskNumber
        taskIdMatches || taskNumberMatches
    }

private val MemberProfile.memberKey: String
    get() = "${kind.name}:${id.value}"

private fun MemberProfile.toKuiklyThreadMemberItem(localServerId: ServerId?): KuiklyThreadMemberItem =
    KuiklyThreadMemberItem(
        id = id.value,
        displayName = displayName,
        handle = handle,
        roleLabel =
            roleDescription
                ?: when (kind) {
                    MemberKind.Agent -> "Agent"
                    MemberKind.Human -> "Member"
                    MemberKind.System -> "System"
                },
        avatarUrl = avatarWireValueForAvatarSlot(),
        avatarLabel = avatarLabel,
        kind = kind.name.lowercase(),
        sourceServerId = serverId?.value,
        sourceServerLabel = remoteSourceServerLabel(localServerId),
        canRemove = isLocalToServer(localServerId),
        presence = presence.takeIf { kind == MemberKind.Agent && (agentStatus != null || presence != Presence.Offline) },
        agentStatus = agentStatus.takeIf { kind == MemberKind.Agent }
    )

private fun MemberProfile.isLocalToServer(localServerId: ServerId?): Boolean {
    val sourceId = serverId?.value?.takeIf { it.isNotBlank() } ?: return true
    val localId = localServerId?.value?.takeIf { it.isNotBlank() } ?: return true
    return sourceId == localId
}

private fun MemberProfile.remoteSourceServerLabel(localServerId: ServerId?): String? {
    if (isLocalToServer(localServerId)) return null
    return serverName?.takeIf { it.isNotBlank() } ?: serverSlug?.takeIf { it.isNotBlank() }
}

private fun ChannelFilesSnapshot.toKuiklyThreadFilesPayload(
    basePayload: KuiklyThreadScreenPayload,
    currentPayload: KuiklyThreadFilesPayload?,
    append: Boolean
): KuiklyThreadFilesPayload {
    val incoming = items.map { item ->
        val attachment = item.attachment
        KuiklyThreadFileItem(
            attachmentId = attachment.id.value,
            messageId = item.messageId?.value.orEmpty(),
            channelId = item.channelId?.value.orEmpty(),
            name = attachment.name,
            sizeBytes = attachment.sizeBytes,
            sizeLabel = attachment.sizeLabel,
            createdAtLabel = basePayload.formatFilesTimestampLabel(item.createdAt.orEmpty()),
            sourceLabel = item.filesSourceLabel(),
            sourceType = item.sourceType.orEmpty(),
            sourceChannelId = item.sourceChannelId?.value.orEmpty(),
            sourceParentMessageId = item.sourceParentMessageId?.value.orEmpty(),
            sourceParentMessageShortId = item.sourceParentMessageShortId.orEmpty(),
            mimeType = attachment.mimeType,
            thumbnailUrl = attachment.thumbnailUrl
        )
    }
    val merged =
        if (append) {
            currentPayload.orEmptyFilesPayload(basePayload).items + incoming
        } else {
            incoming
        }.distinctBy { item -> item.attachmentId }
    return KuiklyThreadFilesPayload(
        instanceId = basePayload.instanceId,
        status = if (merged.isEmpty()) "empty" else "content",
        items = merged,
        hasMore = hasMore,
        loadingMore = false,
        nextCursor = nextCursor,
        errorCode = null
    )
}

private fun KuiklyThreadFilesPayload?.orEmptyFilesPayload(basePayload: KuiklyThreadScreenPayload): KuiklyThreadFilesPayload =
    this ?: KuiklyThreadFilesPayload(instanceId = basePayload.instanceId)

private fun build.raft.app.data.ChannelFileItem.filesSourceLabel(): String =
    sourceParentMessageShortId
        ?.takeIf { it.isNotBlank() }
        ?.let { "Thread #$it" }
        ?: sourceType
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { char -> char.uppercaseChar() }
            .orEmpty()

private fun KuiklyThreadScreenPayload.formatFilesTimestampLabel(value: String): String =
    formatSlockTimestampLabel(value, toSlockTimeContext())

private fun KuiklyThreadScreenPayload.hasRenderableThreadContent(): Boolean =
    messages.isNotEmpty() || originMessage != null || tasks.isNotEmpty()

private fun KuiklyThreadScreenPayload.cacheThreadId(): String? =
    resolveThreadRouteIdentity().timelineChannelId

private fun Message.belongsToReplyThreadContent(threadChannelId: String): Boolean =
    matchesResolvedReplyThreadTimeline(
        resolvedThreadChannelId = threadChannelId,
        messageChannelId = channelId?.value,
        messageThreadId = threadId.value
    )

private fun KuiklyThreadScreenPayload.isUsableCachedPayloadForRoute(route: KuiklyThreadScreenPayload): Boolean =
    ThreadCandidateProofGate
        .prove(route = route.toThreadRouteId(), candidate = toThreadRouteId())
        .let { it is ThreadIdentityProof.Accepted }

private fun KuiklyThreadScreenPayload.toThreadRouteId(serverId: ServerId? = null): ThreadRouteId {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val parentMessageStr =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
    val parentMessage = parentMessageStr?.let(::MessageId)
    val stream = streamChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val source = sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
    val isReplyThread =
        surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
            (parentChannel != null && parentMessage != null)
    return when {
        isReplyThread && parentChannel != null && parentMessage != null -> {
            // v0.2 contract: ReplyThread requires explicit threadChannelId. If stream is
            // missing, we still construct ReplyThread (callers handle missing stream via
            // #275-style `activity_thread_channel_missing` internal state + refresh).
            val resolvedStream = stream ?: source?.let { ChannelId(it.value) }
            if (resolvedStream != null) {
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = resolvedStream,
                    serverId = serverId
                )
            } else {
                // Fallback: synthesize from source threadId to keep v0.2 proof path alive.
                val synthesized = source?.let { ChannelId(it.value) } ?: parentChannel
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = synthesized,
                    serverId = serverId
                )
            }
        }
        else -> ThreadRouteId.ChannelThread(
            channelId = source?.let { ChannelId(it.value) } ?: parentChannel ?: ChannelId(""),
            serverId = serverId
        )
    }
}

private fun KuiklyThreadScreenPayload.visibleReadMarker(): ThreadVisibleReadMarker? {
    val channelId = cacheThreadId() ?: return null
    val latest = messages.lastOrNull() ?: return null
    return ThreadVisibleReadMarker(
        channelId = channelId,
        latestMessageId = latest.id,
        latestSeq = latest.seq
    )
}

private fun KuiklyThreadScreenPayload.applyRealtimeEvent(
    event: RealtimeEvent,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId? = null,
    shouldPreserveReactionsForRealtime: (Message, String?) -> Boolean = { _, _ -> false }
): KuiklyThreadScreenPayload =
    when (event) {
        is MessageNew ->
            applyRealtimeMessages(listOf(event.message), currentUserId, activeReplyParentMessageId)
        is MessageUpdated ->
            applyRealtimeMessageUpdate(
                message = event.message,
                currentUserId = currentUserId,
                activeReplyParentMessageId = activeReplyParentMessageId,
                preserveExistingReactions = shouldPreserveReactionsForRealtime(event.message, currentUserId)
            )
        is SyncResumeResponse ->
            applyRealtimeMessages(event.messages, currentUserId, activeReplyParentMessageId)
        is ThreadUpdated ->
            applyThreadUpdated(event)
        is MemberPresenceUpdated ->
            this
        else -> this
    }

private fun KuiklyThreadScreenPayload.realtimeNewerGapServerSeq(
    event: RealtimeEvent,
    activeReplyParentMessageId: MessageId? = null
): Long? {
    val currentMaxSeq = messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
    if (currentMaxSeq <= 0L) return null
    val existingIds = messages.mapTo(mutableSetOf()) { it.id }
    val eventMessages =
        when (event) {
            is MessageNew -> listOf(event.message)
            is SyncResumeResponse -> event.messages
            else -> return null
        }
    return eventMessages
        .asSequence()
        .filter { message -> message.matchesSharedThread(this, activeReplyParentMessageId) }
        .filterNot { message -> message.id.value in existingIds }
        .mapNotNull { message -> message.seq }
        .filter { seq -> seq > currentMaxSeq + 1L }
        .maxOrNull()
}

private fun KuiklyThreadScreenPayload.applyRealtimeMessages(
    messages: List<Message>,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?
): KuiklyThreadScreenPayload {
    val currentMaxSeq = this.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
    val existingIds = this.messages.mapTo(mutableSetOf()) { it.id }
    val relevant =
        messages.filter { message ->
            message.matchesSharedThread(this, activeReplyParentMessageId)
        }.filter { message ->
            if (!canLoadMoreAfter) return@filter true
            if (message.id.value in existingIds) return@filter true
            val seq = message.seq ?: return@filter true
            currentMaxSeq <= 0L || seq <= currentMaxSeq + 1L
        }
    if (relevant.isEmpty()) return this
    val incoming = relevant.map { message -> message.toKuiklyThreadMessageItem(currentUserId) }
    val baseMessages = this.messages.absorbingCanonicalSendEchoes(incoming)
    val merged =
        (baseMessages + incoming)
            .distinctBy { item -> item.id }
    return copy(
        status = "content",
        errorCode = null,
        messages = merged.sortedByThreadTimeline()
    ).withOriginReplyCountAtLeastLoadedReplies()
        .asLoadedEmptyReplyThreadPayload()
}

private fun KuiklyThreadScreenPayload.mergePagedMessages(
    pageMessages: List<KuiklyThreadMessageItem>,
    before: Boolean
): KuiklyThreadScreenPayload {
    val baseMessages = messages.absorbingCanonicalSendEchoes(pageMessages)
    val incoming = pageMessages.filterNot { pageMessage -> baseMessages.any { it.id == pageMessage.id } }
    val mergedMessages =
        if (before) {
            (incoming + baseMessages).distinctBy { it.id }
        } else {
            (baseMessages + incoming).distinctBy { it.id }
        }.sortedByThreadTimeline()
    val hasMore = incoming.size >= SharedThreadPaging.pageSize
    return copy(
        status = "content",
        errorCode = null,
        messages = mergedMessages,
        canLoadMoreBefore = if (before) hasMore else canLoadMoreBefore,
        canLoadMoreAfter = if (before) canLoadMoreAfter else hasMore,
        olderMessagesStatus = if (before) "idle" else olderMessagesStatus,
        newerMessagesStatus = if (before) newerMessagesStatus else "idle"
    ).withOriginReplyCountAtLeastLoadedReplies()
        .asLoadedEmptyReplyThreadPayload()
}

private fun KuiklyThreadScreenPayload.withLocalSendStarted(
    message: KuiklyThreadMessageItem
): KuiklyThreadScreenPayload {
    val sendingMessage =
        message.copy(
            deliveryState = ThreadMessageDeliverySending,
            pendingSendErrorCode = null,
            pendingSendErrorMessage = null
        )
    val nextMessages =
        if (messages.any { it.id == sendingMessage.id }) {
            messages.map { existing -> if (existing.id == sendingMessage.id) sendingMessage else existing }
        } else {
            messages + sendingMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = nextMessages.sortedByThreadTimeline()
    ).withOriginReplyCountAtLeastLoadedReplies()
}

private fun KuiklyThreadScreenPayload.withLocalSendFailure(
    messageId: String,
    errorCode: String?,
    errorMessage: String?
): KuiklyThreadScreenPayload =
    copy(
        messages =
            messages.map { message ->
                if (message.id == messageId && message.pendingSendContext != null) {
                    message.copy(
                        deliveryState = ThreadMessageDeliveryFailed,
                        pendingSendErrorCode = errorCode,
                        pendingSendErrorMessage = errorMessage
                    )
                } else {
                    message
                }
            }
    )

private fun KuiklyThreadScreenPayload.withoutLocalSendMessage(messageId: String): KuiklyThreadScreenPayload =
    copy(messages = messages.filterNot { it.id == messageId && it.pendingSendContext != null })

private fun KuiklyThreadScreenPayload.withoutLocalFailedSendMessage(messageId: String): KuiklyThreadScreenPayload =
    copy(messages = messages.filterNot { it.id == messageId && it.isPersistentLocalFailedMessage() })

private fun KuiklyThreadScreenPayload.withoutTransientLocalSendingMessages(): KuiklyThreadScreenPayload {
    val retainedMessages = messages.filterNot { it.isTransientLocalSendingMessage() }
    return if (retainedMessages.size == messages.size) this else copy(messages = retainedMessages)
}

private fun List<KuiklyThreadMessageItem>.withRetainedTransientLocalSendingMessagesFrom(
    previousMessages: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    val existingIds = map { it.id }.toSet()
    val retainedSendingMessages =
        previousMessages.filter { message ->
            message.id !in existingIds && message.isTransientLocalSendingMessage()
        }
    return if (retainedSendingMessages.isEmpty()) this else (this + retainedSendingMessages).sortedByThreadTimeline()
}

private fun List<KuiklyThreadMessageItem>.withRetainedLocalFailedMessagesFrom(
    previousMessages: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    val existingIds = map { it.id }.toSet()
    val retainedFailedMessages =
        previousMessages.filter { message ->
            message.id !in existingIds && message.isPersistentLocalFailedMessage()
        }
    return if (retainedFailedMessages.isEmpty()) this else (this + retainedFailedMessages).sortedByThreadTimeline()
}

private fun KuiklyThreadMessageItem.isPersistentLocalFailedMessage(): Boolean =
    id.startsWith(ThreadTransientLocalSendIdPrefix) &&
        deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) &&
        pendingSendContext != null

private fun KuiklyThreadScreenPayload.trimToLatestThreadWindow(limit: Int): KuiklyThreadScreenPayload {
    if (limit <= 0 || messages.isEmpty()) return withOriginReplyCountAtLeastLoadedReplies()
    val sortedMessages = messages.sortedByThreadTimeline()
    if (sortedMessages.size <= limit) {
        return (if (sortedMessages == messages) this else copy(messages = sortedMessages))
            .withOriginReplyCountAtLeastLoadedReplies()
    }
    val projectedOrigin =
        originMessage?.let { origin ->
            if (sortedMessages.size > origin.replyCount) {
                origin.copy(replyCount = sortedMessages.size)
            } else {
                origin
            }
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = sortedMessages.takeLast(limit),
        originMessage = projectedOrigin,
        canLoadMoreBefore = true,
        olderMessagesStatus = "idle"
    ).withOriginReplyCountAtLeastLoadedReplies()
}

internal fun KuiklyThreadScreenPayload.withOriginReplyCountAtLeastLoadedReplies(): KuiklyThreadScreenPayload {
    val origin = originMessage ?: return this
    val loadedReplyCount = messages.size
    if (loadedReplyCount <= origin.replyCount) return this
    return copy(originMessage = origin.copy(replyCount = loadedReplyCount))
}

private fun List<KuiklyThreadMessageItem>.sortedByThreadTimeline(): List<KuiklyThreadMessageItem> =
    sortedWith(
        Comparator { left, right ->
            val leftLocal = left.hasLocalSendState()
            val rightLocal = right.hasLocalSendState()
            if (leftLocal != rightLocal) {
                return@Comparator if (leftLocal) 1 else -1
            }
            if (left.seq != null && right.seq != null) {
                compareValues(left.seq, right.seq).takeIf { it != 0 }?.let { return@Comparator it }
            }
            left.timestampLabel.compareTo(right.timestampLabel).takeIf { it != 0 }
                ?: left.id.compareTo(right.id)
        }
    )

private fun KuiklyThreadScreenPayload.applyRealtimeMessageUpdate(
    message: Message,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?,
    preserveExistingReactions: Boolean = false
): KuiklyThreadScreenPayload {
    val incoming = message.toKuiklyThreadMessageItem(currentUserId)
    val matchesTimeline = messages.any { it.id == incoming.id }
    val matchesOrigin = originMessage?.id == incoming.id
    val replyThreadContentChannelId = replyThreadContentChannelId()
    val matchesReplyOrigin =
        replyThreadContentChannelId != null &&
            (
                matchesOrigin ||
                    parentMessageId?.takeIf { it.isNotBlank() } == incoming.id ||
                    originParentMessageId?.takeIf { it.isNotBlank() } == incoming.id
                )
    val matchesThreadContent =
        if (matchesReplyOrigin) {
            false
        } else if (replyThreadContentChannelId != null) {
            message.belongsToReplyThreadContent(replyThreadContentChannelId)
        } else {
            message.matchesSharedThread(this, activeReplyParentMessageId)
        }
    val shouldUpdateTimeline = matchesTimeline || matchesThreadContent
    if (!matchesOrigin && !shouldUpdateTimeline) return this
    val reactionUpdate = message.reactions.toRealtimeThreadReactionItems(currentUserId, threadReactorNameById())
    val updatedMessages =
        if (shouldUpdateTimeline) {
            messages.map { item ->
                if (item.id == incoming.id) {
                    item.mergeRealtimeUpdate(
                        update = incoming,
                        reactionUpdate = reactionUpdate,
                        preserveExistingReactions = preserveExistingReactions
                    )
                } else {
                    item
                }
            }
        } else {
            messages
        }
    val mergedMessages =
        if (!shouldUpdateTimeline || updatedMessages.any { it.id == incoming.id }) {
            updatedMessages
        } else {
            updatedMessages + incoming
        }
    val updatedOrigin =
        if (matchesOrigin) {
            originMessage.mergeRealtimeUpdate(
                update = incoming,
                reactionUpdate = reactionUpdate,
                preserveExistingReactions = preserveExistingReactions
            )
        } else {
            originMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = mergedMessages.distinctBy { it.id },
        originMessage = updatedOrigin
    ).withOriginReplyCountAtLeastLoadedReplies()
}

private fun KuiklyThreadScreenPayload.applyThreadUpdated(event: ThreadUpdated): KuiklyThreadScreenPayload {
    fun KuiklyThreadMessageItem.updateIfMatches(): KuiklyThreadMessageItem =
        if (id == event.parentMessageId.value) {
            copy(
                threadChannelId = event.threadChannelId.value,
                replyCount = event.replyCount,
                unreadReplyCount = event.unreadCount ?: unreadReplyCount,
                firstUnreadReplyMessageId =
                    when {
                        event.unreadCount == 0 -> null
                        event.firstUnreadMessageId != null -> event.firstUnreadMessageId.value
                        else -> firstUnreadReplyMessageId
                    }
            )
        } else {
            this
        }
    val updatedMessages = messages.map { it.updateIfMatches() }
    val updatedOrigin = originMessage?.updateIfMatches()
    if (updatedMessages == messages && updatedOrigin == originMessage) return this
    return copy(messages = updatedMessages, originMessage = updatedOrigin)
        .withOriginReplyCountAtLeastLoadedReplies()
}

internal fun KuiklyThreadScreenPayload.withActionCard(messageId: String, card: ThreadActionCard): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        }
    )

private fun KuiklyThreadMessageItem.withActionCard(card: ThreadActionCard): KuiklyThreadMessageItem =
    copy(metadata = metadata.filterKeys { !it.startsWith("actionCard.") } + card.toMessageMetadata())

private fun List<String>.cleanActionCardIds(): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun KuiklyThreadScreenPayload.withContentSliceFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
    return copy(
        status = patch.status,
        backgroundRefreshing = patch.backgroundRefreshing,
        messages = patch.messages,
        originMessage = patch.originMessage,
        originPreview = patch.originPreview,
        originParentMessageId = patch.originParentMessageId,
        originStatus = patch.originStatus,
        originErrorCode = patch.originErrorCode,
        knownMarkdownChannelNames =
            patch.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
        canLoadMoreBefore = patch.canLoadMoreBefore,
        canLoadMoreAfter = patch.canLoadMoreAfter,
        olderMessagesStatus = patch.olderMessagesStatus,
        newerMessagesStatus = patch.newerMessagesStatus,
        errorCode = patch.errorCode,
        emptyTitle = patch.emptyTitle,
        emptyDetail = patch.emptyDetail,
        dmPeerName = dmPeerName,
        dmPeerAvatarUrl = dmPeerAvatarUrl,
        dmPeerAvatarLabel = dmPeerAvatarLabel
    ).withOriginReplyCountAtLeastLoadedReplies()
}

internal fun KuiklyThreadScreenPayload.withThreadContentProjectionFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    withResolvedReplyThreadIdentityFrom(patch)
        .withContentSliceFrom(patch)
        .withTaskSliceFrom(patch)

internal fun KuiklyThreadScreenPayload.withCanonicalConversationAccessFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    copy(
        joined = patch.joined,
        canSend = patch.canSend,
        composer = composer.copy(enabled = patch.composer.enabled)
    )

internal fun KuiklyThreadScreenPayload.withInitialHomeConversationAccessFallback(
    candidate: ConversationAccessCandidate?
): KuiklyThreadScreenPayload {
    candidate ?: return this
    if (!acceptsInitialConversationAccessCandidate(candidate)) return this
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
        // Reply capability belongs to the parent Channel/DM, not to the
        // synthetic thread conversation returned with reply content.
        return copy(
            joined = candidate.joined,
            canSend = candidate.permissions.canSend,
            composer =
                composer.copy(
                    enabled = candidate.joined && candidate.permissions.canSend
                )
        )
    }
    if (candidate.joined) return this
    return copy(
        joined = false,
        canSend = candidate.permissions.canSend,
        composer = composer.copy(enabled = false)
    )
}

private fun ThreadAccessResolution.afterRefreshResult(
    result: DataResult<*>,
    canonicalAccessResolved: Boolean,
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?,
    requiresParentAccessCandidate: Boolean
): ThreadAccessResolution =
    when (result) {
        is DataResult.Content ->
            when {
                this == ThreadAccessResolution.Resolved -> ThreadAccessResolution.Resolved
                !canonicalAccessResolved || !homeLookupComplete -> ThreadAccessResolution.Loading
                requiresParentAccessCandidate && homeAccessCandidate == null -> ThreadAccessResolution.Error
                else -> ThreadAccessResolution.Resolved
            }
        is DataResult.Empty ->
            when {
                this == ThreadAccessResolution.Resolved -> ThreadAccessResolution.Resolved
                homeAccessCandidate != null -> ThreadAccessResolution.Resolved
                homeLookupComplete -> ThreadAccessResolution.Error
                else -> ThreadAccessResolution.Loading
            }
        is DataResult.NoPermission -> ThreadAccessResolution.NoPermission
        is DataResult.Error -> if (this == ThreadAccessResolution.Resolved) this else ThreadAccessResolution.Error
    }

internal fun KuiklyThreadScreenPayload.withTaskSliceFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(tasks = patch.tasks)

internal fun KuiklyThreadScreenPayload.withResolvedReplyThreadIdentityFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    if (patch.hasResolvedReplyThreadStreamFrom(this)) {
        copy(
            instanceId = patch.instanceId,
            title = patch.title,
            subtitle = patch.subtitle,
            sourceThreadId = patch.sourceThreadId,
            streamChannelId = patch.streamChannelId,
            parentChannelId = patch.parentChannelId,
            parentMessageId = patch.parentMessageId,
            composer =
                composer.copy(
                    streamChannelId =
                        patch.composer.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: patch.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: patch.sourceThreadId?.takeIf { it.isNotBlank() }
                )
        )
    } else {
        this
    }

internal fun KuiklyThreadScreenPayload.withMembersPatchFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(
        membersCount = patch.membersCount,
        members = patch.members,
        memberCandidates = patch.memberCandidates,
        canAddMembers = patch.canAddMembers,
        addMemberStatus = patch.addMemberStatus,
        addMemberErrorCode = patch.addMemberErrorCode,
        removeMemberStatus = patch.removeMemberStatus,
        removingMemberId = patch.removingMemberId,
        removeMemberErrorCode = patch.removeMemberErrorCode
    )

internal fun KuiklyThreadScreenPayload.withMessageReaction(
    messageId: String,
    emoji: String,
    selected: Boolean
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withMessageReactions(
    messageId: String,
    reactions: List<KuiklyThreadReactionItem>
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withMessageSaved(
    messageId: String,
    saved: Boolean
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withSavedMessageIds(savedIds: Set<String>): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message -> message.copy(isSaved = message.id in savedIds) },
        originMessage = originMessage?.let { message -> message.copy(isSaved = message.id in savedIds) }
    )

// task #569 follow-up: id -> display name over the thread's known members
// (roster + mention candidates) so reaction-tooltip name resolution can backfill
// reactor ids the mutation/realtime payload didn't name. The current user is in
// this roster, so their own just-added reaction resolves without a server round.
private fun KuiklyThreadScreenPayload.threadReactorNameById(): Map<String, String> {
    val byId = LinkedHashMap<String, String>()
    (members + memberCandidates).forEach { member ->
        val id = member.id.trim()
        val name = member.displayName.trim()
        if (id.isNotBlank() && name.isNotBlank() && !byId.containsKey(id)) byId[id] = name
    }
    return byId
}

private fun KuiklyThreadScreenPayload.withMessageMutationSummary(
    summary: MessageMutationSummary,
    currentUserId: String?,
    currentUserName: String? = null
): KuiklyThreadScreenPayload {
    val messageId = summary.messageId.trim()
    if (messageId.isBlank()) return this
    val reactions = summary.reactions.toThreadReactionItems(currentUserId, threadReactorNameById(), currentUserName)
    return copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        }
    )
}

private fun KuiklyThreadScreenPayload.withReplyThreadRead(
    parentMessageId: String,
    threadChannelId: String?
): KuiklyThreadScreenPayload {
    val cleanParentMessageId = parentMessageId.trim()
    if (cleanParentMessageId.isBlank()) return this
    val cleanThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    fun KuiklyThreadMessageItem.clearIfParent(): KuiklyThreadMessageItem {
        if (id != cleanParentMessageId) return this
        return copy(
            threadChannelId = cleanThreadChannelId ?: this.threadChannelId,
            unreadReplyCount = 0,
            firstUnreadReplyMessageId = null
        )
    }
    return copy(
        messages = messages.map { it.clearIfParent() },
        originMessage = originMessage?.clearIfParent()
    )
}

private fun KuiklyThreadScreenPayload.withMessageThreadFollowed(
    parentMessageId: String,
    threadChannelId: String?,
    followed: Boolean,
    replyCount: Int
): KuiklyThreadScreenPayload {
    val cleanParentMessageId = parentMessageId.trim()
    if (cleanParentMessageId.isBlank()) return this
    val cleanThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    fun KuiklyThreadMessageItem.updateIfParent(): KuiklyThreadMessageItem {
        if (id != cleanParentMessageId) return this
        return copy(
            threadChannelId = cleanThreadChannelId ?: this.threadChannelId,
            replyCount = maxOf(this.replyCount, replyCount),
            isThreadFollowed = followed
        )
    }
    return copy(
        messages = messages.map { it.updateIfParent() },
        originMessage = originMessage?.updateIfParent()
    )
}

private fun KuiklyThreadMessageItem.withReactionSelection(emoji: String, selected: Boolean): KuiklyThreadMessageItem {
    var matched = false
    val updated =
        reactions.mapNotNull { reaction ->
            if (reaction.emoji != emoji) return@mapNotNull reaction
            matched = true
            val delta =
                when {
                    selected && !reaction.selected -> 1
                    !selected && reaction.selected -> -1
                    else -> 0
                }
            val nextCount = (reaction.count + delta).coerceAtLeast(0)
            if (nextCount > 0) {
                reaction.copy(count = nextCount, selected = selected)
            } else {
                null
            }
        }
    val nextReactions =
        if (!matched && selected) {
            updated + KuiklyThreadReactionItem(emoji = emoji, count = 1, selected = true)
        } else {
            updated
        }
    return copy(reactions = nextReactions)
}

// task #569 follow-up: the who-reacted tooltip reads reactorNames off the
// KuiklyThreadReactionItem. The server's initial GET already carries names
// (messageService enrichWithSenderNames), but the addReaction mutation summary
// and any reduced payload can arrive with reactorNames empty while reactorIds
// is populated — so right after you tap a reaction the tooltip degraded to the
// bare "1 person" count. Resolve display names on-device (no server change):
// keep any server-provided names, then resolve remaining reactor ids: the
// current user's own id from the session identity (authoritative, always known
// regardless of roster completeness) and everyone else from the thread member
// directory. So a just-added self-reaction shows the name immediately, and
// other reactors resolve whenever they're in the roster. Degrades to the honest
// count when neither a name nor a resolvable id is available.
internal fun resolveThreadReactorNames(
    reactorIds: List<String>,
    reactorNames: List<String>,
    memberNameById: Map<String, String>,
    currentUserId: String? = null,
    currentUserName: String? = null
): List<String> {
    val cleanSelfId = currentUserId?.trim()?.takeIf { it.isNotBlank() }
    val cleanSelfName = currentUserName?.trim()?.takeIf { it.isNotBlank() }
    // Identity is the source of truth: dedup by reactor id (two distinct people
    // who happen to share a display name each count), then map each id to a name
    // — self from the session, others from the index-aligned server name or the
    // member directory. Unresolvable ids are dropped; the tooltip label's "+K"
    // remainder (max(count, names)) still reflects the true reactor count.
    val resolved = LinkedHashSet<String>()
    if (reactorIds.isEmpty()) {
        // Older/edge payload with names but no ids to anchor to — dedup by name.
        reactorNames.forEach { name -> name.trim().takeIf { it.isNotBlank() }?.let(resolved::add) }
        return resolved.toList()
    }
    val seenIds = HashSet<String>()
    reactorIds.forEachIndexed { index, rawId ->
        val id = rawId.trim()
        if (id.isBlank() || !seenIds.add(id)) return@forEachIndexed
        val name =
            if (id == cleanSelfId && cleanSelfName != null) {
                cleanSelfName
            } else {
                reactorNames.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }
                    ?: memberNameById[id]?.trim()?.takeIf { it.isNotBlank() }
            }
        name?.let(resolved::add)
    }
    return resolved.toList()
}

private fun List<MessageReactionSummary>.toThreadReactionItems(
    currentUserId: String?,
    memberNameById: Map<String, String> = emptyMap(),
    currentUserName: String? = null
): List<KuiklyThreadReactionItem> =
    mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        KuiklyThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds,
            reactorIds = reaction.reactorIds,
            reactorNames =
                if (memberNameById.isEmpty() && currentUserName.isNullOrBlank()) {
                    reaction.reactorNames
                } else {
                    resolveThreadReactorNames(
                        reaction.reactorIds,
                        reaction.reactorNames,
                        memberNameById,
                        currentUserId,
                        currentUserName
                    )
                }
        )
    }

private val PendingReactionRealtimeGuard.key: String
    get() = "$messageId\u0000$emoji"

private fun List<KuiklyThreadReactionItem>.selectedForEmoji(emoji: String): Boolean =
    any { reaction -> reaction.emoji == emoji && reaction.selected && reaction.count > 0 }

private fun List<MessageReaction>?.toRealtimeThreadReactionItems(
    currentUserId: String?,
    memberNameById: Map<String, String> = emptyMap()
): List<KuiklyThreadReactionItem>? =
    this?.mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        KuiklyThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds,
            reactorIds = reaction.reactorIds,
            reactorNames =
                if (memberNameById.isEmpty()) {
                    reaction.reactorNames
                } else {
                    resolveThreadReactorNames(reaction.reactorIds, reaction.reactorNames, memberNameById)
                }
        )
    }

private fun Message.matchesSharedThread(
    payload: KuiklyThreadScreenPayload,
    activeReplyParentMessageId: MessageId?
): Boolean {
    val replyThreadContentChannelId = payload.replyThreadContentChannelId()
    if (replyThreadContentChannelId != null) {
        return belongsToReplyThreadContent(replyThreadContentChannelId)
    }
    val selectedThreadId = payload.cacheThreadId()
    val originParentMessageId = payload.originParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
    return selectedThreadId?.let { selectedId ->
        threadId.value == selectedId ||
            channelId?.value == selectedId ||
            threadChannelId?.value == selectedId
    } == true ||
        originParentMessageId?.let { parentMessageId == it } == true ||
        activeReplyParentMessageId?.let { parentMessageId == it } == true
}

private fun KuiklyThreadMessageItem.mergeRealtimeUpdate(
    update: KuiklyThreadMessageItem,
    reactionUpdate: List<KuiklyThreadReactionItem>? = null,
    preserveExistingReactions: Boolean = false
): KuiklyThreadMessageItem {
    val currentAuthorId = authorId.resolvedRealtimeThreadAuthorIdOrNull()
    val incomingAuthorId = update.authorId.resolvedRealtimeThreadAuthorIdOrNull()
    // A message author is immutable. Sparse realtime patches have historically
    // carried an event actor id without matching author presentation fields;
    // accepting that id would leave the old name/avatar clickable as another
    // member. Only hydrate an unresolved author or refresh the same identity.
    val acceptsIncomingAuthor =
        incomingAuthorId != null && (currentAuthorId == null || currentAuthorId == incomingAuthorId)
    return copy(
        authorId = if (acceptsIncomingAuthor) update.authorId else authorId,
        authorName =
            if (acceptsIncomingAuthor) {
                update.authorName.takeUnless { it.isBlank() || it == update.authorId } ?: authorName
            } else {
                authorName
            },
        isOwn = if (acceptsIncomingAuthor) update.isOwn else isOwn,
        avatarLabel = if (acceptsIncomingAuthor) update.avatarLabel.ifBlank { avatarLabel } else avatarLabel,
        avatarWireValue = if (acceptsIncomingAuthor) update.avatarWireValue ?: avatarWireValue else avatarWireValue,
        timestampLabel = update.timestampLabel.ifBlank { timestampLabel },
        seq = update.seq ?: seq,
        messageType = update.messageType.ifBlank { messageType },
        systemEventType = update.systemEventType ?: systemEventType,
        metadata = update.metadata.ifEmpty { metadata },
        body = update.body.ifBlank { body },
        threadChannelId = update.threadChannelId ?: threadChannelId,
        replyCount = maxOf(replyCount, update.replyCount),
        unreadReplyCount = maxOf(unreadReplyCount, update.unreadReplyCount),
        firstUnreadReplyMessageId = update.firstUnreadReplyMessageId ?: firstUnreadReplyMessageId,
        isThreadFollowed = isThreadFollowed || update.isThreadFollowed,
        isSaved = isSaved || update.isSaved,
        taskId = update.taskId ?: taskId,
        taskNumber = update.taskNumber ?: taskNumber,
        taskStatus = update.taskStatus ?: taskStatus,
        taskAssigneeKind = update.taskAssigneeKind ?: taskAssigneeKind,
        taskAssigneeId = update.taskAssigneeId ?: taskAssigneeId,
        taskAssigneeName = update.taskAssigneeName ?: taskAssigneeName,
        reactions =
            when {
                preserveExistingReactions -> reactions
                reactionUpdate != null -> reactionUpdate.orderedLike(reactions)
                else -> update.reactions.ifEmpty { reactions }
            },
        attachments = mergeAttachmentCommentMetadata(update.attachments),
        commentRef = update.commentRef ?: commentRef
    )
}

private fun String.resolvedRealtimeThreadAuthorIdOrNull(): String? =
    trim().takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }

private fun KuiklyThreadMessageItem.mergeAttachmentCommentMetadata(
    updates: List<KuiklyThreadAttachmentItem>
): List<KuiklyThreadAttachmentItem> {
    if (updates.isEmpty()) return attachments
    if (attachments.isEmpty()) return updates
    val previousById = attachments.associateBy { it.id }
    return updates.map { update ->
        val previous = previousById[update.id]
        if (previous != null && update.commentCount == 0 && previous.commentCount > 0) {
            update.copy(commentCount = previous.commentCount)
        } else {
            update
        }
    }
}

private fun ThreadSnapshot.toKuiklyThreadPayload(
    base: KuiklyThreadScreenPayload,
    currentUserId: String?
): KuiklyThreadScreenPayload {
    val baseHasReplyThreadIdentity =
        base.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
            (
                base.parentChannelId?.isNotBlank() == true &&
                    (base.parentMessageId?.isNotBlank() == true || base.originParentMessageId?.isNotBlank() == true)
                )
    val baseTargetsParentConversation =
        !baseHasReplyThreadIdentity &&
            base.parentChannelId.isNullOrBlank() &&
            base.focusedMessageId?.isNotBlank() == true &&
            (
                base.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage ||
                    base.surfaceKind == KuiklyThreadSurfaceKind.Channel
                )
    val routeIsReplyThread =
        baseHasReplyThreadIdentity ||
            (
                !baseTargetsParentConversation &&
                    conversation.kind == ConversationKind.Thread
                )
    val routeParentMessageId =
        base.parentMessageId?.takeIf { it.isNotBlank() }
            ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
    val parentId =
        if (routeIsReplyThread) {
            routeParentMessageId
                ?: conversation.thread?.parentMessageId?.value
                ?: parentMessage?.id?.value
        } else {
            null
        }
    val resolvedParentMessage =
        parentMessage
            ?.takeIf { message -> parentId == null || message.id.value == parentId }
            ?.takeUnless { it.isSparseReplyOriginParentCandidate() }
            ?: parentId?.let { id ->
                messages.firstOrNull { message ->
                    message.id.value == id && !message.isSparseReplyOriginParentCandidate()
                }
            }
    val surfaceKind =
        when {
            conversation.kind == ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            routeIsReplyThread &&
                (resolvedParentMessage != null || !base.parentMessageId.isNullOrBlank() || !base.originParentMessageId.isNullOrBlank()) ->
                KuiklyThreadSurfaceKind.ReplyThread
            else -> base.surfaceKind
        }
    val channelKind =
        if (surfaceKind == KuiklyThreadSurfaceKind.Channel) {
            conversation.resolveThreadRouteIdentity().channelKind
                .withRouteChannelKindFallback(base.resolveThreadRouteIdentity().channelKind ?: base.channelKind)
                ?: base.channelKind
        } else {
            base.channelKind
        }
    val streamChannelId =
        base.streamChannelId
            ?: if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                conversation.thread?.streamChannelId?.value
            } else {
                conversation.channelId?.value ?: conversation.threadId.value
            }
    val parentChannelId =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.parentChannelId ?: conversation.thread?.parentChannelId?.value
        } else {
            null
        }
    val parentMessageId =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.parentMessageId ?: resolvedParentMessage?.id?.value ?: conversation.thread?.parentMessageId?.value
        } else {
            null
        }
    val parentChannelKind =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.parentChannelKind.normalizedThreadChannelKind()
                ?: conversation.threadParentChannelKind()
        } else {
            null
        }
    val sourceThreadId =
        base.sourceThreadId
            ?: if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                conversation.thread?.streamChannelId?.value
            } else {
                conversation.channelId?.value ?: conversation.threadId.value
            }
    val replyThreadContentChannelId =
        base.copy(
            surfaceKind = surfaceKind,
            sourceThreadId = sourceThreadId,
            streamChannelId = streamChannelId
        ).replyThreadContentChannelId()
    val parentConversationMessagePatch =
        if (baseTargetsParentConversation) {
            parentMessage?.toKuiklyThreadMessageItem(currentUserId)
        } else {
            null
        }
    val timelineMessages =
        if (
            parentConversationMessagePatch != null &&
            messages.none { message -> message.id.value == parentConversationMessagePatch.id }
        ) {
            messages + listOfNotNull(parentMessage)
        } else {
            messages
        }
    val cachedMessages =
        timelineMessages
            .filterNot { message -> parentId != null && message.id.value == parentId }
            .filter { message ->
                replyThreadContentChannelId == null || message.belongsToReplyThreadContent(replyThreadContentChannelId)
            }
            .map { message ->
                val item = message.toKuiklyThreadMessageItem(currentUserId)
                if (parentConversationMessagePatch?.id == item.id) {
                    item.mergeRealtimeUpdate(parentConversationMessagePatch)
                } else {
                    item
                }
            }
            .sortedByThreadTimeline()
    val isDirectMessage = surfaceKind == KuiklyThreadSurfaceKind.DirectMessage
    val safeDirectMessagePeerName = conversation.safeDisplayNameOrNull().takeIf { isDirectMessage }
    val headerContext =
        SharedThreadHeaderContextStore.conversationContext(
            surfaceKind = surfaceKind,
            conversation = conversation,
            originMessage = resolvedParentMessage?.toKuiklyThreadMessageItem(currentUserId),
            parentMessageId = parentId,
            originPending =
                surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                    resolvedParentMessage == null &&
                    parentId != null &&
                    base.originStatus == "loading"
        )
    val resolvedComposer =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.composer.copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                mentionChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                streamChannelId = streamChannelId
            )
        } else {
            base.composer
        }
    val taskChannelKind =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            parentChannelKind
        } else {
            channelKind
        }
    val supportsChannelTasks =
        conversation.permissions.canCreateTask &&
            !taskChannelKind.equals(JointChannelKindWireValue, ignoreCase = true)
    val resolvedCapabilityComposer =
        resolvedComposer.copy(
            showAsTaskToggle = resolvedComposer.showAsTaskToggle && supportsChannelTasks
        )
    return base.copy(
        title = headerContext.title,
        subtitle = headerContext.subtitle,
        status = if (cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty()) "empty" else "content",
        sourceThreadId = sourceThreadId,
        surfaceKind = surfaceKind,
        channelKind = channelKind ?: base.channelKind,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentChannelKind = parentChannelKind,
        memberScopedMentions = base.memberScopedMentions || conversation.usesMemberScopedMentions(),
        messages = cachedMessages,
        originMessage = headerContext.originMessage,
        originPreview = headerContext.originPreview,
        originParentMessageId = headerContext.originParentMessageId,
        focusedMessageId = base.focusedMessageId,
        focusedMessageRequestNonce = base.focusedMessageRequestNonce,
        originStatus = headerContext.originStatus,
        originErrorCode = headerContext.originErrorCode,
        emptyTitle = if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) ThreadReplyEmptyTitle else base.emptyTitle,
        emptyDetail = if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) ThreadReplyEmptyDetail else base.emptyDetail,
        composer = resolvedCapabilityComposer,
        showTaskOpenActions = base.showTaskOpenActions && supportsChannelTasks,
        showTaskStatusActions = base.showTaskStatusActions && supportsChannelTasks,
        tasks = tasks.map { task -> task.toKuiklyThreadTaskItem() },
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        joined = conversation.joined,
        canSend = conversation.permissions.canSend,
        membersCount = conversation.memberCount ?: base.membersCount,
        errorCode = null,
        dmPeerName = safeDirectMessagePeerName,
        dmPeerAvatarUrl = null,
        dmPeerAvatarLabel = safeDirectMessagePeerName?.take(2)?.uppercase(),
        activityMuted = conversation.activityMuted,
        muteFromSeq = conversation.muteFromSeq,
        activityMuteSupported = conversation.activityMuteSupported
    ).withOriginReplyCountAtLeastLoadedReplies()
}

private fun Message.isSparseReplyOriginParentCandidate(): Boolean =
    body.isBlank() && attachments.isEmpty()

private fun String?.withRouteChannelKindFallback(routeKind: String?): String? {
    val normalized = normalizedThreadChannelKind()
    val route = routeKind.normalizedThreadChannelKind()
    return if (normalized == KuiklyThreadSurfaceKind.Channel && route != null && route != KuiklyThreadSurfaceKind.Channel) {
        route
    } else {
        normalized ?: route
    }
}

private fun ThreadSnapshot.matchesFocusedThreadRoute(
    routeThreadId: ThreadId,
    focusedMessageId: String
): Boolean {
    val routeId = routeThreadId.value.takeIf { it.isNotBlank() } ?: return false
    val focusedMessage =
        messages.firstOrNull { message -> message.id.value == focusedMessageId }
            ?: parentMessage?.takeIf { message -> message.id.value == focusedMessageId }
            ?: return false
    return focusedMessage.channelId?.value == routeId || focusedMessage.threadId.value == routeId
}

private fun KuiklyThreadScreenPayload.focusedMessageContextChannelIdOrNull(): ChannelId? {
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
    return listOfNotNull(streamChannelId, sourceThreadId)
        .firstNotNullOfOrNull { value -> value.takeIf { it.isNotBlank() }?.let(::ChannelId) }
}

private fun ThreadSnapshot.withConversationAccessFallback(
    fallback: ConversationAccessCandidate?
): ThreadSnapshot {
    fallback ?: return this
    val current = conversation
    if (current.kind == ConversationKind.DirectMessage) return this
    if (fallback.joined || !current.matchesHeaderFallbackId(fallback.ids)) return this
    val nextConversation =
        current.copy(
            joined = false,
            permissions = fallback.permissions
        )
    return if (nextConversation == current) this else copy(conversation = nextConversation)
}

private fun KuiklyThreadScreenPayload.conversationAccessFallbackIds(): List<String> =
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
        listOfNotNull(replyParentAccessIdentityOrNull()?.first)
    } else {
        listOfNotNull(sourceThreadId, streamChannelId, parentChannelId)
            .mapNotNull { id -> id.takeIf { it.isNotBlank() } }
            .distinct()
    }

private fun KuiklyThreadScreenPayload.acceptsInitialConversationAccessCandidate(
    candidate: ConversationAccessCandidate
): Boolean {
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
        return acceptsConversationAccessCandidate(candidate)
    }
    val (parentId, parentKind) = replyParentAccessIdentityOrNull() ?: return false
    if (parentId !in candidate.ids) return false
    return candidate.conversationKind == parentKind
}

private fun KuiklyThreadScreenPayload.replyParentAccessIdentityOrNull(): Pair<String, ConversationKind>? {
    val routeIdentity = resolveThreadRouteIdentity()
    if (
        routeIdentity.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
        !routeIdentity.hasCompleteReplyIdentity
    ) {
        return null
    }
    val parentId = parentChannelId?.takeIf { it.isNotBlank() } ?: return null
    val parentMessage =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    if (routeIdentity.parentKey != "$parentId:$parentMessage") return null
    return parentId to (routeIdentity.parentConversationKind ?: return null)
}

private fun ConversationSummary.matchesHeaderFallbackId(ids: Set<String>): Boolean =
    headerFallbackIds().any { it in ids }

private fun ConversationSummary.headerFallbackIds(): Set<String> =
    listOfNotNull(
        threadId.value,
        channelId?.value,
        thread?.streamChannelId?.value
    ).filter { it.isNotBlank() }.toSet()

internal fun KuiklyThreadMessageItem.toMessage(
    threadId: ThreadId,
    channelId: ChannelId,
    currentUserId: String? = null,
    currentUserName: String? = null,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Message? {
    val authorMemberKind =
        authorKind.strictMemberKindOrNull(
            source = "thread_item_author",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.Projected,
        ) ?: return null
    val resolvedMessageType = messageType.strictMessageTypeOrNull("thread_item_message", diagnostics) ?: return null
    val hasTaskProjection = taskId != null || taskNumber != null || taskStatus != null
    val resolvedTaskStatus =
        if (hasTaskProjection) taskStatus.strictTaskStatusOrNull("thread_item_task", diagnostics) else null
    val taskSummary =
        if (hasTaskProjection && resolvedTaskStatus != null) {
            TaskSummary(
                id = TaskId(taskId ?: "task-${taskNumber ?: id}"),
                title = body.ifBlank { taskNumber?.let { "Task #$it" } ?: "Task" },
                status = resolvedTaskStatus,
                taskNumber = taskNumber,
                messageId = MessageId(id),
                channelId = channelId,
                threadId = threadId,
                assignee = taskAssigneeId?.takeIf { it.isNotBlank() }?.let { id ->
                    val name = taskAssigneeName?.takeIf { it.isDisplaySafeTaskAssigneeName(id) } ?: id
                    taskAssigneeKind.strictMemberKindOrNull(
                        source = "thread_item_task_assignee",
                        diagnostics = diagnostics,
                        contract = MemberKindWireContract.Projected,
                    )
                        ?.let { kind ->
                            MemberProfile(
                                id = MemberId(id),
                                displayName = name,
                                handle = name.removePrefix("@"),
                                kind = kind,
                            )
                        }
                },
                updatedAtLabel = timestampLabel
            )
        } else {
            null
        }
    return Message(
        id = MessageId(id),
        threadId = threadId,
        channelId = channelId,
        threadChannelId = threadChannelId?.let(::ChannelId),
        author = toMemberProfile(authorMemberKind),
        seq = seq,
        timestampLabel = timestampLabel,
        body = body,
        messageType = resolvedMessageType,
        systemEventType = systemEventType,
        metadata = metadata.withLocalSendMetadata(pendingSendContext, pendingSendErrorCode, pendingSendErrorMessage),
        attachments = attachments.map { attachment -> attachment.toAttachment() },
        deliveryState = deliveryState.toMessageDeliveryState(),
        task = taskSummary,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.let(::MessageId),
        isThreadFollowed = isThreadFollowed,
        isSaved = isSaved,
        reactions = reactions.toMessageReactions(currentUserId, currentUserName),
        commentRef = commentRef?.toMessageCommentRef()
    )
}

private fun List<KuiklyThreadReactionItem>.toMessageReactions(
    currentUserId: String?,
    currentUserName: String?
): List<MessageReaction>? =
    mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        val reactorIds =
            reaction.reactorIds
                .plus(if (reaction.selected && currentUserId != null) listOf(currentUserId) else emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val reactorNames =
            reaction.reactorNames
                .plus(if (reaction.selected && !currentUserName.isNullOrBlank()) listOf(currentUserName) else emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        MessageReaction(
            emoji = emoji,
            count = count,
            reactorIds = reactorIds,
            reactorNames = reactorNames
        )
    }.takeIf { it.isNotEmpty() }

private fun KuiklyThreadMessageItem.toMemberProfile(kind: MemberKind): MemberProfile {
    val cleanAuthorDeletedAt = authorDeletedAt?.takeIf { it.isNotBlank() }
    val cleanMembershipStatus =
        senderMembershipStatus?.takeIf { it.isNotBlank() }
            ?: if (authorKind.equals("agent", ignoreCase = true) && cleanAuthorDeletedAt != null) "deleted" else null
    return MemberProfile(
        id = MemberId(authorId),
        displayName = authorName,
        handle = authorName.removePrefix("@"),
        kind = kind,
        avatarUrl = avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.take(1).uppercase() },
        presence = Presence.Offline,
        agentStatus = AgentStatus.fromWireValue(authorAgentStatus),
        membershipStatus = cleanMembershipStatus,
        agentDeletedAt = cleanAuthorDeletedAt
    )
}

private fun KuiklyThreadAttachmentItem.toAttachment(): Attachment =
    Attachment(
        id = AttachmentId(id),
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sizeLabel = sizeLabel,
        thumbnailUrl = thumbnailUrl,
        commentCount = commentCount
    )

internal fun StoredAuthSession.currentServerSlug(): String? =
    selectedServerSlug?.takeIf { it.isNotBlank() }
        ?: currentServerId
            ?.let { selectedId -> servers.firstOrNull { it.id == selectedId }?.slug }
            ?.takeIf { it.isNotBlank() }
        ?: servers.firstOrNull()?.slug?.takeIf { it.isNotBlank() }

private fun KuiklyThreadMessageCommentRef.toMessageCommentRef(): MessageCommentRef? {
    val cleanAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val cleanFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentRef(
        attachmentId = AttachmentId(cleanAttachmentId),
        filename = cleanFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        hostSource = hostSource?.toMessageCommentHostSource(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    )
}

private fun KuiklyThreadMessageCommentHostSource.toMessageCommentHostSource(): MessageCommentHostSource? {
    val cleanType = type.commentHostTypeOrNull() ?: return null
    val cleanRouteKind = routeKind.commentHostRouteKindOrNull() ?: return null
    val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentHostSource(
        type = cleanType,
        routeKind = cleanRouteKind,
        channelId = ChannelId(cleanChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    )
}

internal fun Message.toKuiklyThreadMessageItem(currentUserId: String?): KuiklyThreadMessageItem {
    val pendingSendContext = metadata.localSendContextFromMetadata()
    val resolvedAvatar =
        resolveAvatarSlot(
            kind = author.kind,
            id = author.id.value,
            avatarWireValue = author.avatar?.wireValue,
            avatarUrl = author.avatarUrl
        )
    return KuiklyThreadMessageItem(
        id = id.value,
        authorId = author.id.value,
        authorName = author.displayName,
        authorHandle = author.handle,
        authorDescription =
            author.description?.takeIf { it.isNotBlank() }
                ?: author.roleDescription?.takeIf { it.isNotBlank() },
        authorKind =
            when (author.kind) {
                MemberKind.Agent -> "agent"
                MemberKind.System -> "system"
                else -> "human"
            },
        isOwn = currentUserId != null && author.id.value == currentUserId,
        avatarLabel = author.avatarLabel,
        avatarWireValue = resolvedAvatar.effectiveAvatarWireValue,
        senderMembershipStatus =
            senderMembershipStatus?.takeIf { it.isNotBlank() }
                ?: author.membershipStatus?.takeIf { it.isNotBlank() },
        authorDeletedAt = author.agentDeletedAt?.takeIf { it.isNotBlank() },
        authorAgentStatus = author.agentStatus?.name,
        seq = seq,
        timestampLabel = timestampLabel,
        messageType = if (messageType == MessageType.System) "System" else "Chat",
        systemEventType = systemEventType,
        metadata = metadata.withoutLocalSendMetadata(),
        body = body,
        randomId = randomId?.takeIf { it.isNotBlank() } ?: pendingSendContext?.randomId?.takeIf { it.isNotBlank() },
        threadChannelId = threadChannelId?.value,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.value,
        isThreadFollowed = isThreadFollowed,
        isSaved = isSaved,
        taskId = task?.id?.value,
        taskNumber = task?.taskNumber,
        taskStatus = task?.status?.toWireValue(),
        taskAssigneeKind = task?.assignee?.kind?.memberKindWireValue(),
        taskAssigneeId = task?.assignee?.id?.value,
        taskAssigneeName = task?.assignee?.displayName,
        deliveryState = deliveryState.toKuiklyThreadDeliveryState(),
        attachments = attachments.map { attachment ->
            KuiklyThreadAttachmentItem(
                id = attachment.id.value,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                sizeLabel = attachment.sizeLabel,
                thumbnailUrl = attachment.thumbnailUrl,
                commentCount = attachment.commentCount
            )
        },
        reactions = reactions.toRealtimeThreadReactionItems(currentUserId).orEmpty(),
        commentRef = commentRef?.toKuiklyThreadCommentRef(),
        forwardBundle = forwardBundle,
        pendingSendContext = pendingSendContext,
        pendingSendErrorCode = metadata[ThreadLocalSendErrorMetadataKey]?.takeIf { it.isNotBlank() },
        pendingSendErrorMessage = metadata[ThreadLocalSendErrorMessageMetadataKey]?.takeIf { it.isNotBlank() }
    )
}

private const val ThreadLocalSendContextMetadataKey = "localSend.context"
private const val ThreadLocalSendErrorMetadataKey = "localSend.errorCode"
private const val ThreadLocalSendErrorMessageMetadataKey = "localSend.errorMessage"

private val ThreadLocalSendMetadataJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private fun Map<String, String>.withLocalSendMetadata(
    context: KuiklyThreadPendingSendContext?,
    errorCode: String?,
    errorMessage: String?
): Map<String, String> {
    val metadata = withoutLocalSendMetadata().toMutableMap()
    if (context != null) {
        metadata[ThreadLocalSendContextMetadataKey] = ThreadLocalSendMetadataJson.encodeToString(context)
    }
    if (!errorCode.isNullOrBlank()) {
        metadata[ThreadLocalSendErrorMetadataKey] = errorCode
    }
    if (!errorMessage.isNullOrBlank()) {
        metadata[ThreadLocalSendErrorMessageMetadataKey] = errorMessage
    }
    return metadata
}

private fun Map<String, String>.withoutLocalSendMetadata(): Map<String, String> =
    filterKeys { key ->
        key != ThreadLocalSendContextMetadataKey &&
            key != ThreadLocalSendErrorMetadataKey &&
            key != ThreadLocalSendErrorMessageMetadataKey
    }

private fun Map<String, String>.localSendContextFromMetadata(): KuiklyThreadPendingSendContext? =
    get(ThreadLocalSendContextMetadataKey)
        ?.let { value ->
            runCatching {
                ThreadLocalSendMetadataJson.decodeFromString<KuiklyThreadPendingSendContext>(value)
            }.getOrNull()
        }

private fun String.toMessageDeliveryState(): MessageDeliveryState =
    when (trim().lowercase()) {
        ThreadMessageDeliverySending -> MessageDeliveryState.Sending
        ThreadMessageDeliveryFailed -> MessageDeliveryState.Failed
        else -> MessageDeliveryState.Sent
    }

private fun MessageDeliveryState.toKuiklyThreadDeliveryState(): String =
    when (this) {
        MessageDeliveryState.Sending -> ThreadMessageDeliverySending
        MessageDeliveryState.Failed -> ThreadMessageDeliveryFailed
        MessageDeliveryState.Sent -> ThreadMessageDeliverySent
    }

private fun MessageCommentRef.toKuiklyThreadCommentRef(): KuiklyThreadMessageCommentRef =
    KuiklyThreadMessageCommentRef(
        attachmentId = attachmentId.value,
        filename = filename,
        hostMessageId = hostMessageId?.value,
        hostSource = hostSource?.toKuiklyThreadCommentHostSource(),
        anchorLabel = anchorLabel
    )

private fun MessageCommentHostSource.toKuiklyThreadCommentHostSource(): KuiklyThreadMessageCommentHostSource =
    KuiklyThreadMessageCommentHostSource(
        type = type,
        routeKind = routeKind,
        channelId = channelId.value,
        parentMessageId = parentMessageId?.value,
        threadChannelId = threadChannelId?.value,
        rootThreadChannelId = rootThreadChannelId?.value
    )

private fun TaskSummary.toKuiklyThreadTaskItem(): KuiklyThreadTaskItem =
    KuiklyThreadTaskItem(
        id = id.value,
        title = title,
        status = status.toWireValue(),
        taskNumber = taskNumber,
        messageId = messageId?.value,
        assigneeId = assignee?.id?.value,
        assigneeName = assignee?.displayName,
        updatedAtLabel = updatedAtLabel
    )

private fun Throwable.safeThreadDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
