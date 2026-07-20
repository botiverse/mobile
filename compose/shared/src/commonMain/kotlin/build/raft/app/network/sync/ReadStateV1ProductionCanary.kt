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
        val preparedPublication: ReadStateV1PreparedPublication? = null,
    ) : ReadStateV1ProductionDisposition

    data class SelectedFailure(
        val preparedPublication: ReadStateV1PreparedPublication? = null,
    ) : ReadStateV1ProductionDisposition
}

class ReadStateV1PreparedPublication internal constructor(
    internal val owner: ReadStateV1ProductionOwner,
    internal val channelId: String,
    internal val witness: ReadStateV1GrantWitness,
)

class ReadStateV1ProductionCanary(
    private val isEnabledForServer: (String) -> Boolean,
    store: ReadStateV1Store,
    private val onProjection: (
        owner: ReadStateV1ProductionOwner,
        witness: ReadStateV1GrantWitness,
        fact: ReadStateV1Fact,
    ) -> Unit,
    private val onRebaseline: (
        owner: ReadStateV1ProductionOwner,
        witness: ReadStateV1GrantWitness,
        channelId: String,
    ) -> Unit,
    private val logger: (String) -> Unit = {},
) {
    private data class Selection(
        val owner: ReadStateV1ProductionOwner,
        val channelId: String,
        val witness: ReadStateV1GrantWitness,
        var projectedFact: ReadStateV1Fact? = null,
        var preparingHydrate: Boolean = false,
        var offBarrierRequested: Boolean = false,
        var requiresSnapshotBarrier: Boolean = false,
        var releaseAfterSnapshotBarrier: Boolean = false,
        var pendingPreparedFact: ReadStateV1Fact? = null,
    )

    private data class SelectionDecision(val selection: Selection, val newlySelected: Boolean)
    private data class SnapshotBarrierDecision(val selection: Selection, val requested: Boolean)

    private val lock = SynchronizedObject()
    private val selectionIssueLock = SynchronizedObject()
    private val authority = ReadStateV1GrantAuthority()
    private var selection: Selection? = null
    private val engine =
        ReadStateV1GenerationEngine(
            authority = authority,
            store = store,
            projectionObserver = ReadStateV1ProjectionObserver { witness, fact ->
                val selected = synchronized(lock) {
                    selection?.takeIf { it.witness === witness }?.also {
                        if (it.preparingHydrate) it.pendingPreparedFact = fact
                    }
                } ?: return@ReadStateV1ProjectionObserver
                if (synchronized(lock) { selection === selected && selected.preparingHydrate }) return@ReadStateV1ProjectionObserver
                onProjection(selected.owner, witness, fact)
                synchronized(lock) {
                    if (selection === selected) selected.projectedFact = fact
                }
            },
            rebaselineRequester = ReadStateV1RebaselineRequester { witness ->
                synchronized(lock) {
                    selection?.takeIf { it.witness === witness }
                }?.let { selected -> onRebaseline(selected.owner, witness, selected.channelId) }
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
            owner = owner,
            channelId = channelId,
            source = ReadStateV1FactSource.Hydrate,
            readStateVersion = readStateVersion,
            maxReadSeq = maxReadSeq,
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
            selection?.takeIf { it.owner == owner }
        } ?: return
        replaceWithSnapshotBarrier(revoked) ?: return
        logger("revoke serverId=${owner.serverId} channelId=${revoked.channelId}")
    }

    fun isSelected(owner: ReadStateV1ProductionOwner, channelId: String): Boolean =
        synchronized(lock) { selection?.let { it.owner == owner && it.channelId == channelId } == true }

    fun selectedOwner(): ReadStateV1ProductionOwner? = synchronized(lock) { selection?.owner }

    fun completePreparedHydrateProjection(
        owner: ReadStateV1ProductionOwner,
        fact: ReadStateV1Fact,
        publishContent: () -> Unit = {},
    ): Boolean {
        val publication = synchronized(lock) {
            selection?.takeIf {
                it.owner == owner && it.channelId == fact.channelId && it.pendingPreparedFact == fact
            }?.let(::preparedPublication)
        } ?: return false
        return completePreparedPublication(publication, fact, publishContent)
    }

    fun completePreparedPublication(
        publication: ReadStateV1PreparedPublication,
        projectionFact: ReadStateV1Fact? = null,
        publishContent: () -> Unit = {},
    ): Boolean {
        val selected = synchronized(lock) {
            selection?.takeIf {
                it.owner == publication.owner &&
                    it.channelId == publication.channelId &&
                    it.witness === publication.witness &&
                    (projectionFact == null || it.pendingPreparedFact == projectionFact)
            }
        } ?: return false
        return try {
            var completedInsideFence = false
            val published = selected.witness.publicationFence.publishIfActive {
                val exact = synchronized(lock) {
                    selection === selected &&
                        (projectionFact == null || selected.pendingPreparedFact == projectionFact)
                }
                if (!exact) return@publishIfActive
                publishContent()
                if (projectionFact != null) {
                    onProjection(publication.owner, selected.witness, projectionFact)
                    synchronized(lock) {
                        if (selection === selected && selected.pendingPreparedFact == projectionFact) {
                            selected.projectedFact = projectionFact
                            selected.pendingPreparedFact = null
                            selected.requiresSnapshotBarrier = false
                            selected.offBarrierRequested = false
                        }
                    }
                }
                completedInsideFence = true
            }
            if (!published || !completedInsideFence) return false
            if (projectionFact != null) releaseAfterCompletedBarrier(selected)
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger(
                "publication_failed source=hydrate serverId=${publication.owner.serverId} " +
                    "channelId=${publication.channelId} " +
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
        var selected = decision.selection
        if (selected.owner != owner || selected.channelId != channelId) {
            return ReadStateV1ProductionDisposition.Legacy
        }
        val enabled = isEnabledForServer(owner.serverId)
        var barrierRequested = false
        if (!enabled && !selected.releaseAfterSnapshotBarrier) {
            val barrier =
                replaceWithSnapshotBarrier(selected)
                    ?: return ReadStateV1ProductionDisposition.SelectedFailure(
                        preparedPublication = if (deferProjection) preparedPublication(selected) else null,
                    )
            selected = barrier.selection
            barrierRequested = barrier.requested
        }
        val projectionOrSnapshotBarrier = synchronized(lock) {
            selection === selected && (selected.requiresSnapshotBarrier || selected.pendingPreparedFact != null)
        }
        if ((!enabled || projectionOrSnapshotBarrier) && source != ReadStateV1FactSource.Hydrate) {
            synchronized(lock) { if (selection === selected) selected.requiresSnapshotBarrier = true }
            return ReadStateV1ProductionDisposition.Selected(
                if (barrierRequested) ReadStateV1GenerationResult.BaselineRequired(true) else requestOffBarrier(selected),
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
            if (deferProjection) {
                synchronized(lock) {
                    if (selection === selected) selected.preparingHydrate = true
                }
            }
            val result = try {
                if (source == ReadStateV1FactSource.Hydrate) {
                    engine.applySnapshot(selected.witness, candidate)
                } else {
                    engine.applyIncremental(selected.witness, candidate)
                }
            } finally {
                if (deferProjection) {
                    synchronized(lock) {
                        if (selection === selected) selected.preparingHydrate = false
                    }
                }
            }
            logger(
                "apply source=${source.name.lowercase()} serverId=${owner.serverId} channelId=$channelId " +
                    "result=${result::class.simpleName.orEmpty()}"
            )
            if (!deferProjection && source == ReadStateV1FactSource.Hydrate && result == ReadStateV1GenerationResult.Current) {
                val currentFact = ReadStateV1Fact(
                    channelId,
                    requireNotNull(readStateVersion),
                    requireNotNull(maxReadSeq),
                )
                val published = selected.witness.publicationFence.publishIfActive {
                    val exact = synchronized(lock) { selection === selected }
                    if (!exact) return@publishIfActive
                    onProjection(owner, selected.witness, currentFact)
                    synchronized(lock) {
                        if (selection === selected) selected.projectedFact = currentFact
                    }
                }
                if (!published || synchronized(lock) { selection !== selected }) {
                    return ReadStateV1ProductionDisposition.Selected(
                        ReadStateV1GenerationResult.RejectedCurrentGrant,
                        selected.projectedFact,
                    )
                }
            }
            val preparedFact =
                if (deferProjection && result in setOf(ReadStateV1GenerationResult.Committed, ReadStateV1GenerationResult.Current)) {
                    synchronized(lock) {
                        if (selection !== selected) {
                            null
                        } else {
                            selected.pendingPreparedFact
                                ?: ReadStateV1Fact(
                                    channelId,
                                    requireNotNull(readStateVersion),
                                    requireNotNull(maxReadSeq),
                                ).also { selected.pendingPreparedFact = it }
                        }
                    }
                } else {
                    null
                }
            val disposition =
                ReadStateV1ProductionDisposition.Selected(
                    result = result,
                    projectedFact = preparedFact ?: selected.projectedFact,
                    requiresProjectionConfirmation = preparedFact != null,
                    preparedPublication = if (deferProjection) preparedPublication(selected) else null,
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
            synchronized(lock) {
                if (selection === selected) selected.preparingHydrate = false
            }
            logger(
                "apply_failed source=${source.name.lowercase()} serverId=${owner.serverId} channelId=$channelId " +
                    "reason=${error::class.simpleName ?: "unknown"}"
            )
            ReadStateV1ProductionDisposition.SelectedFailure(
                preparedPublication = if (deferProjection) preparedPublication(selected) else null,
            )
        }
    }

    private fun preparedPublication(selected: Selection): ReadStateV1PreparedPublication =
        ReadStateV1PreparedPublication(selected.owner, selected.channelId, selected.witness)

    private fun select(
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        readStateVersion: Long?,
        maxReadSeq: Long?,
    ): SelectionDecision? {
        if (!isEligible(owner, channelId)) return null
        return synchronized(selectionIssueLock) {
            val existing = synchronized(lock) { selection }
            if (existing != null) return@synchronized SelectionDecision(existing, newlySelected = false)
            if (!isEnabledForServer(owner.serverId) ||
                readStateVersion == null || readStateVersion <= 0L || maxReadSeq == null || maxReadSeq < 0L
            ) return@synchronized null
            val scope =
                ReadStateV1Scope(
                    serverId = owner.serverId,
                    principalId = durablePrincipalId(owner),
                    channelId = channelId,
                )
            val candidate = Selection(owner, channelId, authority.issue(scope))
            synchronized(lock) {
                selection?.let { SelectionDecision(it, newlySelected = false) }
                    ?: SelectionDecision(candidate.also { selection = it }, newlySelected = true)
            }.also { decision ->
                if (decision.selection !== candidate) engine.revoke(candidate.witness)
            }
        }
    }

    private fun isEligible(owner: ReadStateV1ProductionOwner, channelId: String): Boolean =
        owner.environmentId.isNotBlank() && owner.accountId.isNotBlank() &&
            owner.serverId.isNotBlank() && channelId.isNotBlank()

    private fun durablePrincipalId(owner: ReadStateV1ProductionOwner): String =
        "${owner.environmentId.trim()}\u001f${owner.accountId.trim()}"

    private fun replaceWithSnapshotBarrier(revoked: Selection): SnapshotBarrierDecision? = synchronized(selectionIssueLock) {
        val observed = synchronized(lock) { selection }
        if (observed !== revoked) {
            return@synchronized observed?.takeIf {
                it.owner == revoked.owner && it.channelId == revoked.channelId && it.releaseAfterSnapshotBarrier
            }?.let { SnapshotBarrierDecision(it, requested = false) }
        }
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
            return@synchronized null
        }
        val requested =
            (requestOffBarrier(barrier) as? ReadStateV1GenerationResult.BaselineRequired)?.requested == true
        SnapshotBarrierDecision(barrier, requested)
    }

    private fun requestOffBarrier(selected: Selection): ReadStateV1GenerationResult {
        var requested = false
        val published = selected.witness.publicationFence.publishIfActive {
            requested = synchronized(lock) {
                if (selection !== selected || selected.offBarrierRequested) {
                    false
                } else {
                    selected.offBarrierRequested = true
                    true
                }
            }
            if (requested) onRebaseline(selected.owner, selected.witness, selected.channelId)
        }
        if (!published) return ReadStateV1GenerationResult.RejectedCurrentGrant
        return ReadStateV1GenerationResult.BaselineRequired(requested)
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
