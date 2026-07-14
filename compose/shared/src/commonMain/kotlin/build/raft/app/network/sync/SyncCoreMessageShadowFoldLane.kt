package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.RealtimeOwnerEpoch
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SlockSqlDatabase
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The `sync_core_messages_v0` "sole-apply t0" SHADOW fold+persist lane for message-canonical frames.
 *
 * This is a SHADOW-OF-RECORD: it folds each accepted message frame through the pure [SyncCore] messages
 * domain and durably persists the resulting per-channel fact window via the one-txn [FoldPersistenceAdapter].
 * It is INVISIBLE to the legacy realtime sinks (it neither reads nor mutates any sink output) and is
 * FAIL-OPEN — every fold/persist error is logged with a bounded diagnostic and swallowed, so it can never
 * block or throw into the legacy accept path. Sole-apply stays OFF ([CANONICAL_FACT_SOLE_APPLY_ELIGIBLE]);
 * nothing here changes any read path.
 *
 * The flag gate ([isEnabled]) is the same `isEnabledForAnyResolvedServer` global gate the shadow-compare
 * observer uses. When the flag is off (default) the caller never invokes the lane, so it is dormant.
 */
internal class SyncCoreMessageShadowFoldLane(
    private val featureFlags: SharedServerFeatureFlags,
    adapter: FoldPersistenceAdapter,
    internal val ownerCommitGate: OwnerCommitGate,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
    // One active message-domain core per owner/shell. The host also owns authoritative rebaseline swaps.
    private val domainHost: MessageDomainSyncHost = MessageDomainSyncHost(),
) {
    private val ownerBoundAdapter = OwnerBoundFoldPersistenceAdapter(ownerCommitGate, adapter)
    private data class QueuedPersistFrame(
        val ownerToken: RealtimeOwnerEpoch,
        val serverId: String,
        val frame: FoldPersistFrame,
    )

    private val foldAndEnqueueLock = SynchronizedObject()
    private val ownerLock = SynchronizedObject()
    private var latestOwnerGeneration = 0L
    private var currentOwnerToken: RealtimeOwnerEpoch? = null
    private val persistQueue = Channel<QueuedPersistFrame>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (queued in persistQueue) {
                runCatching {
                    ownerBoundAdapter.applyFrame(queued.frame, queued.ownerToken.connectionEpoch)
                }
                    .onFailure { logShadowFailure(queued.serverId, it) }
            }
        }.invokeOnCompletion { persistQueue.close() }
    }

    fun isEnabled(): Boolean =
        featureFlags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)

    fun rotateOwner(ownerToken: RealtimeOwnerEpoch) {
        synchronized(ownerLock) {
            if (ownerToken.connectionEpoch <= latestOwnerGeneration) return
            latestOwnerGeneration = ownerToken.connectionEpoch
            currentOwnerToken = ownerToken
            domainHost.clearAll()
        }
    }

    fun revokeOwner() {
        synchronized(ownerLock) {
            currentOwnerToken = null
            domainHost.clearAll()
        }
    }

    /**
     * Fold one canonical message frame into the shadow core, then persist that channel scope's resulting fact
     * window off the caller thread. [updated] selects [MessageDomainEvent.Updated] vs [MessageDomainEvent.New].
     * [epoch] is the caller's per-scope recovery epoch (consistent with the legacy cursor). FAIL-OPEN: the
     * whole body is guarded; the async persist uses runCatching; nothing propagates to the caller.
     */
    fun foldAndPersist(
        resolvedServerId: String,
        ownerToken: RealtimeOwnerEpoch,
        decoded: CanonicalMessageCarry,
        updated: Boolean,
        epoch: SyncEpoch?,
    ) {
        try {
            synchronized(ownerLock) {
                if (currentOwnerToken != ownerToken || ownerToken.serverId != resolvedServerId) return
                synchronized(foldAndEnqueueLock) {
                    val row = decoded.row
                    val channelId = row.channelId
                    val domainEvent: MessageDomainEvent =
                        if (updated) MessageDomainEvent.Updated(row, decoded.presentAggregates)
                        else MessageDomainEvent.New(row, decoded.presentAggregates)
                    domainHost.ingestFrame(
                        SyncFrame(scopeId = channelId, seq = row.seq, epoch = epoch, event = domainEvent),
                    )
                    val appliedSeq = domainHost.scopeSyncState(channelId)?.appliedSeq ?: row.seq
                    val facts =
                        domainHost.state(channelId)
                            .channelMessages
                            .get(channelId)
                            .orEmpty()
                    val frame =
                        FoldPersistFrame(
                            scope = SyncScopeKey(resolvedServerId, channelId, SHADOW_SCOPE_KIND),
                            facts = facts,
                            cursor =
                                PersistedCursor(
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
                    if (persistQueue.trySend(QueuedPersistFrame(ownerToken, resolvedServerId, frame)).isFailure) {
                        logShadowFailure(resolvedServerId, IllegalStateException("shadow persist queue unavailable"))
                    }
                }
            }
        } catch (throwable: Throwable) {
            logShadowFailure(resolvedServerId, throwable)
        }
    }

    /** Start the per-scope generation before the host issues the authoritative snapshot request. */
    internal fun beginAuthoritativeRebaseline(
        ownerToken: RealtimeOwnerEpoch,
        scopeId: SyncScopeId,
        generation: Long
    ): Boolean =
        synchronized(ownerLock) {
            currentOwnerToken == ownerToken && domainHost.beginAuthoritativeRebaseline(scopeId, generation)
        }

    /** Complete a begun authoritative conversation hydrate/rebaseline generation. */
    internal fun ingestAuthoritativeSnapshot(
        ownerToken: RealtimeOwnerEpoch,
        snapshot: SyncSnapshot,
        generation: Long,
    ): SyncIngestOutcome =
        synchronized(ownerLock) {
            if (currentOwnerToken != ownerToken) {
                SyncIngestOutcome.DuplicateDropped(snapshot.scopeId, snapshot.watermark)
            } else {
                domainHost.ingestAuthoritativeSnapshot(snapshot, generation)
            }
        }

    fun logShadowFailure(serverId: String, throwable: Throwable) {
        runCatching {
            logger("sync_core_t0_shadow_persist_failed serverId=$serverId error=${throwable::class.simpleName}")
        }
    }

    companion object {
        private const val SHADOW_SCOPE_KIND: String = "channel"

        /** Build the production lane over the real SQLite one-txn persistence for [sqlDatabase]. */
        fun create(
            featureFlags: SharedServerFeatureFlags,
            sqlDatabase: SlockSqlDatabase,
            ownerCommitGate: OwnerCommitGate,
            now: () -> Long,
            scope: CoroutineScope,
            logger: (String) -> Unit,
        ): SyncCoreMessageShadowFoldLane =
            SyncCoreMessageShadowFoldLane(
                featureFlags = featureFlags,
                adapter =
                    DefaultFoldPersistenceAdapter(
                        runner = SqlDelightFoldPersistTransactionRunner(sqlDatabase),
                        store = SqlDelightFoldPersistStore(sqlDatabase, now = now),
                ),
                ownerCommitGate = ownerCommitGate,
                scope = scope,
                logger = logger,
            )
    }
}
