package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.model.ServerId
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoader
import build.raft.app.network.sync.CanonicalMessageV2SnapshotRequest
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val networkClient: SlockNetworkClient,
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
        )
        return canary.accept(
            partition = partition,
            foldInput = foldInput,
            snapshotLoader = CanonicalMessageV2SnapshotLoader { request -> loadSnapshot(grant, request) },
        )
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

    private fun unavailable(diagnostic: String): CanonicalMessageV2SnapshotLoadResult.Unavailable =
        CanonicalMessageV2SnapshotLoadResult.Unavailable(diagnostic)
}

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
