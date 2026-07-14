package build.raft.app.network.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owner-generation ↔ transaction-commit binding (the #875-root-4 correction).
 *
 * The disease this kills: an applier checks the owner generation, the check passes, the owner LOCK is
 * released, and only then do the SQLite writes + COMMIT run — so a rotation can land between the passed
 * check and the commit, and a stale owner's writes still become durable (A check=true → B rotate →
 * A commit). A check that does not stay valid THROUGH the commit is not a check.
 *
 * The correction: the generation check and the storage transaction are one critical section. [withCurrentGeneration]
 * holds the gate across the ENTIRE body — the body is the single `transaction { }` (BEGIN → writes → COMMIT) —
 * and [rotate] takes the same lock. Rotation therefore serializes strictly before the check (the check fails
 * closed, zero statements reach the database) or strictly after the COMMIT (the writes were made by the
 * still-current owner). The forbidden interleave is unrepresentable, not merely detected.
 *
 * Authority: there is exactly ONE gate per database, owned by the central owner authority (the connect/auth
 * lifecycle). Only connect/auth establishment calls [rotate]; logout/reset burns via the same monotonic bump
 * (a burn is a rotation that no live consumer was granted). Store layers must NOT clone their own gate —
 * a second authority is the twin-truth disease this replaces.
 *
 * Reentrancy: the body of [withCurrentGeneration] must never call [rotate], [currentGeneration], or nest
 * another [withCurrentGeneration] on the same gate — the gate is non-reentrant by design (a commit that
 * rotates its own authority mid-flight is a contradiction, so it deadlocks rather than passes).
 */
class OwnerCommitGate(initialGeneration: Long = FIRST_GENERATION) {

    private val mutex = Mutex()
    private var generation = initialGeneration

    /** The current generation, for GRANTING only (connect/auth hands it to a consumer). An applier must
     *  present the generation it was granted — re-reading here just before applying would recreate the
     *  check-then-act window this gate exists to close. */
    suspend fun currentGeneration(): Long = mutex.withLock { generation }

    /**
     * Strictly-monotonic rotation (connect/auth establish) / burn (logout/reset). Suspends until no owned
     * commit holds the gate, so a rotation can never land inside another owner's check→COMMIT window.
     */
    suspend fun rotate(): Long = mutex.withLock { ++generation }

    /**
     * Runs [body] iff [expected] is still the current generation, holding the gate for the body's whole
     * duration. Stale generation → [StaleOwnerGeneration] BEFORE the body runs: fail-closed, zero writes,
     * no transaction is ever opened.
     */
    suspend fun <T> withCurrentGeneration(expected: Long, body: suspend () -> T): T =
        mutex.withLock {
            if (generation != expected) throw StaleOwnerGeneration(expected = expected, current = generation)
            body()
        }

    companion object {
        const val FIRST_GENERATION = 1L
    }
}

/** A commit was attempted under a generation that is no longer current. The apply wrote nothing. */
class StaleOwnerGeneration(val expected: Long, val current: Long) :
    RuntimeException("stale owner generation: applier holds $expected but current is $current — zero-write reject")

/**
 * The persistence-side binding: one applied frame = one [OwnerCommitGate.withCurrentGeneration] holding one
 * storage transaction. The generation is a REQUIRED argument — there is no ungated overload and no nullable
 * bypass (an optional owner is how unowned paths keep mutating, #875 root 1).
 */
class OwnerBoundFoldPersistenceAdapter(
    private val gate: OwnerCommitGate,
    private val inner: FoldPersistenceAdapter,
) {
    /** Durably commits [frame] iff [expectedOwnerGeneration] is still current at COMMIT time (held, not
     *  just checked). Stale → [StaleOwnerGeneration], zero statements reach the database. */
    suspend fun applyFrame(frame: FoldPersistFrame, expectedOwnerGeneration: Long) {
        gate.withCurrentGeneration(expectedOwnerGeneration) { inner.applyFrame(frame) }
    }

    /** Restore is a read of the last committed state; the write gate above is what keeps it un-torn. */
    suspend fun restore(scope: SyncScopeKey): RestoredFoldState = inner.restore(scope)
}
