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
import build.raft.app.network.sync.frameLabel
import build.raft.app.network.sync.toCanonicalMessageV2Conversion
import build.raft.app.network.sync.unknownFieldsByFactId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
 * The server's resume rows carry no `conversationContext` and no `randomId` (slock `syncMessagesWithHorizon` selects
 * neither), so a row alone does not say which scope it belongs to. The context comes from the stored scope header
 * of the row's channel id (design 3b, option (a)):
 * - exactly one stored header for the id: its kind; a reply thread also needs its stored parent (kind, channel,
 *   root), which becomes the context;
 * - no header (a channel never entered nor seen live) or more than one (ambiguous): skipped and logged;
 * - a task conversation (its context needs a parent channel the header does not store), a row naming a reply thread
 *   (`threadId`: its reply count is unknown, Phase 3c) and the user's own messages (their unknown `randomId` must
 *   not stand in for the one a local send reconciles by) are skipped too.
 *
 * Every replayed row, written or not, is first reported to the live coverage tracker as unrecorded: a resume means
 * the stream had a hole, so no window of those channels may stay continuous across it.
 *
 * The rows are written insert-only (a stored fact may already be newer; an insert-only write still fills in what a
 * stored partial fact did not know), one transaction per row, bound to the partition generation read under the
 * exact current grant. Disk work runs on [dispatcher] in its own scope: a caller that stops waiting (the owner's
 * fail-open timeout) never cancels it half-way, and resumes are processed one at a time in arrival order.
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
    ) = scope.async { order.withLock { commit(partition, resume, expectedPartitionGeneration) } }

    /** In-memory only: reports every row of [resume] to the tracker as unrecorded. */
    suspend fun noteUnrecorded(partition: DurablePartitionKey, resume: SyncResumeResponse) {
        withContext(NonCancellable) {
            resume.decodedCanonical.forEach { carry ->
                tracker.noteFrameUnrecorded(partition, CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, carry))
            }
        }
    }

    internal fun close() {
        scope.cancel()
    }

    internal suspend fun commit(
        partition: DurablePartitionKey,
        resume: SyncResumeResponse,
        expectedPartitionGeneration: Long,
    ): CanonicalMessageV2ResumeIngestResult {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val skipped = linkedMapOf<String, Int>()
        fun skip(reason: String) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }
        val rows = resume.decodedCanonical.sortedBy { it.row.seq }
        // The stream had a hole: no window of these channels stays continuous across it, written or not.
        withContext(NonCancellable) {
            rows.forEach { carry -> tracker.noteFrameUnrecorded(partition, CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, carry)) }
        }
        var written = 0
        for (carry in rows) {
            val reason = try {
                writeLoose(partition, carry, expectedPartitionGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                "poisoned"
            } catch (throwable: Throwable) {
                "write_failed:${throwable::class.simpleName ?: "unknown"}"
            }
            if (reason == null) written += 1 else skip(reason)
        }
        logger(
            "canonical_v2_resume server=${partition.serverId} rows=${resume.messages.size} decoded=${rows.size} " +
                "written=$written skipped=${skipped.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }}",
        )
        return CanonicalMessageV2ResumeIngestResult(written, skipped)
    }

    /** `null` = written; otherwise why not. */
    private suspend fun writeLoose(
        partition: DurablePartitionKey,
        carry: CanonicalMessageCarry,
        expectedPartitionGeneration: Long,
    ): String? {
        if (!isEnabledForServer(partition.serverId)) return "flag_off"
        val row = carry.row
        if (row.threadId != null) return "thread_root"
        if (row.senderType == "user" && row.senderId == partition.principalId) return "own_message"
        val kinds = generationEngine.baselinedScopeKinds(partition, row.channelId)
        val kind = when (kinds.size) {
            0 -> return "no_header"
            1 -> kinds.single()
            else -> return "ambiguous_header"
        }
        val scope = SyncScopeKey(partition = partition, scopeId = row.channelId, scopeKind = kind)
        val (context, threadParent) = when {
            kind == REPLY_THREAD_SCOPE_KIND -> {
                val cursor = generationEngine.storedCursor(scope) ?: return "no_header"
                val parentKind = cursor.parentScopeKind?.takeIf { it in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS }
                    ?: return "thread_parent_unknown"
                val parentId = cursor.parentScopeId ?: return "thread_parent_unknown"
                val root = cursor.rootMessageId ?: return "thread_parent_unknown"
                CanonicalConversationContextWire(
                    channelType = REPLY_THREAD_SCOPE_KIND,
                    parentChannelId = parentId,
                    parentChannelType = parentKind,
                    parentMessageId = root,
                ) to CanonicalMessageV2ThreadParent(parentKind, parentId, root)
            }
            kind in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS -> CanonicalConversationContextWire(channelType = kind) to null
            else -> return "context_unknown:$kind"
        }
        val input = CanonicalMessageFoldInput.from(
            CanonicalMessageFoldKind.New,
            carry.copy(
                row = row.copy(conversationContext = context),
                presentAggregates = carry.presentAggregates + MessageAggregateField.ConversationContext,
            ),
        )
        val converted = when (
            val conversion = input.toCanonicalMessageV2Conversion(partition, replyCount = 0, allowPartial = true)
        ) {
            is CanonicalMessageV2EnvelopeConversion.Converted -> conversion
            is CanonicalMessageV2EnvelopeConversion.NotFull -> return "not_full_v2"
        }
        val result = generationEngine.withScopeWriter(scope) {
            generationEngine.applyCoverage(
                scope = scope,
                envelopes = listOf(converted.envelope),
                coverage = null,
                revAtRequest = generationEngine.storedLocalRev(scope),
                userDriven = false,
                expectedPartitionGeneration = expectedPartitionGeneration,
                threadParent = threadParent,
                insertOnly = true,
                unknownFieldsByFactId = converted.unknownFieldsByFactId(),
                runPartitionRetention = false,
                countsAsScopeCommit = false,
            )
        }
        return when (result) {
            CanonicalMessageV2CommitResult.Committed -> null
            CanonicalMessageV2CommitResult.PartitionRevoked -> "partition_revoked"
            CanonicalMessageV2CommitResult.ScopeEvicted -> "retention_evicted"
            is CanonicalMessageV2CommitResult.BaselineRequired -> "baseline_required"
        }.also { if (it != null) logger("canonical_v2_resume skipped ${input.frameLabel()} reason=$it") }
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
        if (resume.decodedCanonical.isEmpty()) return null
        val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        // Flag off: nothing is stored, but the hole is still reported (in memory) so no window claims across it.
        if (!ingest.isEnabledFor(partition.serverId)) return { ingest.noteUnrecorded(partition, resume) }
        val expectedGeneration = ingest.partitionGeneration(partition)
        return { ingest.commitDetached(partition, resume, expectedGeneration).await() }
    }
}
