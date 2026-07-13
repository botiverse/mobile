package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.RealtimeIngressOwnerToken
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
 * The in-memory message-domain owner is always fed so hydrate/projection has one canonical source. The
 * flag gate ([isEnabled]) controls only the legacy SQLite shadow persistence and diagnostics rollout.
 */
internal class SyncCoreMessageShadowFoldLane(
    private val featureFlags: SharedServerFeatureFlags,
    private val adapter: FoldPersistenceAdapter,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
    // One active message-domain core per owner/shell. The host also owns authoritative rebaseline swaps.
    private val domainHost: MessageDomainSyncHost = MessageDomainSyncHost(),
) {
    private data class MessageDomainOwner(
        val serverId: String,
        val memberId: String,
        val generation: Long,
    )

    private data class QueuedPersistFrame(
        val serverId: String,
        val frame: FoldPersistFrame,
    )

    private val foldAndEnqueueLock = SynchronizedObject()
    private val ownerLock = SynchronizedObject()
    private val generationLock = SynchronizedObject()
    private val nextGenerationByScope = mutableMapOf<SyncScopeId, Long>()
    private var nextOwnerGeneration = 0L
    private var compatibilityOwnerToken: RealtimeIngressOwnerToken? = null
    private var currentOwner: MessageDomainOwner? = null
    private val persistQueue = Channel<QueuedPersistFrame>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (queued in persistQueue) {
                runCatching { adapter.applyFrame(queued.frame) }
                    .onFailure { logShadowFailure(queued.serverId, it) }
            }
        }.invokeOnCompletion { persistQueue.close() }
    }

    fun isEnabled(): Boolean =
        featureFlags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)

    fun rotateOwner(ownerToken: RealtimeIngressOwnerToken) {
        synchronized(ownerLock) { bindOwnerLocked(ownerToken) }
    }

    /**
     * Fold one canonical message frame into the shadow core, then persist that channel scope's resulting fact
     * window off the caller thread. [updated] selects [MessageDomainEvent.Updated] vs [MessageDomainEvent.New].
     * [epoch] is the caller's per-scope recovery epoch (consistent with the legacy cursor). FAIL-OPEN: the
     * whole body is guarded; the async persist uses runCatching; nothing propagates to the caller.
     */
    fun foldAndPersist(
        resolvedServerId: String,
        ownerToken: RealtimeIngressOwnerToken?,
        decoded: CanonicalMessageCarry,
        updated: Boolean,
        epoch: SyncEpoch?,
    ) {
        try {
            synchronized(ownerLock) {
                resolveOwnerLocked(resolvedServerId, ownerToken) ?: return
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
                    val facts = domainHost.state(channelId)
                        .channelMessages
                        .get(channelId)
                        .orEmpty()
                    val frame = FoldPersistFrame(
                        scope = SyncScopeKey(resolvedServerId, channelId, SHADOW_SCOPE_KIND),
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
                    if (isEnabled() && persistQueue.trySend(QueuedPersistFrame(resolvedServerId, frame)).isFailure) {
                        logShadowFailure(resolvedServerId, IllegalStateException("shadow persist queue unavailable"))
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (isEnabled()) logShadowFailure(resolvedServerId, throwable)
        }
    }

    internal fun foldAndPersist(
        resolvedServerId: String,
        memberId: String?,
        decoded: CanonicalMessageCarry,
        updated: Boolean,
        epoch: SyncEpoch?,
    ) {
        val ownerToken = memberId?.let { compatibilityOwnerToken(resolvedServerId, it) }
        foldAndPersist(resolvedServerId, ownerToken, decoded, updated, epoch)
    }

    /** Start the per-scope generation before the host issues the authoritative snapshot request. */
    internal fun beginAuthoritativeRebaseline(scopeId: SyncScopeId, generation: Long): Boolean =
        domainHost.beginAuthoritativeRebaseline(scopeId, generation)

    fun beginAuthoritativeRebaseline(
        ownerToken: RealtimeIngressOwnerToken,
        scopeId: SyncScopeId,
        epoch: SyncEpoch?,
    ): MessageScopeRebaselineToken? {
        return synchronized(ownerLock) {
            val owner = bindOwnerLocked(ownerToken) ?: return@synchronized null
            val generation = synchronized(generationLock) {
                val next = (nextGenerationByScope[scopeId] ?: 0L) + 1L
                nextGenerationByScope[scopeId] = next
                next
            }
            if (domainHost.beginAuthoritativeRebaseline(scopeId, generation)) {
                MessageScopeRebaselineToken(
                    serverId = ownerToken.serverId,
                    memberId = ownerToken.memberId,
                    ownerGeneration = owner.generation,
                    scopeId = scopeId,
                    generation = generation,
                    epoch = epoch,
                )
            } else {
                null
            }
        }
    }

    internal fun beginAuthoritativeRebaseline(
        serverId: String,
        memberId: String,
        scopeId: SyncScopeId,
        epoch: SyncEpoch?,
    ): MessageScopeRebaselineToken? =
        beginAuthoritativeRebaseline(compatibilityOwnerToken(serverId, memberId), scopeId, epoch)

    /** Complete a begun authoritative conversation hydrate/rebaseline generation. */
    internal fun ingestAuthoritativeSnapshot(
        snapshot: SyncSnapshot,
        generation: Long,
    ): SyncIngestOutcome =
        domainHost.ingestAuthoritativeSnapshot(snapshot, generation)

    fun completeAuthoritativeRebaseline(
        token: MessageScopeRebaselineToken,
        memberId: String,
        receiverKind: MessageReceiverKind,
        rows: List<CanonicalMessageRow>,
        coveredAfterSeq: Long,
        coveredFromSeq: Long,
        coveredThroughSeq: Long,
        remoteHighWaterSeq: Long,
        hasGap: Boolean,
        hasNewer: Boolean,
    ): SyncIngestOutcome {
        return synchronized(ownerLock) {
            if (!token.matches(currentOwner) || token.memberId != memberId) {
                return@synchronized SyncIngestOutcome.DuplicateDropped(token.scopeId, remoteHighWaterSeq)
            }
            val expectedCoveredThrough =
                if (!hasGap && !hasNewer) remoteHighWaterSeq
                else rows.maxOfOrNull { it.seq } ?: coveredFromSeq - 1L
            if (coveredThroughSeq != expectedCoveredThrough) {
                domainHost.invalidateAuthoritativeRebaseline(token.scopeId, token.generation)
                return@synchronized SyncIngestOutcome.DuplicateDropped(token.scopeId, remoteHighWaterSeq)
            }
            val state =
                rebaselineMessageScope(
                    createInitialMessageDomainState(),
                    MessageScopeCoverageSnapshot(
                        serverId = token.serverId,
                        memberId = memberId,
                        receiverKind = receiverKind,
                        scopeId = token.scopeId,
                        epoch = token.epoch,
                        rows = rows,
                        coveredAfterSeq = coveredAfterSeq,
                        coveredFromSeq = coveredFromSeq,
                        remoteHighWaterSeq = remoteHighWaterSeq,
                        hasGap = hasGap,
                        hasNewer = hasNewer,
                    ),
                )
            domainHost.ingestAuthoritativeSnapshot(
                SyncSnapshot(
                    scopeId = token.scopeId,
                    watermark = remoteHighWaterSeq,
                    epoch = token.epoch,
                    state = state,
                ),
                token.generation,
            )
        }
    }

    fun invalidateAuthoritativeRebaseline(token: MessageScopeRebaselineToken) {
        synchronized(ownerLock) {
            if (token.matches(currentOwner)) {
                domainHost.invalidateAuthoritativeRebaseline(token.scopeId, token.generation)
            }
        }
    }

    fun invalidateScopeCoverage(
        serverId: String,
        ownerToken: RealtimeIngressOwnerToken?,
        scopeId: SyncScopeId,
    ) {
        synchronized(ownerLock) {
            resolveOwnerLocked(serverId, ownerToken) ?: return
            domainHost.invalidateScopeCoverage(scopeId)
        }
    }

    internal fun invalidateScopeCoverage(serverId: String, memberId: String?, scopeId: SyncScopeId) {
        val ownerToken = memberId?.let { compatibilityOwnerToken(serverId, it) }
        invalidateScopeCoverage(serverId, ownerToken, scopeId)
    }

    fun invalidateAllCoverage(serverId: String, ownerToken: RealtimeIngressOwnerToken?) {
        synchronized(ownerLock) {
            resolveOwnerLocked(serverId, ownerToken) ?: return
            domainHost.clearAll()
        }
    }

    fun projectReadState(
        serverId: String,
        memberId: String,
        receiverKind: MessageReceiverKind,
        scopeId: SyncScopeId,
        expectedEpoch: SyncEpoch?,
        checkpoint: MessageReadStateCheckpoint,
    ): MessageReadStateProjection {
        return synchronized(ownerLock) {
            val owner = currentOwner
            if (owner == null || owner.serverId != serverId || owner.memberId != memberId) {
                return@synchronized MessageDomainStore.NoOp.projectReadState(
                    serverId,
                    memberId,
                    receiverKind,
                    scopeId,
                    checkpoint,
                )
            }
            val state = domainHost.state(scopeId)
            applyReadStateProjection(
                state = state,
                serverId = serverId,
                memberId = memberId,
                receiverKind = receiverKind,
                scopeId = scopeId,
                epoch = expectedEpoch,
                checkpoint = checkpoint,
            )
        }
    }

    private fun bindOwnerLocked(ownerToken: RealtimeIngressOwnerToken): MessageDomainOwner? {
        val current = currentOwner
        if (
            current != null &&
            current.serverId == ownerToken.serverId &&
            current.memberId == ownerToken.memberId &&
            current.generation == ownerToken.generation
        ) return current
        if (current != null && ownerToken.generation <= current.generation) return null
        return MessageDomainOwner(ownerToken.serverId, ownerToken.memberId, ownerToken.generation).also { next ->
            currentOwner = next
            domainHost.clearAll()
        }
    }

    private fun resolveOwnerLocked(
        serverId: String,
        ownerToken: RealtimeIngressOwnerToken?,
    ): MessageDomainOwner? {
        val token = ownerToken?.takeIf { it.serverId == serverId } ?: return null
        return bindOwnerLocked(token)
    }

    private fun compatibilityOwnerToken(serverId: String, memberId: String): RealtimeIngressOwnerToken =
        synchronized(ownerLock) {
            compatibilityOwnerToken
                ?.takeIf { it.serverId == serverId && it.memberId == memberId }
                ?: run {
                    nextOwnerGeneration = maxOf(nextOwnerGeneration, currentOwner?.generation ?: 0L) + 1L
                    RealtimeIngressOwnerToken(serverId, memberId, nextOwnerGeneration).also {
                        compatibilityOwnerToken = it
                    }
                }
        }

    private fun MessageScopeRebaselineToken.matches(owner: MessageDomainOwner?): Boolean =
        owner != null &&
            owner.serverId == serverId &&
            owner.memberId == memberId &&
            owner.generation == ownerGeneration

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
            now: () -> Long,
            scope: CoroutineScope,
            logger: (String) -> Unit,
        ): SyncCoreMessageShadowFoldLane =
            SyncCoreMessageShadowFoldLane(
                featureFlags = featureFlags,
                adapter = DefaultFoldPersistenceAdapter(
                    runner = SqlDelightFoldPersistTransactionRunner(sqlDatabase),
                    store = SqlDelightFoldPersistStore(sqlDatabase, now = now),
                ),
                scope = scope,
                logger = logger,
            )
    }
}
