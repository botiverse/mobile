package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.model.Message
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoader
import build.raft.app.network.sync.CanonicalMessageV2SnapshotRequest
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
import build.raft.app.network.sync.CanonicalMessageV2VisitedRealtimeResult
import build.raft.app.network.sync.CanonicalMessageV2VisitedWindowGateway
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import build.raft.app.network.sync.toAppMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal fun interface CanonicalMessageV2OwnerGateway {
    suspend fun accept(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
    ): CanonicalMessageV2CanaryOutcome
}

internal class ProviderBackedCanonicalMessageV2OwnerGateway(
    private val canary: CanonicalMessageV2ProductionCanary,
    private val authStore: SlockAuthSessionStore,
    private val apiEnvironmentStore: SharedApiEnvironmentStore,
    private val networkClient: SlockNetworkClient,
    private val visitedWindowGateway: CanonicalMessageV2VisitedWindowGateway? = null,
    private val logger: (String) -> Unit = {},
) : CanonicalMessageV2OwnerGateway {
    override suspend fun accept(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
    ): CanonicalMessageV2CanaryOutcome {
        val partition = DurablePartitionKey(
            serverId = grant.serverId,
            principalId = grant.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = normalizedEnvironmentId(),
        )
        val loader = CanonicalMessageV2SnapshotLoader { request -> loadSnapshot(grant, request) }
        val canaryOutcome = canary.accept(
            partition = partition,
            foldInput = foldInput,
            snapshotLoader = loader,
        )
        if (canaryOutcome != CanonicalMessageV2CanaryOutcome.NotSelected) return canaryOutcome
        when (val visited = visitedWindowGateway?.acceptRealtime(partition, foldInput, loader)) {
            CanonicalMessageV2VisitedRealtimeResult.Committed ->
                logger("canonical_v2_visited realtime_committed server=${grant.serverId} message=${foldInput.row.id}")
            is CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable ->
                logger(
                    "canonical_v2_visited refresh_unavailable server=${grant.serverId} " +
                        "message=${foldInput.row.id} reason=${visited.diagnostic}",
                )
            CanonicalMessageV2VisitedRealtimeResult.NotVisited,
            null,
            -> Unit
        }
        return CanonicalMessageV2CanaryOutcome.NotSelected
    }

    private suspend fun loadSnapshot(
        grant: RealtimeGrantRecord,
        snapshotRequest: CanonicalMessageV2SnapshotRequest,
    ): CanonicalMessageV2SnapshotLoadResult {
        if (!authMatches(grant)) return unavailable("canonical_v2_auth_mismatch")
        val detail = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/${snapshotRequest.scope.scopeId}",
            serverId = grant.serverId,
        ) ?: return unavailable("canonical_v2_channel_detail_unavailable")
        val channelType = (detail["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it in setOf("channel", "private", "dm", "joint") }
            ?: return unavailable("canonical_v2_scope_not_top_level")
        if (channelType != snapshotRequest.scope.scopeKind) {
            return unavailable("canonical_v2_scope_kind_changed")
        }

        val page = fetchJson(
            priority = DataRequestPriority.Background,
            path = "messages/channel/${snapshotRequest.scope.scopeId}",
            serverId = grant.serverId,
            query = listOf("limit" to CANONICAL_V2_CANARY_PAGE_LIMIT.toString()),
        ) ?: return unavailable("canonical_v2_snapshot_unavailable")
        if (!authMatches(grant)) return unavailable("canonical_v2_auth_changed")
        val rawMessages = page["messages"] as? JsonArray
            ?: return unavailable("canonical_v2_snapshot_shape")
        val context = CanonicalConversationContextWire(channelType = channelType)
        val envelopes = rawMessages.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            if (!raw.hasFullV2SnapshotNodeKeys()) return@mapNotNull null
            val carry = decodeCanonicalMessageWire(raw, ProductionFace.EnrichedUpdated) { violation ->
                logger(
                    "canonical_v2_snapshot_decode_rejected scope=${snapshotRequest.scope.scopeId} " +
                        "fields=${violation.missingOrInvalidRequiredFields.sorted().joinToString(",")}",
                )
            } ?: return@mapNotNull null
            val replyCount = raw.replyCountOrNull() ?: return@mapNotNull null
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.Updated, carry)
                .toCanonicalMessageV2Envelope(
                    partition = snapshotRequest.scope.partition,
                    replyCount = replyCount,
                    producerContextOverride = context,
                )
        }
        if (envelopes.size != rawMessages.size || envelopes.isEmpty()) {
            return unavailable("canonical_v2_snapshot_not_full_v6")
        }
        val seqs = envelopes.map { it.fact.seq }
        return CanonicalMessageV2SnapshotLoadResult.Loaded(
            CanonicalMessageV2SnapshotFrame(
                scope = snapshotRequest.scope,
                envelopes = envelopes,
                cursor = PersistedCursor(
                    latestSeq = seqs.maxOrNull(),
                    oldestLoadedSeq = seqs.minOrNull(),
                    newestLoadedSeq = seqs.maxOrNull(),
                    hasLoadedOldest = envelopes.size < CANONICAL_V2_CANARY_PAGE_LIMIT,
                    hasLoadedNewest = true,
                    epoch = null,
                ),
            ),
        )
    }

    private suspend fun fetchJson(
        priority: DataRequestPriority,
        path: String,
        serverId: String,
        query: List<Pair<String, String>> = emptyList(),
    ): JsonObject? {
        return try {
            val response = networkClient.request(
                priority = priority,
                path = path,
                query = query,
                serverIdOverride = ServerId(serverId),
            )
            if (!response.isSuccessful) return null
            response.bodyText?.takeIf { it.isNotBlank() }
                ?.let { canonicalV2CanaryNetworkJson.parseToJsonElement(it) as? JsonObject }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger("canonical_v2_network_failed path=$path reason=${throwable::class.simpleName ?: "unknown"}")
            null
        }
    }

    private suspend fun authMatches(grant: RealtimeGrantRecord): Boolean {
        val session = authStore.readSession() ?: return false
        return session.user.id == grant.principalId &&
            session.servers.any { it.id == grant.serverId }
    }

    private fun normalizedEnvironmentId(): String =
        apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/').also {
            require(it.isNotEmpty()) { "canonical V2 environment id must not be blank" }
        }

    private fun unavailable(diagnostic: String): CanonicalMessageV2SnapshotLoadResult.Unavailable =
        CanonicalMessageV2SnapshotLoadResult.Unavailable(diagnostic)
}

internal data class CanonicalMessageV2VisitedSnapshotIdentity(
    val scope: build.raft.app.network.sync.SyncScopeKey,
    val producerContext: CanonicalConversationContextWire,
)

/** Raw-response binding used by Thread repository loads; the existing parser still receives the same response. */
class CanonicalMessageV2VisitedSnapshotBinding internal constructor(
    private val gateway: CanonicalMessageV2VisitedWindowGateway,
    private val authStore: SlockAuthSessionStore,
    private val apiEnvironmentStore: SharedApiEnvironmentStore,
    private val logger: (String) -> Unit = {},
) {
    internal fun topLevelIdentity(
        serverId: String,
        principalId: String,
        scopeId: String,
        scopeKind: String,
    ): CanonicalMessageV2VisitedSnapshotIdentity {
        require(scopeKind in setOf("channel", "private", "dm", "joint"))
        return CanonicalMessageV2VisitedSnapshotIdentity(
            scope = build.raft.app.network.sync.SyncScopeKey(
                partition = partition(serverId, principalId),
                scopeId = scopeId,
                scopeKind = scopeKind,
            ),
            producerContext = CanonicalConversationContextWire(channelType = scopeKind),
        )
    }

    internal fun threadIdentity(
        serverId: String,
        principalId: String,
        replyScopeId: String,
        parentScopeId: String,
        parentScopeKind: String,
        rootMessageId: String,
    ): CanonicalMessageV2VisitedSnapshotIdentity {
        require(parentScopeKind in setOf("channel", "private", "dm", "joint"))
        return CanonicalMessageV2VisitedSnapshotIdentity(
            scope = build.raft.app.network.sync.SyncScopeKey(
                partition = partition(serverId, principalId),
                scopeId = replyScopeId,
                scopeKind = "thread",
            ),
            producerContext = CanonicalConversationContextWire(
                channelType = "thread",
                parentChannelId = parentScopeId,
                parentChannelType = parentScopeKind,
                parentMessageId = rootMessageId,
            ),
        )
    }

    internal suspend fun load(
        identity: CanonicalMessageV2VisitedSnapshotIdentity,
        block: suspend () -> HomeNetworkResponse,
    ): HomeNetworkResponse {
        var response: HomeNetworkResponse? = null
        gateway.refresh(identity.scope) {
            val loaded = block().also { response = it }
            loaded.toCanonicalVisitedSnapshot(identity)
        }
        return response ?: block()
    }

    internal suspend fun loadReply(
        identity: CanonicalMessageV2VisitedSnapshotIdentity,
        parentBlock: suspend () -> HomeNetworkResponse,
        replyBlock: suspend () -> HomeNetworkResponse,
    ): HomeNetworkResponse {
        require(identity.scope.scopeKind == "thread")
        val parentIdentity = identity.parentTopLevelIdentity()
        val rootMessageId = requireNotNull(identity.producerContext.parentMessageId)
        val parentResult = parentBlock().toCanonicalVisitedSnapshot(parentIdentity, exactMessageId = rootMessageId)
        if (
            parentResult is CanonicalMessageV2SnapshotLoadResult.Unavailable &&
            parentResult.diagnostic.requiresReplyScopePurge()
        ) {
            gateway.clearScope(identity.scope)
            return replyBlock()
        }
        val replyResponse = load(identity, replyBlock)
        if (parentResult is CanonicalMessageV2SnapshotLoadResult.Loaded && replyResponse.isSuccessful) {
            val parentCommit = gateway.refreshPreservingWindow(parentIdentity.scope) { parentResult }
            if (parentCommit is CanonicalMessageV2SnapshotLoadResult.Unavailable) {
                gateway.clearScope(identity.scope)
            }
        }
        return replyResponse
    }

    internal suspend fun restore(
        identity: CanonicalMessageV2VisitedSnapshotIdentity,
    ): ThreadSnapshot? {
        val restored = try {
            gateway.restore(identity.scope)
        } catch (_: CanonicalMessageV2ScopePoisoned) {
            return null
        }
        if (restored.cursor == null) return null
        if (!restored.cursor.matchesVisitedSnapshotIdentity(identity)) {
            gateway.clearScope(identity.scope)
            return null
        }
        if (restored.envelopes.any { !it.context.matchesVisitedSnapshotIdentity(identity) }) {
            gateway.clearScope(identity.scope)
            return null
        }
        val messages = restored.envelopes
            .filter { envelope -> envelope.fact.seq.isInside(restored.cursor) }
            .map { envelope -> envelope.toAppMessage(identity.scope, logger) ?: return null }
        val parentMessage =
            if (identity.scope.scopeKind == "thread") {
                val rootMessageId = requireNotNull(identity.producerContext.parentMessageId)
                restoreExactParentMessage(identity.parentTopLevelIdentity(), rootMessageId)
                    ?: return null
            } else {
                null
            }
        return buildCanonicalRestoredThreadSnapshot(
            identity = identity,
            messages = messages,
            threadId = ThreadId(identity.scope.scopeId),
            parentMessage = parentMessage,
        )
    }

    internal suspend fun clear(identity: CanonicalMessageV2VisitedSnapshotIdentity) {
        gateway.clearScope(identity.scope)
    }

    private suspend fun restoreExactParentMessage(
        parentIdentity: CanonicalMessageV2VisitedSnapshotIdentity,
        rootMessageId: String,
    ): Message? {
        val restored = try {
            gateway.restore(parentIdentity.scope)
        } catch (_: CanonicalMessageV2ScopePoisoned) {
            return null
        }
        val cursor = restored.cursor ?: return null
        if (!cursor.matchesVisitedSnapshotIdentity(parentIdentity)) return null
        val root = restored.envelopes.firstOrNull { it.fact.id == rootMessageId } ?: return null
        if (!root.context.matchesVisitedSnapshotIdentity(parentIdentity)) return null
        return root.toAppMessage(parentIdentity.scope, logger)
    }

    private suspend fun HomeNetworkResponse.toCanonicalVisitedSnapshot(
        identity: CanonicalMessageV2VisitedSnapshotIdentity,
        exactMessageId: String? = null,
    ): CanonicalMessageV2SnapshotLoadResult {
        if (code == 401 || code == 403) {
            return unavailable("canonical_v2_access_revoked_$code")
        }
        if (!isSuccessful) return unavailable("canonical_v2_snapshot_http_$code")
        if (!authorityMatches(identity.scope.partition)) {
            return unavailable("canonical_v2_auth_changed")
        }
        val page = try {
            bodyText?.takeIf { it.isNotBlank() }
                ?.let { canonicalV2CanaryNetworkJson.parseToJsonElement(it) as? JsonObject }
        } catch (_: Throwable) {
            null
        } ?: return unavailable("canonical_v2_snapshot_corrupt_body")
        val rawMessages = page["messages"] as? JsonArray
            ?: return unavailable("canonical_v2_snapshot_corrupt_shape")
        val selectedMessages =
            if (exactMessageId == null) {
                rawMessages.toList()
            } else {
                rawMessages.filter { element ->
                    ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull == exactMessageId
                }.takeIf { it.size == 1 }
                    ?: return unavailable("canonical_v2_snapshot_corrupt_exact_message")
            }
        val envelopes = selectedMessages.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            if (!raw.hasFullV2SnapshotNodeKeys()) return@mapNotNull null
            val carry = decodeCanonicalMessageWire(raw, ProductionFace.EnrichedUpdated) { violation ->
                logger(
                    "canonical_v2_visited_decode_rejected scope=${identity.scope.scopeId} " +
                        "fields=${violation.missingOrInvalidRequiredFields.sorted().joinToString(",")}",
                )
            } ?: return@mapNotNull null
            val replyCount = raw.replyCountOrNull() ?: return@mapNotNull null
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.Updated, carry)
                .toCanonicalMessageV2Envelope(
                    partition = identity.scope.partition,
                    replyCount = replyCount,
                    producerContextOverride = identity.producerContext,
                )
                ?.takeIf { envelope ->
                    envelope.context.matchesVisitedSnapshotIdentity(identity)
                }
        }
        if (envelopes.size != selectedMessages.size) {
            return unavailable("canonical_v2_snapshot_corrupt_not_full_v6")
        }
        val seqs = envelopes.map { it.fact.seq }
        return CanonicalMessageV2SnapshotLoadResult.Loaded(
            CanonicalMessageV2SnapshotFrame(
                scope = identity.scope,
                envelopes = envelopes,
                cursor = PersistedCursor(
                    latestSeq = seqs.maxOrNull(),
                    oldestLoadedSeq = seqs.minOrNull(),
                    newestLoadedSeq = seqs.maxOrNull(),
                    hasLoadedOldest = envelopes.size < CANONICAL_V2_CANARY_PAGE_LIMIT,
                    hasLoadedNewest = true,
                    epoch = null,
                    parentScopeKind = identity.producerContext.parentChannelType,
                    parentScopeId = identity.producerContext.parentChannelId,
                    rootMessageId = identity.producerContext.parentMessageId,
                ),
            ),
        )
    }

    private suspend fun authorityMatches(partition: DurablePartitionKey): Boolean {
        val environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
        val session = authStore.readSession() ?: return false
        return environmentId == partition.environmentId &&
            session.user.id == partition.principalId &&
            session.servers.any { it.id == partition.serverId }
    }

    private fun partition(serverId: String, principalId: String) = DurablePartitionKey(
        environmentId = apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/'),
        serverId = serverId,
        principalId = principalId,
        consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
    )

    private fun unavailable(diagnostic: String) =
        CanonicalMessageV2SnapshotLoadResult.Unavailable(diagnostic)

    private fun CanonicalMessageV2VisitedSnapshotIdentity.parentTopLevelIdentity():
        CanonicalMessageV2VisitedSnapshotIdentity =
        topLevelIdentity(
            serverId = scope.serverId,
            principalId = scope.principalId,
            scopeId = requireNotNull(producerContext.parentChannelId),
            scopeKind = requireNotNull(producerContext.parentChannelType),
        )
}

private fun build.raft.app.network.sync.CanonicalConversationContextV2.matchesVisitedSnapshotIdentity(
    identity: CanonicalMessageV2VisitedSnapshotIdentity,
): Boolean =
    when (val expected = identity.producerContext.channelType) {
        "channel", "private", "dm", "joint" ->
            this is build.raft.app.network.sync.CanonicalConversationContextV2.TopLevel &&
                scope.scopeId == identity.scope.scopeId && identity.scope.scopeKind == expected
        "thread" ->
            this is build.raft.app.network.sync.CanonicalConversationContextV2.Thread &&
                replyScope.scopeId == identity.scope.scopeId &&
                parentTopLevelScope.scopeId == identity.producerContext.parentChannelId &&
                rootMessageRef.messageId == identity.producerContext.parentMessageId
        else -> false
    }

private fun PersistedCursor.matchesVisitedSnapshotIdentity(
    identity: CanonicalMessageV2VisitedSnapshotIdentity,
): Boolean =
    if (identity.scope.scopeKind == "thread") {
        parentScopeKind == identity.producerContext.parentChannelType &&
            parentScopeId == identity.producerContext.parentChannelId &&
            rootMessageId == identity.producerContext.parentMessageId
    } else {
        parentScopeKind == null && parentScopeId == null && rootMessageId == null
    }

private fun Long.isInside(cursor: PersistedCursor): Boolean {
    val oldest = cursor.oldestLoadedSeq
    val newest = cursor.newestLoadedSeq
    return (oldest == null || this >= oldest) && (newest == null || this <= newest)
}

private fun String.requiresReplyScopePurge(): Boolean =
    startsWith("canonical_v2_snapshot_corrupt") ||
        startsWith("canonical_v2_access_revoked") ||
        startsWith("canonical_v2_auth_changed")

private fun JsonObject.replyCountOrNull(): Int? {
    val summary = this["threadSummary"] as? JsonObject
    val summaryCount = summary
        ?.takeIf { it.containsKey("replyCount") }
        ?.get("replyCount")
        ?.let { it as? JsonPrimitive }
        ?.intOrNull
    val directCount = takeIf { it.containsKey("replyCount") }
        ?.get("replyCount")
        ?.let { it as? JsonPrimitive }
        ?.intOrNull
    return (summaryCount ?: directCount)?.takeIf { it >= 0 }
}

private fun JsonObject.hasFullV2SnapshotNodeKeys(): Boolean =
    listOf(
        "senderName",
        "senderDescription",
        "senderMembershipStatus",
        "mentions",
        "attachments",
        "reactions",
        "actionMetadata",
    ).all(::containsKey)

private const val CANONICAL_V2_CANARY_PAGE_LIMIT: Int = 200

private val canonicalV2CanaryNetworkJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
