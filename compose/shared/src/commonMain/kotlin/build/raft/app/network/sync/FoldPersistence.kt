package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.model.MessageId

/**
 * T-wrapper persistence contract (RFC 043 §7.6 / §9.9) — first increment: the ONE-TXN gate.
 *
 * The wrapper durably commits one applied fold frame. Its defining invariant (§9.9 transaction-count):
 * **one frame apply = exactly one storage transaction**, committing the message facts AND the scope
 * cursor + epoch AND the timeline-index maintenance together on one backend. When the commit count is 1,
 * no torn window exists by construction. Splitting any of them across transactions/backends reintroduces
 * the torn window and fails the count invariant — see [FoldPersistenceAdapter.applyFrame].
 *
 * This file is the contract the RED tests target; the SQLite-backed implementation lands next.
 */

/** Identifies one scope inside an explicit durable server/principal/consumer partition. */
data class SyncScopeKey(
    val partition: DurablePartitionKey,
    val scopeId: SyncScopeId,
    val scopeKind: String,
) {
    init {
        requireOpaqueDurableIdentity("scopeId", scopeId)
        requireOpaqueDurableIdentity("scopeKind", scopeKind)
    }

    val serverId: String
        get() = partition.serverId

    val principalId: String
        get() = partition.principalId

    val consumerId: String
        get() = partition.consumerId

    val environmentId: String
        get() = partition.environmentId
}

/** The durable per-scope cursor + epoch (mirror of `syncCursorEntity`) committed with the facts. */
data class PersistedCursor(
    val latestSeq: Long?,
    val oldestLoadedSeq: Long?,
    val newestLoadedSeq: Long?,
    val hasLoadedOldest: Boolean = false,
    val hasLoadedNewest: Boolean = true,
    val epoch: SyncEpoch? = null,
    val parentScopeKind: String? = null,
    val parentScopeId: String? = null,
    val rootMessageId: String? = null,
)

/**
 * One applied INGEST result: the exact rows that MUST commit atomically for [scope]. The unit of atomicity
 * is one ingest CALL, not one wire frame (赵 note-review must-fix) — a single realtime frame is [facts] of
 * size 1; a difference response is N [facts] + [cursor]; a snapshot rebaseline is [rebaseline]=true (clear
 * the scope) + the rebuilt [facts] + [cursor]/epoch. All three commit in exactly one transaction.
 */
data class FoldPersistFrame(
    val scope: SyncScopeKey,
    val facts: List<CanonicalMessageRow>,
    val cursor: PersistedCursor,
    /** Snapshot rebaseline: clear the scope's existing facts (then rebuild from [facts]) in the SAME txn. */
    val rebaseline: Boolean = false,
    /** Retention gate — empty in the one-txn increment; pruning joins the SAME transaction when present. */
    val prunedFactIds: List<MessageId> = emptyList(),
)

/** A frame fact whose payload channel does not match the declared durable scope. */
internal class FoldPersistScopeMismatch(
    scope: SyncScopeKey,
    fact: CanonicalMessageRow,
) : IllegalArgumentException(
    "canonical fact ${fact.id} channelId=${fact.channelId} does not match " +
        "scope channelId=${scope.scopeId} in " +
        "${scope.serverId}/${scope.principalId}/${scope.consumerId}/${scope.scopeKind}",
)

/** Validate payload-to-scope identity before opening a transaction or touching a direct store. */
internal fun requireFactsMatchScope(scope: SyncScopeKey, facts: List<CanonicalMessageRow>) {
    facts.firstOrNull { fact -> fact.channelId != scope.scopeId }
        ?.let { fact -> throw FoldPersistScopeMismatch(scope, fact) }
}

/** The durable post-state for a scope: the fact window plus the cursor + epoch. */
data class RestoredFoldState(
    val facts: List<CanonicalMessageRow>,
    val cursor: PersistedCursor?,
)

/**
 * Deterministic crash-injection boundary — NEVER wall-clock. The kill instant is the test's whole
 * semantics: [BeforeCommit] tears before the single commit, [AfterCommit] after it.
 */
enum class PersistFaultPoint { None, BeforeCommit, AfterCommit }

/** Host-injected fault hook. Default never kills. */
fun interface PersistFaultHook {
    fun shouldKillAt(point: PersistFaultPoint): Boolean

    companion object {
        val Never: PersistFaultHook = PersistFaultHook { false }
    }
}

/** Thrown to model process death at a fault point; restore must then yield a complete (never torn) state. */
class PersistKilled(val point: PersistFaultPoint) : RuntimeException("persist killed at $point")

/**
 * Backend transaction boundary. The adapter runs the entire frame apply inside exactly ONE [run] call —
 * that call is what the count invariant observes. The SQLite binding is a thin `transaction { }` wrapper.
 */
fun interface FoldPersistTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

/**
 * Row sinks/sources the adapter drives inside the single transaction. Kept backend-neutral so the wrapper
 * logic (one-txn orchestration) is testable without a real SQLite driver; the SQLite binding implements it.
 */
interface FoldPersistStore {
    suspend fun upsertFacts(scope: SyncScopeKey, facts: List<CanonicalMessageRow>)
    suspend fun writeCursor(scope: SyncScopeKey, cursor: PersistedCursor)
    suspend fun maintainTimelineIndex(scope: SyncScopeKey, facts: List<CanonicalMessageRow>)
    suspend fun prune(scope: SyncScopeKey, factIds: List<MessageId>)
    /** Snapshot rebaseline: drop all of [scope]'s facts + index rows, so the frame rebuilds from scratch. */
    suspend fun clearScope(scope: SyncScopeKey)
    suspend fun readFacts(scope: SyncScopeKey): List<CanonicalMessageRow>
    suspend fun readCursor(scope: SyncScopeKey): PersistedCursor?
}

/** The wrapper. [applyFrame] MUST perform exactly one [FoldPersistTransactionRunner.run]. */
interface FoldPersistenceAdapter {
    suspend fun applyFrame(frame: FoldPersistFrame)
    suspend fun restore(scope: SyncScopeKey): RestoredFoldState
}

/**
 * Default orchestration over a [FoldPersistTransactionRunner] + [FoldPersistStore]. The one-txn gate:
 * facts + cursor + epoch + timeline index (+ pruning) all inside a single [runner].run { } call, with the
 * fault hook consulted at the commit boundary. Implementation lands with the RED tests (currently pending).
 */
class DefaultFoldPersistenceAdapter(
    private val runner: FoldPersistTransactionRunner,
    private val store: FoldPersistStore,
) : FoldPersistenceAdapter {

    override suspend fun applyFrame(frame: FoldPersistFrame) {
        // Identity validation is deliberately outside runner.run: an invalid frame must fail before BEGIN,
        // so it cannot advance a cursor or produce any other storage side effect.
        requireFactsMatchScope(frame.scope, frame.facts)
        // The ONE-TXN gate: everything the frame changes commits in a single runner.run { }. Pruning,
        // fact upserts, timeline-index maintenance, and the cursor+epoch all live inside this one boundary,
        // so §9.9's transaction-count invariant holds (exactly one commit) and no torn window can exist.
        runner.run {
            if (frame.rebaseline) store.clearScope(frame.scope) // snapshot rebaseline: clear + rebuild, one txn
            if (frame.prunedFactIds.isNotEmpty()) store.prune(frame.scope, frame.prunedFactIds)
            store.upsertFacts(frame.scope, frame.facts)
            store.maintainTimelineIndex(frame.scope, frame.facts)
            store.writeCursor(frame.scope, frame.cursor)
        }
    }

    override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
        RestoredFoldState(facts = store.readFacts(scope), cursor = store.readCursor(scope))
}
