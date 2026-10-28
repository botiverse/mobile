package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.model.SyncResumeResponse
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2EnvelopeConversion
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2LiveCoverageTracker
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
import build.raft.app.network.sync.CanonicalMessageV2ThreadParent
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.v2ScopeOrNull
import build.raft.app.network.sync.toCanonicalMessageV2Conversion
import build.raft.app.network.sync.unknownFieldsByFactId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What [CanonicalMessageV2ResumeIngest.commit] did with one `sync:resume:response`. */
internal data class CanonicalMessageV2ResumeIngestResult(
    val written: Int,
    val skipped: Map<String, Int>,
)

/**
 * Message storage design 2026-10-27, Phase 3b (P4): the messages a `sync:resume:response` replays are stored as
 * LOOSE V2 facts before the realtime cursor moves past them (the owner commits, then its legacy continuation saves
 * the applied seq). A resume never claims a range: nothing proves a resume page continuous with any segment.
 *
 * Scope of a row. The server's resume rows have carried no `conversationContext` and no `randomId` (slock
 * `syncMessagesWithHorizon` selected neither; slock #11710 adds the context):
 * - a row WITH a valid context (the same shape checks as a live frame) uses it. No stored header: stored loose under
 *   it (the header is created from it). Exactly one stored header that agrees: stored. A header of another kind, or
 *   a thread header under another parent channel / root: `context_mismatch` is logged and the row skipped, so the
 *   confirmed header is never changed and no second header of the id appears (only page writers change kinds). A
 *   thread whose parent KIND alone differs is written with the row's context under the stored header (the store
 *   decides, as for a live frame). An invalid context is logged and ignored (header rule below);
 * - a row WITHOUT context (design 3b option (a)): exactly one stored header for the id gives the kind; a reply thread
 *   also takes the header's parent. No header, more than one (ambiguous), a thread header without a parent, or a task
 *   conversation (its context needs a parent channel the header does not store): skipped and logged.
 * Also skipped: a row naming a reply thread (`threadId`: its reply count is unknown, Phase 3c) and the user's own
 * messages (their unknown `randomId` must not stand in for the one a local send reconciles by).
 *
 * Continuity. A resume means the stream had a hole. Every replayed row, written or not, is reported to the live
 * coverage tracker; so is every row the decoder refused (its channel, or the whole partition when it names none),
 * and the whole partition when the page says `hasMore` (the rest is unknown) or `horizonReset`.
 *
 * horizonReset (design I7). Late messages were dropped: every segment of the partition stops claiming the server
 * head (`headAtVerify`, and the headers' `hasLoadedNewest` mirror), under the same partition-generation fence; only
 * a later page write (or a proven-continuous run after a new enter) claims it again.
 *
 * Writes: insert-only (a stored fact may already be newer; an insert-only write still fills in what a stored partial
 * fact did not know), ONE transaction per scope for the whole page, bound to the partition generation read under the
 * exact current grant, partition retention once per resume. Headers are read once per channel id per resume. Disk
 * work runs on [dispatcher] in its own scope, one resume at a time in arrival order: a caller that stops waiting
 * (the owner's fail-open timeout) never cancels it half-way.
 */
internal class CanonicalMessageV2ResumeIngest(
    private val tracker: CanonicalMessageV2LiveCoverageTracker,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    logger: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger: (String) -> Unit = { line ->
        try {
            logger(line)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Diagnostics are best-effort.
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val order = Mutex()

    /** The generation a commit of [partition] is bound to; read it under the exact current grant. */
    suspend fun partitionGeneration(partition: DurablePartitionKey): Long = generationEngine.partitionGeneration(partition)

    fun isEnabledFor(serverId: String): Boolean = isEnabledForServer(serverId)

    /** Stores [resume]'s rows; the returned job is never cancelled by a caller that stops awaiting it. */
    fun commitDetached(
        partition: DurablePartitionKey,
        resume: SyncResumeResponse,
        expectedPartitionGeneration: Long,
    ): Deferred<CanonicalMessageV2ResumeIngestResult> =
        scope.async { order.withLock { commit(partition, resume, expectedPartitionGeneration) } }

    /** In-memory only (flag off): reports the resume's hole to the tracker; nothing is stored. */
    suspend fun noteUnrecorded(partition: DurablePartitionKey, resume: SyncResumeResponse) {
        withContext(NonCancellable) { reportHoles(partition, resume) }
    }

    /** Returns once every resume handed over before this call is processed (tests, diagnostics). */
    internal suspend fun awaitIdle() {
        order.withLock {}
    }

    internal fun close() {
        scope.cancel()
    }

    private suspend fun reportHoles(partition: DurablePartitionKey, resume: SyncResumeResponse) {
        if (resume.hasMore) tracker.notePartitionHole(partition, "resume_has_more")
        if (resume.horizonReset) tracker.notePartitionHole(partition, "resume_horizon_reset")
        resume.canonicalDecodeMisses.forEach { channelId ->
            if (channelId == null) tracker.notePartitionHole(partition, "resume_row_undecodable")
            else tracker.noteScopeHole(partition, channelId, "resume_row_undecodable")
        }
        resume.decodedCanonical.forEach { carry ->
            tracker.noteFrameUnrecorded(partition, CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, carry))
        }
    }

    private class Target(val scope: SyncScopeKey, val threadParent: CanonicalMessageV2ThreadParent?)

    private class StoredHeader(val kinds: List<String>, val threadParent: CanonicalMessageV2ThreadParent?)

    internal suspend fun commit(
        partition: DurablePartitionKey,
        resume: SyncResumeResponse,
        expectedPartitionGeneration: Long,
    ): CanonicalMessageV2ResumeIngestResult {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val skipped = linkedMapOf<String, Int>()
        fun skip(reason: String, count: Int = 1) {
            skipped[reason] = (skipped[reason] ?: 0) + count
        }
        withContext(NonCancellable) { reportHoles(partition, resume) }
        val rowCount = resume.decodedCanonical.size
        if (generationEngine.partitionGeneration(partition) != expectedPartitionGeneration) {
            // Cleared after the grant check (server access lost): nothing of this resume may refill it.
            if (rowCount > 0) skip("partition_revoked", rowCount)
            return finish(partition, resume, rowCount, 0, skipped)
        }
        if (resume.horizonReset) {
            val cleared = try {
                generationEngine.clearHeadVerification(partition, expectedPartitionGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                null
            }
            logger("canonical_v2_resume horizon_reset server=${partition.serverId} headVerification=${cleared?.toString() ?: "failed"}")
        }
        val rows = resume.decodedCanonical.sortedBy { it.row.seq }
        if (resume.canonicalDecodeMisses.isNotEmpty()) skip("undecodable", resume.canonicalDecodeMisses.size)
        if (!isEnabledForServer(partition.serverId)) {
            if (rows.isNotEmpty()) skip("flag_off", rows.size)
            return finish(partition, resume, rows.size, 0, skipped)
        }
        // Resolve every row's scope, reading each channel id's header once.
        val headers = mutableMapOf<String, StoredHeader>()
        val batches = LinkedHashMap<Pair<SyncScopeKey, CanonicalMessageV2ThreadParent?>, LinkedHashMap<String, CanonicalMessageV2EnvelopeConversion.Converted>>()
        for (carry in rows) {
            val reason = try {
                resolve(partition, carry, headers) { target, converted ->
                    batches.getOrPut(target.scope to target.threadParent) { LinkedHashMap() }[converted.envelope.fact.id] = converted
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                "poisoned"
            }
            if (reason != null) {
                skip(reason)
                logger("canonical_v2_resume skipped server=${partition.serverId} scope=${carry.row.channelId} msg=${carry.row.id} seq=${carry.row.seq} reason=$reason")
            }
        }
        // One transaction per scope; partition retention once, on the last one.
        var written = 0
        batches.entries.forEachIndexed { index, (key, facts) ->
            val (scope, threadParent) = key
            val reason = try {
                val result = generationEngine.withScopeWriter(scope) {
                    generationEngine.applyCoverage(
                        scope = scope,
                        envelopes = facts.values.map { it.envelope },
                        coverage = null,
                        revAtRequest = generationEngine.storedLocalRev(scope),
                        userDriven = false,
                        expectedPartitionGeneration = expectedPartitionGeneration,
                        threadParent = threadParent,
                        insertOnly = true,
                        unknownFieldsByFactId = facts.values.fold(emptyMap()) { acc, converted -> acc + converted.unknownFieldsByFactId() },
                        runPartitionRetention = index == batches.size - 1,
                        countsAsScopeCommit = false,
                    )
                }
                when (result) {
                    CanonicalMessageV2CommitResult.Committed -> null
                    CanonicalMessageV2CommitResult.PartitionRevoked -> "partition_revoked"
                    CanonicalMessageV2CommitResult.ScopeEvicted -> "retention_evicted"
                    is CanonicalMessageV2CommitResult.BaselineRequired -> "baseline_required"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                "poisoned"
            } catch (throwable: Throwable) {
                "write_failed:${throwable::class.simpleName ?: "unknown"}"
            }
            if (reason == null) {
                written += facts.size
            } else {
                skip(reason, facts.size)
                logger("canonical_v2_resume skipped server=${partition.serverId} scope=${scope.scopeId} scopeKind=${scope.scopeKind} rows=${facts.size} reason=$reason")
            }
        }
        return finish(partition, resume, rows.size, written, skipped)
    }

    private fun finish(
        partition: DurablePartitionKey,
        resume: SyncResumeResponse,
        decoded: Int,
        written: Int,
        skipped: Map<String, Int>,
    ): CanonicalMessageV2ResumeIngestResult {
        logger(
            "canonical_v2_resume server=${partition.serverId} rows=${resume.messages.size} decoded=$decoded " +
                "written=$written hasMore=${resume.hasMore} horizonReset=${resume.horizonReset} " +
                "skipped=${skipped.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }}",
        )
        return CanonicalMessageV2ResumeIngestResult(written, skipped)
    }

    /** `null` = [add] got the row; otherwise why the row is not stored. */
    private suspend fun resolve(
        partition: DurablePartitionKey,
        carry: CanonicalMessageCarry,
        headers: MutableMap<String, StoredHeader>,
        add: (Target, CanonicalMessageV2EnvelopeConversion.Converted) -> Unit,
    ): String? {
        val row = carry.row
        if (row.threadId != null) return "thread_root"
        if (row.senderType == "user" && row.senderId == partition.principalId) return "own_message"
        val header = headers.getOrPut(row.channelId) { readHeader(partition, row.channelId) }
        val payloadContext = row.conversationContext?.takeIf { MessageAggregateField.ConversationContext in carry.presentAggregates }
        val payloadScope = payloadContext?.let {
            CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, carry).v2ScopeOrNull(partition)
        }
        if (payloadContext != null && payloadScope == null) {
            logger("canonical_v2_resume payload_context_invalid server=${partition.serverId} msg=${row.id} channelType=${payloadContext.channelType}")
        }
        val (context, target) = if (payloadScope != null && payloadContext != null) {
            val payloadParent = payloadContext.threadParentOrNull()
            when (header.kinds.size) {
                0 -> payloadContext to Target(payloadScope, payloadParent)
                1 -> {
                    val storedKind = header.kinds.single()
                    val storedParent = header.threadParent
                    val mismatch = when {
                        storedKind != payloadScope.scopeKind -> "kind stored=$storedKind payload=${payloadScope.scopeKind}"
                        storedParent != null && payloadParent != null && (
                            storedParent.parentScopeId != payloadParent.parentScopeId ||
                                storedParent.rootMessageId != payloadParent.rootMessageId
                            ) -> "parent stored=${storedParent.parentScopeId}/${storedParent.rootMessageId} " +
                            "payload=${payloadParent.parentScopeId}/${payloadParent.rootMessageId}"
                        else -> null
                    }
                    if (mismatch != null) {
                        logger("canonical_v2_resume context_mismatch server=${partition.serverId} scope=${row.channelId} msg=${row.id} $mismatch")
                        return "context_mismatch"
                    }
                    if (storedParent != null && payloadParent != null && storedParent.parentScopeKind != payloadParent.parentScopeKind) {
                        logger(
                            "canonical_v2_resume context_mismatch server=${partition.serverId} scope=${row.channelId} msg=${row.id} " +
                                "parentKind stored=${storedParent.parentScopeKind} payload=${payloadParent.parentScopeKind} fact=payload",
                        )
                    }
                    payloadContext to Target(payloadScope, payloadParent)
                }
                else -> return "ambiguous_header"
            }
        } else {
            val kind = when (header.kinds.size) {
                0 -> return "no_header"
                1 -> header.kinds.single()
                else -> return "ambiguous_header"
            }
            val scope = SyncScopeKey(partition = partition, scopeId = row.channelId, scopeKind = kind)
            when {
                kind == REPLY_THREAD_SCOPE_KIND -> {
                    val parent = header.threadParent ?: return "thread_parent_unknown"
                    CanonicalConversationContextWire(
                        channelType = REPLY_THREAD_SCOPE_KIND,
                        parentChannelId = parent.parentScopeId,
                        parentChannelType = parent.parentScopeKind,
                        parentMessageId = parent.rootMessageId,
                    ) to Target(scope, parent)
                }
                kind in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS -> CanonicalConversationContextWire(channelType = kind) to Target(scope, null)
                else -> return "context_unknown:$kind"
            }
        }
        val input = CanonicalMessageFoldInput.from(
            CanonicalMessageFoldKind.New,
            carry.copy(
                row = row.copy(conversationContext = context),
                presentAggregates = carry.presentAggregates + MessageAggregateField.ConversationContext,
            ),
        )
        return when (val conversion = input.toCanonicalMessageV2Conversion(partition, replyCount = 0, allowPartial = true)) {
            is CanonicalMessageV2EnvelopeConversion.Converted -> {
                add(target, conversion)
                null
            }
            is CanonicalMessageV2EnvelopeConversion.NotFull -> "not_full_v2"
        }
    }

    private suspend fun readHeader(partition: DurablePartitionKey, channelId: String): StoredHeader {
        val kinds = generationEngine.baselinedScopeKinds(partition, channelId)
        val parent = kinds.singleOrNull()?.takeIf { it == REPLY_THREAD_SCOPE_KIND }?.let { kind ->
            val cursor = generationEngine.storedCursor(SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = kind))
            val parentKind = cursor?.parentScopeKind?.takeIf { it in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS }
            val parentId = cursor?.parentScopeId
            val root = cursor?.rootMessageId
            if (parentKind != null && parentId != null && root != null) CanonicalMessageV2ThreadParent(parentKind, parentId, root) else null
        }
        return StoredHeader(kinds, parent)
    }

    private fun CanonicalConversationContextWire.threadParentOrNull(): CanonicalMessageV2ThreadParent? {
        if (channelType != REPLY_THREAD_SCOPE_KIND) return null
        val kind = parentChannelType ?: return null
        val id = parentChannelId ?: return null
        val root = parentMessageId ?: return null
        return CanonicalMessageV2ThreadParent(kind, id, root)
    }

    private companion object {
        const val REPLY_THREAD_SCOPE_KIND = "thread"
    }
}

/**
 * Phase 3b: the owner's resume seam. [captureUnderGrant] runs inside the exact-current-grant slot and reads only the
 * partition generation; the returned commit runs after the slot, before the resume's legacy continuation saves the
 * applied seq.
 */
internal class RealtimeResumeCanonicalMessageV2Gateway(
    private val ingest: CanonicalMessageV2ResumeIngest,
) {
    suspend fun captureUnderGrant(grant: RealtimeGrantRecord, resume: SyncResumeResponse): (suspend () -> Unit)? {
        // Nothing stored and no hole to report: no work at all.
        if (resume.decodedCanonical.isEmpty() && resume.canonicalDecodeMisses.isEmpty() && !resume.hasMore && !resume.horizonReset) {
            return null
        }
        val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        // Flag off: nothing is stored, but the hole is still reported (in memory) so no window claims across it.
        if (!ingest.isEnabledFor(partition.serverId)) return { ingest.noteUnrecorded(partition, resume) }
        val expectedGeneration = ingest.partitionGeneration(partition)
        return { ingest.commitDetached(partition, resume, expectedGeneration).await() }
    }
}
