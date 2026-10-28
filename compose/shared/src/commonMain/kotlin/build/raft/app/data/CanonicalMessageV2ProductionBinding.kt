package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.model.ServerId
import build.raft.app.raftLogInfo
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
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
    private val networkClient: RaftNetworkClient,
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
            ?.takeIf { it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS }
            ?: return unavailable("canonical_v2_scope_not_top_level")
        // Task v2: a task conversation's page converts under its parent channel (`channels/{id}`
        // names it; slock `schema.ts` `channels.parent_channel_id`).
        val taskParentChannelId =
            if (channelType == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) {
                detail.stringOrNull("parentChannelId")?.takeIf { it.isNotBlank() && it == it.trim() }
                    ?: return unavailable("canonical_v2_task_parent_missing")
            } else {
                null
            }
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
        val messageWindow = decodeChannelMessageWindow(page["messageWindow"])
        val historyLimited = page.historyLimited()
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
                latestPageCoverage(partition, scopeId, emptyList(), CANONICAL_V2_CANARY_PAGE_LIMIT, messageWindow, historyLimited),
            )
        }
        val envelopes = when (
            val converted = convertPage(partition, scopeId, channelType, rawMessages, taskParentChannelId = taskParentChannelId)
        ) {
            is CanonicalMessageV2PageConversion.Converted -> converted.envelopes
            is CanonicalMessageV2PageConversion.Refused -> return unavailable(converted.diagnostic)
        }
        return CanonicalMessageV2SnapshotLoadResult.Loaded(
            CanonicalMessageV2SnapshotFrame(scope = scope, envelopes = envelopes, cursor = snapshotCursor(envelopes)),
            latestPageCoverage(
                partition, scopeId, envelopes.map { it.fact.seq }, CANONICAL_V2_CANARY_PAGE_LIMIT, messageWindow, historyLimited,
            ),
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
        val parent = when (val confirmed = confirmReplyParent(partition, threadChannelId, replyParent)) {
            is CanonicalMessageV2ReplyParentConfirmation.Confirmed -> confirmed.parent
            is CanonicalMessageV2ReplyParentConfirmation.Refused -> return unavailable(confirmed.diagnostic)
        }
        val page = fetchJson(
            priority = DataRequestPriority.Background,
            path = "messages/channel/$threadChannelId",
            serverId = partition.serverId,
            query = listOf("limit" to CANONICAL_V2_CANARY_PAGE_LIMIT.toString()),
        ) ?: return unavailable("canonical_v2_snapshot_unavailable")
        val rawMessages = page["messages"] as? JsonArray
            ?: return unavailable("canonical_v2_snapshot_shape")
        return replyThreadFrame(
            partition,
            threadChannelId,
            parent,
            rawMessages,
            CANONICAL_V2_CANARY_PAGE_LIMIT,
            messageWindow = decodeChannelMessageWindow(page["messageWindow"]),
            historyLimited = page.historyLimited(),
        )
    }

    /**
     * Steps 1–2 of [fetchReplyThread] (task #14b: shared with the Activity prewarm writer): the server
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
                cursor = snapshotCursor(envelopes, pageLimit).copy(
                    parentScopeKind = parent.parentScopeKind,
                    parentScopeId = parent.parentScopeId,
                    rootMessageId = parent.rootMessageId,
                ),
            ),
            latestPageCoverage(partition, threadChannelId, envelopes.map { it.fact.seq }, pageLimit, messageWindow, historyLimited),
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
        require((channelType == REPLY_THREAD_SCOPE_KIND) == (replyParent != null)) {
            "a reply parent is required for, and only for, a thread page"
        }
        require((channelType == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) == (taskParentChannelId != null)) {
            "a task parent channel is required for, and only for, a task page"
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
            parentChannelId = replyParent?.parentScopeId ?: taskParentChannelId,
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
    private val fetcher: CanonicalMessageV2RestSnapshotFetcher,
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
    private val inFlight = mutableSetOf<Pair<DurablePartitionKey, SyncScopeId>>()
    /** Task #14b: prewarm writes in flight, guarded by [inFlightMutex]; never blocks an enter. */
    private val prewarmInFlight = mutableSetOf<Pair<DurablePartitionKey, SyncScopeId>>()

    override suspend fun loadOnEnter(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        stillCurrent: () -> Boolean,
    ) = loadAndCommit(partition, channelId, replyParentScopeId = null, stillCurrent) {
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
    ) = loadAndCommit(partition, threadChannelId, replyParentScopeId = replyParent.parentChannelId, stillCurrent) {
        if (replyParent.parentChannelId == threadChannelId) {
            CanonicalMessageV2SnapshotLoadResult.Unavailable("canonical_v2_thread_parent_mismatch")
        } else {
            fetcher.fetchReplyThread(partition, threadChannelId, replyParent)
        }
    }

    private suspend fun loadAndCommit(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        /** The route's parent channel of a reply thread; `null` for a top-level channel. */
        replyParentScopeId: SyncScopeId?,
        stillCurrent: () -> Boolean,
        fetch: suspend () -> CanonicalMessageV2SnapshotLoadResult,
    ) {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val replyThread = replyParentScopeId != null
        if (scopeId.isBlank() || scopeId != scopeId.trim()) return
        if (!isEnabledForServer(partition.serverId)) return
        fun log(outcome: String) = log(partition, scopeId, replyThread, outcome)
        val key = partition to scopeId
        val admitted = inFlightMutex.withLock { inFlight.add(key) }
        if (!admitted) {
            log("in_flight")
            return
        }
        // Registered before the fetch (hole A), for a reply thread too (task #14a). Task #14c: the thread's own
        // membership and its parent's are separate (a thread join ack may lift the former, never the latter).
        fun nonMember() = isKnownNonMember(partition.serverId, scopeId)
        fun parentNonMember() = replyParentScopeId != null && isKnownNonMember(partition.serverId, replyParentScopeId)
        val baseline: CanonicalMessageV2EnterBaseline? =
            canary.beginEnterBaseline(
                partition,
                scopeId,
                nonMember = nonMember(),
                replyParentScopeId = replyParentScopeId,
                parentNonMember = parentNonMember(),
            )
        try {
            val expectedGeneration = generationEngine.partitionGeneration(partition)
            // Read before the fetch: a fact written after this point is newer than the page and stays.
            val revAtRequest = generationEngine.storedLocalRev(partition)
            val fetched = when (val result = fetch()) {
                is CanonicalMessageV2SnapshotLoadResult.Loaded -> result
                is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                    log(result.diagnostic)
                    return
                }
            }
            val frame = fetched.frame
            if (replyThread) {
                val foreign = frame.scope.scopeKind != "thread" || frame.envelopes.any { envelope ->
                    (envelope.context as? CanonicalConversationContextV2.Thread)?.replyScope?.scopeId != scopeId
                }
                if (foreign) {
                    log("foreign_scope")
                    return
                }
            }
            generationEngine.withScopeWriter(frame.scope) {
                if (!stillCurrent()) {
                    log("identity_changed")
                    return@withScopeWriter
                }
                if (!isEnabledForServer(partition.serverId)) {
                    log("flag_revoked")
                    return@withScopeWriter
                }
                try {
                    clearHeaderOfAnotherParent(frame.scope, frame.cursor.threadParent())?.let(::log)
                    // Phase 2a (P1): the page is added, never a rebaseline — older stored pages stay.
                    val result = generationEngine.applyCoverage(
                        scope = frame.scope,
                        envelopes = frame.envelopes,
                        coverage = fetched.coverage,
                        revAtRequest = revAtRequest,
                        userDriven = true,
                        expectedPartitionGeneration = expectedGeneration,
                        threadParent = frame.cursor.threadParent(),
                        // A page that proves no range (an unusable server window) stores its facts loose; it is
                        // no realtime baseline — the stored head would otherwise be continued past a hole.
                        establishesBaseline = fetched.coverage?.headAtVerify == true,
                    )
                    log(result.wireOutcome(frame.envelopes.size) + if (fetched.coverage?.headAtVerify != true) " coverage=${fetched.coverage?.let { "not_head" } ?: "none"}" else "")
                    if (result == CanonicalMessageV2CommitResult.Committed) {
                        val expectedKinds =
                            if (replyThread) CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS else CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS
                        if (baseline != null && fetched.coverage?.headAtVerify == true && frame.scope.scopeKind in expectedKinds) {
                            canary.onEnterCommitted(
                                baseline,
                                frame,
                                nonMemberAtCommit = nonMember(),
                                parentNonMemberAtCommit = parentNonMember(),
                            )
                        } else {
                            canary.onExternalCommit(frame.scope)
                        }
                    }
                } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                    // A stale/cross-scope durable row must never escape the background enter job.
                    // The page already has its network-backed content; leave the durable window
                    // untouched and fail closed until a later clean baseline can be written.
                    log("poisoned fact=${poisoned.factId.orEmpty()}")
                }
            }
        } finally {
            // No-op once committed; otherwise the frames buffered meanwhile were never written.
            baseline?.let { canary.abandonEnterBaseline(it) }
            // NonCancellable: a cancelled job must still release its marker, or the scope is refused forever.
            withContext(NonCancellable) { inFlightMutex.withLock { inFlight.remove(key) } }
        }
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
    override fun replyThreadCommitGeneration(partition: DurablePartitionKey, threadChannelId: SyncScopeId): Long =
        generationEngine.scopeCommitGeneration(
            SyncScopeKey(partition = partition, scopeId = threadChannelId, scopeKind = REPLY_THREAD_SCOPE_KIND),
        )

    override suspend fun persistReplyThreadPrewarmPage(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        rawMessages: JsonArray,
        requestedLimit: Int,
        expectedCommitGeneration: Long,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
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
                key in inFlight -> "enter_in_flight"
                !prewarmInFlight.add(key) -> "in_flight"
                else -> null
            }
        }
        if (admission != null) return skip(admission)
        try {
            val scope = SyncScopeKey(partition = partition, scopeId = threadChannelId, scopeKind = REPLY_THREAD_SCOPE_KIND)
            val expectedGeneration = generationEngine.partitionGeneration(partition)
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
                    // TODO(message storage 2b): read revAtRequest before the caller's fetch. The commit-generation
                    // check above does not see realtime loose writes (countsAsScopeCommit = false), so a loose
                    // update that landed during the fetch may be overwritten by this page (as before 2a).
                    if (current == null) clearHeaderOfAnotherParent(scope, frame.cursor.threadParent())?.let { skipped ->
                        log("parent_rewritten", skipped)
                    }
                    val before = generationEngine.storedSegments(scope)
                    val result = generationEngine.applyCoverage(
                        scope = scope,
                        envelopes = frame.envelopes,
                        coverage = loaded.coverage,
                        revAtRequest = generationEngine.storedLocalRev(partition),
                        userDriven = true,
                        expectedPartitionGeneration = expectedGeneration,
                        threadParent = frame.cursor.threadParent(),
                        establishesBaseline = loaded.coverage?.headAtVerify == true,
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
 * Production [ThreadDurablePageExtender] (task #168): persists a paging page into the V2 window the
 * enter snapshot created, only when it is exactly adjacent to that window.
 *
 * Order of gates, each answering "write nothing + one `canonical_v2_adjacent_page` log line":
 * 1. malformed channel id / server flag off / empty page / non-positive limit → before any I/O;
 * 2. the scope kind comes from the store's committed cursors — top-level kinds only, or only
 *    `thread` when the caller passes a reply parent (task #168 step 3): none → `no_window` (only the
 *    enter snapshot creates a window), more than one → ambiguous; a thread window must name the
 *    caller's parent (`parent_mismatch` otherwise, re-checked under the scope writer);
 * 3. (task #168) the canary's scope is written too: see step 8;
 * 4. the page converts as a whole (shared [CanonicalMessageV2RestSnapshotFetcher.convertPage],
 *    reply counts fetched here, outside the scope writer), belongs to this scope, has no duplicate
 *    ids, and lies entirely on the requested side of the anchor;
 * 5. under the scope writer: `stillCurrent()` and flag rechecked; the stored scope is
 *    restored (cursor + facts); the anchor must lie inside the stored window
 *    (`oldestLoadedSeq..newestLoadedSeq`) and its fact must be on disk. Since the window is
 *    contiguous and the page is the server's contiguous block next to the anchor, page ∪ window
 *    is contiguous. The edge (and its `hasLoaded*` flag) moves only when the page extends past it;
 *    a page entirely inside the window is a plain refresh with the cursor unchanged;
 * 6. retention: per-scope pruning keeps the newest [maxFactsPerScope] facts (and, since task #168,
 *    the store itself moves the oldest edge past whatever it prunes). An older page that would push the scope past the cap would be pruned straight back out
 *    while the cursor claimed it, so it is refused (`at_capacity`). A newer page that overflows the
 *    cap prunes the oldest end, so the cursor's oldest edge is moved to the oldest fact that
 *    survives and `hasLoadedOldest` is cleared;
 * 7. `applyIncremental` with the UNION cursor (the engine overwrites the cursor wholesale), bound
 *    to the partition generation captured before the network call;
 * 8. after a commit, still inside the scope writer the realtime canary serializes on: an OLDER page only
 *    moves the oldest edge, so the window keeps its realtime continuity
 *    ([CanonicalMessageV2ProductionCanary.onOlderPageCommitted]); a NEWER page (and the gap catch-up)
 *    moves the newest edge, which nothing proves continuous with the realtime stream, so the window is
 *    display-only for realtime frames until the next enter commit
 *    ([CanonicalMessageV2ProductionCanary.onExternalCommit], #3008).
 */
internal class CanonicalMessageV2AdjacentPageWriter(
    private val canary: CanonicalMessageV2ProductionCanary,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val fetcher: CanonicalMessageV2RestSnapshotFetcher,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    private val logger: (String) -> Unit = {},
    /** Must equal the store's [CanonicalMessageV2RetentionPolicy.maxFactsPerScope]. */
    private val maxFactsPerScope: Int = CanonicalMessageV2RetentionPolicy().maxFactsPerScope,
    /** Same rule as the enter loader's: unknown = member. */
    private val isKnownNonMember: (serverId: String, channelId: String) -> Boolean = { _, _ -> false },
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

    override suspend fun abandonLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) =
        canary.abandonEnterBaseline(baseline)

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
        // Task v2: a task window is written by its enter snapshot and live frames only; paging pages
        // carry no parent context to convert under (TODO(task-v2 P1): extend task windows too).
        if (scopeKind == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) return log("task_window_extend_unsupported")
        val scope = SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = scopeKind)

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
                val result = generationEngine.applyIncremental(
                    CanonicalMessageV2IncrementalFrame(scope = scope, envelopes = envelopes, cursor = cursor),
                    expectedGeneration,
                )
                log(result.wireOutcome(envelopes.size))
                if (result == CanonicalMessageV2CommitResult.Committed) {
                    when (direction) {
                        // Task #168: an older page only moves the oldest edge; realtime frames are appended
                        // at the newest edge, serialized with it by this same scope writer. The window
                        // stays continuous (the channel the user is viewing keeps its realtime cache).
                        ThreadDurablePageDirection.Older -> canary.onOlderPageCommitted(scope)
                        // A newer page moves the newest edge: nothing proves it continuous with the
                        // realtime stream (#3008).
                        ThreadDurablePageDirection.Newer -> canary.onExternalCommit(scope)
                    }
                }
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                log("poisoned fact=${poisoned.factId.orEmpty()}")
            }
        }
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
        stillCurrent: () -> Boolean,
    ) = applyLatestPageAfterBaseline(
        partition, channelId, rawMessages, requestedLimit, replyParent, null, messageWindow, historyLimited, stillCurrent,
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
        stillCurrent: () -> Boolean,
    ) {
        try {
            applyLatestPageInternal(
                partition, channelId, rawMessages, requestedLimit, replyParent, baseline, messageWindow, historyLimited, stillCurrent,
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
        val pageMin = pageSeqs.min()
        val pageMax = pageSeqs.max()

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
                val result = generationEngine.applyCoverage(
                    scope = scope,
                    envelopes = envelopes,
                    coverage = coverage,
                    // TODO(message storage 2b): read before the caller's fetch; until then a page written now may
                    // overwrite a realtime update of the same fact that landed during the fetch (as before 2a).
                    revAtRequest = generationEngine.storedLocalRev(partition),
                    userDriven = true,
                    expectedPartitionGeneration = expectedGeneration,
                    threadParent = stored.threadParent(),
                    establishesBaseline = coverage?.headAtVerify == true,
                )
                // What the window holds now, for the baseline replay: the page under the committed cursor.
                val committedFrame = generationEngine.currentCursor(scope)?.let { cursor ->
                    CanonicalMessageV2SnapshotFrame(scope = scope, envelopes = envelopes, cursor = cursor)
                }
                val outcome = if (result == CanonicalMessageV2CommitResult.Committed) {
                    "${rangeOutcome(before, generationEngine.storedSegments(scope))} new=$pageMin..$pageMax " +
                        result.wireOutcome(envelopes.size)
                } else {
                    result.wireOutcome(envelopes.size)
                }
                log(outcome)
                if (result == CanonicalMessageV2CommitResult.Committed) {
                    val frame = committedFrame
                    if (ownBaseline != null && frame != null && coverage?.headAtVerify == true) {
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
     * Task #14 part C: the anchor context page (see [ThreadDurablePageExtender.persistContextPage]). Gates 1–4 are
     * [extendWithAdjacentPage]'s (flag, page, scope family / `no_window`, stored parent, whole-page conversion,
     * foreign scope, duplicate ids) plus: the anchor must be on the page (`anchor_not_on_page`). Under the scope
     * writer (`stillCurrent()` and flag rechecked, the stored scope restored):
     * - join FACT: a fact stored inside the window (`oldestLoadedSeq..newestLoadedSeq`, on disk) is one of the
     *   page's ids — otherwise `not_joined`, nothing written (no islands; see the interface);
     * - only the page's facts outside the window's range are written, insert-only (`inside_window` when none);
     * - the edges move only where the page reaches past them, each with the page's own edge fact
     *   (`hasLoadedOldest = !hasOlder`, `hasLoadedNewest = !hasNewer`);
     * - retention: an older reach past the cap is refused (`at_capacity`, it would be pruned straight back
     *   out); a newer reach moves the oldest edge to the oldest fact that survives, like a newer page;
     * - after a commit the realtime canary is told exactly as for an adjacent page: only the oldest edge
     *   moved ⇒ continuity kept ([CanonicalMessageV2ProductionCanary.onOlderPageCommitted]); the newest edge
     *   moved ⇒ display only until the next enter ([CanonicalMessageV2ProductionCanary.onExternalCommit]).
     * One `canonical_v2_context_page ... outcome=committed|skipped reason=... facts=N` line per call.
     */
    override suspend fun persistContextPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        anchorMessageId: String,
        rawMessages: JsonArray,
        hasOlder: Boolean,
        hasNewer: Boolean,
        replyParent: ThreadDurableReplyParent?,
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

        val allowedKinds =
            if (replyParent == null) CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS else CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS
        val kinds = generationEngine.baselinedScopeKinds(partition, channelId).filter { it in allowedKinds }
        val scopeKind = when (kinds.size) {
            0 -> return skip("no_window")
            1 -> kinds.single()
            else -> return skip("ambiguous_scope_kind")
        }
        // As the sibling writers: a task window is never extended from a page (no task parent to convert under).
        if (scopeKind == CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND) return skip("task_window_extend_unsupported")
        val scope = SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = scopeKind)
        val storedParent = if (replyParent == null) {
            null
        } else {
            storedReplyParentOrNull(generationEngine.storedCursor(scope), replyParent)
                ?: return skip("parent_mismatch")
        }

        val expectedGeneration = generationEngine.partitionGeneration(partition)
        val envelopes = when (
            val converted = fetcher.convertPage(partition, channelId, scopeKind, rawMessages, storedParent)
        ) {
            is CanonicalMessageV2PageConversion.Converted -> converted.envelopes
            is CanonicalMessageV2PageConversion.Refused -> return skip(converted.diagnostic)
        }
        val foreign = envelopes.any { envelope ->
            val envelopeScopeId = when (val context = envelope.context) {
                is CanonicalConversationContextV2.TopLevel -> context.scope.scopeId.takeIf { replyParent == null }
                is CanonicalConversationContextV2.Thread -> context.replyScope.scopeId.takeIf { replyParent != null }
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
                val restored = generationEngine.restore(scope)
                val stored = restored.cursor ?: return@withScopeWriter skip("no_window")
                if (replyParent != null && storedReplyParentOrNull(stored, replyParent) != storedParent) {
                    return@withScopeWriter skip("parent_mismatch")
                }
                val storedOldest = stored.oldestLoadedSeq ?: return@withScopeWriter skip("not_joined")
                val storedNewest = stored.newestLoadedSeq ?: return@withScopeWriter skip("not_joined")
                val pageIds = envelopes.mapTo(mutableSetOf()) { it.fact.id }
                val joined = restored.envelopes.any { it.fact.id in pageIds && it.fact.seq in storedOldest..storedNewest }
                if (!joined) return@withScopeWriter skip("not_joined")
                // Inside the range the window is already the complete record (a missing message there was
                // deleted); a stored fact outside it (a pinned root) is left as it is too.
                val storedIds = restored.envelopes.mapTo(mutableSetOf()) { it.fact.id }
                val added = envelopes.filter { it.fact.seq !in storedOldest..storedNewest && it.fact.id !in storedIds }
                if (added.isEmpty()) return@withScopeWriter skip("inside_window")
                val addedSeqs = added.map { it.fact.seq }
                val addedMin = addedSeqs.min()
                val addedMax = addedSeqs.max()
                val extendsOlder = addedMin < storedOldest
                val extendsNewer = addedMax > storedNewest
                var cursor = stored
                if (extendsOlder) cursor = cursor.copy(oldestLoadedSeq = addedMin, hasLoadedOldest = !hasOlder)
                if (extendsNewer) {
                    cursor = cursor.copy(
                        latestSeq = maxOf(stored.latestSeq ?: addedMax, addedMax),
                        newestLoadedSeq = addedMax,
                        hasLoadedNewest = !hasNewer,
                    )
                }
                if (restored.envelopes.size + added.size > maxFactsPerScope) {
                    if (extendsOlder) return@withScopeWriter skip("at_capacity")
                    // Counted over every stored fact (pinned thread roots included), so the edge is
                    // conservative: pruning never removes a fact at or above it.
                    val survivingOldest = (restored.envelopes.map { it.fact.seq } + addedSeqs)
                        .sortedDescending()
                        .take(maxFactsPerScope)
                        .min()
                    if (survivingOldest > storedOldest) {
                        cursor = cursor.copy(oldestLoadedSeq = survivingOldest, hasLoadedOldest = false)
                    }
                }
                val result = generationEngine.applyIncremental(
                    CanonicalMessageV2IncrementalFrame(scope = scope, envelopes = added, cursor = cursor, insertOnly = true),
                    expectedGeneration,
                )
                val reason = when {
                    extendsOlder && extendsNewer -> "window_extended_both"
                    extendsOlder -> "window_extended_older"
                    else -> "window_extended_newer"
                }
                if (result == CanonicalMessageV2CommitResult.Committed) {
                    log("committed", reason, added.size)
                    if (extendsNewer) canary.onExternalCommit(scope) else canary.onOlderPageCommitted(scope)
                } else {
                    skip(result.wireOutcome(added.size))
                }
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                skip("poisoned fact=${poisoned.factId.orEmpty()}")
            }
        }
    }
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
