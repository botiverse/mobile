package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.model.ServerId
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoader
import build.raft.app.network.sync.CanonicalMessageV2SnapshotRequest
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    networkClient: SlockNetworkClient,
    private val logger: (String) -> Unit = {},
) : CanonicalMessageV2OwnerGateway {
    private val fetcher = CanonicalMessageV2RestSnapshotFetcher(networkClient, logger)

    override suspend fun accept(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
    ): CanonicalMessageV2CanaryOutcome {
        val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
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
        val fetched = fetcher.fetch(
            partition = snapshotRequest.scope.partition,
            scopeId = snapshotRequest.scope.scopeId,
            expectedScopeKind = snapshotRequest.scope.scopeKind,
        )
        if (fetched is CanonicalMessageV2SnapshotLoadResult.Loaded && !authMatches(grant)) {
            return unavailable("canonical_v2_auth_changed")
        }
        return fetched
    }

    private suspend fun authMatches(grant: RealtimeGrantRecord): Boolean {
        val session = authStore.readSession() ?: return false
        return session.user.id == grant.principalId &&
            session.servers.any { it.id == grant.serverId }
    }

    private fun unavailable(diagnostic: String): CanonicalMessageV2SnapshotLoadResult.Unavailable =
        CanonicalMessageV2SnapshotLoadResult.Unavailable(diagnostic)
}

/**
 * The REST page → full-V2 snapshot frame step shared by the canary's authoritative snapshot and the
 * enter-snapshot writer (task #1124). Pure fetch + decode: no auth check, no store write, no
 * selection. Every rejection is an [CanonicalMessageV2SnapshotLoadResult.Unavailable] with the
 * same diagnostics the canary always emitted.
 *
 * `expectedScopeKind == null` means "resolve the kind from `channels/{id}`" (the enter path knows
 * only the id); a non-null value must match the server's answer (the canary path).
 */
internal class CanonicalMessageV2RestSnapshotFetcher(
    private val networkClient: SlockNetworkClient,
    private val logger: (String) -> Unit = {},
) {
    suspend fun fetch(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        expectedScopeKind: String?,
    ): CanonicalMessageV2SnapshotLoadResult {
        val detail = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/$scopeId",
            serverId = partition.serverId,
        ) ?: return unavailable("canonical_v2_channel_detail_unavailable")
        val channelType = (detail["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it in setOf("channel", "private", "dm", "joint") }
            ?: return unavailable("canonical_v2_scope_not_top_level")
        if (expectedScopeKind != null && channelType != expectedScopeKind) {
            return unavailable("canonical_v2_scope_kind_changed")
        }
        val scope = SyncScopeKey(partition = partition, scopeId = scopeId, scopeKind = channelType)

        val page = fetchJson(
            priority = DataRequestPriority.Background,
            path = "messages/channel/$scopeId",
            serverId = partition.serverId,
            query = listOf("limit" to CANONICAL_V2_CANARY_PAGE_LIMIT.toString()),
        ) ?: return unavailable("canonical_v2_snapshot_unavailable")
        val rawMessages = page["messages"] as? JsonArray
            ?: return unavailable("canonical_v2_snapshot_shape")
        val context = CanonicalConversationContextWire(channelType = channelType)
        val envelopes = rawMessages.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            if (!raw.hasFullV2SnapshotNodeKeys()) return@mapNotNull null
            val carry = decodeCanonicalMessageWire(raw, ProductionFace.EnrichedUpdated) { violation ->
                logger(
                    "canonical_v2_snapshot_decode_rejected scope=$scopeId " +
                        "fields=${violation.missingOrInvalidRequiredFields.sorted().joinToString(",")}",
                )
            } ?: return@mapNotNull null
            val replyCount = raw.replyCountOrNull() ?: return@mapNotNull null
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.Updated, carry)
                .toCanonicalMessageV2Envelope(
                    partition = partition,
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
                scope = scope,
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

    private fun unavailable(diagnostic: String): CanonicalMessageV2SnapshotLoadResult.Unavailable =
        CanonicalMessageV2SnapshotLoadResult.Unavailable(diagnostic)
}

/**
 * Production [ThreadEnteredScopeSnapshotLoader] (task #1124, Phase 2-2).
 *
 * Order of gates, each answering "write nothing" on failure:
 * 1. server flag off → return before any network request;
 * 2. same (partition, channel) already in flight → return (one page per entry, not per Start storm);
 * 3. REST page must be a full-V2 top-level page (shared fetcher);
 * 4. the canary owns this scope → leave it to the canary (checked again under the scope writer);
 * 5. `stillCurrent()` re-read under the scope writer, after the network round trip;
 * 6. `applySnapshot` bound to the partition generation captured before the fetch, so a partition
 *    cleared in between (sign-out) is refused by the engine rather than repopulated.
 */
internal class CanonicalMessageV2EnteredScopeLoader(
    private val canary: CanonicalMessageV2ProductionCanary,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val fetcher: CanonicalMessageV2RestSnapshotFetcher,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    private val logger: (String) -> Unit = {},
) : ThreadEnteredScopeSnapshotLoader {
    private val inFlightMutex = Mutex()
    private val inFlight = mutableSetOf<Pair<DurablePartitionKey, SyncScopeId>>()

    override suspend fun loadOnEnter(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        stillCurrent: () -> Boolean,
    ) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (channelId.isBlank() || channelId != channelId.trim()) return
        if (!isEnabledForServer(partition.serverId)) return
        val key = partition to channelId
        val admitted = inFlightMutex.withLock { inFlight.add(key) }
        if (!admitted) {
            log(partition, channelId, "in_flight")
            return
        }
        try {
            val expectedGeneration = generationEngine.partitionGeneration(partition)
            val frame = when (val fetched = fetcher.fetch(partition, channelId, expectedScopeKind = null)) {
                is CanonicalMessageV2SnapshotLoadResult.Loaded -> fetched.frame
                is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                    log(partition, channelId, fetched.diagnostic)
                    return
                }
            }
            if (canary.isSelected(frame.scope)) {
                log(partition, channelId, "canary_owned")
                return
            }
            generationEngine.withScopeWriter(frame.scope) {
                if (canary.isSelected(frame.scope)) {
                    log(partition, channelId, "canary_owned")
                    return@withScopeWriter
                }
                if (!stillCurrent()) {
                    log(partition, channelId, "identity_changed")
                    return@withScopeWriter
                }
                if (!isEnabledForServer(partition.serverId)) {
                    log(partition, channelId, "flag_revoked")
                    return@withScopeWriter
                }
                val outcome = when (generationEngine.applySnapshot(frame, expectedGeneration)) {
                    CanonicalMessageV2CommitResult.Committed -> "committed facts=${frame.envelopes.size}"
                    CanonicalMessageV2CommitResult.PartitionRevoked -> "partition_revoked"
                    CanonicalMessageV2CommitResult.ScopeEvicted -> "scope_evicted"
                    is CanonicalMessageV2CommitResult.BaselineRequired -> "baseline_required"
                }
                log(partition, channelId, outcome)
            }
        } finally {
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    private fun log(partition: DurablePartitionKey, channelId: SyncScopeId, outcome: String) {
        logger("canonical_v2_enter_snapshot server=${partition.serverId} scope=$channelId outcome=$outcome")
    }
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
