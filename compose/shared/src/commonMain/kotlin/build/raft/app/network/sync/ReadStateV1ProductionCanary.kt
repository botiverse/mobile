package build.raft.app.network.sync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException

data class ReadStateV1ProductionOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
)

sealed interface ReadStateV1ProductionDisposition {
    data object Legacy : ReadStateV1ProductionDisposition
    data class Selected(
        val result: ReadStateV1GenerationResult,
        val projectedFact: ReadStateV1Fact? = null,
        val requiresProjectionConfirmation: Boolean = false,
    ) : ReadStateV1ProductionDisposition
    data object SelectedFailure : ReadStateV1ProductionDisposition
}

class ReadStateV1ProductionCanary(
    private val isEnabledForServer: (String) -> Boolean,
    store: ReadStateV1Store,
    private val onProjection: (ReadStateV1ProductionOwner, ReadStateV1Fact) -> Unit,
    private val onRebaseline: (ReadStateV1ProductionOwner, String) -> Unit,
    private val logger: (String) -> Unit = {},
) {
    private data class Selection(
        val owner: ReadStateV1ProductionOwner,
        val channelId: String,
        val witness: ReadStateV1GrantWitness,
        var projectedFact: ReadStateV1Fact? = null,
        var offBarrierRequested: Boolean = false,
        var requiresSnapshotBarrier: Boolean = false,
        var releaseAfterSnapshotBarrier: Boolean = false,
        var pendingPreparedFact: ReadStateV1Fact? = null,
    )

    private data class SelectionDecision(val selection: Selection, val newlySelected: Boolean)

    private val lock = SynchronizedObject()
    private val authority = ReadStateV1GrantAuthority()
    private var selection: Selection? = null
    private val engine =
        ReadStateV1GenerationEngine(
            authority = authority,
            store = store,
            projectionObserver = ReadStateV1ProjectionObserver { scope, fact ->
                synchronized(lock) {
                    selection?.takeIf { it.witness.scope == scope }?.also { it.projectedFact = fact }
                }?.let { selected -> onProjection(selected.owner, fact) }
            },
            rebaselineRequester = ReadStateV1RebaselineRequester { witness ->
                synchronized(lock) {
                    selection?.takeIf { it.witness === witness }
                }?.let { selected -> onRebaseline(selected.owner, selected.channelId) }
            },
        )

    suspend fun applyHydrate(
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        readStateVersion: Long?,
        maxReadSeq: Long?,
    ): ReadStateV1ProductionDisposition =
        apply(owner, channelId, ReadStateV1FactSource.Hydrate, readStateVersion, maxReadSeq)

    suspend fun prepareHydrate(
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        readStateVersion: Long?,
        maxReadSeq: Long?,
    ): ReadStateV1ProductionDisposition =
        apply(
            owner,
            channelId,
            ReadStateV1FactSource.Hydrate,
            readStateVersion,
            maxReadSeq,
            deferProjection = true,
        )

    suspend fun applyMutationAck(
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        readStateVersion: Long?,
        maxReadSeq: Long?,
    ): ReadStateV1ProductionDisposition =
        apply(owner, channelId, ReadStateV1FactSource.MutationAck, readStateVersion, maxReadSeq)

    suspend fun applyOwnerRealtime(
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        readStateVersion: Long?,
        maxReadSeq: Long?,
    ): ReadStateV1ProductionDisposition =
        apply(owner, channelId, ReadStateV1FactSource.OwnerRealtime, readStateVersion, maxReadSeq)

    fun revoke(owner: ReadStateV1ProductionOwner) {
        val revoked = synchronized(lock) {
            selection?.takeIf { it.owner == owner }?.also {
                it.requiresSnapshotBarrier = true
                it.releaseAfterSnapshotBarrier = true
            }
        } ?: return
        val barrier =
            Selection(
                owner = revoked.owner,
                channelId = revoked.channelId,
                witness = authority.issue(revoked.witness.scope),
                projectedFact = revoked.projectedFact,
                requiresSnapshotBarrier = true,
                releaseAfterSnapshotBarrier = true,
            )
        val installed = synchronized(lock) {
            if (selection === revoked) {
                selection = barrier
                true
            } else {
                false
            }
        }
        engine.revoke(revoked.witness)
        if (!installed) {
            engine.revoke(barrier.witness)
            return
        }
        requestOffBarrier(barrier)
        logger("revoke serverId=${owner.serverId} channelId=${revoked.channelId}")
    }

    fun isSelected(owner: ReadStateV1ProductionOwner, channelId: String): Boolean =
        synchronized(lock) { selection?.let { it.owner == owner && it.channelId == channelId } == true }

    fun completePreparedHydrateProjection(
        owner: ReadStateV1ProductionOwner,
        fact: ReadStateV1Fact,
    ): Boolean {
        val selected = synchronized(lock) {
            selection?.takeIf {
                it.owner == owner && it.channelId == fact.channelId && it.pendingPreparedFact == fact
            }
        } ?: return false
        return try {
            val published = authority.publishIfCurrent(selected.witness) {
                onProjection(owner, fact)
                synchronized(lock) {
                    if (selection === selected && selected.pendingPreparedFact == fact) {
                        selected.projectedFact = fact
                        selected.pendingPreparedFact = null
                        selected.requiresSnapshotBarrier = false
                        selected.offBarrierRequested = false
                    }
                }
            }
            if (!published) return false
            releaseAfterCompletedBarrier(selected)
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger(
                "projection_failed source=hydrate serverId=${owner.serverId} channelId=${fact.channelId} " +
                    "reason=${error::class.simpleName ?: "unknown"}"
            )
            false
        }
    }

    fun clear() {
        val revoked = synchronized(lock) { selection.also { selection = null } } ?: return
        engine.revoke(revoked.witness)
        logger("clear serverId=${revoked.owner.serverId} channelId=${revoked.channelId}")
    }

    private suspend fun apply(
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        source: ReadStateV1FactSource,
        readStateVersion: Long?,
        maxReadSeq: Long?,
        deferProjection: Boolean = false,
    ): ReadStateV1ProductionDisposition {
        val decision =
            select(owner, channelId, readStateVersion, maxReadSeq)
                ?: return ReadStateV1ProductionDisposition.Legacy
        val selected = decision.selection
        if (selected.owner != owner || selected.channelId != channelId) {
            return ReadStateV1ProductionDisposition.Legacy
        }
        val enabled = isEnabledForServer(owner.serverId)
        val projectionOrSnapshotBarrier = synchronized(lock) {
            selection === selected && (selected.requiresSnapshotBarrier || selected.pendingPreparedFact != null)
        }
        if ((!enabled || projectionOrSnapshotBarrier) && source != ReadStateV1FactSource.Hydrate) {
            synchronized(lock) { if (selection === selected) selected.requiresSnapshotBarrier = true }
            return ReadStateV1ProductionDisposition.Selected(
                requestOffBarrier(selected),
                selected.projectedFact,
            )
        }
        val candidate =
            ReadStateV1WireCandidate(
                channelId = channelId,
                readStateVersion = readStateVersion,
                maxReadSeq = maxReadSeq,
                source = source,
            )
        return try {
            if (decision.newlySelected && source != ReadStateV1FactSource.Hydrate) {
                val restored = engine.restore(selected.witness)
                if (restored != ReadStateV1GenerationResult.Committed) {
                    return ReadStateV1ProductionDisposition.Selected(restored, selected.projectedFact)
                }
            }
            val result =
                if (source == ReadStateV1FactSource.Hydrate) {
                    engine.applySnapshot(selected.witness, candidate, notifyProjection = !deferProjection)
                } else {
                    engine.applyIncremental(selected.witness, candidate)
                }
            logger(
                "apply source=${source.name.lowercase()} serverId=${owner.serverId} channelId=$channelId " +
                    "result=${result::class.simpleName.orEmpty()}"
            )
            val preparedFact =
                if (deferProjection && result in setOf(ReadStateV1GenerationResult.Committed, ReadStateV1GenerationResult.Current)) {
                    ReadStateV1Fact(channelId, requireNotNull(readStateVersion), requireNotNull(maxReadSeq))
                } else {
                    null
                }
            if (preparedFact != null) {
                synchronized(lock) {
                    if (selection === selected) selected.pendingPreparedFact = preparedFact
                }
            }
            val disposition =
                ReadStateV1ProductionDisposition.Selected(
                    result = result,
                    projectedFact = preparedFact ?: selected.projectedFact,
                    requiresProjectionConfirmation = preparedFact != null,
                )
            if (preparedFact != null) return disposition
            if (result in setOf(ReadStateV1GenerationResult.Committed, ReadStateV1GenerationResult.Current)) {
                synchronized(lock) {
                    if (selection === selected) {
                        selected.requiresSnapshotBarrier = false
                        selected.offBarrierRequested = false
                    }
                }
            }
            if (!releaseAfterCompletedBarrier(selected) && !enabled) {
                synchronized(lock) { if (selection === selected) selected.requiresSnapshotBarrier = true }
                requestOffBarrier(selected)
            }
            disposition
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger(
                "apply_failed source=${source.name.lowercase()} serverId=${owner.serverId} channelId=$channelId " +
                    "reason=${error::class.simpleName ?: "unknown"}"
            )
            ReadStateV1ProductionDisposition.SelectedFailure
        }
    }

    private fun select(
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        readStateVersion: Long?,
        maxReadSeq: Long?,
    ): SelectionDecision? {
        if (!isEligible(owner, channelId)) return null
        return synchronized(lock) {
            selection?.let { SelectionDecision(it, newlySelected = false) }
                ?: if (!isEnabledForServer(owner.serverId) ||
                    readStateVersion == null || readStateVersion <= 0L || maxReadSeq == null || maxReadSeq < 0L
                ) {
                    null
                } else ReadStateV1Scope(
                    serverId = owner.serverId,
                    principalId = durablePrincipalId(owner),
                    channelId = channelId,
                ).let { scope ->
                    Selection(owner, channelId, authority.issue(scope))
                        .also { selection = it }
                        .let { SelectionDecision(it, newlySelected = true) }
                }
        }
    }

    private fun isEligible(owner: ReadStateV1ProductionOwner, channelId: String): Boolean =
        owner.environmentId.isNotBlank() && owner.accountId.isNotBlank() &&
            owner.serverId.isNotBlank() && channelId.isNotBlank()

    private fun durablePrincipalId(owner: ReadStateV1ProductionOwner): String =
        "${owner.environmentId.trim()}\u001f${owner.accountId.trim()}"

    private fun requestOffBarrier(selected: Selection): ReadStateV1GenerationResult {
        var requested = false
        val current = authority.publishIfCurrent(selected.witness) {
            requested = synchronized(lock) {
                if (selection !== selected || selected.offBarrierRequested) {
                    false
                } else {
                    selected.offBarrierRequested = true
                    true
                }
            }
            if (requested) onRebaseline(selected.owner, selected.channelId)
        }
        return if (current) {
            ReadStateV1GenerationResult.BaselineRequired(requested)
        } else {
            ReadStateV1GenerationResult.RejectedCurrentGrant
        }
    }

    private fun releaseAfterCompletedBarrier(selected: Selection): Boolean {
        val shouldRelease = synchronized(lock) {
            selection === selected &&
                !selected.requiresSnapshotBarrier &&
                (!isEnabledForServer(selected.owner.serverId) || selected.releaseAfterSnapshotBarrier)
        }
        if (!shouldRelease) return false
        val released = synchronized(lock) {
            if (selection === selected) {
                selection = null
                true
            } else {
                false
            }
        }
        if (released) {
            engine.revoke(selected.witness)
            logger("off_barrier_complete serverId=${selected.owner.serverId} channelId=${selected.channelId}")
        }
        return released
    }
}
