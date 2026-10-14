package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.model.ServerId
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal fun interface CanonicalMessageV2OwnerGateway {
    /** [transportEpoch]: the realtime connection that delivered the frame, when the host names it (task #168). */
    suspend fun accept(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): CanonicalMessageV2CanaryOutcome

    suspend fun accept(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
    ): CanonicalMessageV2CanaryOutcome = accept(grant, foldInput, null)

    /** Task #167: the transport behind [grant] (re)connected as [transportEpoch]; callers check the grant is current. */
    suspend fun noteTransportEpoch(grant: RealtimeGrantRecord, transportEpoch: Long) {}
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
        transportEpoch: Long?,
    ): CanonicalMessageV2CanaryOutcome {
        val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        return canary.accept(
            partition = partition,
            foldInput = foldInput,
            snapshotLoader = CanonicalMessageV2SnapshotLoader { request -> loadSnapshot(grant, request) },
            frameTransportEpoch = transportEpoch,
        )
    }

    override suspend fun noteTransportEpoch(grant: RealtimeGrantRecord, transportEpoch: Long) {
        canary.noteTransportEpoch(grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID), transportEpoch)
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
 * The server-confirmed parent of a reply thread scope (task #168 step 3): exactly the three values a
 * `thread` cursor stores and every reply envelope's context carries.
 */
internal data class CanonicalMessageV2ReplyParentScope(
    val parentScopeKind: String,
    val parentScopeId: SyncScopeId,
    val rootMessageId: String,
) {
    init {
        require(parentScopeKind in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS)
    }
}

/** Result of [CanonicalMessageV2RestSnapshotFetcher.convertPage]. */
internal sealed interface CanonicalMessageV2PageConversion {
    data class Converted(val envelopes: List<CanonicalMessageEnvelopeV2>) : CanonicalMessageV2PageConversion

    data class Refused(val diagnostic: String) : CanonicalMessageV2PageConversion
}

/**
 * The REST page → full-V2 snapshot frame step shared by the canary's authoritative snapshot and the
 * enter-snapshot writer (task #1124). Pure fetch + decode: no auth check, no store write, no
 * selection. Every rejection is an [CanonicalMessageV2SnapshotLoadResult.Unavailable] with the
 * same diagnostics the canary always emitted. Reply counts are sourced from
 * `channels/{id}/threads?parentMessageIds=…` because page nodes never carry them (task #1136:
 * requiring them on the node rejected every real page as `not_full_v6`).
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
        if (rawMessages.isEmpty()) {
            // An empty page is a full answer (task #1137): the channel has no top-level messages. It is
            // persisted as an empty baseline — no facts, no seqs, both ends loaded — so the offline
            // reader can render "empty channel" instead of "cannot load". An incremental message later
            // advances the null seqs like any other (`advancedBy`).
            logger("canonical_v2_snapshot_empty_page scope=$scopeId")
            return CanonicalMessageV2SnapshotLoadResult.Loaded(
                CanonicalMessageV2SnapshotFrame(
                    scope = scope,
                    envelopes = emptyList(),
                    cursor = PersistedCursor(
                        latestSeq = null,
                        oldestLoadedSeq = null,
                        newestLoadedSeq = null,
                        hasLoadedOldest = true,
                        hasLoadedNewest = true,
                        epoch = null,
                    ),
                ),
            )
        }
        val envelopes = when (val converted = convertPage(partition, scopeId, channelType, rawMessages)) {
            is CanonicalMessageV2PageConversion.Converted -> converted.envelopes
            is CanonicalMessageV2PageConversion.Refused -> return unavailable(converted.diagnostic)
        }
        return CanonicalMessageV2SnapshotLoadResult.Loaded(
            CanonicalMessageV2SnapshotFrame(scope = scope, envelopes = envelopes, cursor = snapshotCursor(envelopes)),
        )
    }

    /**
     * Task #168 step 3: the reply-thread twin of [fetch]. The route names the thread and its parent;
     * the server must confirm both before a page is fetched:
     * 1. `channels/{thread}` is a `thread` whose `parentChannelId` / `parentMessageId` equal
     *    [replyParent] (a moved or foreign thread is refused, never re-parented);
     * 2. `channels/{parent}` is a top-level kind — it becomes the cursor's `parentScopeKind`, which is
     *    what pins the root fact inside the parent scope's stored window;
     * 3. `messages/channel/{thread}` converts as a whole into `thread` envelopes under that parent.
     * The cursor rules are the top-level page's ([snapshotCursor]) plus the three parent fields.
     */
    suspend fun fetchReplyThread(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
    ): CanonicalMessageV2SnapshotLoadResult {
        val detail = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/$threadChannelId",
            serverId = partition.serverId,
        ) ?: return unavailable("canonical_v2_channel_detail_unavailable")
        if (detail.stringOrNull("type") != "thread") return unavailable("canonical_v2_scope_not_thread")
        if (
            detail.stringOrNull("parentChannelId") != replyParent.parentChannelId ||
            detail.stringOrNull("parentMessageId") != replyParent.rootMessageId
        ) {
            return unavailable("canonical_v2_thread_parent_mismatch")
        }
        val parentDetail = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/${replyParent.parentChannelId}",
            serverId = partition.serverId,
        ) ?: return unavailable("canonical_v2_parent_detail_unavailable")
        val parentKind = parentDetail.stringOrNull("type")
            ?.takeIf { it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS }
            ?: return unavailable("canonical_v2_parent_not_top_level")
        val parent = CanonicalMessageV2ReplyParentScope(
            parentScopeKind = parentKind,
            parentScopeId = replyParent.parentChannelId,
            rootMessageId = replyParent.rootMessageId,
        )
        val scope = SyncScopeKey(partition = partition, scopeId = threadChannelId, scopeKind = REPLY_THREAD_SCOPE_KIND)

        val page = fetchJson(
            priority = DataRequestPriority.Background,
            path = "messages/channel/$threadChannelId",
            serverId = partition.serverId,
            query = listOf("limit" to CANONICAL_V2_CANARY_PAGE_LIMIT.toString()),
        ) ?: return unavailable("canonical_v2_snapshot_unavailable")
        val rawMessages = page["messages"] as? JsonArray
            ?: return unavailable("canonical_v2_snapshot_shape")
        val envelopes = if (rawMessages.isEmpty()) {
            // A thread with no replies yet is an answer too (task #1137 rule): an empty baseline.
            logger("canonical_v2_snapshot_empty_page scope=$threadChannelId scopeKind=$REPLY_THREAD_SCOPE_KIND")
            emptyList()
        } else {
            when (
                val converted = convertPage(partition, threadChannelId, REPLY_THREAD_SCOPE_KIND, rawMessages, parent)
            ) {
                is CanonicalMessageV2PageConversion.Converted -> converted.envelopes
                is CanonicalMessageV2PageConversion.Refused -> return unavailable(converted.diagnostic)
            }
        }
        return CanonicalMessageV2SnapshotLoadResult.Loaded(
            CanonicalMessageV2SnapshotFrame(
                scope = scope,
                envelopes = envelopes,
                cursor = snapshotCursor(envelopes).copy(
                    parentScopeKind = parent.parentScopeKind,
                    parentScopeId = parent.parentScopeId,
                    rootMessageId = parent.rootMessageId,
                ),
            ),
        )
    }

    /** The cursor of a latest page: both edges from its seqs, oldest reached iff the page was short. */
    private fun snapshotCursor(envelopes: List<CanonicalMessageEnvelopeV2>): PersistedCursor {
        val seqs = envelopes.map { it.fact.seq }
        return PersistedCursor(
            latestSeq = seqs.maxOrNull(),
            oldestLoadedSeq = seqs.minOrNull(),
            newestLoadedSeq = seqs.maxOrNull(),
            hasLoadedOldest = envelopes.size < CANONICAL_V2_CANARY_PAGE_LIMIT,
            hasLoadedNewest = true,
            epoch = null,
        )
    }

    /**
     * The REST `messages` array → full-V2 envelopes step shared by [fetch] and the adjacent-page
     * writer (task #168). All or nothing: one node that is not full-v6, fails to decode, or is a
     * thread parent without a reply count refuses the whole page (`not_full_v6`); a summaries
     * outage while thread parents are present refuses it too. The only network call is the
     * `channels/{id}/threads` reply-count lookup, made only when the page has thread parents.
     * Callers handle the empty page themselves.
     *
     * [replyParent] is required exactly when [channelType] is `thread` (task #168 step 3): reply
     * envelopes carry their parent scope and root in their context. A reply page never makes the
     * summaries call — a reply that anchors a nested thread is refused instead (the V2 normalizer
     * has no nested-thread shape), so every reply has zero replies.
     */
    suspend fun convertPage(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        channelType: String,
        rawMessages: JsonArray,
        replyParent: CanonicalMessageV2ReplyParentScope? = null,
    ): CanonicalMessageV2PageConversion {
        require((channelType == REPLY_THREAD_SCOPE_KIND) == (replyParent != null)) {
            "a reply parent is required for, and only for, a thread page"
        }
        // The messages page never carries replyCount/threadSummary (server emits those only via
        // `channels/{id}/threads` and the internal agent API). Messages without `threadId` have
        // exactly zero replies; thread parents get the real count from the summaries endpoint, and
        // if that call fails while parents are present the page is refused rather than persisted
        // with silently zeroed counts.
        val replyCountByParentId = if (replyParent != null) {
            val nested = rawMessages.any { (it as? JsonObject)?.threadChannelIdOrNull() != null }
            if (nested) return CanonicalMessageV2PageConversion.Refused("canonical_v2_thread_nested_reply")
            emptyMap()
        } else {
            fetchThreadReplyCounts(partition, scopeId, rawMessages)
                ?: return CanonicalMessageV2PageConversion.Refused("canonical_v2_thread_summaries_unavailable")
        }
        val context = CanonicalConversationContextWire(
            channelType = channelType,
            parentChannelId = replyParent?.parentScopeId,
            parentChannelType = replyParent?.parentScopeKind,
            parentMessageId = replyParent?.rootMessageId,
        )
        val envelopes = rawMessages.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            if (!raw.hasFullV2SnapshotNodeKeys()) return@mapNotNull null
            val carry = decodeCanonicalMessageWire(raw, ProductionFace.EnrichedUpdated) { violation ->
                logger(
                    "canonical_v2_snapshot_decode_rejected scope=$scopeId " +
                        "fields=${violation.missingOrInvalidRequiredFields.sorted().joinToString(",")}",
                )
            } ?: return@mapNotNull null
            val replyCount = raw.snapshotReplyCountOrNull(replyCountByParentId)
            if (replyCount == null) {
                logger(
                    "canonical_v2_snapshot_reply_count_missing scope=$scopeId " +
                        "message=${(raw["id"] as? JsonPrimitive)?.contentOrNull}",
                )
                return@mapNotNull null
            }
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.Updated, carry)
                .toCanonicalMessageV2Envelope(
                    partition = partition,
                    replyCount = replyCount,
                    producerContextOverride = context,
                )
        }
        if (envelopes.size != rawMessages.size) {
            return CanonicalMessageV2PageConversion.Refused("canonical_v2_snapshot_not_full_v6")
        }
        return CanonicalMessageV2PageConversion.Converted(envelopes)
    }

    /**
     * Real reply counts for the page's thread parents, keyed by parent message id. `null` means the
     * summaries call was needed and failed; an empty map means the page has no thread parents and
     * no call was made.
     */
    private suspend fun fetchThreadReplyCounts(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        rawMessages: JsonArray,
    ): Map<String, Int>? {
        val parentIds = rawMessages.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            if (raw.threadChannelIdOrNull() == null) return@mapNotNull null
            (raw["id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() }
        }.distinct()
        if (parentIds.isEmpty()) return emptyMap()
        val summaries = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/$scopeId/threads",
            serverId = partition.serverId,
            query = listOf("parentMessageIds" to parentIds.joinToString(",")),
        ) ?: return null
        return summaries.entries.mapNotNull { (parentId, element) ->
            val count = ((element as? JsonObject)?.get("replyCount") as? JsonPrimitive)
                ?.intOrNull
                ?.takeIf { it >= 0 }
                ?: return@mapNotNull null
            parentId to count
        }.toMap()
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
 * 3. REST page must be a full-V2 top-level page (shared fetcher) — or, for a reply thread
 *    ([loadReplyThreadOnEnter], task #168 step 3), a full-V2 reply page of this thread whose parent
 *    the server confirmed;
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
    ) = loadAndCommit(partition, channelId, replyThread = false, stillCurrent) {
        fetcher.fetch(partition, channelId, expectedScopeKind = null)
    }

    /**
     * Task #168 step 3: the same gates, in the same order, for a reply thread. Only the fetch differs
     * ([CanonicalMessageV2RestSnapshotFetcher.fetchReplyThread] confirms the parent with the server),
     * plus one check before the write: every envelope belongs to this thread scope, so a foreign
     * reply is logged and refused instead of poisoning the scope inside the store.
     */
    override suspend fun loadReplyThreadOnEnter(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        stillCurrent: () -> Boolean,
    ) = loadAndCommit(partition, threadChannelId, replyThread = true, stillCurrent) {
        if (replyParent.parentChannelId == threadChannelId) {
            CanonicalMessageV2SnapshotLoadResult.Unavailable("canonical_v2_thread_parent_mismatch")
        } else {
            fetcher.fetchReplyThread(partition, threadChannelId, replyParent)
        }
    }

    private suspend fun loadAndCommit(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        replyThread: Boolean,
        stillCurrent: () -> Boolean,
        fetch: suspend () -> CanonicalMessageV2SnapshotLoadResult,
    ) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (scopeId.isBlank() || scopeId != scopeId.trim()) return
        if (!isEnabledForServer(partition.serverId)) return
        fun log(outcome: String) = log(partition, scopeId, replyThread, outcome)
        val key = partition to scopeId
        val admitted = inFlightMutex.withLock { inFlight.add(key) }
        if (!admitted) {
            log("in_flight")
            return
        }
        try {
            val expectedGeneration = generationEngine.partitionGeneration(partition)
            val frame = when (val fetched = fetch()) {
                is CanonicalMessageV2SnapshotLoadResult.Loaded -> fetched.frame
                is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                    log(fetched.diagnostic)
                    return
                }
            }
            if (replyThread) {
                val foreign = frame.scope.scopeKind != "thread" || frame.envelopes.any { envelope ->
                    (envelope.context as? CanonicalConversationContextV2.Thread)?.replyScope?.scopeId != scopeId
                }
                if (foreign) {
                    log("foreign_scope")
                    return
                }
            }
            if (canary.isSelected(frame.scope)) {
                log("canary_owned")
                return
            }
            generationEngine.withScopeWriter(frame.scope) {
                if (canary.isSelected(frame.scope)) {
                    log("canary_owned")
                    return@withScopeWriter
                }
                if (!stillCurrent()) {
                    log("identity_changed")
                    return@withScopeWriter
                }
                if (!isEnabledForServer(partition.serverId)) {
                    log("flag_revoked")
                    return@withScopeWriter
                }
                try {
                    val outcome = when (generationEngine.applySnapshot(frame, expectedGeneration)) {
                        CanonicalMessageV2CommitResult.Committed -> "committed facts=${frame.envelopes.size}"
                        CanonicalMessageV2CommitResult.PartitionRevoked -> "partition_revoked"
                        CanonicalMessageV2CommitResult.ScopeEvicted -> "scope_evicted"
                        is CanonicalMessageV2CommitResult.BaselineRequired -> "baseline_required"
                    }
                    log(outcome)
                } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                    // A stale/cross-scope durable row must never escape the background enter job.
                    // The page already has its network-backed content; leave the durable window
                    // untouched and fail closed until a later clean baseline can be written.
                    log("poisoned fact=${poisoned.factId.orEmpty()}")
                }
            }
        } finally {
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    private fun log(partition: DurablePartitionKey, scopeId: SyncScopeId, replyThread: Boolean, outcome: String) {
        val kind = if (replyThread) " scopeKind=thread" else ""
        logger("canonical_v2_enter_snapshot server=${partition.serverId} scope=$scopeId$kind outcome=$outcome")
    }
}

/**
 * Production [ThreadDurablePageExtender] (task #168): persists a paging page into the V2 window the
 * enter snapshot created, only when it is exactly adjacent to that window.
 *
 * Order of gates, each answering "write nothing + one `canonical_v2_adjacent_page` log line":
 * 1. malformed channel id / server flag off / empty page / non-positive limit → before any I/O;
 * 2. the scope kind comes from the store's committed cursors — top-level kinds only, or only
 *    `thread` when the caller passes a reply parent (task #168 step 3): none → `no_window` (only the
 *    enter snapshot creates a window), more than one → ambiguous; a thread window must name the
 *    caller's parent (`parent_mismatch` otherwise, re-checked under the scope writer);
 * 3. the canary owns the scope → leave it to the canary (it keeps its own cursor state);
 * 4. the page converts as a whole (shared [CanonicalMessageV2RestSnapshotFetcher.convertPage],
 *    reply counts fetched here, outside the scope writer), belongs to this scope, has no duplicate
 *    ids, and lies entirely on the requested side of the anchor;
 * 5. under the scope writer: canary, `stillCurrent()` and flag rechecked; the stored scope is
 *    restored (cursor + facts); the anchor must lie inside the stored window
 *    (`oldestLoadedSeq..newestLoadedSeq`) and its fact must be on disk. Since the window is
 *    contiguous and the page is the server's contiguous block next to the anchor, page ∪ window
 *    is contiguous. The edge (and its `hasLoaded*` flag) moves only when the page extends past it;
 *    a page entirely inside the window is a plain refresh with the cursor unchanged;
 * 6. retention: per-scope pruning keeps the newest [maxFactsPerScope] facts and never rewrites the
 *    cursor. An older page that would push the scope past the cap would be pruned straight back out
 *    while the cursor claimed it, so it is refused (`at_capacity`). A newer page that overflows the
 *    cap prunes the oldest end, so the cursor's oldest edge is moved to the oldest fact that
 *    survives and `hasLoadedOldest` is cleared;
 * 7. `applyIncremental` with the UNION cursor (the engine overwrites the cursor wholesale), bound
 *    to the partition generation captured before the network call.
 */
internal class CanonicalMessageV2AdjacentPageWriter(
    private val canary: CanonicalMessageV2ProductionCanary,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val fetcher: CanonicalMessageV2RestSnapshotFetcher,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    private val logger: (String) -> Unit = {},
    /** Must equal the store's [CanonicalMessageV2RetentionPolicy.maxFactsPerScope]. */
    private val maxFactsPerScope: Int = CanonicalMessageV2RetentionPolicy().maxFactsPerScope,
) : ThreadDurablePageExtender {
    override suspend fun extendWithAdjacentPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        stillCurrent: () -> Boolean,
    ) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val kindLabel = if (replyParent != null) " scopeKind=thread" else ""
        fun log(outcome: String) {
            logger(
                "canonical_v2_adjacent_page server=${partition.serverId} scope=$channelId$kindLabel " +
                    "direction=${direction.wireName} outcome=$outcome",
            )
        }
        if (channelId.isBlank() || channelId != channelId.trim()) return log("invalid_channel_id")
        if (!isEnabledForServer(partition.serverId)) return log("flag_off")
        if (rawMessages.isEmpty()) return log("empty_page")
        if (requestedLimit <= 0) return log("invalid_limit")

        // The scope family is the caller's explicit choice: a top-level window, or (task #168 step 3)
        // a reply thread. Neither ever resolves to the other's kinds.
        val allowedKinds =
            if (replyParent == null) CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS else CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
        val kinds = generationEngine.baselinedScopeKinds(partition, channelId).filter { it in allowedKinds }
        val scopeKind = when (kinds.size) {
            0 -> return log("no_window")
            1 -> kinds.single()
            else -> return log("ambiguous_scope_kind")
        }
        val scope = SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = scopeKind)
        if (canary.isSelected(scope)) return log("canary_owned")

        // A reply page is converted under the parent its stored cursor names, and only when that is
        // the route's parent (re-checked against the restored cursor under the scope writer).
        val storedParent = if (replyParent == null) {
            null
        } else {
            storedReplyParentOrNull(generationEngine.storedCursor(scope), replyParent)
                ?: return log("parent_mismatch")
        }

        val expectedGeneration = generationEngine.partitionGeneration(partition)
        val envelopes = when (
            val converted = fetcher.convertPage(partition, channelId, scopeKind, rawMessages, storedParent)
        ) {
            is CanonicalMessageV2PageConversion.Converted -> converted.envelopes
            is CanonicalMessageV2PageConversion.Refused -> return log(converted.diagnostic)
        }
        val foreign = envelopes.any { envelope ->
            val envelopeScopeId = when (val context = envelope.context) {
                is CanonicalConversationContextV2.TopLevel -> context.scope.scopeId.takeIf { replyParent == null }
                is CanonicalConversationContextV2.Thread -> context.replyScope.scopeId.takeIf { replyParent != null }
            }
            envelopeScopeId != channelId
        }
        if (foreign) return log("foreign_scope")
        if (envelopes.map { it.fact.id }.distinct().size != envelopes.size) return log("duplicate_fact")
        val pageSeqs = envelopes.map { it.fact.seq }
        val onRequestedSide = when (direction) {
            ThreadDurablePageDirection.Older -> pageSeqs.all { it < anchorSeq }
            ThreadDurablePageDirection.Newer -> pageSeqs.all { it > anchorSeq }
        }
        if (!onRequestedSide) return log("wrong_side_of_anchor")

        generationEngine.withScopeWriter(scope) {
            if (canary.isSelected(scope)) return@withScopeWriter log("canary_owned")
            if (!stillCurrent()) return@withScopeWriter log("identity_changed")
            if (!isEnabledForServer(partition.serverId)) return@withScopeWriter log("flag_revoked")
            try {
                // `restore` re-reads the committed cursor + facts and (re)marks the scope baselined in
                // this process, which `applyIncremental` requires. It only republishes what is on disk.
                val restored = generationEngine.restore(scope)
                val stored = restored.cursor ?: return@withScopeWriter log("no_window")
                if (replyParent != null && storedReplyParentOrNull(stored, replyParent) != storedParent) {
                    return@withScopeWriter log("parent_mismatch")
                }
                val storedOldest = stored.oldestLoadedSeq
                val storedNewest = stored.newestLoadedSeq
                // The anchor must lie inside the stored window: the window holds every message from
                // its oldest to its newest edge, and the page is the server's contiguous block right
                // next to the anchor, so page ∪ window is contiguous.
                if (storedOldest == null || storedNewest == null || anchorSeq !in storedOldest..storedNewest) {
                    return@withScopeWriter log("not_adjacent")
                }
                // ...and the anchor message itself must be on disk: a cursor range whose facts were
                // pruned (or never written) is not evidence that the window reaches the anchor.
                if (restored.envelopes.none { it.fact.seq == anchorSeq }) {
                    return@withScopeWriter log("anchor_missing")
                }
                val storedIds = restored.envelopes.mapTo(mutableSetOf()) { it.fact.id }
                val addedSeqs = envelopes.filter { it.fact.id !in storedIds }.map { it.fact.seq }
                val factCountAfter = restored.envelopes.size + addedSeqs.size
                val reachedEnd = envelopes.size < requestedLimit
                val cursor = when (direction) {
                    ThreadDurablePageDirection.Older -> {
                        if (factCountAfter > maxFactsPerScope) return@withScopeWriter log("at_capacity")
                        val pageMin = pageSeqs.min()
                        if (pageMin < storedOldest) {
                            stored.copy(oldestLoadedSeq = pageMin, hasLoadedOldest = reachedEnd)
                        } else {
                            stored // entirely inside the window: a plain refresh of known facts
                        }
                    }
                    ThreadDurablePageDirection.Newer -> {
                        val pageMax = pageSeqs.max()
                        val extended = if (pageMax > storedNewest) {
                            stored.copy(
                                latestSeq = maxOf(stored.latestSeq ?: pageMax, pageMax),
                                newestLoadedSeq = pageMax,
                                hasLoadedNewest = reachedEnd,
                            )
                        } else {
                            stored // entirely inside the window: a plain refresh of known facts
                        }
                        if (factCountAfter > maxFactsPerScope) {
                            // Counted over every stored fact (pinned thread roots included), so the
                            // edge is conservative: pruning never removes a fact at or above it.
                            val survivingOldest = (restored.envelopes.map { it.fact.seq } + addedSeqs)
                                .sortedDescending()
                                .take(maxFactsPerScope)
                                .min()
                            if (survivingOldest > storedOldest) {
                                extended.copy(oldestLoadedSeq = survivingOldest, hasLoadedOldest = false)
                            } else {
                                extended
                            }
                        } else {
                            extended
                        }
                    }
                }
                val outcome = when (
                    generationEngine.applyIncremental(
                        CanonicalMessageV2IncrementalFrame(scope = scope, envelopes = envelopes, cursor = cursor),
                        expectedGeneration,
                    )
                ) {
                    CanonicalMessageV2CommitResult.Committed -> "committed facts=${envelopes.size}"
                    CanonicalMessageV2CommitResult.PartitionRevoked -> "partition_revoked"
                    CanonicalMessageV2CommitResult.ScopeEvicted -> "scope_evicted"
                    is CanonicalMessageV2CommitResult.BaselineRequired -> "baseline_required"
                }
                log(outcome)
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                log("poisoned fact=${poisoned.factId.orEmpty()}")
            }
        }
    }
}

/** The stored cursor's parent, when it is exactly the route's [replyParent]; otherwise `null`. */
private fun storedReplyParentOrNull(
    cursor: PersistedCursor?,
    replyParent: ThreadDurableReplyParent,
): CanonicalMessageV2ReplyParentScope? {
    val stored = cursor ?: return null
    val kind = stored.parentScopeKind?.takeIf { it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS } ?: return null
    if (stored.parentScopeId != replyParent.parentChannelId || stored.rootMessageId != replyParent.rootMessageId) {
        return null
    }
    return CanonicalMessageV2ReplyParentScope(kind, replyParent.parentChannelId, replyParent.rootMessageId)
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private const val REPLY_THREAD_SCOPE_KIND: String = "thread"

private fun JsonObject.threadChannelIdOrNull(): String? =
    (this["threadId"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }

/** `null` = thread parent whose summary is missing; the caller drops the node (fail-closed). */
private fun JsonObject.snapshotReplyCountOrNull(replyCountByParentId: Map<String, Int>): Int? {
    threadChannelIdOrNull() ?: return 0
    val id = (this["id"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: return null
    return replyCountByParentId[id]
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
