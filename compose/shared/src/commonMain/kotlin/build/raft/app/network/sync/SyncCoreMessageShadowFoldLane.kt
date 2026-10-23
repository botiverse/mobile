package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.RaftSqlDatabase
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal enum class PartitionDenialReason(val wireValue: String) {
    PrincipalUnavailable("principal_unavailable"),
}

internal data class PartitionDenied(
    val reason: PartitionDenialReason,
    val deniedFrameCount: Int,
)

/**
 * The sync-core messages "sole-apply t0" SHADOW fold+persist lane for message-canonical frames.
 *
 * This is a SHADOW-OF-RECORD: it folds each accepted message frame through the pure [SyncCore] messages
 * domain and durably persists the resulting per-channel fact window via the one-txn [FoldPersistenceAdapter].
 * It is INVISIBLE to the legacy realtime sinks (it neither reads nor mutates any sink output) and is
 * FAIL-OPEN — every fold/persist error is logged with a bounded diagnostic and swallowed, so it can never
 * block or throw into the legacy accept path. Sole-apply stays OFF ([CANONICAL_FACT_SOLE_APPLY_ELIGIBLE]);
 * nothing here changes any read path.
 */
internal class SyncCoreMessageShadowFoldLane(
    private val adapter: FoldPersistenceAdapter,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
    // One active message-domain core per owner/shell. The host also owns authoritative rebaseline swaps.
    private val domainHost: MessageDomainSyncHost = MessageDomainSyncHost(),
) {
    private data class QueuedPersistFrame(
        val serverId: String,
        val frame: FoldPersistFrame,
    )

    private val foldAndEnqueueLock = SynchronizedObject()
    private val persistQueue = Channel<QueuedPersistFrame>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (queued in persistQueue) {
                runCatching { adapter.applyFrame(queued.frame) }
                    .onFailure { logShadowFailure(queued.serverId, it) }
            }
        }.invokeOnCompletion { persistQueue.close() }
    }

    /**
     * Fold one canonical message frame into the shadow core, then persist the channel scope derived from the
     * canonical payload inside [partition]. [updated] selects [MessageDomainEvent.Updated] vs
     * [MessageDomainEvent.New]. Callers cannot supply a second scope identity that could disagree with the
     * payload, and no server-only/principal-inferred overload exists.
     */
    fun foldAndPersist(
        partition: DurablePartitionKey,
        decoded: CanonicalMessageCarry,
        updated: Boolean,
        epoch: SyncEpoch?,
    ) {
        val scopeKey = SyncScopeKey(
            partition = partition,
            scopeId = decoded.row.channelId,
            scopeKind = MESSAGE_SCOPE_KIND,
        )
        try {
            synchronized(foldAndEnqueueLock) {
                val row = decoded.row
                val channelId = row.channelId
                val domainEvent: MessageDomainEvent =
                    if (updated) MessageDomainEvent.Updated(row, decoded.presentAggregates)
                    else MessageDomainEvent.New(row, decoded.presentAggregates)
                domainHost.ingestFrame(
                    SyncFrame(scopeId = channelId, seq = row.seq.toSyncSeq(), epoch = epoch, event = domainEvent),
                )
                val appliedSeq = domainHost.scopeSyncState(channelId)?.appliedSeq?.toLongExact() ?: row.seq
                val facts = domainHost.state(channelId)
                    .channelMessages
                    .get(channelId)
                    .orEmpty()
                val frame = FoldPersistFrame(
                    scope = scopeKey,
                    facts = facts,
                    cursor = PersistedCursor(
                        // Updated frames carry the message creation seq. Persist the host watermark so
                        // an edit to an older message cannot rewind the durable scope cursor.
                        latestSeq = appliedSeq,
                        oldestLoadedSeq = facts.firstOrNull()?.seq,
                        newestLoadedSeq = facts.lastOrNull()?.seq ?: appliedSeq,
                        hasLoadedOldest = false,
                        hasLoadedNewest = true,
                        epoch = epoch,
                    ),
                    rebaseline = false,
                    prunedFactIds = emptyList(),
                )
                if (persistQueue.trySend(QueuedPersistFrame(scopeKey.serverId, frame)).isFailure) {
                    logShadowFailure(scopeKey.serverId, IllegalStateException("shadow persist queue unavailable"))
                }
                // Per-frame evidence line for the dual-surface subscription audit: ids/seqs
                // only — no content, sender identity, or credentials. `applied` is the host
                // watermark the frame landed under; a replayed delivery folds to the same
                // watermark and row count does not grow.
                runCatching {
                    logger(
                        "sync_core_t0_frame id=${row.id} seq=${row.seq} applied=$appliedSeq " +
                            "updated=$updated scope=${scopeKey.scopeId} partition=${partition.serverId}:${partition.consumerId}"
                    )
                }
            }
        } catch (throwable: Throwable) {
            logShadowFailure(scopeKey.serverId, throwable)
        }
    }

    /** Fail closed when a legacy/unowned caller cannot supply the durable principal partition. */
    fun partitionDenied(
        serverId: String,
        reason: PartitionDenialReason,
    ): PartitionDenied =
        partitionDenied(serverId, reason, deniedFrameCount = 1)

    /** Emit one bounded diagnostic for a denied eligible batch without logging identity or content. */
    fun partitionDenied(
        serverId: String,
        reason: PartitionDenialReason,
        deniedFrameCount: Int,
    ): PartitionDenied =
        PartitionDenied(
            reason = reason,
            deniedFrameCount = deniedFrameCount.also { count ->
                require(count > 0) { "deniedFrameCount must be positive" }
            },
        ).also {
            runCatching {
                logger(
                    "sync_core_partition_persist_denied reason=${reason.wireValue} " +
                        "serverId=$serverId consumer=$SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID " +
                        "deniedFrameCount=$deniedFrameCount",
                )
            }
        }

    /** Start the per-scope generation before the host issues the authoritative snapshot request. */
    internal fun beginAuthoritativeRebaseline(scopeId: SyncScopeId, generation: Long): Boolean =
        domainHost.beginAuthoritativeRebaseline(scopeId, generation)

    /** Complete a begun authoritative conversation hydrate/rebaseline generation. */
    internal fun ingestAuthoritativeSnapshot(
        snapshot: SyncSnapshot,
        generation: Long,
    ): SyncIngestOutcome =
        domainHost.ingestAuthoritativeSnapshot(snapshot, generation)

    fun logShadowFailure(serverId: String, throwable: Throwable) {
        runCatching {
            logger("sync_core_t0_shadow_persist_failed serverId=$serverId error=${throwable::class.simpleName}")
        }
    }

    companion object {
        private const val MESSAGE_SCOPE_KIND: String = "channel"

        /** Build the production lane over the real SQLite one-txn persistence for [sqlDatabase]. */
        fun create(
            sqlDatabase: RaftSqlDatabase,
            now: () -> Long,
            scope: CoroutineScope,
            logger: (String) -> Unit,
        ): SyncCoreMessageShadowFoldLane =
            SyncCoreMessageShadowFoldLane(
                adapter = DefaultFoldPersistenceAdapter(
                    runner = SqlDelightFoldPersistTransactionRunner(sqlDatabase),
                    store = SqlDelightFoldPersistStore(sqlDatabase, now = now),
                ),
                scope = scope,
                logger = logger,
            )
    }
}
