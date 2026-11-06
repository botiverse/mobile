package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.model.ServerId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.raftLogInfo
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
import build.raft.app.network.sync.CanonicalMessageV2ThreadJoinConfirmed
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2Coverage
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra
import build.raft.app.network.sync.CanonicalMessageV2SegmentSource
import build.raft.app.network.sync.CanonicalMessageV2ThreadParent
import build.raft.app.network.sync.threadParent
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Task #168: the log sink of the canary and of the enter / paging / catch-up writers. Their lines already
 * start with their own tag (`canonical_v2_canary`, `canonical_v2_enter_snapshot`, `canonical_v2_adjacent_page`,
 * `canonical_v2_latest_page`), so nothing is prepended (a prefixing logger produced
 * `canonical_v2_adjacent_page canonical_v2_latest_page …` on device).
 */
internal fun canonicalMessageV2SelfTaggedLogger(
    platformTag: String,
    sink: (tag: String, message: String) -> Unit = { tag, message -> raftLogInfo(tag, message) },
): (String) -> Unit = { message -> sink(platformTag, message) }

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

    /**
     * Message storage Phase 3a: called INSIDE the exact-current-grant slot; reads only what binds the commit to that
     * grant and returns the commit, which the owner runs after the slot (never holding the authority registry
     * during disk work), before display. Default: [accept] as the deferred commit.
     */
    suspend fun captureUnderGrant(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): suspend () -> Unit = { accept(grant, foldInput, transportEpoch) }
}

/**
 * The realtime half of the durable canary (task #168 step 3): hands every frame to the canary with the
 * connection that delivered it. Never touches the network — a window is only ever created by the enter
 * snapshot (a channel's, or since task #14a a reply thread's), and a realtime frame never requests one.
 */
internal class ProviderBackedCanonicalMessageV2OwnerGateway(
    private val canary: CanonicalMessageV2ProductionCanary,
) : CanonicalMessageV2OwnerGateway {
    override suspend fun accept(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): CanonicalMessageV2CanaryOutcome =
        canary.accept(
            partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID),
            foldInput = foldInput,
            frameTransportEpoch = transportEpoch,
        )

    override suspend fun noteTransportEpoch(grant: RealtimeGrantRecord, transportEpoch: Long) {
        canary.noteTransportEpoch(grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID), transportEpoch)
    }
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
        require(parentScopeKind in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS)
    }
}

/** Result of [CanonicalMessageV2RestSnapshotFetcher.confirmReplyParent]. */
internal sealed interface CanonicalMessageV2ReplyParentConfirmation {
    data class Confirmed(val parent: CanonicalMessageV2ReplyParentScope) : CanonicalMessageV2ReplyParentConfirmation

    data class Refused(val diagnostic: String) : CanonicalMessageV2ReplyParentConfirmation
}

/**
 * Result of [CanonicalMessageV2RestSnapshotFetcher.convertPageRows] (message storage Phase 2b): the converted rows,
 * the seqs of the rows left out, how many left-out rows had no readable seq, and the first reason a row was left
 * out (`null` = every row converted).
 */
internal data class CanonicalMessageV2PageRows(
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val rejectedSeqs: Set<Long>,
    val rejectedWithoutSeq: Int,
    val firstRefusal: String?,
) {
    /** A left-out row with no seq cannot be split around: the page proves no range. */
    val claimable: Boolean get() = rejectedWithoutSeq == 0
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
    internal val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
) {
    /**
     * Message storage Phase 2b: the top-level kind of [scopeId] from `channels/{id}`, and a task's parent channel
     * (slock `schema.ts` `channels.parent_channel_id`). `null` = unavailable, not top-level, or a task without its
     * parent. Only asked when the page and the stored header cannot name the kind.
     */
    suspend fun topLevelKind(partition: DurablePartitionKey, scopeId: SyncScopeId): Pair<String, SyncScopeId?>? {
        val detail = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/$scopeId",
            serverId = partition.serverId,
        ) ?: return null
        // A forum channel (`threadSort: activity`, `channel_thread_sort_v0`) is never written from a page of this path.
        if (detail.stringOrNull("threadSort") == CHANNEL_THREAD_SORT_ACTIVITY) return null
        val channelType = (detail["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS }
            ?: return null
        if (channelType != CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) return channelType to null
        val parent = detail.stringOrNull("parentChannelId")?.takeIf { it.isNotBlank() && it == it.trim() } ?: return null
        return channelType to parent
    }

    /**
     * Task #14b / Phase 2b (shared by the enter write-through and the Activity prewarm writer): the server
     * confirms that [threadChannelId] is a `thread` under exactly [replyParent], and names the parent's
     * top-level kind. Never re-parents: any disagreement is [CanonicalMessageV2ReplyParentConfirmation.Refused].
     */
    suspend fun confirmReplyParent(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
    ): CanonicalMessageV2ReplyParentConfirmation {
        fun refused(diagnostic: String) = CanonicalMessageV2ReplyParentConfirmation.Refused(diagnostic)
        val detail = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/$threadChannelId",
            serverId = partition.serverId,
        ) ?: return refused("canonical_v2_channel_detail_unavailable")
        if (detail.stringOrNull("type") != "thread") return refused("canonical_v2_scope_not_thread")
        if (
            detail.stringOrNull("parentChannelId") != replyParent.parentChannelId ||
            detail.stringOrNull("parentMessageId") != replyParent.rootMessageId
        ) {
            return refused("canonical_v2_thread_parent_mismatch")
        }
        val parentDetail = fetchJson(
            priority = DataRequestPriority.Background,
            path = "channels/${replyParent.parentChannelId}",
            serverId = partition.serverId,
        ) ?: return refused("canonical_v2_parent_detail_unavailable")
        val parentKind = parentDetail.stringOrNull("type")
            ?.takeIf { it in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS }
            ?: return refused("canonical_v2_parent_not_top_level")
        return CanonicalMessageV2ReplyParentConfirmation.Confirmed(
            CanonicalMessageV2ReplyParentScope(
                parentScopeKind = parentKind,
                parentScopeId = replyParent.parentChannelId,
                rootMessageId = replyParent.rootMessageId,
            ),
        )
    }

    /**
     * Step 3 of [fetchReplyThread]: a reply page (the server's latest [pageLimit] replies of the thread)
     * → the `thread` snapshot frame under [parent]. The page converts as a whole or not at all; an
     * empty page is an empty baseline (task #1137 rule). `hasLoadedOldest` iff the page was short of
     * the limit the request sent.
     */
    suspend fun replyThreadFrame(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        parent: CanonicalMessageV2ReplyParentScope,
        rawMessages: JsonArray,
        pageLimit: Int,
        /** The page's server window and history flag (Phase 2a: what range the page proves). */
        messageWindow: ChannelMessageWindow? = null,
        historyLimited: Boolean = false,
    ): CanonicalMessageV2SnapshotLoadResult {
        val scope = SyncScopeKey(partition = partition, scopeId = threadChannelId, scopeKind = REPLY_THREAD_SCOPE_KIND)
        if (rawMessages.isEmpty()) {
            // A thread with no replies yet is an answer too (task #1137 rule): an empty baseline.
            logger("canonical_v2_snapshot_empty_page scope=$threadChannelId scopeKind=$REPLY_THREAD_SCOPE_KIND")
        }
        // Phase 2b: row by row — a bad reply is left out and the claim is split around it.
        val rows = convertPageRows(partition, threadChannelId, REPLY_THREAD_SCOPE_KIND, rawMessages, parent)
        rows.firstRefusal?.let { logger("canonical_v2_page_rows_left_out scope=$threadChannelId scopeKind=thread reason=$it") }
        val envelopes = rows.envelopes
        if (envelopes.isEmpty() && rawMessages.isNotEmpty()) return unavailable(rows.firstRefusal ?: "canonical_v2_snapshot_not_full_v6")
        return CanonicalMessageV2SnapshotLoadResult.Loaded(
            CanonicalMessageV2SnapshotFrame(
                scope = scope,
                envelopes = envelopes,
                cursor = snapshotCursor(envelopes, pageLimit).copy(
                    parentScopeKind = parent.parentScopeKind,
                    parentScopeId = parent.parentScopeId,
                    rootMessageId = parent.rootMessageId,
                ),
            ),
            latestPageCoverage(
                partition, threadChannelId, envelopes.map { it.fact.seq } + rows.rejectedSeqs, pageLimit, messageWindow, historyLimited,
            )?.takeIf { rows.claimable },
            excludedSeqs = rows.rejectedSeqs,
        )
    }

    /** The cursor of a latest page: both edges from its seqs, oldest reached iff the page was short. */
    private fun snapshotCursor(
        envelopes: List<CanonicalMessageEnvelopeV2>,
        pageLimit: Int = CANONICAL_V2_CANARY_PAGE_LIMIT,
    ): PersistedCursor {
        val seqs = envelopes.map { it.fact.seq }
        return PersistedCursor(
            latestSeq = seqs.maxOrNull(),
            oldestLoadedSeq = seqs.minOrNull(),
            newestLoadedSeq = seqs.maxOrNull(),
            hasLoadedOldest = envelopes.size < pageLimit,
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
        /** Task v2: the task's channel, required for, and only for, a `task` page. */
        taskParentChannelId: SyncScopeId? = null,
    ): CanonicalMessageV2PageConversion {
        val rows = convertPageRows(partition, scopeId, channelType, rawMessages, replyParent, taskParentChannelId)
        rows.firstRefusal?.let { return CanonicalMessageV2PageConversion.Refused(it) }
        return CanonicalMessageV2PageConversion.Converted(rows.envelopes)
    }

    /**
     * Message storage Phase 2b: [convertPage] row by row. A row that is not full v6, fails to decode, or is a thread
     * parent whose reply count is unknown is left out instead of refusing the page: the writer stores the other rows
     * and splits the page's claim around the left-out seqs ([CanonicalMessageV2PageRows.rejectedSeqs]). A left-out
     * row without a readable seq ([CanonicalMessageV2PageRows.rejectedWithoutSeq]) cannot be split around, so the
     * page then claims no range at all.
     *
     * [replyCountByParentId]: the counts the visible page already carried (its embedded summaries); `null` = fetch
     * them from `channels/{id}/threads` (only when the page has thread parents). A failed fetch leaves only the
     * thread-parent rows out.
     */
    suspend fun convertPageRows(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        channelType: String,
        rawMessages: JsonArray,
        replyParent: CanonicalMessageV2ReplyParentScope? = null,
        taskParentChannelId: SyncScopeId? = null,
        replyCountByParentId: Map<String, Int>? = null,
    ): CanonicalMessageV2PageRows {
        require((channelType == REPLY_THREAD_SCOPE_KIND) == (replyParent != null)) {
            "a reply parent is required for, and only for, a thread page"
        }
        require((channelType == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) == (taskParentChannelId != null)) {
            "a task parent channel is required for, and only for, a task page"
        }
        // The messages page never carries replyCount/threadSummary (server emits those only via
        // `channels/{id}/threads`, embedded in the page as `threadSummariesByParentMessageId`, and the internal agent
        // API). Messages without `threadId` have exactly zero replies; thread parents get the real count from the
        // summaries, and a parent whose count is unknown is left out rather than persisted with a zeroed count.
        val counts: Map<String, Int>? = when {
            replyParent != null -> emptyMap()
            replyCountByParentId != null -> replyCountByParentId
            else -> fetchThreadReplyCounts(partition, scopeId, rawMessages)
        }
        val context = CanonicalConversationContextWire(
            channelType = channelType,
            parentChannelId = replyParent?.parentScopeId ?: taskParentChannelId,
            parentChannelType = replyParent?.parentScopeKind,
            parentMessageId = replyParent?.rootMessageId,
        )
        val envelopes = mutableListOf<CanonicalMessageEnvelopeV2>()
        val rejectedSeqs = mutableSetOf<Long>()
        var rejectedWithoutSeq = 0
        var firstRefusal: String? = null
        fun reject(raw: JsonObject?, diagnostic: String) {
            if (firstRefusal == null) firstRefusal = diagnostic
            val seq = (raw?.get("seq") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            if (seq != null && seq >= 0L) rejectedSeqs += seq else rejectedWithoutSeq += 1
        }
        rawMessages.forEach { element ->
            val raw = element as? JsonObject ?: return@forEach reject(null, "canonical_v2_snapshot_not_full_v6")
            if (replyParent != null && raw.threadChannelIdOrNull() != null) {
                // The V2 normalizer has no nested-thread shape.
                return@forEach reject(raw, "canonical_v2_thread_nested_reply")
            }
            if (!raw.hasFullV2SnapshotNodeKeys()) return@forEach reject(raw, "canonical_v2_snapshot_not_full_v6")
            val carry = decodeCanonicalMessageWire(raw, ProductionFace.EnrichedUpdated) { violation ->
                logger(
                    "canonical_v2_snapshot_decode_rejected scope=$scopeId " +
                        "fields=${violation.missingOrInvalidRequiredFields.sorted().joinToString(",")}",
                )
            } ?: return@forEach reject(raw, "canonical_v2_snapshot_not_full_v6")
            val replyCount = when {
                raw.threadChannelIdOrNull() == null -> 0
                counts == null -> return@forEach reject(raw, "canonical_v2_thread_summaries_unavailable")
                else -> raw.snapshotReplyCountOrNull(counts)
            }
            if (replyCount == null) {
                logger(
                    "canonical_v2_snapshot_reply_count_missing scope=$scopeId " +
                        "message=${(raw["id"] as? JsonPrimitive)?.contentOrNull}",
                )
                return@forEach reject(raw, "canonical_v2_snapshot_not_full_v6")
            }
            val envelope = CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.Updated, carry)
                .toCanonicalMessageV2Envelope(
                    partition = partition,
                    replyCount = replyCount,
                    producerContextOverride = context,
                ) ?: return@forEach reject(raw, "canonical_v2_snapshot_not_full_v6")
            envelopes += envelope
        }
        return CanonicalMessageV2PageRows(envelopes, rejectedSeqs, rejectedWithoutSeq, firstRefusal)
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
 * 4. `stillCurrent()` re-read under the scope writer, after the network round trip;
 * 5. `applySnapshot` bound to the partition generation captured before the fetch, so a partition
 *    cleared in between (sign-out) is refused by the engine rather than repopulated.
 *
 * Task #168 step 3: the enter snapshot is the realtime canary's ONLY baseline. For a top-level channel it
 * registers a pending baseline with the canary BEFORE the fetch
 * ([CanonicalMessageV2ProductionCanary.beginEnterBaseline]: request epoch, room-leave count, membership),
 * so realtime frames of the channel that arrive during the fetch are displayed and buffered instead of
 * being written and then wiped by this commit (hole A). Right after the commit, inside the same scope
 * writer, [CanonicalMessageV2ProductionCanary.onEnterCommitted] replays them and makes the window
 * continuous — unless the user is not a member of the channel ([isKnownNonMember]: the server joins a
 * non-member's room asynchronously on open, so the page may miss messages that are never pushed either).
 * Any path that does not commit abandons the baseline. Task #14a: a reply-thread enter registers the same
 * baseline for its thread channel, naming the server-confirmed parent channel and whether the directory says
 * the user is not a member of the thread / of its parent channel. Task #14c: its commit makes the thread window
 * continuous only under a public parent whose thread-room `channel:joined` arrived before the request (see
 * [CanonicalMessageV2ProductionCanary]); otherwise its realtime replies stay display only while this page is
 * still written.
 *
 * Task #14b: [persistReplyThreadPrewarmPage] writes the reply page the Activity prewarm already fetched,
 * through the same gates minus the baseline (see there).
 */
internal class CanonicalMessageV2EnteredScopeLoader(
    private val canary: CanonicalMessageV2ProductionCanary,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    internal val fetcher: CanonicalMessageV2RestSnapshotFetcher,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    private val logger: (String) -> Unit = {},
    /**
     * Whether the conversation directory says the user is NOT a member of (serverId, channelId). Unknown =
     * `false` (treated as a member: auto-joined at connect, like #2994's leave rule).
     */
    private val isKnownNonMember: (serverId: String, channelId: String) -> Boolean = { _, _ -> false },
    /** Task #14b, the prewarm's extend: must equal the store's [CanonicalMessageV2RetentionPolicy.maxFactsPerScope]. */
    private val maxFactsPerScope: Int = CanonicalMessageV2RetentionPolicy().maxFactsPerScope,
) : ThreadEnteredScopeSnapshotLoader {
    private val inFlightMutex = Mutex()
    /** Task #14b: prewarm writes in flight, guarded by [inFlightMutex]; never blocks an enter. */
    private val prewarmInFlight = mutableSetOf<Pair<DurablePartitionKey, SyncScopeId>>()

    override suspend fun beginEnter(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent?,
        registerBaseline: Boolean,
        stamp: ThreadDurableRequestStamp?,
    ): ThreadDurableEnterTicket? {
        if (partition.consumerId != SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) return null
        if (channelId.isBlank() || channelId != channelId.trim()) return null
        if (replyParent != null && replyParent.parentChannelId == channelId) return null
        if (!isEnabledForServer(partition.serverId)) return null
        // Read BEFORE the baseline is registered: a cancellation or failure here leaves no open baseline behind.
        // Read before the fetch: a fact written after this point (also a realtime loose one) is newer and stays.
        val taken = stamp?.takeIf { it.partition == partition } ?: requestStamp(partition)
        // Registered before the fetch (hole A), for a reply thread too (task #14a). Task #14c: the thread's own
        // membership and its parent's are separate (a thread join ack may lift the former, never the latter).
        val baseline = if (registerBaseline) {
            canary.beginEnterBaseline(
                partition,
                channelId,
                nonMember = isKnownNonMember(partition.serverId, channelId),
                replyParentScopeId = replyParent?.parentChannelId,
                parentNonMember = replyParent != null && isKnownNonMember(partition.serverId, replyParent.parentChannelId),
            )
        } else {
            null
        }
        return ThreadDurableEnterTicket(
            partition = partition,
            channelId = channelId,
            replyParent = replyParent,
            expectedPartitionGeneration = taken.expectedPartitionGeneration,
            revAtRequest = taken.revAtRequest,
            baseline = baseline,
            expectedHeadEpoch = taken.expectedHeadEpoch,
        )
    }

    override suspend fun requestStamp(partition: DurablePartitionKey): ThreadDurableRequestStamp =
        ThreadDurableRequestStamp(
            partition = partition,
            expectedPartitionGeneration = generationEngine.partitionGeneration(partition),
            revAtRequest = generationEngine.storedLocalRev(partition),
            // Phase 3b: a horizonReset after this read voids the page's head claim (never its facts).
            expectedHeadEpoch = generationEngine.partitionHeadEpoch(partition),
        )

    override suspend fun abandonEnter(ticket: ThreadDurableEnterTicket) {
        ticket.baseline?.let { canary.abandonEnterBaseline(it) }
    }

    /**
     * Phase 2b: the thread store's own page, written through. Gates, each answering "write nothing" (and
     * abandoning the baseline):
     * 1. flag; the page is the ticket's channel;
     * 2. the scope: a reply thread's parent is the stored, server-confirmed header's when it names the route's
     *    parent, else confirmed with the server ([CanonicalMessageV2RestSnapshotFetcher.confirmReplyParent]); a
     *    top-level kind comes from the page itself (its rows' `conversationContext`), else the channel detail the
     *    same load fetched, else the stored header, else `channels/{id}` — the only request this writer may make
     *    besides the reply counts of an old server's page and a reply thread's first confirmation;
     * 3. rows convert one by one; a left-out row splits the page's claim ([CanonicalMessageV2PageRows]);
     * 4. under the scope writer: `stillCurrent()` and flag rechecked; a thread header naming another parent is
     *    cleared; [CanonicalMessageV2GenerationEngine.applyCoverage] with the ticket's generation and revision. Only
     *    a page whose (split) claim still reaches the head, and whose head claim no horizonReset voided since the
     *    ticket ([CanonicalMessageV2GenerationEngine.applyCoverageReportingBaseline]), is the realtime baseline
     *    ([CanonicalMessageV2ProductionCanary.onEnterCommitted]); any other commit is an external commit.
     */
    override suspend fun persistEnteredPage(
        ticket: ThreadDurableEnterTicket,
        page: ThreadDurableEnteredPage,
        stillCurrent: () -> Boolean,
    ) {
        val partition = ticket.partition
        val scopeId = ticket.channelId
        val replyParent = ticket.replyParent
        val replyThread = replyParent != null
        fun log(outcome: String) = log(partition, scopeId, replyThread, outcome)
        try {
            if (!isEnabledForServer(partition.serverId)) return log("flag_off")
            if (page.channelId != scopeId) return log("page_mismatch")
            val scope: SyncScopeKey
            val threadParent: CanonicalMessageV2ReplyParentScope?
            val taskParentChannelId: SyncScopeId?
            if (replyParent != null) {
                scope = SyncScopeKey(partition = partition, scopeId = scopeId, scopeKind = REPLY_THREAD_SCOPE_KIND)
                // A windowless header was written by a realtime reply (the parent its frame claimed): unconfirmed.
                val stored = generationEngine.storedCursor(scope)?.takeIf { it.oldestLoadedSeq != null || it.hasLoadedOldest }
                threadParent = stored?.let { storedReplyParentOrNull(it, replyParent) }
                    ?: when (val confirmed = fetcher.confirmReplyParent(partition, scopeId, replyParent)) {
                        is CanonicalMessageV2ReplyParentConfirmation.Confirmed -> confirmed.parent
                        is CanonicalMessageV2ReplyParentConfirmation.Refused -> return log(confirmed.diagnostic)
                    }
                taskParentChannelId = null
            } else {
                val kind = topLevelKindOf(partition, scopeId, page) ?: return log("canonical_v2_scope_not_top_level")
                scope = SyncScopeKey(partition = partition, scopeId = scopeId, scopeKind = kind.first)
                threadParent = null
                taskParentChannelId = kind.second
            }
            if (page.rawMessages.isEmpty()) {
                logger("canonical_v2_snapshot_empty_page scope=$scopeId${if (replyThread) " scopeKind=thread" else ""}")
            }
            val rows = fetcher.convertPageRows(
                partition = partition,
                scopeId = scopeId,
                channelType = scope.scopeKind,
                rawMessages = page.rawMessages,
                replyParent = threadParent,
                taskParentChannelId = taskParentChannelId,
                replyCountByParentId = page.replyCountByParentId,
            )
            if (rows.envelopes.isEmpty() && page.rawMessages.isNotEmpty()) {
                return log(rows.firstRefusal ?: "canonical_v2_snapshot_not_full_v6")
            }
            val foreign = rows.envelopes.any { envelope ->
                val envelopeScopeId = when (val context = envelope.context) {
                    is CanonicalConversationContextV2.TopLevel -> context.scope.scopeId.takeIf { !replyThread }
                    is CanonicalConversationContextV2.Thread -> context.replyScope.scopeId.takeIf { replyThread }
                }
                envelopeScopeId != scopeId
            }
            if (foreign) return log("foreign_scope")
            val coverage = latestPageCoverage(
                partition,
                scopeId,
                rows.envelopes.map { it.fact.seq } + rows.rejectedSeqs,
                page.requestedLimit,
                page.messageWindow,
                page.historyLimited,
            )?.takeIf { rows.claimable }
            val atHead = coverage?.excluding(rows.rejectedSeqs)?.lastOrNull()?.headAtVerify == true
            val leftOut = rows.firstRefusal?.let { " left_out=${rows.rejectedSeqs.size + rows.rejectedWithoutSeq} reason=$it" }.orEmpty()
            val cursorParent = threadParent?.let {
                CanonicalMessageV2ThreadParent(it.parentScopeKind, it.parentScopeId, it.rootMessageId)
            }
            generationEngine.withScopeWriter(scope) {
                if (!stillCurrent()) return@withScopeWriter log("identity_changed")
                if (!isEnabledForServer(partition.serverId)) return@withScopeWriter log("flag_revoked")
                try {
                    clearHeaderOfAnotherParent(scope, cursorParent)?.let(::log)
                    // Phase 2a (P1): the page is added, never a rebaseline — older stored pages stay.
                    val commit = generationEngine.applyCoverageReportingBaseline(
                        scope = scope,
                        envelopes = rows.envelopes,
                        coverage = coverage,
                        revAtRequest = ticket.revAtRequest,
                        userDriven = true,
                        expectedPartitionGeneration = ticket.expectedPartitionGeneration,
                        threadParent = cursorParent,
                        // A page that proves no head range stores its facts; it is no realtime baseline — the stored
                        // head would otherwise be continued past a hole.
                        establishesBaseline = atHead,
                        excludedSeqs = rows.rejectedSeqs,
                        // Phase 3b: read with the ticket, before the request.
                        expectedHeadEpoch = ticket.expectedHeadEpoch,
                    )
                    val result = commit.result
                    // A horizonReset since the ticket voided the page's head claim: facts and range only, no baseline.
                    val headVoided = atHead && result == CanonicalMessageV2CommitResult.Committed && !commit.baselineAdmitted
                    log(
                        result.wireOutcome(rows.envelopes.size) + leftOut +
                            (if (!atHead) " coverage=${coverage?.let { "not_head" } ?: "none"}" else "") +
                            (if (headVoided) " head_claim=stale_epoch" else ""),
                    )
                    if (result == CanonicalMessageV2CommitResult.Committed) {
                        val baseline = ticket.baseline
                        if (baseline != null && commit.baselineAdmitted) {
                            val committed = generationEngine.currentCursor(scope)
                            val frame = CanonicalMessageV2SnapshotFrame(
                                scope = scope,
                                envelopes = rows.envelopes,
                                cursor = committed ?: PersistedCursor(null, null, null),
                            )
                            canary.onEnterCommitted(
                                baseline,
                                frame,
                                nonMemberAtCommit = isKnownNonMember(partition.serverId, scopeId),
                                parentNonMemberAtCommit = replyParent != null &&
                                    isKnownNonMember(partition.serverId, replyParent.parentChannelId),
                            )
                        } else {
                            canary.onExternalCommit(scope)
                        }
                    }
                } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                    // A stale/cross-scope durable row must never escape the background write. The page already
                    // has its network-backed content; leave the durable scope untouched.
                    log("poisoned fact=${poisoned.factId.orEmpty()}")
                }
            }
        } finally {
            // No-op once committed; otherwise the frames buffered meanwhile were never written.
            ticket.baseline?.let { canary.abandonEnterBaseline(it) }
        }
    }

    /**
     * The top-level scope kind of [page] (and a task's parent channel): the page's own rows first (every row names
     * its `conversationContext.channelType`), then the channel detail the same load fetched, then the stored header
     * (exactly one top-level kind), then `channels/{id}`. `null` = not a top-level conversation.
     */
    private suspend fun topLevelKindOf(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        page: ThreadDurableEnteredPage,
    ): Pair<String, SyncScopeId?>? {
        val contexts = page.rawMessages.mapNotNull { (it as? JsonObject)?.get("conversationContext") as? JsonObject }
        val rowKinds = contexts.mapNotNull { it.stringOrNull("channelType") }.toSet()
        val storedKinds = generationEngine.baselinedScopeKinds(partition, scopeId).filter { it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS }
        val kind = rowKinds.singleOrNull()
            ?: page.channelType?.takeIf { rowKinds.isEmpty() }
            ?: storedKinds.singleOrNull()?.takeIf { rowKinds.isEmpty() }
        if (kind != null && kind !in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS) return null
        if (kind != null && kind != CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) return kind to null
        val rowTaskParent = contexts.mapNotNull { it.stringOrNull("parentChannelId") }.toSet().singleOrNull()
        if (kind != null && (page.taskParentChannelId ?: rowTaskParent) != null) {
            return kind to (page.taskParentChannelId ?: rowTaskParent)
        }
        // Unknown (an empty page of a scope never stored) or a task without its parent: ask the server.
        return fetcher.topLevelKind(partition, scopeId)
    }

    /**
     * Message storage Phase 3a/2a: a realtime reply may have created the thread's header naming the parent its
     * frame claimed. The server-confirmed [parent] of an enter / prewarm wins: a header naming another parent
     * channel or root is another thread's, so the scope is cleared before the page is written (the design's
     * "a mismatch triggers a clear and rewrite"). Returns the log outcome when it cleared, else `null`. Must run
     * inside the scope writer.
     */
    private suspend fun clearHeaderOfAnotherParent(scope: SyncScopeKey, parent: CanonicalMessageV2ThreadParent?): String? {
        parent ?: return null
        val stored = generationEngine.storedCursor(scope)?.threadParent() ?: return null
        if (stored.parentScopeId == parent.parentScopeId && stored.rootMessageId == parent.rootMessageId) return null
        generationEngine.clearScope(scope)
        return "parent_rewritten old=${stored.parentScopeId}/${stored.rootMessageId}"
    }

    private fun log(partition: DurablePartitionKey, scopeId: SyncScopeId, replyThread: Boolean, outcome: String) {
        val kind = if (replyThread) " scopeKind=thread" else ""
        logger("canonical_v2_enter_snapshot server=${partition.serverId} scope=$scopeId$kind outcome=$outcome")
    }

    /**
     * Task #14b, gates in order, each answering "write nothing + one `canonical_v2_prewarm_page
     * outcome=skipped` line":
     * 1. malformed thread id / server flag off / non-positive limit / parent equal to the thread → before any I/O;
     * 2. an ENTER of the same (partition, thread) in flight → `enter_in_flight` (it writes a page fetched
     *    later, so at least as new); a second prewarm of it → `in_flight`. A prewarm never makes an enter wait
     *    or skip: the enter keeps its own [inFlight] set;
     * 3. the parent is server-confirmed: a stored `thread` window's cursor parent when one exists (the
     *    adjacent writer's rule; it must be exactly the route's parent), otherwise the enter's
     *    [CanonicalMessageV2RestSnapshotFetcher.confirmReplyParent]. Never taken from the Activity row;
     * 4. the page converts as a whole into envelopes of this thread scope (shared
     *    [CanonicalMessageV2RestSnapshotFetcher.replyThreadFrame]);
     * 5. under the scope writer (serialized with the enter / paging writers): `stillCurrent()` and flag
     *    rechecked; `superseded` when the scope's commit generation moved since the caller read it before the
     *    page fetch ([replyThreadCommitGeneration]: a deletion seen by a later enter is never undone); race
     *    guard — a stored window whose parent differs, or whose newest edge is at or past
     *    the page's newest reply, is kept (`stored_not_older`): an enter that committed first, or a newer
     *    prewarm, is never regressed by an older page;
     * 6. a strictly newer page is written exactly like the gap catch-up's latest page
     *    ([latestPageJoinedCursorOrNull]): joined to the stored window → `applyIncremental` with the union
     *    cursor (`window_extended`, every stored fact kept); a real gap → `applySnapshot` (`window_replaced`);
     *    no window → `applySnapshot` (`window_created`). Each bound to the partition generation captured
     *    before step 3. After a commit the canary
     *    is told [CanonicalMessageV2ProductionCanary.onExternalCommit] (no baseline: the thread window stays
     *    display only for realtime replies, `thread_delivery_unconfirmed` is untouched).
     */
    override suspend fun currentLocalRev(partition: DurablePartitionKey): Long? =
        if (partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) generationEngine.storedLocalRev(partition) else null

    override fun replyThreadCommitGeneration(partition: DurablePartitionKey, threadChannelId: SyncScopeId): Long =
        generationEngine.scopeCommitGeneration(
            SyncScopeKey(partition = partition, scopeId = threadChannelId, scopeKind = REPLY_THREAD_SCOPE_KIND),
        )

    override fun headEpoch(partition: DurablePartitionKey): Long = generationEngine.partitionHeadEpoch(partition)

    override suspend fun persistReplyThreadPrewarmPage(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        rawMessages: JsonArray,
        requestedLimit: Int,
        expectedCommitGeneration: Long,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        requestHeadEpoch: Long?,
        revAtRequest: Long?,
        stillCurrent: () -> Boolean,
    ) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        fun log(outcome: String, reason: String, facts: Int = 0) {
            logger(
                "canonical_v2_prewarm_page server=${partition.serverId} scope=$threadChannelId scopeKind=thread " +
                    "outcome=$outcome reason=$reason facts=$facts",
            )
        }
        fun skip(reason: String) = log("skipped", reason)
        if (threadChannelId.isBlank() || threadChannelId != threadChannelId.trim()) return skip("invalid_scope_id")
        if (!isEnabledForServer(partition.serverId)) return skip("flag_off")
        if (requestedLimit <= 0) return skip("invalid_limit")
        if (replyParent.parentChannelId == threadChannelId) return skip("parent_mismatch")
        val key = partition to threadChannelId
        val admission = inFlightMutex.withLock {
            when {
                !prewarmInFlight.add(key) -> "in_flight"
                else -> null
            }
        }
        if (admission != null) return skip(admission)
        try {
            val scope = SyncScopeKey(partition = partition, scopeId = threadChannelId, scopeKind = REPLY_THREAD_SCOPE_KIND)
            val expectedGeneration = generationEngine.partitionGeneration(partition)
            // Phase 3b: a horizonReset after this read voids the page's head claim (never its facts).
            val expectedHeadEpoch = requestHeadEpoch ?: generationEngine.partitionHeadEpoch(partition)
            // Message storage Phase 3a: a header with no window was created by a realtime reply and names only the
            // parent that frame claimed: not server-confirmed, so it is confirmed here like a missing one.
            val stored = generationEngine.storedCursor(scope)?.takeIf { it.oldestLoadedSeq != null || it.hasLoadedOldest }
            val parent = if (stored != null) {
                storedReplyParentOrNull(stored, replyParent) ?: return skip("parent_mismatch")
            } else {
                when (val confirmed = fetcher.confirmReplyParent(partition, threadChannelId, replyParent)) {
                    is CanonicalMessageV2ReplyParentConfirmation.Confirmed -> confirmed.parent
                    is CanonicalMessageV2ReplyParentConfirmation.Refused -> return skip(confirmed.diagnostic)
                }
            }
            val loaded = when (
                val built = fetcher.replyThreadFrame(
                    partition, threadChannelId, parent, rawMessages, requestedLimit, messageWindow, historyLimited,
                )
            ) {
                is CanonicalMessageV2SnapshotLoadResult.Loaded -> built
                is CanonicalMessageV2SnapshotLoadResult.Unavailable -> return skip(built.diagnostic)
            }
            val frame = loaded.frame
            val foreign = frame.envelopes.any { envelope ->
                (envelope.context as? CanonicalConversationContextV2.Thread)?.replyScope?.scopeId != threadChannelId
            }
            if (foreign) return skip("foreign_scope")
            generationEngine.withScopeWriter(scope) {
                if (!stillCurrent()) return@withScopeWriter skip("identity_changed")
                if (!isEnabledForServer(partition.serverId)) return@withScopeWriter skip("flag_revoked")
                // Any write of this scope since the prewarm read the generation (before its page fetch) — e.g. an
                // enter whose page no longer has a reply deleted meanwhile — makes the page stale: the seq guard
                // below cannot see a deletion, and extending would write the deleted reply back.
                if (generationEngine.scopeCommitGeneration(scope) != expectedCommitGeneration) {
                    return@withScopeWriter skip("superseded")
                }
                try {
                    // Phase 3a: a windowless (realtime-only) header is no window: the page creates one.
                    val current = generationEngine.storedCursor(scope)?.takeIf { it.oldestLoadedSeq != null || it.hasLoadedOldest }
                    if (current != null) {
                        if (storedReplyParentOrNull(current, replyParent) != parent) {
                            return@withScopeWriter skip("parent_mismatch")
                        }
                        val pageNewest = frame.cursor.newestLoadedSeq
                        val storedNewest = current.newestLoadedSeq
                        if (pageNewest == null || (storedNewest != null && pageNewest <= storedNewest)) {
                            return@withScopeWriter skip("stored_not_older")
                        }
                    }
                    // Phase 2a (P1): the page's range is added to the stored ones; nothing stored is dropped.
                    // Phase 2b: revAtRequest was read by the caller before its fetch. The commit-generation check above
                    // does not see realtime loose writes (countsAsScopeCommit = false); their revision does.
                    if (current == null) clearHeaderOfAnotherParent(scope, frame.cursor.threadParent())?.let { skipped ->
                        log("parent_rewritten", skipped)
                    }
                    val before = generationEngine.storedSegments(scope)
                    val result = generationEngine.applyCoverage(
                        scope = scope,
                        envelopes = frame.envelopes,
                        coverage = loaded.coverage,
                        revAtRequest = revAtRequest ?: generationEngine.storedLocalRev(partition),
                        userDriven = true,
                        expectedPartitionGeneration = expectedGeneration,
                        threadParent = frame.cursor.threadParent(),
                        establishesBaseline = loaded.coverage?.excluding(loaded.excludedSeqs)?.lastOrNull()?.headAtVerify == true,
                        excludedSeqs = loaded.excludedSeqs,
                        expectedHeadEpoch = expectedHeadEpoch,
                    )
                    when (result) {
                        CanonicalMessageV2CommitResult.Committed -> {
                            log("committed", rangeOutcome(before, generationEngine.storedSegments(scope)), frame.envelopes.size)
                            canary.onExternalCommit(frame.scope)
                        }
                        else -> skip(result.wireOutcome(frame.envelopes.size))
                    }
                } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                    skip("poisoned fact=${poisoned.factId.orEmpty()}")
                }
            }
        } finally {
            withContext(NonCancellable) { inFlightMutex.withLock { prewarmInFlight.remove(key) } }
        }
    }
}

/**
 * Production [ThreadDurablePageExtender] (task #168; message storage Phase 2c): stores paging and context pages through
 * the segment model. Every refusal writes nothing and logs one line.
 *
 * - The scope key comes from the response: a paging page's `messageWindow.scopeId`, a context page's top-level
 *   `channelId`. A page with neither proves nothing and is not written.
 * - A claim only connects to stored coverage by the proofs [adjacentPageCoverage] and [contextPageWrite] use. A page
 *   that connects to nothing is an island: stored, invisible until Phase 2d (head rule: [CanonicalMessageV2SegmentAlgebra.head]).
 * - Only a `direction: latest` fetch proves the head; the newest [PAGE_SEAL_GRACE_MS] of a page are never sealed
 *   ([sealPageClaim]).
 * - After a commit, still inside the scope writer the realtime canary serializes on: a write that moved the head's
 *   top (nothing proves it continuous with the realtime stream, #3008) is an external commit; any other keeps
 *   continuity and any enter baseline in flight ([reportCommitToCanary]).
 */
internal class CanonicalMessageV2AdjacentPageWriter(
    private val canary: CanonicalMessageV2ProductionCanary,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val fetcher: CanonicalMessageV2RestSnapshotFetcher,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    private val logger: (String) -> Unit = {},
    /** Same rule as the enter loader's: unknown = member. */
    private val isKnownNonMember: (serverId: String, channelId: String) -> Boolean = { _, _ -> false },
    private val nowMs: () -> Long = ::defaultNowMillis,
) : ThreadDurablePageExtender {
    override suspend fun beginLatestPageBaseline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent?,
    ): CanonicalMessageV2EnterBaseline? {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (channelId.isBlank() || channelId != channelId.trim()) return null
        if (!isEnabledForServer(partition.serverId)) return null
        if (replyParent != null && replyParent.parentChannelId == channelId) return null
        return canary.beginCatchUpBaseline(
            partition,
            channelId,
            nonMember = isKnownNonMember(partition.serverId, channelId),
            replyParentScopeId = replyParent?.parentChannelId,
            parentNonMember = replyParent != null && isKnownNonMember(partition.serverId, replyParent.parentChannelId),
        )
    }

    /** Task #14c: the canary's late-ack catch-up requests (see [CanonicalMessageV2ProductionCanary.threadJoinConfirmations]). */
    override fun threadJoinConfirmations(): Flow<CanonicalMessageV2ThreadJoinConfirmed> = canary.threadJoinConfirmations

    override suspend fun currentLocalRev(partition: DurablePartitionKey): Long? =
        if (partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) generationEngine.storedLocalRev(partition) else null

    override suspend fun abandonLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) =
        canary.abandonEnterBaseline(baseline)

    override fun headEpoch(partition: DurablePartitionKey): Long = generationEngine.partitionHeadEpoch(partition)

    override suspend fun extendWithAdjacentPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        requestHeadEpoch: Long?,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        revAtRequest: Long?,
        replyCountByParentId: Map<String, Int>?,
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
        // An empty page claims nothing. Its window is no range (coveredAfterSeq is the high-water with no row), and it
        // can only follow a page whose claim could not reach the oldest. `after` says nothing about the head either.
        if (rawMessages.isEmpty()) return log("empty_page")
        if (requestedLimit <= 0) return log("invalid_limit")
        // The scope key is the response's. No window (an older server, or a page that is not a seq page such as
        // `by-activity`) names no scope and proves no range, so nothing is written.
        val window = messageWindow ?: return log("no_message_window")
        val scopeId = window.scopeId?.takeIf { it.isNotBlank() && it == it.trim() } ?: return log("no_message_window")
        if (window.serverId != partition.serverId) return log("window_server_mismatch")
        val routeParent = replyParent?.takeIf { scopeId == channelId }
        if (replyParent != null && routeParent == null) return log("foreign_scope")

        // The scope family is the caller's explicit choice: a top-level window, or (task #168 step 3)
        // a reply thread. Neither ever resolves to the other's kinds.
        val allowedKinds =
            if (replyParent == null) CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS else CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
        val kinds = generationEngine.baselinedScopeKinds(partition, scopeId).filter { it in allowedKinds }
        val scopeKind = when (kinds.size) {
            // Nothing stored names the kind, and paging rows do not carry it.
            0 -> return log("no_window")
            1 -> kinds.single()
            else -> return log("ambiguous_scope_kind")
        }
        // Task v2: a task window is written by its enter snapshot and live frames only; paging pages
        // carry no parent context to convert under (TODO(task-v2 P1): extend task windows too).
        if (scopeKind == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) return log("task_window_extend_unsupported")
        val scope = SyncScopeKey(partition = partition, scopeId = scopeId, scopeKind = scopeKind)

        // A reply page is converted under the parent its stored cursor names, and only when that is
        // the route's parent (re-checked against the stored cursor under the scope writer).
        val storedParent = if (routeParent == null) {
            null
        } else {
            storedReplyParentOrNull(generationEngine.storedCursor(scope), routeParent)
                ?: return log("parent_mismatch")
        }

        val expectedGeneration = generationEngine.partitionGeneration(partition)

        // Phase 3b: a horizonReset after this read voids the page's head claim (never its facts).

        val expectedHeadEpoch = requestHeadEpoch ?: generationEngine.partitionHeadEpoch(partition)
        // Row by row, with the counts the response embeds: a row whose count is unknown is left out and the claim
        // splits around it. Never a `channels/{id}/threads` request.
        val rows = fetcher.convertPageRows(
            partition, scopeId, scopeKind, rawMessages, storedParent, replyCountByParentId = replyCountByParentId.orEmpty(),
        )
        val envelopes = rows.envelopes
        if (envelopes.isEmpty()) return log(rows.firstRefusal ?: "canonical_v2_snapshot_not_full_v6")
        if (!rows.claimable) return log("left_out_without_seq")
        val foreign = envelopes.any { envelope ->
            val envelopeScopeId = when (val context = envelope.context) {
                is CanonicalConversationContextV2.TopLevel -> context.scope.scopeId.takeIf { replyParent == null }
                is CanonicalConversationContextV2.Thread -> context.replyScope.scopeId.takeIf { replyParent != null }
            }
            envelopeScopeId != scopeId
        }
        if (foreign) return log("foreign_scope")
        if (envelopes.map { it.fact.id }.distinct().size != envelopes.size) return log("duplicate_fact")
        val pageSeqs = envelopes.map { it.fact.seq } + rows.rejectedSeqs
        val onRequestedSide = when (direction) {
            ThreadDurablePageDirection.Older -> pageSeqs.all { it < anchorSeq }
            ThreadDurablePageDirection.Newer -> pageSeqs.all { it > anchorSeq }
        }
        if (!onRequestedSide) return log("wrong_side_of_anchor")

        generationEngine.withScopeWriter(scope) {
            if (!stillCurrent()) return@withScopeWriter log("identity_changed")
            if (!isEnabledForServer(partition.serverId)) return@withScopeWriter log("flag_revoked")
            try {
                val stored = generationEngine.storedCursor(scope) ?: return@withScopeWriter log("no_window")
                if (routeParent != null && storedReplyParentOrNull(stored, routeParent) != storedParent) {
                    return@withScopeWriter log("parent_mismatch")
                }
                val before = generationEngine.storedSegments(scope)
                val claim = adjacentPageCoverage(direction, window, partition.serverId, scopeId, historyLimited, anchorSeq, before)
                    ?: return@withScopeWriter log("no_claim")
                // An older page's top is the stored coverage it continues, already claimed: nothing new to seal there.
                val sealTop = direction == ThreadDurablePageDirection.Newer ||
                    CanonicalMessageV2SegmentAlgebra.memberOf(before, anchorSeq) == null
                val write = if (sealTop) {
                    sealPageClaim(claim, envelopes, rows.rejectedSeqs, before, nowMs())
                } else {
                    CanonicalMessageV2PageWrite(envelopes, claim)
                }
                if (write.envelopes.isEmpty()) return@withScopeWriter log("too_recent")
                val result = generationEngine.applyCoverage(
                    scope = scope,
                    envelopes = write.envelopes,
                    coverage = write.coverage,
                    revAtRequest = revAtRequest ?: generationEngine.storedLocalRev(partition),
                    userDriven = true,
                    expectedPartitionGeneration = expectedGeneration,
                    threadParent = stored.threadParent(),
                    excludedSeqs = rows.rejectedSeqs,
                    expectedHeadEpoch = expectedHeadEpoch,
                )
                log(result.wireOutcome(write.envelopes.size))
                if (result == CanonicalMessageV2CommitResult.Committed) reportCommitToCanary(scope, before, write)
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                log("poisoned fact=${poisoned.factId.orEmpty()}")
            }
        }
    }

    /**
     * Whether this commit moved the top of what users see: the head's top, or a loose row above it ([sealPageClaim]
     * keeps those only when connected to the head). Only then does nothing prove the window continuous with the
     * realtime stream, so only then is an enter baseline in flight voided ([CanonicalMessageV2ProductionCanary.onExternalCommit]).
     * An older reach, an island or a refresh inside the head leaves both alone.
     */
    private suspend fun reportCommitToCanary(
        scope: SyncScopeKey,
        before: List<build.raft.app.network.sync.CanonicalMessageV2Segment>,
        write: CanonicalMessageV2PageWrite,
    ) {
        val oldTop = CanonicalMessageV2SegmentAlgebra.head(before)?.highSeq
        val after = generationEngine.storedSegments(scope)
        val newTop = CanonicalMessageV2SegmentAlgebra.head(after)?.highSeq
        val looseAboveHead = oldTop != null && write.envelopes.any { envelope ->
            envelope.fact.seq > oldTop && CanonicalMessageV2SegmentAlgebra.memberOf(after, envelope.fact.seq) == null
        }
        if (newTop != oldTop || looseAboveHead) canary.onExternalCommit(scope) else canary.onOlderPageCommitted(scope)
    }

    /**
     * Gap catch-up (task #168 step 1). Gates 1–3 are [extendWithAdjacentPage]'s (minus the
     * anchor side check: the latest page has no cursor). Under the scope writer the stored window is
     * restored and the join FACT is read: is any fact stored inside the window
     * (`oldestLoadedSeq..newestLoadedSeq`, on disk) one of the page's message ids.
     * - Joined: `applyIncremental` with the union cursor — newest edge = the page's newest
     *   (`hasLoadedNewest`, it is the latest page), oldest edge moves only when the page reaches
     *   below it, and the retention cap moves the oldest edge exactly like a newer page.
     * - Not joined (the gap was wider than the page, or the window is an empty baseline):
     *   `applySnapshot` with the page as the new window (the engine drops the old unpinned facts in
     *   the same transaction); a thread window keeps its stored parent. Logged as `window_replaced`
     *   with the old and new bounds.
     * Either commit is one SQLite transaction: a failure leaves the stored window as it was. A committed
     * one is reported to the realtime canary inside the same scope writer
     * ([CanonicalMessageV2ProductionCanary.onExternalCommit]); the canary's scope is never skipped (task
     * #168 device FAIL: the catch-up page of the canary channel never reached disk).
     */
    override suspend fun applyLatestPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        requestHeadEpoch: Long?,
        revAtRequest: Long?,
        stillCurrent: () -> Boolean,
    ) = applyLatestPageAfterBaseline(
        partition, channelId, rawMessages, requestedLimit, replyParent, null, messageWindow, historyLimited, requestHeadEpoch,
        revAtRequest, stillCurrent,
    )

    /**
     * Task #168 step 3: with a [baseline] (registered before the fetch, only for a member channel whose
     * connection had seen `rooms:joined`), the committed page is the window's new realtime baseline: frames
     * buffered during the fetch are replayed and the window is continuous again
     * ([CanonicalMessageV2ProductionCanary.onEnterCommitted]). Without one, #3008's external commit.
     * The baseline is abandoned on every path that does not commit. Task #14c: a reply thread's catch-up passes the
     * baseline [beginLatestPageBaseline] registered with the same parent; its commit follows the thread rule (the
     * late-ack catch-up: [CanonicalMessageV2ProductionCanary.threadJoinConfirmations]).
     */
    override suspend fun applyLatestPageAfterBaseline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        baseline: CanonicalMessageV2EnterBaseline?,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        requestHeadEpoch: Long?,
        revAtRequest: Long?,
        stillCurrent: () -> Boolean,
    ) {
        try {
            applyLatestPageInternal(
                partition, channelId, rawMessages, requestedLimit, replyParent, baseline, messageWindow, historyLimited, requestHeadEpoch,
                revAtRequest, stillCurrent,
            )
        } finally {
            baseline?.let { canary.abandonEnterBaseline(it) }
        }
    }

    private suspend fun applyLatestPageInternal(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        baseline: CanonicalMessageV2EnterBaseline?,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        requestHeadEpoch: Long?,
        revAtRequest: Long?,
        stillCurrent: () -> Boolean,
    ) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        // A baseline is only ever for this exact channel of this partition, of the same scope family (task #14c: a
        // reply thread's names the same parent channel).
        val ownBaseline = baseline?.takeIf {
            it.partition == partition && it.scopeId == channelId && it.replyParentScopeId == replyParent?.parentChannelId
        }
        val kindLabel = if (replyParent != null) " scopeKind=thread" else ""
        fun log(outcome: String) {
            logger("canonical_v2_latest_page server=${partition.serverId} scope=$channelId$kindLabel outcome=$outcome")
        }
        if (channelId.isBlank() || channelId != channelId.trim()) return log("invalid_channel_id")
        if (!isEnabledForServer(partition.serverId)) return log("flag_off")
        if (rawMessages.isEmpty()) return log("empty_page")
        if (requestedLimit <= 0) return log("invalid_limit")

        val allowedKinds =
            if (replyParent == null) CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS else CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
        val kinds = generationEngine.baselinedScopeKinds(partition, channelId).filter { it in allowedKinds }
        val scopeKind = when (kinds.size) {
            0 -> return log("no_window")
            1 -> kinds.single()
            else -> return log("ambiguous_scope_kind")
        }
        // Task v2: a task window is written by its enter snapshot and live frames only; paging pages
        // carry no parent context to convert under (TODO(task-v2 P1): extend task windows too).
        if (scopeKind == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) return log("task_window_extend_unsupported")
        val scope = SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = scopeKind)
        val storedParent = if (replyParent == null) {
            null
        } else {
            storedReplyParentOrNull(generationEngine.storedCursor(scope), replyParent)
                ?: return log("parent_mismatch")
        }

        val expectedGeneration = generationEngine.partitionGeneration(partition)
        // Phase 3b: a horizonReset after this read voids the page's head claim (never its facts).
        val expectedHeadEpoch = requestHeadEpoch ?: generationEngine.partitionHeadEpoch(partition)
        // Phase 2b: row by row — a bad row is left out and the page's claim is split around it.
        val rows = fetcher.convertPageRows(partition, channelId, scopeKind, rawMessages, storedParent)
        val envelopes = rows.envelopes
        if (envelopes.isEmpty()) return log(rows.firstRefusal ?: "canonical_v2_snapshot_not_full_v6")
        val foreign = envelopes.any { envelope ->
            val envelopeScopeId = when (val context = envelope.context) {
                is CanonicalConversationContextV2.TopLevel -> context.scope.scopeId.takeIf { replyParent == null }
                is CanonicalConversationContextV2.Thread -> context.replyScope.scopeId.takeIf { replyParent != null }
            }
            envelopeScopeId != channelId
        }
        if (foreign) return log("foreign_scope")
        if (envelopes.map { it.fact.id }.distinct().size != envelopes.size) return log("duplicate_fact")
        val pageSeqs = envelopes.map { it.fact.seq } + rows.rejectedSeqs
        val pageMin = pageSeqs.min()
        val pageMax = pageSeqs.max()
        val leftOut = rows.firstRefusal?.let { " left_out=${rows.rejectedSeqs.size + rows.rejectedWithoutSeq} reason=$it" }.orEmpty()

        generationEngine.withScopeWriter(scope) {
            if (!stillCurrent()) return@withScopeWriter log("identity_changed")
            if (!isEnabledForServer(partition.serverId)) return@withScopeWriter log("flag_revoked")
            try {
                val stored = generationEngine.storedCursor(scope) ?: return@withScopeWriter log("no_window")
                if (replyParent != null && storedReplyParentOrNull(stored, replyParent) != storedParent) {
                    return@withScopeWriter log("parent_mismatch")
                }
                // Phase 2a (P1): the latest page is ADDED with the range it proves. A page that does not reach the
                // stored ranges becomes a new head; nothing stored is dropped (the gap shows up between them).
                val before = generationEngine.storedSegments(scope)
                val coverage = latestPageCoverage(partition, channelId, pageSeqs, requestedLimit, messageWindow, historyLimited)
                    ?.takeIf { rows.claimable }
                val baseline = coverage?.excluding(rows.rejectedSeqs)?.lastOrNull()?.headAtVerify == true
                val commit = generationEngine.applyCoverageReportingBaseline(
                    scope = scope,
                    envelopes = envelopes,
                    coverage = coverage,
                    // Phase 2b: read by the caller before its fetch, so a fact written meanwhile (also a realtime loose
                    // write, which bumps no commit generation) is newer and kept. `null` = an older caller: now.
                    revAtRequest = revAtRequest ?: generationEngine.storedLocalRev(partition),
                    userDriven = true,
                    expectedPartitionGeneration = expectedGeneration,
                    threadParent = stored.threadParent(),
                    establishesBaseline = baseline,
                    excludedSeqs = rows.rejectedSeqs,
                    expectedHeadEpoch = expectedHeadEpoch,
                )
                val result = commit.result
                // What the window holds now, for the baseline replay: the page under the committed cursor.
                val committedFrame = generationEngine.currentCursor(scope)?.let { cursor ->
                    CanonicalMessageV2SnapshotFrame(scope = scope, envelopes = envelopes, cursor = cursor)
                }
                val outcome = if (result == CanonicalMessageV2CommitResult.Committed) {
                    "${rangeOutcome(before, generationEngine.storedSegments(scope))} new=$pageMin..$pageMax " +
                        result.wireOutcome(envelopes.size) + leftOut
                } else {
                    result.wireOutcome(envelopes.size)
                }
                log(outcome)
                if (result == CanonicalMessageV2CommitResult.Committed) {
                    val frame = committedFrame
                    // Only a page admitted as the baseline (its head claim not voided by a horizonReset since
                    // [expectedHeadEpoch] was read) makes the window continuous.
                    if (ownBaseline != null && frame != null && commit.baselineAdmitted) {
                        canary.onEnterCommitted(
                            ownBaseline,
                            frame,
                            nonMemberAtCommit = isKnownNonMember(partition.serverId, channelId),
                            parentNonMemberAtCommit =
                                replyParent != null && isKnownNonMember(partition.serverId, replyParent.parentChannelId),
                        )
                    } else {
                        canary.onExternalCommit(scope)
                    }
                }
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                log("poisoned fact=${poisoned.factId.orEmpty()}")
            }
        }
    }

    /**
     * Message storage Phase 2c: the anchor context page (see [ThreadDurablePageExtender.persistContextPage]). [channelId]
     * is the response's top-level `channelId`. A page joins a stored range only through a shared message id; otherwise it
     * is an island. One `canonical_v2_context_page ... outcome=committed|skipped reason=... facts=N` line per call.
     */
    override suspend fun persistContextPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        anchorMessageId: String,
        rawMessages: JsonArray,
        hasOlder: Boolean,
        hasNewer: Boolean,
        replyParent: ThreadDurableReplyParent?,
        requestHeadEpoch: Long?,
        historyLimited: Boolean,
        revAtRequest: Long?,
        replyCountByParentId: Map<String, Int>?,
        userDriven: Boolean,
        channelType: String?,
        stillCurrent: () -> Boolean,
    ) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val kindLabel = if (replyParent != null) " scopeKind=thread" else ""
        fun log(outcome: String, reason: String, facts: Int = 0) {
            logger(
                "canonical_v2_context_page server=${partition.serverId} scope=$channelId$kindLabel " +
                    "anchor=$anchorMessageId outcome=$outcome reason=$reason facts=$facts",
            )
        }
        fun skip(reason: String) = log("skipped", reason)
        if (channelId.isBlank() || channelId != channelId.trim()) return skip("invalid_channel_id")
        if (!isEnabledForServer(partition.serverId)) return skip("flag_off")
        if (rawMessages.isEmpty()) return skip("empty_page")
        if (replyParent != null && replyParent.parentChannelId == channelId) return skip("parent_mismatch")

        val scope: SyncScopeKey
        val parent: CanonicalMessageV2ReplyParentScope?
        when (val resolved = resolveContextScope(partition, channelId, replyParent, channelType)) {
            is ContextPageScope.Refused -> return skip(resolved.reason)
            is ContextPageScope.Resolved -> {
                scope = resolved.scope
                parent = resolved.parent
            }
        }

        val expectedGeneration = generationEngine.partitionGeneration(partition)

        // Phase 3b: a horizonReset after this read voids the page's head claim (never its facts).

        val expectedHeadEpoch = requestHeadEpoch ?: generationEngine.partitionHeadEpoch(partition)
        val rows = fetcher.convertPageRows(
            partition, channelId, scope.scopeKind, rawMessages, parent, replyCountByParentId = replyCountByParentId.orEmpty(),
        )
        val envelopes = rows.envelopes
        if (envelopes.isEmpty()) return skip(rows.firstRefusal ?: "canonical_v2_snapshot_not_full_v6")
        if (!rows.claimable) return skip("left_out_without_seq")
        val foreign = envelopes.any { envelope ->
            val envelopeScopeId = when (val context = envelope.context) {
                is CanonicalConversationContextV2.TopLevel -> context.scope.scopeId.takeIf { parent == null }
                is CanonicalConversationContextV2.Thread -> context.replyScope.scopeId.takeIf { parent != null }
            }
            envelopeScopeId != channelId
        }
        if (foreign) return skip("foreign_scope")
        if (envelopes.map { it.fact.id }.distinct().size != envelopes.size) return skip("duplicate_fact")
        if (envelopes.none { it.fact.id == anchorMessageId }) return skip("anchor_not_on_page")

        generationEngine.withScopeWriter(scope) {
            if (!stillCurrent()) return@withScopeWriter skip("identity_changed")
            if (!isEnabledForServer(partition.serverId)) return@withScopeWriter skip("flag_revoked")
            try {
                val threadParent = parent?.let { CanonicalMessageV2ThreadParent(it.parentScopeKind, it.parentScopeId, it.rootMessageId) }
                clearHeaderOfAnotherParent(scope, threadParent)?.let { rewritten -> log("parent_rewritten", rewritten) }
                val before = generationEngine.storedSegments(scope)
                val claim = CanonicalMessageV2Coverage.fromContextPage(
                    envelopes.map { it.fact.seq } + rows.rejectedSeqs, hasOlder, historyLimited,
                ) ?: return@withScopeWriter skip("no_claim")
                // Every stored range the claim would merge with must share a message with the page. A range it only
                // overlaps or touches by seq is not a join, and a claim left beside it would not be normalized.
                val joined = claim.excluding(rows.rejectedSeqs).flatMap { piece ->
                    before.filter { CanonicalMessageV2SegmentAlgebra.joins(it, piece.toSegment(0L)) }
                }.distinct()
                val unshared = joined.any { segment ->
                    envelopes.none { it.fact.seq in segment && generationEngine.storedFactExists(scope, it.fact.id) }
                }
                if (unshared) return@withScopeWriter skip("touches_without_shared_ids")
                val write = sealPageClaim(claim, envelopes, rows.rejectedSeqs, before, nowMs())
                if (write.envelopes.isEmpty()) return@withScopeWriter skip("too_recent")
                val result = generationEngine.applyCoverage(
                    scope = scope,
                    envelopes = write.envelopes,
                    coverage = write.coverage,
                    revAtRequest = revAtRequest ?: generationEngine.storedLocalRev(partition),
                    userDriven = userDriven,
                    expectedPartitionGeneration = expectedGeneration,
                    threadParent = threadParent,
                    excludedSeqs = rows.rejectedSeqs,
                    expectedHeadEpoch = expectedHeadEpoch,
                )
                if (result == CanonicalMessageV2CommitResult.Committed) {
                    val after = generationEngine.storedSegments(scope)
                    val headChanged = CanonicalMessageV2SegmentAlgebra.head(before) != CanonicalMessageV2SegmentAlgebra.head(after)
                    log("committed", if (headChanged) "head_extended" else "island_stored", write.envelopes.size)
                    reportCommitToCanary(scope, before, write)
                } else {
                    skip(result.wireOutcome(write.envelopes.size))
                }
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                skip("poisoned fact=${poisoned.factId.orEmpty()}")
            }
        }
    }

    private sealed interface ContextPageScope {
        data class Resolved(val scope: SyncScopeKey, val parent: CanonicalMessageV2ReplyParentScope?) : ContextPageScope

        data class Refused(val reason: String) : ContextPageScope
    }

    /**
     * The scope of a context page and, for a thread, its parent. The header decides when there is one; a never-stored
     * scope takes its kind from what the caller already read ([channelType]), else from `channels/{id}`, at most once
     * per scope since the write then leaves a header. Unknown = deferred, never guessed.
     */
    private suspend fun resolveContextScope(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent?,
        channelType: String?,
    ): ContextPageScope {
        if (replyParent != null) {
            val scope = SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = REPLY_THREAD_SCOPE_KIND)
            val stored = generationEngine.storedCursor(scope)
            storedReplyParentOrNull(stored, replyParent)?.let { return ContextPageScope.Resolved(scope, it) }
            // A header with a window under another parent is another thread. A windowless one only names what a realtime
            // frame claimed: the server decides, and the write replaces it ([clearHeaderOfAnotherParent]).
            if (stored != null && (stored.oldestLoadedSeq != null || stored.hasLoadedOldest)) {
                return ContextPageScope.Refused("parent_mismatch")
            }
            return when (val confirmed = fetcher.confirmReplyParent(partition, channelId, replyParent)) {
                is CanonicalMessageV2ReplyParentConfirmation.Confirmed -> ContextPageScope.Resolved(scope, confirmed.parent)
                is CanonicalMessageV2ReplyParentConfirmation.Refused -> ContextPageScope.Refused(confirmed.diagnostic)
            }
        }
        val kinds = generationEngine.baselinedScopeKinds(partition, channelId).filter {
            it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS || it in CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
        }
        if (kinds.size > 1) return ContextPageScope.Refused("ambiguous_scope_kind")
        val kind = kinds.singleOrNull()
            ?: channelType?.takeIf { it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS }
            ?: fetcher.topLevelKind(partition, channelId)?.first
            ?: return ContextPageScope.Refused("scope_kind_unknown")
        // As the sibling writers: a task window is never extended from a page (no task parent to convert under).
        if (kind == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) return ContextPageScope.Refused("task_window_extend_unsupported")
        val scope = SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = kind)
        if (kind != REPLY_THREAD_SCOPE_KIND) return ContextPageScope.Resolved(scope, null)
        val stored = generationEngine.storedCursor(scope)
        val parentKind = stored?.parentScopeKind?.takeIf { it in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS }
        val parentId = stored?.parentScopeId
        val rootId = stored?.rootMessageId
        if (parentKind == null || parentId == null || rootId == null) return ContextPageScope.Refused("parent_mismatch")
        return ContextPageScope.Resolved(scope, CanonicalMessageV2ReplyParentScope(parentKind, parentId, rootId))
    }
}

/** What a page write stores: [envelopes] under [coverage] (`null` = no claim, facts only). */
internal class CanonicalMessageV2PageWrite(
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val coverage: CanonicalMessageV2Coverage?,
)

/** Late commits: a seq is allocated before commit (prepared system messages up to 30 s, agent inbox grace 60 s). */
internal const val PAGE_SEAL_GRACE_MS: Long = 60_000L

/**
 * Phase 2c: the claim of a `before` / `after` page from its server [window]. Never the head (only a `latest` fetch proves
 * that), and the bounds are the server's: the page proves `[coveredAfterSeq + 1, coveredThroughSeq]`, so a newer page
 * connects exactly when `coveredAfterSeq` is at or below the local covered tail, whatever seqs other channels used.
 * - Older: `coveredThroughSeq` is the page's last row, not the anchor. `before=anchor` returned the newest rows below
 *   it, so when the anchor is inside stored coverage the claim runs up to `anchor - 1` and connects.
 * - Newer: an `after` page does not compute `historyLimited` (a false says nothing), so it never reaches the oldest.
 * `null`: an unusable or mismatched window.
 */
internal fun adjacentPageCoverage(
    direction: ThreadDurablePageDirection,
    window: ChannelMessageWindow,
    serverId: String,
    scopeId: SyncScopeId,
    historyLimited: Boolean,
    anchorSeq: Long,
    segments: List<build.raft.app.network.sync.CanonicalMessageV2Segment>,
): CanonicalMessageV2Coverage? = when (direction) {
    ThreadDurablePageDirection.Older -> {
        val claim = window.toCoverage(serverId, scopeId, historyLimited, CanonicalMessageV2SegmentSource.Older)?.copy(headAtVerify = false)
        val anchored = CanonicalMessageV2SegmentAlgebra.memberOf(segments, anchorSeq) != null
        if (claim != null && anchored && anchorSeq - 1L > claim.highSeq) claim.copy(highSeq = anchorSeq - 1L) else claim
    }
    ThreadDurablePageDirection.Newer ->
        window.toCoverage(serverId, scopeId, historyLimited = true, CanonicalMessageV2SegmentSource.Newer)?.copy(headAtVerify = false)
}

/**
 * Phase 2c: never seal the last [PAGE_SEAL_GRACE_MS] of a range: a message that took a lower seq may still commit. The
 * claim stops below the first row that recent (or with an unreadable time); a catch-up claims those rows later.
 *
 * The recent rows themselves are kept only in the part of the page connected to the head, where they are loose facts
 * above it as a realtime frame would be. Elsewhere they are left out: a loose fact is read offline, and one far from the
 * head would change what users see before Phase 2d.
 */
internal fun sealPageClaim(
    claim: CanonicalMessageV2Coverage,
    envelopes: List<CanonicalMessageEnvelopeV2>,
    excludedSeqs: Set<Long>,
    segments: List<build.raft.app.network.sync.CanonicalMessageV2Segment>,
    nowMs: Long,
): CanonicalMessageV2PageWrite {
    val sealedBefore = nowMs - PAGE_SEAL_GRACE_MS
    val firstRecent = envelopes.filter { envelope ->
        val createdAt = parseRaftTimestampEpochMillisOrNull(envelope.fact.createdAt, localOffsetMinutes = 0)
        createdAt == null || createdAt > sealedBefore
    }.minOfOrNull { it.fact.seq } ?: return CanonicalMessageV2PageWrite(envelopes, claim)
    val head = CanonicalMessageV2SegmentAlgebra.head(segments)
    val connectedHigh = head?.let { h ->
        claim.excluding(excludedSeqs).firstOrNull { CanonicalMessageV2SegmentAlgebra.joins(h, it.toSegment(0L)) }?.highSeq
    }
    return CanonicalMessageV2PageWrite(
        envelopes = envelopes.filter { it.fact.seq < firstRecent || (connectedHigh != null && it.fact.seq <= connectedHigh) },
        coverage = claim.takeIf { firstRecent - 1L >= it.lowSeq }?.copy(highSeq = firstRecent - 1L),
    )
}

/**
 * Message storage Phase 2a: the range a LATEST page (`messages/channel/{id}?limit=[pageLimit]`, no cursor) of
 * [scopeId] proves. With the server [messageWindow] it is the window's claim, checked against this scope
 * ([ChannelMessageWindow.toCoverage]; a mismatched or unusable window proves nothing). A page without one (older
 * server) keeps the legacy claim: `[min, max]` at the head, reaching the oldest when the page was short of the
 * limit and no plan cutoff hid older rows; an empty such page is the empty baseline `[0, 0]`.
 */
internal fun latestPageCoverage(
    partition: DurablePartitionKey,
    scopeId: SyncScopeId,
    seqs: List<Long>,
    pageLimit: Int,
    messageWindow: ChannelMessageWindow?,
    historyLimited: Boolean,
): CanonicalMessageV2Coverage? {
    if (messageWindow != null) {
        return messageWindow.toCoverage(partition.serverId, scopeId, historyLimited, CanonicalMessageV2SegmentSource.Latest)
    }
    val reachesOldest = seqs.size < pageLimit && !historyLimited
    val min = seqs.minOrNull()
        ?: return if (reachesOldest) CanonicalMessageV2Coverage(0L, 0L, true, true, CanonicalMessageV2SegmentSource.Latest) else null
    return CanonicalMessageV2Coverage(min, seqs.max(), reachesOldest, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest)
}

/**
 * One log word for what a range write did to the stored segments: `window_created` (none before),
 * `window_extended` (the old head is inside the new head), `island_kept` (the page did not reach the old head,
 * which stays as an older island).
 */
private fun rangeOutcome(
    before: List<build.raft.app.network.sync.CanonicalMessageV2Segment>,
    after: List<build.raft.app.network.sync.CanonicalMessageV2Segment>,
): String {
    val oldHead = CanonicalMessageV2SegmentAlgebra.head(before) ?: return "window_created"
    val newHead = CanonicalMessageV2SegmentAlgebra.head(after) ?: return "island_kept"
    return if (oldHead.highSeq in newHead && oldHead.effectiveLowSeq >= newHead.effectiveLowSeq) "window_extended" else "island_kept"
}

private fun JsonObject.historyLimited(): Boolean = (this["historyLimited"] as? JsonPrimitive)?.booleanOrNull ?: false

private fun CanonicalMessageV2CommitResult.wireOutcome(facts: Int): String =
    when (this) {
        CanonicalMessageV2CommitResult.Committed -> "committed facts=$facts"
        CanonicalMessageV2CommitResult.PartitionRevoked -> "partition_revoked"
        CanonicalMessageV2CommitResult.ScopeEvicted -> "scope_evicted"
        is CanonicalMessageV2CommitResult.BaselineRequired -> "baseline_required"
    }

/** The stored cursor's parent, when it is exactly the route's [replyParent]; otherwise `null`. */
private fun storedReplyParentOrNull(
    cursor: PersistedCursor?,
    replyParent: ThreadDurableReplyParent,
): CanonicalMessageV2ReplyParentScope? {
    val stored = cursor ?: return null
    val kind = stored.parentScopeKind?.takeIf { it in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS } ?: return null
    if (stored.parentScopeId != replyParent.parentChannelId || stored.rootMessageId != replyParent.rootMessageId) {
        return null
    }
    return CanonicalMessageV2ReplyParentScope(kind, replyParent.parentChannelId, replyParent.rootMessageId)
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private const val REPLY_THREAD_SCOPE_KIND: String = "thread"

/** The channel `threadSort` value of a forum channel (server `channel_thread_sort_v0`). */
internal const val CHANNEL_THREAD_SORT_ACTIVITY: String = "activity"

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
