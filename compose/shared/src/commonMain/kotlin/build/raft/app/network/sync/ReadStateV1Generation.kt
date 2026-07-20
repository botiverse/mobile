package build.raft.app.network.sync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

class ReadStateV1GrantWitness internal constructor(
    val scope: ReadStateV1Scope,
) {
    internal val publicationFence = ReadStateV1PublicationFence()
}

/**
 * Exact-witness destination barrier for a final projection write or rebaseline emission.
 *
 * The callback is the linearized destination operation. It must not call back into grant
 * issue/revoke or this fence: replacement/revoke waits for a callback that already entered,
 * while a callback arriving after replacement/revoke observes the closed fence and does nothing.
 */
internal class ReadStateV1PublicationFence {
    private val lock = SynchronizedObject()
    private var active = true

    fun publishIfActive(block: () -> Unit): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        block()
        true
    }

    fun revoke() = synchronized(lock) {
        active = false
    }
}

class ReadStateV1GrantAuthority {
    private val mutationLock = SynchronizedObject()
    private val currentLock = SynchronizedObject()
    private val current = mutableMapOf<ReadStateV1Scope, ReadStateV1GrantWitness>()

    fun issue(scope: ReadStateV1Scope): ReadStateV1GrantWitness = synchronized(mutationLock) {
        val previous = synchronized(currentLock) { current[scope] }
        previous?.publicationFence?.revoke()
        ReadStateV1GrantWitness(scope).also { witness ->
            synchronized(currentLock) { current[scope] = witness }
        }
    }

    fun revoke(witness: ReadStateV1GrantWitness) = synchronized(mutationLock) {
        val matches = synchronized(currentLock) { current[witness.scope] === witness }
        if (!matches) return@synchronized Unit
        witness.publicationFence.revoke()
        synchronized(currentLock) {
            if (current[witness.scope] === witness) current.remove(witness.scope)
        }
        Unit
    }

    fun isCurrent(witness: ReadStateV1GrantWitness): Boolean = synchronized(currentLock) {
        current[witness.scope] === witness
    }
}

fun interface ReadStateV1ProjectionObserver {
    fun onCommitted(witness: ReadStateV1GrantWitness, fact: ReadStateV1Fact)
}

fun interface ReadStateV1RebaselineRequester {
    fun request(witness: ReadStateV1GrantWitness)
}

sealed interface ReadStateV1GenerationResult {
    data object Committed : ReadStateV1GenerationResult
    data object Current : ReadStateV1GenerationResult
    data object Stale : ReadStateV1GenerationResult
    data object Conflict : ReadStateV1GenerationResult
    data object RejectedCandidate : ReadStateV1GenerationResult
    data object RejectedCurrentGrant : ReadStateV1GenerationResult
    data class BaselineRequired(val requested: Boolean) : ReadStateV1GenerationResult
}

class ReadStateV1GenerationEngine(
    private val authority: ReadStateV1GrantAuthority,
    private val store: ReadStateV1Store,
    private val projectionObserver: ReadStateV1ProjectionObserver,
    private val rebaselineRequester: ReadStateV1RebaselineRequester,
) {
    private data class BaselineState(val witness: ReadStateV1GrantWitness, val requestInFlight: Boolean)
    private val lock = SynchronizedObject()
    private val baselines = mutableMapOf<ReadStateV1Scope, BaselineState>()

    suspend fun applySnapshot(
        witness: ReadStateV1GrantWitness,
        candidate: ReadStateV1WireCandidate,
    ): ReadStateV1GenerationResult {
        if (!authority.isCurrent(witness)) return ReadStateV1GenerationResult.RejectedCurrentGrant
        if (candidate.source != ReadStateV1FactSource.Hydrate) return ReadStateV1GenerationResult.RejectedCandidate
        val fact = acceptedFact(witness, candidate) ?: return ReadStateV1GenerationResult.RejectedCandidate
        val committed = store.commit(
            witness.scope,
            fact,
            ReadStateV1CommitMode.Snapshot,
            ReadStateV1CommitGuard { authority.isCurrent(witness) },
        )
        if (committed != ReadStateV1StoreResult.Accepted) {
            return committed.toGenerationResult()
        }
        if (!witness.publicationFence.publishIfActive {
                synchronized(lock) { baselines[witness.scope] = BaselineState(witness, requestInFlight = false) }
                projectionObserver.onCommitted(witness, fact)
            }
        ) return ReadStateV1GenerationResult.RejectedCurrentGrant
        return ReadStateV1GenerationResult.Committed
    }

    suspend fun applyIncremental(
        witness: ReadStateV1GrantWitness,
        candidate: ReadStateV1WireCandidate,
    ): ReadStateV1GenerationResult {
        if (!authority.isCurrent(witness)) return ReadStateV1GenerationResult.RejectedCurrentGrant
        if (candidate.source !in setOf(ReadStateV1FactSource.MutationAck, ReadStateV1FactSource.OwnerRealtime)) {
            return ReadStateV1GenerationResult.RejectedCandidate
        }
        val fact = acceptedFact(witness, candidate) ?: return ReadStateV1GenerationResult.RejectedCandidate
        val baselined = synchronized(lock) {
            val state = baselines[witness.scope]
            state?.witness === witness && !state.requestInFlight
        }
        if (!baselined) return requestBaseline(witness)
        val committed = store.commit(
            witness.scope,
            fact,
            ReadStateV1CommitMode.Incremental,
            ReadStateV1CommitGuard { authority.isCurrent(witness) },
        )
        when (committed) {
            ReadStateV1StoreResult.Accepted -> if (!witness.publicationFence.publishIfActive {
                    projectionObserver.onCommitted(witness, fact)
                }
            ) {
                return ReadStateV1GenerationResult.RejectedCurrentGrant
            }
            ReadStateV1StoreResult.Conflict -> if (!invalidateAndRequest(witness)) {
                return ReadStateV1GenerationResult.RejectedCurrentGrant
            }
            ReadStateV1StoreResult.RejectedCurrentGrant -> synchronized(lock) { baselines.remove(witness.scope) }
            else -> Unit
        }
        return committed.toGenerationResult()
    }

    suspend fun restore(witness: ReadStateV1GrantWitness): ReadStateV1GenerationResult {
        if (!authority.isCurrent(witness)) return ReadStateV1GenerationResult.RejectedCurrentGrant
        val fact = try {
            store.restore(witness.scope)
        } catch (_: ReadStateV1StoredFactCorrupt) {
            return requestBaseline(witness)
        } ?: return requestBaseline(witness)
        if (!witness.publicationFence.publishIfActive {
                synchronized(lock) { baselines[witness.scope] = BaselineState(witness, requestInFlight = false) }
                projectionObserver.onCommitted(witness, fact)
            }
        ) return ReadStateV1GenerationResult.RejectedCurrentGrant
        return ReadStateV1GenerationResult.Committed
    }

    fun revoke(witness: ReadStateV1GrantWitness) {
        authority.revoke(witness)
        synchronized(lock) {
            if (baselines[witness.scope]?.witness === witness) baselines.remove(witness.scope)
        }
    }

    private fun acceptedFact(
        witness: ReadStateV1GrantWitness,
        candidate: ReadStateV1WireCandidate,
    ): ReadStateV1Fact? {
        if (!authority.isCurrent(witness)) return null
        return (normalizeReadStateV1Candidate(witness.scope, candidate) as? ReadStateV1CandidateResult.Accepted)?.fact
    }

    private fun invalidateAndRequest(witness: ReadStateV1GrantWitness): Boolean =
        emitRebaselineRequest(witness)

    private fun requestBaseline(witness: ReadStateV1GrantWitness): ReadStateV1GenerationResult {
        var requested = false
        val published = witness.publicationFence.publishIfActive {
            requested = synchronized(lock) {
                val old = baselines[witness.scope]
                baselines[witness.scope] = BaselineState(witness, requestInFlight = true)
                old?.witness !== witness || !old.requestInFlight
            }
            if (requested) rebaselineRequester.request(witness)
        }
        if (!published) return ReadStateV1GenerationResult.RejectedCurrentGrant
        return ReadStateV1GenerationResult.BaselineRequired(requested)
    }

    private fun emitRebaselineRequest(witness: ReadStateV1GrantWitness): Boolean =
        witness.publicationFence.publishIfActive {
            val requested = synchronized(lock) {
                val old = baselines[witness.scope]
                if (old?.witness === witness && old.requestInFlight) return@synchronized false
                baselines[witness.scope] = BaselineState(witness, requestInFlight = true)
                true
            }
            if (requested) rebaselineRequester.request(witness)
        }
}

private fun ReadStateV1StoreResult.toGenerationResult(): ReadStateV1GenerationResult =
    when (this) {
        ReadStateV1StoreResult.Accepted -> ReadStateV1GenerationResult.Committed
        ReadStateV1StoreResult.Current -> ReadStateV1GenerationResult.Current
        ReadStateV1StoreResult.Stale -> ReadStateV1GenerationResult.Stale
        ReadStateV1StoreResult.Conflict -> ReadStateV1GenerationResult.Conflict
        ReadStateV1StoreResult.RejectedCurrentGrant -> ReadStateV1GenerationResult.RejectedCurrentGrant
    }
