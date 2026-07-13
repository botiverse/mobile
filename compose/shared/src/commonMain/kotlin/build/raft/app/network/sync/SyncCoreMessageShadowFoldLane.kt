package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SlockSqlDatabase
import kotlinx.coroutines.CoroutineScope
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
    private val adapter: FoldPersistenceAdapter,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
    // One active message-domain core per owner/shell. The host also owns authoritative rebaseline swaps.
    private val domainHost: MessageDomainSyncHost = MessageDomainSyncHost(),
) {

    fun isEnabled(): Boolean =
        featureFlags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)

    /**
     * Fold one canonical message frame into the shadow core, then persist that channel scope's resulting fact
     * window off the caller thread. [updated] selects [MessageDomainEvent.Updated] vs [MessageDomainEvent.New].
     * [epoch] is the caller's per-scope recovery epoch (consistent with the legacy cursor). FAIL-OPEN: the
     * whole body is guarded; the async persist uses runCatching; nothing propagates to the caller.
     */
    fun foldAndPersist(
        resolvedServerId: String,
        decoded: CanonicalMessageCarry,
        updated: Boolean,
        epoch: SyncEpoch?,
    ) {
        try {
            val row = decoded.row
            val channelId = row.channelId
            val domainEvent: MessageDomainEvent =
                if (updated) MessageDomainEvent.Updated(row, decoded.presentAggregates)
                else MessageDomainEvent.New(row, decoded.presentAggregates)
            domainHost.ingestFrame(
                SyncFrame(scopeId = channelId, seq = row.seq, epoch = epoch, event = domainEvent),
            )
            val facts = domainHost.state(channelId)
                .channelMessages
                .get(channelId)
                .orEmpty()
            val frame = FoldPersistFrame(
                scope = SyncScopeKey(resolvedServerId, channelId, SHADOW_SCOPE_KIND),
                facts = facts,
                cursor = PersistedCursor(
                    latestSeq = row.seq,
                    oldestLoadedSeq = facts.firstOrNull()?.seq,
                    newestLoadedSeq = facts.lastOrNull()?.seq ?: row.seq,
                    hasLoadedOldest = false,
                    hasLoadedNewest = true,
                    epoch = epoch,
                ),
                rebaseline = false,
                prunedFactIds = emptyList(),
            )
            scope.launch {
                runCatching { adapter.applyFrame(frame) }
                    .onFailure { logShadowFailure(resolvedServerId, it) }
            }
        } catch (throwable: Throwable) {
            logShadowFailure(resolvedServerId, throwable)
        }
    }

    /** Authoritative conversation hydrate/rebaseline seam; caller serializes it with frame ingress. */
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
