package build.raft.app.network.sync

import build.raft.app.data.SlockSqlDatabase
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

sealed interface CanonicalMessageV2IncrementalDecision {
    data object Apply : CanonicalMessageV2IncrementalDecision
    data class Hold(
        val diagnostic: String = CANONICAL_MESSAGE_V2_BASELINE_REQUIRED,
        val requestRebaseline: Boolean,
    ) : CanonicalMessageV2IncrementalDecision
}

/** Per-scope V2 generation gate. V1 state is neither consulted nor accepted as a baseline. */
class CanonicalMessageV2BaselineCoordinator {
    private data class ScopeState(
        val baselined: Boolean = false,
        val rebaselineInFlight: Boolean = false,
    )

    private val states = mutableMapOf<SyncScopeKey, ScopeState>()
    private val lock = SynchronizedObject()

    fun onIncremental(scope: SyncScopeKey): CanonicalMessageV2IncrementalDecision = synchronized(lock) {
        requireV2Scope(scope)
        val state = states[scope] ?: ScopeState()
        if (state.baselined) return@synchronized CanonicalMessageV2IncrementalDecision.Apply
        states[scope] = state.copy(rebaselineInFlight = true)
        CanonicalMessageV2IncrementalDecision.Hold(requestRebaseline = !state.rebaselineInFlight)
    }

    fun onSnapshotCommitted(scope: SyncScopeKey): Unit = synchronized(lock) {
        requireV2Scope(scope)
        states[scope] = ScopeState(baselined = true, rebaselineInFlight = false)
        Unit
    }

    fun onRebaselineFailed(scope: SyncScopeKey): Unit = synchronized(lock) {
        requireV2Scope(scope)
        states[scope] = ScopeState(baselined = false, rebaselineInFlight = false)
        Unit
    }

    fun invalidate(scope: SyncScopeKey): Unit = synchronized(lock) {
        requireV2Scope(scope)
        states.remove(scope)
        Unit
    }

    fun isBaselined(scope: SyncScopeKey): Boolean = synchronized(lock) {
        states[scope]?.baselined == true
    }
}

data class CanonicalMessageV2SnapshotFrame(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor,
)

data class CanonicalMessageV2IncrementalFrame(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor,
)

data class RestoredCanonicalMessageV2Scope(
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor?,
)

interface CanonicalMessageV2Store {
    suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame)
    suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame)
    suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope
}

fun interface CanonicalMessageV2SnapshotCommitObserver {
    fun onSnapshotCommitted(scope: SyncScopeKey)
}

sealed interface CanonicalMessageV2CommitResult {
    data object Committed : CanonicalMessageV2CommitResult
    data class BaselineRequired(val requestRebaseline: Boolean) : CanonicalMessageV2CommitResult
}

/** Integrates the baseline gate with the disjoint V2 store while remaining unbound to production routing. */
class CanonicalMessageV2GenerationEngine(
    private val coordinator: CanonicalMessageV2BaselineCoordinator,
    private val store: CanonicalMessageV2Store,
    private val snapshotCommitObserver: CanonicalMessageV2SnapshotCommitObserver,
) {
    suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2CommitResult {
        store.applySnapshot(frame)
        coordinator.onSnapshotCommitted(frame.scope)
        snapshotCommitObserver.onSnapshotCommitted(frame.scope)
        return CanonicalMessageV2CommitResult.Committed
    }

    suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2CommitResult =
        when (val decision = coordinator.onIncremental(frame.scope)) {
            CanonicalMessageV2IncrementalDecision.Apply -> {
                store.applyIncremental(frame)
                CanonicalMessageV2CommitResult.Committed
            }
            is CanonicalMessageV2IncrementalDecision.Hold ->
                CanonicalMessageV2CommitResult.BaselineRequired(decision.requestRebaseline)
        }

    suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
        store.restore(scope).also { restored ->
            if (restored.cursor != null) {
                coordinator.onSnapshotCommitted(scope)
                snapshotCommitObserver.onSnapshotCommitted(scope)
            }
        }
}

class CanonicalMessageV2ScopePoisoned(
    val scope: SyncScopeKey,
    val factId: String? = null,
) : RuntimeException("$CANONICAL_MESSAGE_V2_SCOPE_POISONED at $scope fact=${factId.orEmpty()}")

/** Existing message/cursor tables, but a disjoint V2 consumer partition and a strict full-v6 row codec. */
internal class SqlDelightCanonicalMessageV2Store(
    private val database: SlockSqlDatabase,
    private val now: () -> Long,
    private val faultHook: PersistFaultHook = PersistFaultHook.Never,
) : CanonicalMessageV2Store {
    override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame) =
        applyFrame(frame.scope, frame.envelopes, frame.cursor, rebaseline = true)

    override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame) =
        applyFrame(frame.scope, frame.envelopes, frame.cursor, rebaseline = false)

    private suspend fun applyFrame(
        scope: SyncScopeKey,
        envelopes: List<CanonicalMessageEnvelopeV2>,
        cursor: PersistedCursor,
        rebaseline: Boolean,
    ) {
        requireV2Scope(scope)
        require(envelopes.map { it.fact.id }.distinct().size == envelopes.size) { "duplicate V2 fact id in frame" }
        envelopes.forEach { envelope -> requireEnvelopeMatchesScope(scope, envelope) }
        database.transaction {
            if (rebaseline) {
                queries.clearCanonicalFactsForScope(
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
            }
            envelopes.forEach { envelope ->
                val bytes = encodeCanonicalMessageV2(envelope, scope.serverId)
                queries.insertCanonicalFactIfAbsent(
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    id = envelope.fact.id,
                    channelId = scope.scopeId,
                    scopeKind = scope.scopeKind,
                    seq = envelope.fact.seq,
                    createdAt = envelope.fact.createdAt,
                    canonicalFactJson = bytes,
                )
                queries.updateCanonicalFact(
                    seq = envelope.fact.seq,
                    createdAt = envelope.fact.createdAt,
                    canonicalFactJson = bytes,
                    id = envelope.fact.id,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
                if (queries.countCanonicalFactInScope(
                        id = envelope.fact.id,
                        serverId = scope.serverId,
                        principalId = scope.principalId,
                        consumerId = scope.consumerId,
                        scopeKind = scope.scopeKind,
                        channelId = scope.scopeId,
                    ).executeAsOne() != 1L
                ) throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
            }
            queries.upsertSyncCursor(
                scopeId = scope.scopeId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                latestSeq = cursor.latestSeq,
                oldestLoadedSeq = cursor.oldestLoadedSeq,
                newestLoadedSeq = cursor.newestLoadedSeq,
                hasLoadedOldest = if (cursor.hasLoadedOldest) 1L else 0L,
                hasLoadedNewest = if (cursor.hasLoadedNewest) 1L else 0L,
                updatedAtMs = now(),
                epoch = cursor.epoch,
            )
            if (faultHook.shouldKillAt(PersistFaultPoint.BeforeCommit)) {
                throw PersistKilled(PersistFaultPoint.BeforeCommit)
            }
        }
        if (faultHook.shouldKillAt(PersistFaultPoint.AfterCommit)) {
            throw PersistKilled(PersistFaultPoint.AfterCommit)
        }
    }

    override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope {
        requireV2Scope(scope)
        val envelopes = database.queries.selectServerBackedMessagesForChannel(
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsList().map { row ->
            val accepted = decodeCanonicalMessageV2(row.canonicalFactJson, scope.serverId)
                as? CanonicalMessageV2DecodeResult.Accepted
                ?: throw CanonicalMessageV2ScopePoisoned(scope, row.id)
            val envelope = accepted.envelope
            if (
                row.id != envelope.fact.id ||
                row.seq != envelope.fact.seq ||
                row.createdAt != envelope.fact.createdAt ||
                row.channelId != scope.scopeId ||
                row.scopeKind != scope.scopeKind
            ) throw CanonicalMessageV2ScopePoisoned(scope, row.id)
            requireEnvelopeMatchesScope(scope, envelope)
            envelope
        }
        val cursor = database.queries.selectSyncCursor(
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
        ).executeAsOneOrNull()?.let {
            PersistedCursor(
                latestSeq = it.latestSeq,
                oldestLoadedSeq = it.oldestLoadedSeq,
                newestLoadedSeq = it.newestLoadedSeq,
                hasLoadedOldest = it.hasLoadedOldest != 0L,
                hasLoadedNewest = it.hasLoadedNewest != 0L,
                epoch = it.epoch,
            )
        }
        if (envelopes.isNotEmpty() && cursor == null) throw CanonicalMessageV2ScopePoisoned(scope)
        return RestoredCanonicalMessageV2Scope(envelopes = envelopes, cursor = cursor)
    }
}

sealed interface AttachmentCommentsCacheLookupV2 {
    data object Unknown : AttachmentCommentsCacheLookupV2
    data class Loaded(val commentIds: List<String>) : AttachmentCommentsCacheLookupV2
}

/** Receiver-private typed read cache. A room-stripped null is represented by doing nothing. */
class AttachmentCommentsReadCacheV2 {
    private data class Entry(val parentScope: SyncScopeKey, val commentIds: List<String>)
    private val entries = mutableMapOf<AttachmentCommentsReadKeyV2, Entry>()

    fun put(key: AttachmentCommentsReadKeyV2, parentScope: SyncScopeKey, commentIds: List<String>) {
        require(key.partition == parentScope.partition)
        entries[key] = Entry(parentScope, commentIds.toList())
    }

    fun roomStrippedNull(key: AttachmentCommentsReadKeyV2) = Unit

    fun get(key: AttachmentCommentsReadKeyV2): AttachmentCommentsCacheLookupV2 =
        entries[key]?.let { AttachmentCommentsCacheLookupV2.Loaded(it.commentIds) }
            ?: AttachmentCommentsCacheLookupV2.Unknown

    fun invalidateParent(parentScope: SyncScopeKey) {
        entries.entries.removeAll { it.value.parentScope == parentScope }
    }
}

internal fun requireV2Scope(scope: SyncScopeKey) {
    require(scope.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) {
        "V2 must not read or write the V1 durable partition"
    }
}

private fun requireEnvelopeMatchesScope(scope: SyncScopeKey, envelope: CanonicalMessageEnvelopeV2) {
    val diagnostic = validateCanonicalMessageV2(envelope, scope.serverId)
    if (diagnostic != null) throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
    val matches = when (val context = envelope.context) {
        is CanonicalConversationContextV2.TopLevel ->
            context.scope.scopeId == scope.scopeId && context.scope.scopeKind == scope.scopeKind
        is CanonicalConversationContextV2.Thread ->
            context.replyScope.scopeId == scope.scopeId && scope.scopeKind == "thread"
    }
    if (!matches) throw CanonicalMessageV2ScopePoisoned(scope, envelope.fact.id)
}

private val TopLevelScopeRefV2.scopeKind: String
    get() = when (this) {
        is TopLevelScopeRefV2.Channel -> "channel"
        is TopLevelScopeRefV2.Private -> "private"
        is TopLevelScopeRefV2.DirectMessage -> "dm"
        is TopLevelScopeRefV2.Joint -> "joint"
    }
