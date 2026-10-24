package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val inboxSavedSearchRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Done bodies omit absent frontier fields instead of encoding JSON null. */
internal val inboxDoneRequestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** The only `frontierSpace` the server accepts (routes/channels.ts:1497, 2014). */
internal const val InboxDoneFrontierSpaceStorage = "storage"

private suspend fun RaftNetworkClient.inbox(
    priority: DataRequestPriority,
    filter: InboxFilter,
    limit: Int,
    offset: Int,
    serverId: ServerId
): HomeNetworkResponse =
    request(
        priority,
        "channels/inbox",
        query = inboxRequestQuery(filter, limit, offset),
        serverIdOverride = serverId
    )

/**
 * `GET /channels/inbox` query (botiverse/slock routes/channels.ts `channelRouter.get("/inbox")`):
 * `filter`, `limit`, `offset`, and `scope` for a scoped filter (Pinned: `scope=pinned`).
 */
internal fun inboxRequestQuery(filter: InboxFilter, limit: Int, offset: Int): List<Pair<String, String>> =
    listOfNotNull(
        "filter" to filter.wireValue,
        "limit" to limit.toString(),
        "offset" to offset.toString(),
        filter.scopeWireValue?.let { "scope" to it },
    )

/**
 * `GET /channels/inbox` with web's single RisingWave-overload retry
 * (botiverse/slock 1bb70cbeb, packages/web/src/api/overloadRetry.ts): a
 * `503 {code:"rw_overloaded", retryable:true}` is retried once after
 * `Retry-After` (default 2s, capped at 10s). One retry only: more would add
 * load while RisingWave is behind.
 */
private suspend fun RaftNetworkClient.inboxWithRwOverloadRetry(
    priority: DataRequestPriority,
    filter: InboxFilter,
    limit: Int,
    offset: Int,
    serverId: ServerId
): HomeNetworkResponse {
    val first = inbox(priority, filter, limit, offset, serverId)
    val retryDelayMillis = first.rwOverloadRetryDelayMillis() ?: return first
    delay(retryDelayMillis)
    return inbox(priority, filter, limit, offset, serverId)
}

private const val RwOverloadDefaultRetryAfterMillis = 2_000L
private const val RwOverloadMaxRetryAfterMillis = 10_000L

internal fun HomeNetworkResponse.rwOverloadRetryDelayMillis(): Long? {
    if (!isRetryableRwOverload()) return null
    val seconds =
        responseHeaders.entries
            .firstOrNull { (name, _) -> name.equals("retry-after", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return RwOverloadDefaultRetryAfterMillis
    return minOf((seconds * 1_000.0).toLong(), RwOverloadMaxRetryAfterMillis)
}

private suspend fun RaftNetworkClient.markInboxDone(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/inbox/done", method = "POST", bodyText = bodyText)

private suspend fun RaftNetworkClient.saved(
    priority: DataRequestPriority,
    limit: Int,
    offset: Int,
    serverId: ServerId
): HomeNetworkResponse =
    request(
        priority,
        "channels/saved",
        query = listOf("limit" to limit.toString(), "offset" to offset.toString()),
        serverIdOverride = serverId
    )

private suspend fun RaftNetworkClient.saveMessage(
    priority: DataRequestPriority,
    bodyText: String,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "channels/saved", method = "POST", bodyText = bodyText, serverIdOverride = serverId)

private suspend fun RaftNetworkClient.unsaveMessage(
    priority: DataRequestPriority,
    messageId: MessageId,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "channels/saved/${messageId.value}", method = "DELETE", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.checkSavedMessages(
    priority: DataRequestPriority,
    bodyText: String,
    serverId: ServerId
): HomeNetworkResponse =
    request(priority, "channels/saved/check", method = "POST", bodyText = bodyText, serverIdOverride = serverId)

private suspend fun RaftNetworkClient.searchMessages(
    priority: DataRequestPriority,
    query: String,
    limit: Int,
    offset: Int,
    cursor: String?,
    options: SearchQueryOptions,
    serverId: ServerId
): HomeNetworkResponse =
    request(
        priority,
        "messages/search",
        query = searchRequestQuery(query, limit, offset, cursor, options),
        serverIdOverride = serverId
    )

/**
 * `GET /messages/search` query (botiverse/slock routes/messages.ts `messageRouter.get("/search")`),
 * built like the web's `buildMessageSearchParams` + `loadMoreResults`:
 * - one `senderId` at most: the route reads it only as a single string, so a repeated one would
 *   drop the sender filter;
 * - a walk `cursor` replaces `offset` when [searchWalkCursorApplies] (the route rejects a cursor
 *   without a query or with another sort); otherwise the page starts at `offset`.
 */
internal fun searchRequestQuery(
    query: String,
    limit: Int,
    offset: Int,
    cursor: String?,
    options: SearchQueryOptions
): List<Pair<String, String>> {
    val walkCursor = cursor?.takeIf { it.isNotEmpty() && searchWalkCursorApplies(query, options.sort) }
    return listOfNotNull(
        "q" to query,
        "limit" to limit.toString(),
        if (walkCursor != null) "cursor" to walkCursor else "offset" to offset.toString(),
        options.sort.wireValue?.let { "sort" to it },
        options.senderId?.trim()?.takeIf { it.isNotBlank() }?.let { "senderId" to it },
        options.scopes
            .takeIf { SearchScope.Mentioned in it }
            ?.let { "mentionTarget" to "self" },
        options.scopes.exclusiveSenderTypeWireValue()?.let { "senderType" to it },
        options.channelId?.value?.let { "channelId" to it },
        options.afterIso?.takeIf { it.isNotBlank() }?.let { "after" to it },
        options.beforeIso?.takeIf { it.isNotBlank() }?.let { "before" to it }
    )
}

class RealInboxRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : InboxRepository {
    override suspend fun loadInitial(
        serverId: ServerId,
        filter: InboxFilter,
        limit: Int
    ): DataResult<InboxSnapshot> = load(serverId, filter, limit, offset = 0)

    override suspend fun loadMore(
        serverId: ServerId,
        filter: InboxFilter,
        offset: Int,
        limit: Int
    ): DataResult<InboxSnapshot> = load(serverId, filter, limit, offset)

    override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> {
        sessionStore.readSession() ?: return authRequired()
        return executeInboxLike(
            emptyReason = EmptyReason.NoInboxItems,
            block = {
                networkClient.markInboxDone(
                    DataRequestPriority.Foreground,
                    buildSharedInboxDoneBody(channelId, frontier)
                )
            },
            errorTextDecoder = HomeNetworkResponse::inboxRouteErrorTextOrNull,
            source = "inbox_mark_done",
            warningLogger = warningLogger,
            mapper = { InboxDoneResult(channelId) }
        )
    }

    private suspend fun load(
        serverId: ServerId,
        filter: InboxFilter,
        limit: Int,
        offset: Int
    ): DataResult<InboxSnapshot> {
        val context = sessionStore.serverContext(serverId) ?: return authRequired()
        return executeInboxLike(
            emptyReason = EmptyReason.NoInboxItems,
            block = { networkClient.inboxWithRwOverloadRetry(DataRequestPriority.Visible, filter, limit, offset, serverId) },
            // A defensive attention-filter projection may remove every row in
            // a malformed/lagging page while later pages still exist. Keep
            // that page Content so the Activity store can advance the raw
            // offset instead of terminating pagination early.
            emptyWhen = { it.items.isEmpty() && !it.hasMore },
            errorTextDecoder = HomeNetworkResponse::inboxRouteErrorTextOrNull,
            source = "inbox",
            mapOnDefault = false,
            diagnosticsLogger = logger,
            warningLogger = warningLogger,
            markStale = { snapshot, reason -> snapshot.copy(staleReason = reason) },
            mapper = { bodyText ->
                val parsed = parseSharedInboxSnapshot(bodyText)
                    ?: throw IllegalStateException("Inbox response missing items")
                if ((parsed.totalCount != parsed.items.size) ||
                    (parsed.totalUnreadCount != parsed.items.sumOf { it.unreadCount })
                ) {
                    logger(
                        "inbox_count_override serverId=${serverId.value} filter=${filter.wireValue} offset=$offset wireTotal=${parsed.totalCount} wireUnread=${parsed.totalUnreadCount} itemCount=${parsed.items.size} itemUnread=${parsed.items.sumOf { it.unreadCount }}"
                    )
                }
                InboxSnapshot(
                    server = context.server,
                    items = parsed.items,
                    totalCount = parsed.totalCount,
                    totalUnreadCount = parsed.totalUnreadCount,
                    hasMore = parsed.hasMore,
                    activeUnreadCount = parsed.activeUnreadCount,
                    // Keep page advancement based on raw server rows. The
                    // client may project every row out of an attention filter
                    // when a lagging/older server includes an unfollowed
                    // thread; advancing by projected size would repeat the
                    // same page forever.
                    nextOffset = offset + parsed.rawItemCount
                ).projectActivityFilter(filter)
                    .copy(nextOffset = offset + parsed.rawItemCount)
            }
        )
    }
}

internal class ProcessActivityUnreadRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) {
    suspend fun load(
        owner: ProcessActivityUnreadOwner,
        priority: DataRequestPriority
    ): DataResult<ActivityUnreadSummarySnapshot> {
        val serverId = ServerId(owner.serverId)
        val session = sessionStore.readSession() ?: return authRequired()
        if (session.user.id.trim() != owner.accountId.trim()) return authRequired()
        if (session.servers.none { server -> server.id == owner.serverId }) return authRequired()
        return executeInboxLike(
            emptyReason = EmptyReason.NoInboxItems,
            block = {
                networkClient.inboxWithRwOverloadRetry(
                    priority = priority,
                    filter = InboxFilter.All,
                    limit = 1,
                    offset = 0,
                    serverId = serverId
                )
            },
            errorTextDecoder = HomeNetworkResponse::inboxRouteErrorTextOrNull,
            source = "process_activity_unread",
            mapOnDefault = false,
            diagnosticsLogger = logger,
            warningLogger = warningLogger,
            markStale = { snapshot, reason -> snapshot.copy(staleReason = reason) },
            mapper = { bodyText ->
                val parsed = parseSharedInboxSnapshot(bodyText)
                    ?: throw IllegalStateException("Activity unread response missing summary")
                ActivityUnreadSummarySnapshot(
                    totalUnreadCount = parsed.totalUnreadCount,
                    totalCount = parsed.totalCount
                )
            }
        )
    }
}

class RealSavedRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {}
) : SavedRepository, SavedStatusRepository {
    override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
        load(serverId, limit, offset = 0)

    override suspend fun loadMore(
        serverId: ServerId,
        offset: Int,
        limit: Int
    ): DataResult<SavedSnapshot> = load(serverId, limit, offset)

    override suspend fun saveMessage(
        serverId: ServerId,
        messageId: MessageId
    ): DataResult<SavedMessageMutationResult> =
        mutateSavedMessage(serverId, messageId, save = true)

    override suspend fun unsaveMessage(
        serverId: ServerId,
        messageId: MessageId
    ): DataResult<SavedMessageMutationResult> =
        mutateSavedMessage(serverId, messageId, save = false)

    override suspend fun checkSavedMessages(
        serverId: ServerId,
        messageIds: List<MessageId>
    ): DataResult<SavedMessageStatusSnapshot> {
        sessionStore.serverContext(serverId) ?: return authRequired()
        val normalizedIds = messageIds.map { it.value.trim() }.filter { it.isNotBlank() }.distinct()
        if (normalizedIds.isEmpty()) return DataResult.Empty(EmptyReason.NoInboxItems)
        return executeInboxLike(
            emptyReason = EmptyReason.NoInboxItems,
            block = {
                networkClient.checkSavedMessages(
                    DataRequestPriority.Visible,
                    buildSharedSavedMessageCheckBody(normalizedIds),
                    serverId
                )
            },
            errorTextDecoder = HomeNetworkResponse::savedRouteErrorTextOrNull,
            source = "saved_check",
            warningLogger = warningLogger,
            mapper = { bodyText ->
                parseSharedSavedMessageStatus(bodyText)
                    ?: throw IllegalStateException("Saved status response missing savedIds list")
            }
        )
    }

    private suspend fun mutateSavedMessage(
        serverId: ServerId,
        messageId: MessageId,
        save: Boolean
    ): DataResult<SavedMessageMutationResult> {
        sessionStore.serverContext(serverId) ?: return authRequired()
        return executeInboxLike(
            emptyReason = EmptyReason.NoInboxItems,
            block = {
                if (save) {
                    networkClient.saveMessage(
                        DataRequestPriority.Foreground,
                        buildSharedSaveMessageBody(messageId),
                        serverId
                    )
                } else {
                    networkClient.unsaveMessage(DataRequestPriority.Foreground, messageId, serverId)
                }
            },
            errorTextDecoder = HomeNetworkResponse::savedRouteErrorTextOrNull,
            source = if (save) "saved_save_message" else "saved_unsave_message",
            warningLogger = warningLogger,
            mapper = { SavedMessageMutationResult(messageId) }
        )
    }

    private suspend fun load(
        serverId: ServerId,
        limit: Int,
        offset: Int
    ): DataResult<SavedSnapshot> {
        val context = sessionStore.serverContext(serverId) ?: return authRequired()
        return executeInboxLike(
            emptyReason = EmptyReason.NoInboxItems,
            block = { networkClient.saved(DataRequestPriority.Visible, limit, offset, serverId) },
            emptyWhen = { it.items.isEmpty() },
            errorTextDecoder = HomeNetworkResponse::savedRouteErrorTextOrNull,
            source = "saved",
            warningLogger = warningLogger,
            mapper = { bodyText ->
                val parsed = parseSharedSavedItems(bodyText)
                    ?: throw IllegalStateException("Saved response missing saved list")
                SavedSnapshot(
                    server = context.server,
                    items = parsed.items,
                    totalCount = parsed.totalCount,
                    hasMore = parsed.hasMore,
                    nextOffset = offset + parsed.items.size
                )
            }
        )
    }
}

class RealSearchRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {}
) : SearchRepository {
    override suspend fun search(
        serverId: ServerId,
        query: String,
        offset: Int,
        limit: Int,
        options: SearchQueryOptions,
        cursor: String?
    ): DataResult<SearchSnapshot> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank() && !options.hasMeaningfulFilter()) {
            return DataResult.Empty(EmptyReason.NoSearchResults)
        }
        val context = sessionStore.serverContext(serverId) ?: return authRequired()
        return executeInboxLike(
            emptyReason = EmptyReason.NoSearchResults,
            block = {
                networkClient.searchMessages(
                    DataRequestPriority.Visible,
                    trimmedQuery,
                    limit,
                    offset,
                    cursor,
                    options,
                    serverId
                )
            },
            emptyWhen = { it.items.isEmpty() },
            errorTextDecoder = HomeNetworkResponse::searchRouteErrorTextOrNull,
            errorCodeDecoder = HomeNetworkResponse::searchRouteErrorCodeOrNull,
            source = "search_messages",
            warningLogger = warningLogger,
            mapper = { bodyText ->
                val parsed = parseSharedSearchItems(bodyText)
                    ?: throw IllegalStateException("Search response missing results")
                SearchSnapshot(
                    server = context.server,
                    query = trimmedQuery,
                    items = parsed.items,
                    totalCount = parsed.items.size,
                    hasMore = parsed.hasMore,
                    // Like the web, the next offset counts the rows the server sent; a cursor
                    // page continues from its own cursor, not from an offset.
                    nextOffset =
                        if (cursor != null && searchWalkCursorApplies(trimmedQuery, options.sort)) {
                            null
                        } else {
                            offset + parsed.rawResultCount
                        },
                    nextCursor = parsed.nextCursor?.takeIf { searchWalkCursorApplies(trimmedQuery, options.sort) }
                )
            }
        )
    }
}

@Serializable
private data class SavedMessageCheckRequestBody(
    val messageIds: List<String>
)

/**
 * `POST /channels/inbox/done` body (botiverse/slock 1bb70cbeb,
 * routes/channels.ts:1497). Null fields are omitted, never sent as JSON null:
 * the server treats a present `throughActivitySeq` without `frontierSpace` as
 * 412 DONE_FRONTIER_SPACE_REQUIRED.
 */
@Serializable
private data class InboxDoneRequestBody(
    val channelId: String,
    val throughActivitySeq: String? = null,
    val frontierSpace: String? = null
)

private fun buildSharedInboxDoneBody(channelId: ChannelId, frontier: InboxDoneFrontier): String =
    inboxDoneRequestJson.encodeToString(
        when (frontier) {
            InboxDoneFrontier.Legacy -> InboxDoneRequestBody(channelId = channelId.value)
            is InboxDoneFrontier.Storage ->
                InboxDoneRequestBody(
                    channelId = channelId.value,
                    throughActivitySeq = frontier.throughActivitySeq,
                    frontierSpace = InboxDoneFrontierSpaceStorage
                )
        }
    )

private fun buildSharedSavedMessageCheckBody(messageIds: List<String>): String =
    inboxSavedSearchRepositoryJson.encodeToString(SavedMessageCheckRequestBody(messageIds = messageIds))

@Serializable
data class SaveMessageRequestBody(val messageId: String)

fun buildSharedSaveMessageBody(messageId: MessageId): String =
    inboxSavedSearchRepositoryJson.encodeToString(SaveMessageRequestBody(messageId.value))

private data class InboxLikeServerContext(val server: ServerSummary)

private suspend fun RaftAuthSessionStore.serverContext(serverId: ServerId): InboxLikeServerContext? {
    val session = readSession() ?: return null
    val requestedId = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val server = session.servers.firstOrNull { it.id == requestedId }?.toServerSummary() ?: return null
    return InboxLikeServerContext(server)
}

private suspend fun <T> executeInboxLike(
    emptyReason: EmptyReason,
    block: suspend () -> HomeNetworkResponse,
    emptyWhen: (T) -> Boolean = { false },
    errorTextDecoder: (HomeNetworkResponse) -> String?,
    source: String,
    errorCodeDecoder: (HomeNetworkResponse) -> String? = { null },
    mapOnDefault: Boolean = true,
    diagnosticsLogger: (String) -> Unit = {},
    warningLogger: (String) -> Unit = {},
    /**
     * Task #164: tags a page the server served from its remembered body (`X-Raft-Stale`). A stale
     * page is always Content, even when it has no rows: the store keeps what it showed instead of
     * replacing it with "nothing".
     */
    markStale: ((T, RaftStaleReason) -> T)? = null,
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code
        if (!response.isSuccessful) return response.toInboxLikeDataError(errorTextDecoder(response), errorCodeDecoder(response))
        val body = response.bodyText.orEmpty()
        if (body.isBlank()) return DataResult.Empty(emptyReason)
        val result =
            parseResponseBodyWithTrace(
                source = source,
                markerPrefix = "inbox",
                body = body,
                code = response.code,
                hasBody = response.bodyText != null,
                parseOnDefault = mapOnDefault,
                diagnosticsLogger = diagnosticsLogger,
                resultLabel = { parsed -> if (emptyWhen(parsed)) "empty" else "content" },
                parser = { mapper(body) }
            )
        val staleReason = markStale?.let { response.raftStaleReason() }
        when {
            staleReason != null -> DataResult.Content(markStale(result, staleReason))
            emptyWhen(result) -> DataResult.Empty(emptyReason)
            else -> DataResult.Content(result)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        warningLogger(
            "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
        )
        DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
    }
}

private fun authRequired(): DataResult.NoPermission =
    authSessionRequiredResult()

private fun authRequired(serverMessage: String?): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage)

private fun <T> HomeNetworkResponse.toInboxLikeDataError(serverMessage: String?, publicCode: String? = null): DataResult<T> =
    when (code) {
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
                    serverCode = RwOverloadedErrorCode.takeIf { isRwOverloaded() }
                )
            )
        else ->
            DataResult.Error(ScreenError(code = publicCode ?: "http_$code", retryable = code != 404, serverMessage = serverMessage))
    }

@Serializable
private data class InboxRouteErrorDto(val error: String? = null)

@Serializable
private data class SavedRouteErrorDto(val error: String? = null)

@Serializable
private data class SearchRouteErrorDto(val error: String? = null, val code: String? = null)

private fun HomeNetworkResponse.inboxRouteErrorTextOrNull(): String? =
    inboxLikeRouteErrorTextOrNull(InboxRouteErrorDto.serializer()) { it.error }

private fun HomeNetworkResponse.savedRouteErrorTextOrNull(): String? =
    inboxLikeRouteErrorTextOrNull(SavedRouteErrorDto.serializer()) { it.error }

// Public /api/messages/search contract: HTTP 422 with code (not agent-api errorCode).
// botiverse/slock 7596737a389288234b9f794ceb10057e4975f4e8, messages.api.test.ts.
private fun HomeNetworkResponse.searchRouteErrorCodeOrNull(): String? =
    if (code == 422) {
        inboxLikeRouteErrorTextOrNull(SearchRouteErrorDto.serializer()) { it.code }
            ?.takeIf { it == "QUERY_TOO_BROAD" }
    } else null

private fun HomeNetworkResponse.searchRouteErrorTextOrNull(): String? =
    inboxLikeRouteErrorTextOrNull(SearchRouteErrorDto.serializer()) { it.error }

private fun <T> HomeNetworkResponse.inboxLikeRouteErrorTextOrNull(
    serializer: kotlinx.serialization.KSerializer<T>,
    text: (T) -> String?
): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        text(inboxSavedSearchRepositoryJson.decodeFromString(serializer, payload))
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
