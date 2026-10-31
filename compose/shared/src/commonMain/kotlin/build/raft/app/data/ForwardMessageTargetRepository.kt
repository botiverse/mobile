package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.messageStreamChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val forwardTargetJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
enum class ForwardMessageTargetKind {
    RecentChannel,
    RecentDirectMessage,
    PublicChannelUnjoined,
    HumanDirectMessageCandidate,
    AgentDirectMessageCandidate
}

@Serializable
enum class ForwardMessageTargetRequiredAction(val wireValue: String?) {
    None(null),
    JoinChannel("join_channel"),
    CreateDirectMessage("create_dm")
}

enum class ForwardMessageSendMode(val wireValue: String) {
    Merged("merged"),
    Individual("individual")
}

@Serializable
data class ForwardMessageTarget(
    val serverId: ServerId,
    val kind: ForwardMessageTargetKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.toRaftAvatarLabel(),
    val channelId: ChannelId? = null,
    val channelKind: ConversationKind = ConversationKind.Channel,
    val channelType: String? = null,
    val jointChannelId: String? = null,
    val memberId: MemberId? = null,
    val joined: Boolean? = null,
    val dmExists: Boolean? = null,
    val canForwardNow: Boolean = channelId != null,
    val requiredAction: ForwardMessageTargetRequiredAction = ForwardMessageTargetRequiredAction.None
)

data class ForwardMessageTargetDirectoryCursor(
    val snapshotId: String? = null,
    val sessionId: String? = null,
    val normalizedQuery: String = "",
    val authorityRevision: String? = null,
    val directMessageOffset: Int = 0,
    val publicChannelOffset: Int = 0,
    val privateChannelOffset: Int = 0,
    val jointChannelOffset: Int = 0,
    val memberOffset: Int = 0
)

data class ForwardMessageTargetDirectoryPage(
    val items: List<ForwardMessageTarget>,
    val nextCursor: ForwardMessageTargetDirectoryCursor? = null,
    val reset: Boolean = false
)

internal data class ForwardMessageTargetDirectorySnapshot(
    val id: String,
    val serverId: ServerId,
    val sessionId: String,
    val normalizedQuery: String,
    val authorityRevision: String,
    val createdAtMillis: Long,
    val items: List<ForwardMessageTarget>
) {
    fun page(
        cursor: ForwardMessageTargetDirectoryCursor =
            ForwardMessageTargetDirectoryCursor(
                snapshotId = id,
                sessionId = sessionId,
                normalizedQuery = normalizedQuery,
                authorityRevision = authorityRevision
            ),
        pageSize: Int = 30
    ): ForwardMessageTargetDirectoryPage =
        items.toForwardDirectoryPage(
            cursor =
                cursor.copy(
                    snapshotId = id,
                    sessionId = sessionId,
                    normalizedQuery = normalizedQuery,
                    authorityRevision = authorityRevision
                ),
            pageSize = pageSize
        )
}

internal fun List<ForwardMessageTarget>.toForwardDirectorySnapshot(
    id: String,
    serverId: ServerId,
    sessionId: String,
    normalizedQuery: String = "",
    createdAtMillis: Long = 0L
): ForwardMessageTargetDirectorySnapshot =
    ForwardMessageTargetDirectorySnapshot(
        id = id,
        serverId = serverId,
        sessionId = sessionId,
        normalizedQuery = normalizedQuery,
        authorityRevision = forwardDirectoryAuthorityRevision(),
        createdAtMillis = createdAtMillis,
        items = dedupeForwardTargets()
    )

data class ForwardMessageDestination(
    val serverId: ServerId,
    val channelId: ChannelId,
    val channelKind: ConversationKind,
    val jointChannelId: String? = null,
    /**
     * task #803 provenance: for member-DM resolution this is the member the
     * resolver ACTUALLY resolved against (the API key parameter), letting the
     * send gate prove the wire destination belongs to the frozen member.
     */
    val resolvedMemberId: MemberId? = null
)

data class ForwardMessageExecuteRequest(
    val sourceServerId: ServerId,
    val sourceChannelId: ChannelId,
    val destination: ForwardMessageDestination,
    val messageIds: List<String>,
    val mode: ForwardMessageSendMode,
    val note: String = ""
) {
    val targetChannelId: ChannelId
        get() = destination.channelId
}

data class ForwardMessageSendResult(
    val message: Message?
)

data class ForwardMessageBatchExecuteRequest(
    val sourceServerId: ServerId,
    val sourceChannelId: ChannelId,
    val destinations: List<ForwardMessageDestination>,
    val messageIds: List<String>,
    val mode: ForwardMessageSendMode,
    val note: String,
    val requestId: String
)

data class ForwardMessageBatchDelivery(
    val destination: ForwardMessageDestination,
    val message: Message
)

data class ForwardMessageBatchFailure(
    val destination: ForwardMessageDestination,
    val code: String
)

data class ForwardMessageBatchResult(
    val deliveries: List<ForwardMessageBatchDelivery>,
    val failures: List<ForwardMessageBatchFailure>
)

interface ForwardMessageTargetRepository {
    suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>>
    suspend fun directoryTargets(
        serverId: ServerId,
        sessionId: String = serverId.value,
        cursor: ForwardMessageTargetDirectoryCursor = ForwardMessageTargetDirectoryCursor(),
        pageSize: Int = 30
    ): DataResult<ForwardMessageTargetDirectoryPage> =
        recentTargets(serverId, limit = pageSize).mapContent { items ->
            ForwardMessageTargetDirectoryPage(items = items)
        }
    fun releaseDirectorySnapshot(sessionId: String) = Unit
    /**
     * Authoritative search: the server's ranked `GET messages/forward/targets/search` list.
     * A failed/offline request degrades to [interimSearchTargets] matches.
     */
    suspend fun searchTargets(serverId: ServerId, query: String, limit: Int): DataResult<List<ForwardMessageTarget>>

    /**
     * Instant matches from DM/channel data already held in memory (or one DM+channel load when
     * cold). Never fetches the member directory; shown only until [searchTargets] answers.
     */
    suspend fun interimSearchTargets(serverId: ServerId, query: String, limit: Int): List<ForwardMessageTarget> =
        emptyList()

    /**
     * One keystroke's search: emits [ForwardTargetSearchUpdate.Interim] local matches at once, then
     * (after [serverDebounceMillis]) exactly one [ForwardTargetSearchUpdate.Final] with the server
     * answer. Cancelling the collector cancels the in-flight server request, so the caller's
     * restart-on-keystroke is the latest-wins gate; an interim that is still computing when the
     * server answers is cancelled and can never land after [ForwardTargetSearchUpdate.Final].
     */
    fun searchTargetsProgressively(
        serverId: ServerId,
        query: String,
        limit: Int,
        serverDebounceMillis: Long = 0L
    ): Flow<ForwardTargetSearchUpdate> =
        channelFlow {
            // UNDISPATCHED: a warm in-memory index answers before the server request is even sent.
            val interim =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val local = interimSearchTargets(serverId, query, limit)
                    if (local.isNotEmpty()) send(ForwardTargetSearchUpdate.Interim(local))
                }
            if (serverDebounceMillis > 0L) delay(serverDebounceMillis)
            val result = searchTargets(serverId, query, limit)
            interim.cancel()
            send(ForwardTargetSearchUpdate.Final(result))
        }
    suspend fun resolveTargetChannel(
        sourceServerId: ServerId,
        target: ForwardMessageTarget,
        allowJoin: Boolean = false
    ): DataResult<ForwardMessageDestination>
    suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<ForwardMessageSendResult>

    suspend fun forwardMessagesBatch(request: ForwardMessageBatchExecuteRequest): DataResult<ForwardMessageBatchResult> =
        DataResult.Error(ScreenError(code = "forward_batch_unsupported", retryable = false))
}

sealed interface ForwardTargetSearchUpdate {
    /** Local DM/channel matches; display-only while the server request is in flight. */
    data class Interim(val targets: List<ForwardMessageTarget>) : ForwardTargetSearchUpdate

    /** The server's ranked list (or the local fallback when the server failed). */
    data class Final(val result: DataResult<List<ForwardMessageTarget>>) : ForwardTargetSearchUpdate
}

/** Typing pause before the server search; local interim matches are shown without waiting. */
internal const val ForwardTargetServerSearchDebounceMillis = 200L

/** slock `MAX_FORWARD_SEARCH_LIMIT`; the server clamps anything larger. */
internal const val ForwardTargetServerSearchLimit = 20

internal const val ForwardTargetSearchPath = "messages/forward/targets/search"

class RealForwardMessageTargetRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val channelRepository: ChannelRepository,
    private val memberRepository: MemberRepository,
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val warningLogger: (String) -> Unit = {},
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ForwardMessageTargetRepository {
    private var nextDirectorySnapshotId = 0L
    private val directorySnapshots = mutableMapOf<String, ForwardMessageTargetDirectorySnapshot>()

    // One pre-normalised index per loaded authority. Search keystrokes filter it in
    // memory; only a cold/expired index reaches the channel/DM APIs, and the
    // mutex makes that fill single-flight.
    private val searchIndexMutex = Mutex()
    private var searchIndex: ForwardMessageTargetSearchIndex? = null
    private var searchIndexLoadedAtMillis = 0L

    override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> {
        currentSession() ?: return forwardAuthRequired()
        val directMessages = channelRepository.getDirectMessages(serverId).contentOrEmpty { it.directMessages }
        val channels = channelRepository.getChannels(serverId).contentOrEmpty { it.channels }
        // Never wait for the server member list here (the slowest source on a big server): DM
        // rows carry their own peer name/avatar; an already-loaded roster only refines them.
        val peerProfiles = cachedPeerProfiles(serverId)
        val items =
            (directMessages.mapNotNull { directMessage ->
                directMessage.toRecentForwardTarget(serverId, directMessage.presenceMemberId?.let(peerProfiles::get))
            } +
                channels.mapNotNull { it.toRecentForwardTarget(serverId) })
                .distinctBy { it.serverId.value + ":" + it.kind.name + ":" + it.id }
                .take(limit.coerceAtLeast(1))
        return DataResult.Content(items)
    }

    override suspend fun directoryTargets(
        serverId: ServerId,
        sessionId: String,
        cursor: ForwardMessageTargetDirectoryCursor,
        pageSize: Int
    ): DataResult<ForwardMessageTargetDirectoryPage> {
        currentSession() ?: return forwardAuthRequired()
        val now = nowMillis()
        pruneDirectorySnapshots(now)
        val targets =
            when (val authority = localDirectoryTargets(serverId)) {
                is DataResult.Content -> authority.data
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> return DataResult.Error(authority.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(authority.blocker)
            }
        val revision = targets.forwardDirectoryAuthorityRevision()
        storeSearchIndex(serverId, targets, now)
        val normalizedQuery = ""
        val requestedSnapshot =
            cursor.snapshotId?.let(directorySnapshots::get)?.takeIf { snapshot ->
                snapshot.serverId == serverId &&
                    snapshot.sessionId == sessionId &&
                    cursor.sessionId == sessionId &&
                    snapshot.normalizedQuery == normalizedQuery &&
                    cursor.normalizedQuery == normalizedQuery &&
                    snapshot.authorityRevision == cursor.authorityRevision &&
                    snapshot.authorityRevision == revision
            }
        val reset = cursor.snapshotId != null && requestedSnapshot == null
        val snapshot =
            requestedSnapshot
                ?: createLocalDirectorySnapshot(
                    serverId = serverId,
                    sessionId = sessionId,
                    normalizedQuery = normalizedQuery,
                    targets = targets,
                    nowMillis = now
                )
        val pageCursor =
            if (reset || cursor.snapshotId == null) {
                ForwardMessageTargetDirectoryCursor(
                    snapshotId = snapshot.id,
                    sessionId = snapshot.sessionId,
                    normalizedQuery = snapshot.normalizedQuery,
                    authorityRevision = snapshot.authorityRevision
                )
            } else {
                cursor
            }
        return DataResult.Content(
            snapshot.page(cursor = pageCursor, pageSize = pageSize).copy(reset = reset)
        )
    }

    override fun releaseDirectorySnapshot(sessionId: String) {
        directorySnapshots.entries.removeAll { it.value.sessionId == sessionId }
    }

    override suspend fun searchTargets(serverId: ServerId, query: String, limit: Int): DataResult<List<ForwardMessageTarget>> {
        currentSession() ?: return forwardAuthRequired()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return DataResult.Content(emptyList())
        val startedAt = nowMillis()
        var statusCode: Int? = null
        val failureCode =
            try {
                val response =
                    networkClient.request(
                        priority = DataRequestPriority.Foreground,
                        path = ForwardTargetSearchPath,
                        query =
                            listOf(
                                "q" to cleanQuery,
                                "limit" to limit.coerceIn(1, ForwardTargetServerSearchLimit).toString()
                            ),
                        serverIdOverride = serverId
                    )
                statusCode = response.code
                if (response.isSuccessful) {
                    val targets =
                        parseForwardMessageTargetSearchResults(response.bodyText.orEmpty(), fallbackServerId = serverId)
                            .take(limit.coerceAtLeast(1))
                    warningLogger(
                        "forward_target_search source=server q_len=${cleanQuery.length} results=${targets.size} " +
                            "durationMs=${nowMillis() - startedAt}"
                    )
                    return DataResult.Content(targets)
                }
                when (val failure = response.toForwardTargetDataError<List<ForwardMessageTarget>>()) {
                    is DataResult.NoPermission -> {
                        if (response.code == 401) {
                            warningLogger(
                                "forward_target_search source=server result=error code=${failure.blocker.code} " +
                                    "status=401 q_len=${cleanQuery.length} durationMs=${nowMillis() - startedAt}"
                            )
                            return failure
                        }
                        failure.blocker.code
                    }
                    is DataResult.Error -> failure.error.code
                    else -> "http_${response.code}"
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                if (statusCode == null) "network_unavailable" else "network_parse_failed"
            }
        // Server failure/offline: answer with the local DM/channel matches once; the next
        // keystroke (not a retry loop) is what asks the server again.
        val fallback = localSearchMatches(serverId, cleanQuery, limit)
        warningLogger(
            "forward_target_search source=server result=error code=$failureCode status=${statusCode ?: "none"} " +
                "q_len=${cleanQuery.length} fallback_results=${fallback.size} durationMs=${nowMillis() - startedAt}"
        )
        return if (fallback.isNotEmpty()) {
            DataResult.Content(fallback)
        } else {
            DataResult.Error(ScreenError(code = failureCode, retryable = true))
        }
    }

    override suspend fun interimSearchTargets(
        serverId: ServerId,
        query: String,
        limit: Int
    ): List<ForwardMessageTarget> {
        currentSession() ?: return emptyList()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val startedAt = nowMillis()
        val matches = localSearchMatches(serverId, cleanQuery, limit)
        warningLogger(
            "forward_target_search source=local_interim q_len=${cleanQuery.length} results=${matches.size} " +
                "durationMs=${nowMillis() - startedAt}"
        )
        return matches
    }

    private suspend fun localSearchMatches(
        serverId: ServerId,
        cleanQuery: String,
        limit: Int
    ): List<ForwardMessageTarget> {
        val index = localSearchIndexFor(serverId)
        return if (index.size > ForwardSearchInlineMaxTargets) {
            withContext(computeDispatcher) { index.search(cleanQuery, limit) }
        } else {
            index.search(cleanQuery, limit)
        }
    }

    /**
     * DM/channel-only index. Normally filled by the picker's directory load; when cold it costs
     * one DM + channel read (never the member directory), cached for the TTL even when a source
     * failed so an offline burst of keystrokes cannot turn into a request storm.
     */
    private suspend fun localSearchIndexFor(serverId: ServerId): ForwardMessageTargetSearchIndex =
        searchIndexMutex.withLock {
            val now = nowMillis()
            searchIndex
                ?.takeIf { it.serverId == serverId && now - searchIndexLoadedAtMillis < ForwardSearchIndexTtlMillis }
                ?: run {
                    val directMessages = channelRepository.getDirectMessages(serverId).contentOrEmpty { it.directMessages }
                    val channels = channelRepository.getChannels(serverId).contentOrEmpty { it.channels }
                    val targets =
                        fallbackForwardMessageTargets(
                            serverId = serverId,
                            directMessages = directMessages,
                            channels = channels,
                            query = "",
                            limit = directMessages.size + channels.size
                        )
                    buildSearchIndex(serverId, targets).also { built ->
                        searchIndex = built
                        searchIndexLoadedAtMillis = now
                    }
                }
        }

    private suspend fun storeSearchIndex(
        serverId: ServerId,
        targets: List<ForwardMessageTarget>,
        now: Long
    ) {
        // Only DMs/channels feed local search; people are the server search's job.
        val localTargets = targets.filter { it.isForwardLocalSearchTarget() }
        val revision = localTargets.forwardDirectoryAuthorityRevision()
        searchIndexMutex.withLock {
            val current = searchIndex
            if (current == null || current.serverId != serverId || current.authorityRevision != revision) {
                searchIndex = buildSearchIndex(serverId, localTargets, revision)
            }
            searchIndexLoadedAtMillis = now
        }
    }

    private suspend fun buildSearchIndex(
        serverId: ServerId,
        targets: List<ForwardMessageTarget>,
        revision: String = targets.forwardDirectoryAuthorityRevision()
    ): ForwardMessageTargetSearchIndex =
        if (targets.size > ForwardSearchInlineMaxTargets) {
            withContext(computeDispatcher) { ForwardMessageTargetSearchIndex.build(serverId, targets, revision) }
        } else {
            ForwardMessageTargetSearchIndex.build(serverId, targets, revision)
        }

    override suspend fun resolveTargetChannel(
        sourceServerId: ServerId,
        target: ForwardMessageTarget,
        allowJoin: Boolean
    ): DataResult<ForwardMessageDestination> {
        val session = currentSession() ?: return forwardAuthRequired()
        if (!session.canAccessForwardServer(sourceServerId)) {
            return DataResult.Error(ScreenError(code = "forward_source_server_unauthorized", retryable = false))
        }
        if (!session.canAccessForwardServer(target.serverId)) {
            return DataResult.Error(ScreenError(code = "forward_target_server_unauthorized", retryable = false))
        }
        val directChannelId = target.channelId
        if (directChannelId != null && target.requiredAction != ForwardMessageTargetRequiredAction.JoinChannel) {
            return resolveForwardDestination(
                sourceServerId = sourceServerId,
                target = target,
                destination = target.toForwardDestination(directChannelId)
            )
        }
        return when (target.requiredAction) {
            ForwardMessageTargetRequiredAction.None ->
                directChannelId?.let {
                    resolveForwardDestination(
                        sourceServerId = sourceServerId,
                        target = target,
                        destination = target.toForwardDestination(it)
                    )
                }
                    ?: DataResult.Error(ScreenError(code = "forward_target_missing_channel", retryable = false))
            ForwardMessageTargetRequiredAction.JoinChannel -> {
                if (!allowJoin) {
                    return DataResult.Error(ScreenError(code = "forward_join_confirmation_required", retryable = false))
                }
                if (target.serverId != sourceServerId || session.currentServerId != target.serverId.value) {
                    return DataResult.Error(ScreenError(code = "forward_target_server_not_selected", retryable = false))
                }
                val channelId = directChannelId
                    ?: return DataResult.Error(ScreenError(code = "forward_target_missing_channel", retryable = false))
                channelRepository.joinChannel(channelId).mapContent {
                    target.toForwardDestination(it.channelId)
                }
            }
            ForwardMessageTargetRequiredAction.CreateDirectMessage -> {
                if (target.serverId != sourceServerId || session.currentServerId != target.serverId.value) {
                    return DataResult.Error(ScreenError(code = "forward_target_server_not_selected", retryable = false))
                }
                val memberId = target.memberId
                    ?: return DataResult.Error(ScreenError(code = "forward_target_missing_member", retryable = false))
                val result =
                    when (target.kind) {
                        ForwardMessageTargetKind.AgentDirectMessageCandidate ->
                            channelRepository.openAgentDirectMessage(memberId)
                        ForwardMessageTargetKind.HumanDirectMessageCandidate ->
                            channelRepository.openUserDirectMessage(memberId)
                        else ->
                            DataResult.Error(ScreenError(code = "forward_target_cannot_create_dm", retryable = false))
                    }
                result.mapContent {
                    ForwardMessageDestination(
                        serverId = target.serverId,
                        channelId = it.conversation.toConversationSummary().messageStreamChannelId(),
                        channelKind = ConversationKind.DirectMessage,
                        resolvedMemberId = memberId
                    )
                }
            }
        }
    }

    override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<ForwardMessageSendResult> {
        currentSession() ?: return forwardAuthRequired()
        if (request.messageIds.isEmpty()) {
            return DataResult.Empty(EmptyReason.NoMessages)
        }
        return executeForwardTargets(
            source = "forward_messages",
            block = {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "messages/forward",
                    method = "POST",
                    serverIdOverride = request.destination.serverId,
                    bodyText = forwardTargetJson.encodeToString(request.toWireBody())
                )
            },
            mapper = { bodyText ->
                val message = parseSharedSentMessage(
                    bodyText = bodyText,
                    fallbackThreadId = ThreadId(request.destination.channelId.value),
                    diagnostics = warningLogger
                ) ?: error("forward response missing canonical message")
                ForwardMessageSendResult(message = message)
            }
        )
    }

    override suspend fun forwardMessagesBatch(request: ForwardMessageBatchExecuteRequest): DataResult<ForwardMessageBatchResult> {
        currentSession() ?: return forwardAuthRequired()
        val destinations = request.destinations.distinctBy { it.channelId.value }
        if (request.messageIds.isEmpty() || destinations.isEmpty() || request.requestId.isBlank()) {
            return DataResult.Empty(EmptyReason.NoMessages)
        }
        val executionServerId = destinations.first().serverId
        if (destinations.any { it.serverId != executionServerId }) {
            return DataResult.Error(ScreenError(code = "forward_cross_server_batch_unsupported", retryable = false))
        }
        return executeForwardTargets(
            source = "forward_messages_batch",
            block = {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "messages/forward",
                    method = "POST",
                    serverIdOverride = executionServerId,
                    bodyText = forwardTargetJson.encodeToString(
                        ForwardMessagesBatchRequestBody.serializer(),
                        ForwardMessagesBatchRequestBody(
                            destinationChannelIds = destinations.map { it.channelId.value },
                            requestId = request.requestId,
                            sourceMessageIds = request.messageIds,
                            note = request.note.trim()
                        )
                    )
                )
            },
            mapper = { bodyText ->
                val response = forwardTargetJson.decodeFromString(
                    ForwardMessagesBatchResponseBody.serializer(),
                    bodyText
                )
                val destinationByChannelId = destinations.associateBy { it.channelId.value }
                val expectedIds = destinationByChannelId.keys
                val returnedIds = response.results.map { it.destinationChannelId }
                require(returnedIds.size == expectedIds.size && returnedIds.toSet() == expectedIds) {
                    "forward batch response destinations do not match request"
                }
                val deliveries = mutableListOf<ForwardMessageBatchDelivery>()
                val failures = mutableListOf<ForwardMessageBatchFailure>()
                response.results.forEach { result ->
                    val destination = destinationByChannelId[result.destinationChannelId]
                        ?: error("forward batch response contains an unknown destination")
                    when (result.status) {
                        "failed" -> {
                            failures += ForwardMessageBatchFailure(
                                destination = destination,
                                code = result.code?.takeIf { it.isNotBlank() } ?: "forward_failed"
                            )
                            return@forEach
                        }
                        "success" -> Unit
                        else -> error("forward batch response contains an unknown status")
                    }
                    val message = result.message?.let {
                        parseSharedSentMessage(
                            bodyText = it.toString(),
                            fallbackThreadId = ThreadId(destination.channelId.value),
                            diagnostics = warningLogger
                        )
                    }
                    if (message == null || message.channelId != destination.channelId) {
                        failures += ForwardMessageBatchFailure(destination, "forward_response_invalid")
                    } else {
                        deliveries += ForwardMessageBatchDelivery(destination, message)
                    }
                }
                ForwardMessageBatchResult(deliveries = deliveries, failures = failures)
            }
        )
    }

    private suspend fun currentSession(): StoredAuthSession? =
        sessionStore.readSession()

    /**
     * Empty-query directory: DMs + channels only. The server member list is deliberately not
     * read (on a big server it dominated the picker's open time); people are reached by typing,
     * which goes to the server search, matching web.
     */
    private suspend fun localDirectoryTargets(serverId: ServerId): DataResult<List<ForwardMessageTarget>> {
        val directMessages =
            when (val result = channelRepository.getDirectMessages(serverId)) {
                is DataResult.Content -> result.data.directMessages
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
        val channels =
            when (val result = channelRepository.getChannels(serverId)) {
                is DataResult.Content -> result.data.channels
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
        val targets = fallbackForwardMessageTargets(
            serverId = serverId,
            directMessages = directMessages,
            channels = channels,
            peerProfiles = cachedPeerProfiles(serverId),
            query = "",
            limit = directMessages.size + channels.size
        )
        return DataResult.Content(targets)
    }

    /** Already-in-memory roster keyed by member id; never fetches. */
    private fun cachedPeerProfiles(serverId: ServerId): Map<MemberId, MemberProfile> =
        memberRepository.cachedMembers(serverId).associateBy { it.id }

    private fun createLocalDirectorySnapshot(
        serverId: ServerId,
        sessionId: String,
        normalizedQuery: String,
        targets: List<ForwardMessageTarget>,
        nowMillis: Long
    ): ForwardMessageTargetDirectorySnapshot {
        releaseDirectorySnapshot(sessionId)
        nextDirectorySnapshotId += 1
        val snapshot =
            targets.toForwardDirectorySnapshot(
                id = "${serverId.value}:$sessionId:$nextDirectorySnapshotId",
                serverId = serverId,
                sessionId = sessionId,
                normalizedQuery = normalizedQuery,
                createdAtMillis = nowMillis
            )
        if (directorySnapshots.size >= ForwardDirectorySnapshotCacheSize) {
            directorySnapshots.keys.firstOrNull()?.let(directorySnapshots::remove)
        }
        directorySnapshots[snapshot.id] = snapshot
        return snapshot
    }

    private fun pruneDirectorySnapshots(nowMillis: Long) {
        directorySnapshots.entries.removeAll { entry ->
            nowMillis - entry.value.createdAtMillis >= ForwardDirectorySnapshotTtlMillis
        }
    }

    private suspend fun resolveForwardDestination(
        sourceServerId: ServerId,
        target: ForwardMessageTarget,
        destination: ForwardMessageDestination
    ): DataResult<ForwardMessageDestination> {
        if (destination.serverId == sourceServerId) {
            return DataResult.Content(destination)
        }
        val jointId = target.jointChannelId?.trim()?.takeIf { it.isNotBlank() }
        if (target.channelKind != ConversationKind.Joint || jointId == null) {
            warningLogger(
                "forward_target_rejected reason=cross_server_non_joint " +
                    "sourceServerId=${sourceServerId.value} targetServerId=${target.serverId.value} " +
                    "targetKind=${target.channelKind.name}"
            )
            return DataResult.Error(
                ScreenError(code = "forward_cross_server_target_unsupported", retryable = false)
            )
        }
        val sourceProjection =
            when (val result = channelRepository.getChannels(sourceServerId)) {
                is DataResult.Content ->
                    result.data.channels.firstOrNull { row ->
                        row.joined &&
                            !row.archived &&
                            row.kind == ConversationKind.Joint &&
                            row.jointChannelId?.trim() == jointId
                    }
                is DataResult.Empty -> null
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
        if (sourceProjection == null) {
            warningLogger(
                "forward_target_rejected reason=joint_projection_unavailable " +
                    "sourceServerId=${sourceServerId.value} targetServerId=${target.serverId.value}"
            )
            return DataResult.Error(
                ScreenError(code = "forward_joint_projection_unavailable", retryable = false)
            )
        }
        val resolved =
            ForwardMessageDestination(
                serverId = sourceServerId,
                channelId = sourceProjection.toConversationSummary().messageStreamChannelId(),
                channelKind = ConversationKind.Joint,
                jointChannelId = jointId
            )
        warningLogger(
            "forward_target_projection_resolved sourceServerId=${sourceServerId.value} " +
                "selectedTargetServerId=${target.serverId.value} executionServerId=${resolved.serverId.value} " +
                "targetKind=${resolved.channelKind.name}"
        )
        return DataResult.Content(resolved)
    }

    private suspend fun <T> executeForwardTargets(
        source: String,
        block: suspend () -> HomeNetworkResponse,
        mapper: suspend (String) -> T
    ): DataResult<T> {
        var statusCode: Int? = null
        return try {
            val response = block()
            statusCode = response.code
            if (!response.isSuccessful) return response.toForwardTargetDataError()
            val body = response.bodyText.orEmpty()
            DataResult.Content(mapper(body))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} " +
                    "exception=${exception::class.simpleName}"
            )
            DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
        }
    }
}

private inline fun <T, R> DataResult<T>.contentOrEmpty(selector: (T) -> List<R>): List<R> =
    when (this) {
        is DataResult.Content -> selector(data)
        else -> emptyList()
    }

private inline fun <T, R> DataResult<T>.mapContent(mapper: (T) -> R): DataResult<R> =
    when (this) {
        is DataResult.Content -> DataResult.Content(mapper(data))
        is DataResult.Empty -> DataResult.Empty(reason)
        is DataResult.Error -> DataResult.Error(error)
        is DataResult.NoPermission -> DataResult.NoPermission(blocker)
    }

private fun HomeDirectMessageRow.toRecentForwardTarget(
    serverId: ServerId,
    peer: MemberProfile? = null
): ForwardMessageTarget? {
    val channelId = toConversationSummary().messageStreamChannelId()
    val title = name.trim().takeIf { it.isNotBlank() } ?: return null
    return ForwardMessageTarget(
        serverId = serverId,
        kind = ForwardMessageTargetKind.RecentDirectMessage,
        id = channelId.value,
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() } ?: "Recent DM",
        avatarWireValue =
            peer?.avatar?.wireValue
                ?: directMessagePeerAvatarWireValue?.takeIf { it.isNotBlank() },
        avatarLabel = peer?.avatarLabel?.takeIf { it.isNotBlank() } ?: title.toRaftAvatarLabel(),
        channelId = channelId,
        channelKind = ConversationKind.DirectMessage,
        channelType = "dm",
        memberId = presenceMemberId,
        dmExists = true,
        canForwardNow = true
    )
}

private fun HomeChannelRow.toRecentForwardTarget(serverId: ServerId): ForwardMessageTarget? {
    if (!joined || archived) return null
    val channelId = toConversationSummary().messageStreamChannelId()
    val title = name.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: return null
    return ForwardMessageTarget(
        serverId = serverId,
        kind = ForwardMessageTargetKind.RecentChannel,
        id = channelId.value,
        title = "#$title",
        subtitle = subtitle?.takeIf { it.isNotBlank() } ?: "Joined channel",
        avatarLabel = "#",
        channelId = channelId,
        channelKind = kind,
        channelType = channelType,
        jointChannelId = jointChannelId,
        joined = true,
        canForwardNow = true
    )
}

private fun HomeChannelRow.toSearchForwardTarget(serverId: ServerId): ForwardMessageTarget? {
    if (archived) return null
    val normalizedType = channelType?.trim()?.lowercase()
    if (!joined && normalizedType != "public") return null
    val channelId = toConversationSummary().messageStreamChannelId()
    val title = name.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: return null
    val requiresJoin = !joined
    return ForwardMessageTarget(
        serverId = serverId,
        kind = if (requiresJoin) ForwardMessageTargetKind.PublicChannelUnjoined else ForwardMessageTargetKind.RecentChannel,
        id = channelId.value,
        title = "#$title",
        subtitle = subtitle?.takeIf { it.isNotBlank() } ?: if (requiresJoin) "Public channel" else "Joined channel",
        avatarLabel = "#",
        channelId = channelId,
        channelKind = kind,
        channelType = channelType,
        jointChannelId = jointChannelId,
        joined = joined,
        canForwardNow = joined,
        requiredAction =
            if (requiresJoin) {
                ForwardMessageTargetRequiredAction.JoinChannel
            } else {
                ForwardMessageTargetRequiredAction.None
            }
    )
}

internal fun fallbackForwardMessageTargets(
    serverId: ServerId,
    directMessages: List<HomeDirectMessageRow>,
    channels: List<HomeChannelRow>,
    /** Optional DM-peer enrichment from an in-memory roster; never a source of extra targets. */
    peerProfiles: Map<MemberId, MemberProfile> = emptyMap(),
    query: String,
    limit: Int
): List<ForwardMessageTarget> {
    val matches =
        (
        directMessages.mapNotNull { directMessage ->
            directMessage.toRecentForwardTarget(serverId, directMessage.presenceMemberId?.let(peerProfiles::get))
        } +
            channels.mapNotNull { it.toSearchForwardTarget(serverId) }
        )
        .filter { it.matchesForwardQuery(query) }
        .dedupeForwardTargets()
    return if (query.isBlank()) {
        matches.take(limit.coerceAtLeast(1))
    } else {
        matches.toForwardDirectoryPage(pageSize = limit).items
    }
}

internal fun List<ForwardMessageTarget>.toForwardDirectoryPage(
    cursor: ForwardMessageTargetDirectoryCursor = ForwardMessageTargetDirectoryCursor(),
    pageSize: Int = 30
): ForwardMessageTargetDirectoryPage {
    val sectionSize = (pageSize.coerceAtLeast(5) / 5).coerceAtLeast(1)
    val directMessages = filter { it.kind == ForwardMessageTargetKind.RecentDirectMessage }
    val publicChannels = filter { it.isForwardPublicChannel() }
    val privateChannels = filter { it.isForwardPrivateChannel() }
    val jointChannels = filter { it.isForwardJointChannel() }
    val members = filter {
        it.kind == ForwardMessageTargetKind.HumanDirectMessageCandidate ||
            it.kind == ForwardMessageTargetKind.AgentDirectMessageCandidate
    }

    val directPage = directMessages.drop(cursor.directMessageOffset.coerceAtLeast(0)).take(sectionSize)
    val publicPage = publicChannels.drop(cursor.publicChannelOffset.coerceAtLeast(0)).take(sectionSize)
    val privatePage = privateChannels.drop(cursor.privateChannelOffset.coerceAtLeast(0)).take(sectionSize)
    val jointPage = jointChannels.drop(cursor.jointChannelOffset.coerceAtLeast(0)).take(sectionSize)
    val memberPage = members.drop(cursor.memberOffset.coerceAtLeast(0)).take(sectionSize)
    val next =
        ForwardMessageTargetDirectoryCursor(
            snapshotId = cursor.snapshotId,
            sessionId = cursor.sessionId,
            normalizedQuery = cursor.normalizedQuery,
            authorityRevision = cursor.authorityRevision,
            directMessageOffset = cursor.directMessageOffset + directPage.size,
            publicChannelOffset = cursor.publicChannelOffset + publicPage.size,
            privateChannelOffset = cursor.privateChannelOffset + privatePage.size,
            jointChannelOffset = cursor.jointChannelOffset + jointPage.size,
            memberOffset = cursor.memberOffset + memberPage.size
        )
    val hasMore =
        next.directMessageOffset < directMessages.size ||
            next.publicChannelOffset < publicChannels.size ||
            next.privateChannelOffset < privateChannels.size ||
            next.jointChannelOffset < jointChannels.size ||
            next.memberOffset < members.size
    return ForwardMessageTargetDirectoryPage(
        items = directPage + publicPage + privatePage + jointPage + memberPage,
        nextCursor = next.takeIf { hasMore }
    )
}

internal fun List<ForwardMessageTarget>.forwardDirectoryAuthorityRevision(): String {
    var hash = -0x340d631b7bdddcdbL
    forEach { target ->
        val witness =
            listOf(
                target.serverId.value,
                target.kind.name,
                target.id,
                target.channelId?.value.orEmpty(),
                target.channelKind.name,
                target.channelType.orEmpty(),
                target.jointChannelId.orEmpty(),
                target.memberId?.value.orEmpty(),
                target.joined?.toString().orEmpty(),
                target.requiredAction.name
            ).joinToString(separator = "\u001f")
        witness.forEach { character ->
            hash = hash xor character.code.toLong()
            hash *= 0x100000001b3L
        }
    }
    return hash.toString(16)
}

private const val ForwardDirectorySnapshotCacheSize = 4

internal const val ForwardSearchIndexTtlMillis = 60_000L
private const val ForwardSearchInlineMaxTargets = 400
internal const val ForwardDirectorySnapshotTtlMillis = 60_000L

private fun ForwardMessageTarget.isForwardLocalSearchTarget(): Boolean =
    kind != ForwardMessageTargetKind.HumanDirectMessageCandidate &&
        kind != ForwardMessageTargetKind.AgentDirectMessageCandidate

private fun ForwardMessageTarget.isForwardJointChannel(): Boolean =
    kind in setOf(ForwardMessageTargetKind.RecentChannel, ForwardMessageTargetKind.PublicChannelUnjoined) &&
        (channelKind == ConversationKind.Joint || channelType?.trim()?.lowercase() == "joint")

private fun ForwardMessageTarget.isForwardPrivateChannel(): Boolean =
    kind in setOf(ForwardMessageTargetKind.RecentChannel, ForwardMessageTargetKind.PublicChannelUnjoined) &&
        !isForwardJointChannel() && channelType?.trim()?.lowercase() == "private"

private fun ForwardMessageTarget.isForwardPublicChannel(): Boolean =
    kind in setOf(ForwardMessageTargetKind.RecentChannel, ForwardMessageTargetKind.PublicChannelUnjoined) &&
        !isForwardJointChannel() && !isForwardPrivateChannel()

private fun ForwardMessageTarget.matchesForwardQuery(query: String): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return true
    return forwardSearchValues().any { value -> value.contains(cleanQuery, ignoreCase = true) }
}

private fun List<ForwardMessageTarget>.dedupeForwardTargets(): List<ForwardMessageTarget> =
    distinctBy { it.serverId.value + ":" + it.kind.name + ":" + it.id }

internal fun parseForwardMessageTargetSearchResults(
    bodyText: String,
    fallbackServerId: ServerId
): List<ForwardMessageTarget> =
    parseForwardTargetSearchItems(bodyText)
        .mapNotNull { it.toForwardMessageTarget(fallbackServerId) }
        .dedupeForwardTargets()

@Serializable
internal data class ForwardTargetSearchItemDto(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val avatarUrl: String? = null,
    val avatarLabel: String? = null,
    val serverId: String? = null,
    val channelId: String? = null,
    val channelKind: String? = null,
    val channelType: String? = null,
    val jointChannelId: String? = null,
    val joined: Boolean? = null,
    val dmExists: Boolean? = null,
    val canForwardNow: Boolean = false,
    val requiredAction: String? = null
)

internal fun parseForwardTargetSearchItems(bodyText: String): List<ForwardTargetSearchItemDto> {
    if (bodyText.isBlank()) return emptyList()
    val element = forwardTargetJson.parseToJsonElement(bodyText)
    return when (element) {
        is JsonArray ->
            forwardTargetJson.decodeFromJsonElement(
                ListSerializer(ForwardTargetSearchItemDto.serializer()),
                element
            )
        // slock `GET /api/messages/forward/targets/search` answers `{ targets: [...] }`.
        is JsonObject ->
            (element["targets"] ?: element["items"])?.jsonArray?.let { items ->
                forwardTargetJson.decodeFromJsonElement(
                    ListSerializer(ForwardTargetSearchItemDto.serializer()),
                    items
                )
            } ?: emptyList()
        else -> emptyList()
    }
}

/**
 * Maps one server search row onto the same identity the local directory produces, so a row
 * selected from the interim list and the server's row for it share one selection key:
 * - `channel`: id = channel id; `joined=false` / `join_channel` -> [ForwardMessageTargetKind.PublicChannelUnjoined]
 *   + [ForwardMessageTargetRequiredAction.JoinChannel]; `channelType` keeps the wire type
 *   (`channel`/`private`/`joint`) exactly like [HomeChannelRow.channelType], `joint` -> [ConversationKind.Joint].
 * - `dm`: the server id is the PEER id; the local DM identity is the DM channel id, so id = channelId
 *   and the peer becomes [ForwardMessageTarget.memberId].
 * - `human`/`agent`: DM candidates keyed by member id, `create_dm` -> [ForwardMessageTargetRequiredAction.CreateDirectMessage].
 * Display titles drop the server's `@` prefix (local DM/member titles are bare names).
 */
private fun ForwardTargetSearchItemDto.toForwardMessageTarget(fallbackServerId: ServerId): ForwardMessageTarget? {
    val cleanId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanType = type.trim().lowercase()
    val cleanTitle =
        title.trim()
            .let { if (cleanType in ForwardTargetSearchPersonTypes) it.removePrefix("@").trim() else it }
            .takeIf { it.isNotBlank() } ?: return null
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: fallbackServerId
    val cleanChannelId = channelId?.trim()?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val wireChannelType = channelType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val wireAction = requiredAction.toForwardRequiredAction()
    val action =
        when {
            wireAction != ForwardMessageTargetRequiredAction.None -> wireAction
            cleanType == "channel" && joined == false -> ForwardMessageTargetRequiredAction.JoinChannel
            cleanType in setOf("human", "agent") && cleanChannelId == null && dmExists != true ->
                ForwardMessageTargetRequiredAction.CreateDirectMessage
            else -> ForwardMessageTargetRequiredAction.None
        }
    val kind =
        when (cleanType) {
            "dm" -> ForwardMessageTargetKind.RecentDirectMessage
            "human" -> ForwardMessageTargetKind.HumanDirectMessageCandidate
            "agent" -> ForwardMessageTargetKind.AgentDirectMessageCandidate
            "channel" ->
                if (joined == false || action == ForwardMessageTargetRequiredAction.JoinChannel) {
                    ForwardMessageTargetKind.PublicChannelUnjoined
                } else {
                    ForwardMessageTargetKind.RecentChannel
                }
            else -> return null
        }
    val isPerson = kind != ForwardMessageTargetKind.RecentChannel && kind != ForwardMessageTargetKind.PublicChannelUnjoined
    val targetId = if (kind == ForwardMessageTargetKind.RecentDirectMessage) cleanChannelId?.value ?: cleanId else cleanId
    return ForwardMessageTarget(
        serverId = cleanServerId,
        kind = kind,
        id = targetId,
        title = cleanTitle,
        // The server marks agent rows with the literal "agent"; the picker derives its own
        // localized helper copy from the kind/required action instead.
        subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() && !(isPerson && it.equals("agent", ignoreCase = true)) }.orEmpty(),
        avatarWireValue = avatarUrl?.trim()?.takeIf { it.isNotBlank() },
        avatarLabel = avatarLabel?.trim()?.takeIf { it.isNotBlank() } ?: cleanTitle.toRaftAvatarLabel(),
        channelId = cleanChannelId,
        channelKind =
            when (channelKind?.trim()?.lowercase()) {
                "joint" -> ConversationKind.Joint
                "thread" -> ConversationKind.Thread
                "dm", "direct_message" -> ConversationKind.DirectMessage
                else ->
                    when {
                        isPerson -> ConversationKind.DirectMessage
                        wireChannelType == "joint" -> ConversationKind.Joint
                        else -> ConversationKind.Channel
                    }
            },
        channelType =
            wireChannelType
                ?: channelKind?.trim()?.lowercase()?.takeIf { it == "public" || it == "private" || it == "joint" }
                ?: "dm".takeIf { isPerson },
        jointChannelId = jointChannelId?.trim()?.takeIf { it.isNotBlank() },
        memberId =
            when (kind) {
                ForwardMessageTargetKind.HumanDirectMessageCandidate,
                ForwardMessageTargetKind.AgentDirectMessageCandidate ->
                    MemberId(cleanId)
                ForwardMessageTargetKind.RecentDirectMessage ->
                    cleanId.takeIf { cleanChannelId != null && it != cleanChannelId.value }?.let(::MemberId)
                else -> null
            },
        joined = joined,
        dmExists = dmExists,
        canForwardNow = canForwardNow || (cleanChannelId != null && action == ForwardMessageTargetRequiredAction.None),
        requiredAction = action
    )
}

private val ForwardTargetSearchPersonTypes = setOf("dm", "human", "agent")

private fun String?.toForwardRequiredAction(): ForwardMessageTargetRequiredAction =
    when (this?.trim()?.lowercase()) {
        ForwardMessageTargetRequiredAction.JoinChannel.wireValue -> ForwardMessageTargetRequiredAction.JoinChannel
        ForwardMessageTargetRequiredAction.CreateDirectMessage.wireValue -> ForwardMessageTargetRequiredAction.CreateDirectMessage
        else -> ForwardMessageTargetRequiredAction.None
    }

@Serializable
private data class ForwardMessagesRequestBody(
    val destinationChannelId: String,
    val sourceMessageIds: List<String>,
    val note: String = ""
)

@Serializable
private data class ForwardMessagesBatchRequestBody(
    val destinationChannelIds: List<String>,
    val requestId: String,
    val sourceMessageIds: List<String>,
    val note: String = ""
)

@Serializable
private data class ForwardMessagesBatchResponseBody(
    val results: List<ForwardMessagesBatchResultBody> = emptyList()
)

@Serializable
private data class ForwardMessagesBatchResultBody(
    val destinationChannelId: String,
    val status: String,
    val message: JsonElement? = null,
    val code: String? = null
)

private fun ForwardMessageExecuteRequest.toWireBody(): ForwardMessagesRequestBody =
    ForwardMessagesRequestBody(
        destinationChannelId = targetChannelId.value,
        sourceMessageIds = messageIds,
        note = note.trim()
    )

private fun ForwardMessageTarget.toForwardDestination(channelId: ChannelId): ForwardMessageDestination =
    ForwardMessageDestination(
        serverId = serverId,
        channelId = channelId,
        channelKind = channelKind,
        jointChannelId = jointChannelId?.trim()?.takeIf { it.isNotBlank() }
    )

private fun StoredAuthSession.canAccessForwardServer(serverId: ServerId): Boolean =
    servers.any { it.id == serverId.value }

private fun <T> HomeNetworkResponse.toForwardTargetDataError(): DataResult<T> {
    val serverMessage = forwardTargetServerErrorTextOrNull()
    return when (code) {
        401 -> forwardAuthRequired(serverMessage)
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = forwardTargetErrorCode("server_forbidden"),
                    requiredCapability = "server_access",
                    serverMessage = serverMessage
                )
            )
        404 ->
            DataResult.Error(
                ScreenError(
                    code = forwardTargetErrorCode("forward_endpoint_unavailable"),
                    retryable = false,
                    serverMessage = serverMessage
                )
            )
        in 500..599 ->
            DataResult.Error(ScreenError(code = "server_unavailable", retryable = true, serverMessage = serverMessage))
        else ->
            DataResult.Error(
                ScreenError(
                    code = forwardTargetErrorCode("http_$code"),
                    retryable = false,
                    serverMessage = serverMessage
                )
            )
    }
}

private fun HomeNetworkResponse.forwardTargetErrorCode(fallback: String): String {
    val payload = bodyText ?: errorBodyText ?: return fallback
    return runCatching {
        forwardTargetJson.decodeFromString(ForwardTargetErrorBody.serializer(), payload)
            .code
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: fallback
}

@Serializable
private data class ForwardTargetErrorBody(
    val code: String? = null,
    val error: String? = null
)

private fun HomeNetworkResponse.forwardTargetServerErrorTextOrNull(): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        forwardTargetJson.decodeFromString(ForwardTargetErrorBody.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun forwardAuthRequired(): DataResult.NoPermission =
    authSessionRequiredResult()

private fun forwardAuthRequired(serverMessage: String?): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage)
