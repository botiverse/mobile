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

data class ReadStateV1DestinationPublication(
    val homeStoreCount: Int,
    val activityStoreCount: Int,
)

class ReadStateV1ProductionCanary(
    private val isEnabledForServer: (ReadStateV1ProductionOwner, String) -> Boolean,
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
    private val diagnostics: ReadStateV1DiagnosticOverrideController = ProcessReadStateV1DiagnosticOverride.controller,
    private val publicationGate: ((ReadStateV1ProductionOwner, String, () -> Unit) -> Boolean)? = null,
    private val onProjectionWithEvidence: ((ReadStateV1ProductionOwner, ReadStateV1GrantWitness, ReadStateV1Fact) -> ReadStateV1DestinationPublication)? = null,
) {
    constructor(
        isEnabledForServer: () -> Boolean,
        store: ReadStateV1Store,
        onProjection: (
            owner: ReadStateV1ProductionOwner,
            witness: ReadStateV1GrantWitness,
            fact: ReadStateV1Fact,
        ) -> Unit,
        onRebaseline: (
            owner: ReadStateV1ProductionOwner,
            witness: ReadStateV1GrantWitness,
            channelId: String,
        ) -> Unit,
        logger: (String) -> Unit = {},
        diagnostics: ReadStateV1DiagnosticOverrideController = ProcessReadStateV1DiagnosticOverride.controller,
    ) : this(
        isEnabledForServer = { _, _ -> isEnabledForServer() },
        store = store,
        onProjection = onProjection,
        onRebaseline = onRebaseline,
        logger = logger,
        diagnostics = diagnostics,
    )

    private data class Selection(
        val owner: ReadStateV1ProductionOwner,
        val channelId: String,
        val witness: ReadStateV1GrantWitness,
        val generation: Long,
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
    private var nextSelectionGeneration: Long = 0L
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
                val published = projectIfEffective(selected, fact)
                if (!published) {
                    replaceWithSnapshotBarrier(selected)
                    return@ReadStateV1ProjectionObserver
                }
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
        } ?: return false.also {
            diagnostics.recordStalePublicationRejected()
        }
        return completePreparedPublication(publication, fact, publishContent)
    }

    fun completePreparedPublication(
        publication: ReadStateV1PreparedPublication,
        projectionFact: ReadStateV1Fact? = null,
        publishContent: () -> Unit = {},
    ): Boolean {
        diagnostics.refreshExpired()
        val selected = synchronized(lock) {
            selection?.takeIf {
                it.owner == publication.owner &&
                    it.channelId == publication.channelId &&
                    it.witness === publication.witness &&
                    (projectionFact == null || it.pendingPreparedFact == projectionFact)
            }
        } ?: return false.also {
            diagnostics.recordStalePublicationRejected()
        }
        return try {
            var completedInsideFence = false
            var projectionEvidence: ReadStateV1DestinationPublication? = null
            val published = selected.witness.publicationFence.publishIfActive {
                val exact = synchronized(lock) {
                    selection === selected &&
                        (projectionFact == null || selected.pendingPreparedFact == projectionFact)
                }
                if (!exact) return@publishIfActive
                val effective =
                    runPublicationGate(selected) {
                        publishContent()
                        if (projectionFact != null) {
                            projectionEvidence = performProjection(selected, projectionFact)
                            synchronized(lock) {
                                if (selection === selected && selected.pendingPreparedFact == projectionFact) {
                                    selected.projectedFact = projectionFact
                                    selected.pendingPreparedFact = null
                                    selected.requiresSnapshotBarrier = false
                                    selected.offBarrierRequested = false
                                }
                            }
                        }
                    }
                if (!effective) return@publishIfActive
                completedInsideFence = true
            }
            if (!published || !completedInsideFence) {
                replaceWithSnapshotBarrier(selected)
                diagnostics.recordStalePublicationRejected()
                return false
            }
            projectionEvidence?.let { evidence -> recordPublicationEvidence(selected, evidence) }
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

    fun refreshEffectiveState() {
        val selected = synchronized(lock) { selection } ?: return
        if (isEnabledForServer(selected.owner, selected.channelId)) return
        replaceWithSnapshotBarrier(selected)
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
        val enabled = isEnabledForServer(owner, channelId)
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
            diagnostics.recordAdjudication(
                source = source.name.lowercase(),
                result = result::class.simpleName.orEmpty(),
                fact = candidate.toFactOrNull(),
            )
            if (!deferProjection && source == ReadStateV1FactSource.Hydrate && result == ReadStateV1GenerationResult.Current) {
                val currentFact = ReadStateV1Fact(
                    channelId,
                    requireNotNull(readStateVersion),
                    requireNotNull(maxReadSeq),
                )
                var projectionCompleted = false
                val published = selected.witness.publicationFence.publishIfActive {
                    val exact = synchronized(lock) { selection === selected }
                    if (!exact) return@publishIfActive
                    if (!projectIfEffective(selected, currentFact)) return@publishIfActive
                    synchronized(lock) {
                        if (selection === selected) selected.projectedFact = currentFact
                    }
                    projectionCompleted = true
                }
                if (!published || !projectionCompleted || synchronized(lock) { selection !== selected }) {
                    replaceWithSnapshotBarrier(selected)
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

    private fun runPublicationGate(
        selected: Selection,
        publish: () -> Unit,
    ): Boolean {
        if (selected.releaseAfterSnapshotBarrier) {
            publish()
            return true
        }
        return publicationGate?.invoke(selected.owner, selected.channelId, publish)
            ?: if (isEnabledForServer(selected.owner, selected.channelId)) {
                publish()
                true
            } else {
                false
            }
    }

    private fun projectIfEffective(selected: Selection, fact: ReadStateV1Fact): Boolean {
        var evidence: ReadStateV1DestinationPublication? = null
        val effective =
            runPublicationGate(selected) {
                evidence = performProjection(selected, fact)
            }
        if (!effective) return false
        evidence?.let { publication -> recordPublicationEvidence(selected, publication) }
        return true
    }

    private fun performProjection(
        selected: Selection,
        fact: ReadStateV1Fact,
    ): ReadStateV1DestinationPublication? =
        onProjectionWithEvidence?.invoke(selected.owner, selected.witness, fact)
            ?: run {
                onProjection(selected.owner, selected.witness, fact)
                null
            }

    private fun recordPublicationEvidence(
        selected: Selection,
        publication: ReadStateV1DestinationPublication,
    ) {
        diagnostics.recordPublication(
            homeStoreCount = publication.homeStoreCount,
            activityStoreCount = publication.activityStoreCount,
            generation = selected.generation,
        )
    }

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
            if (!isEnabledForServer(owner, channelId) ||
                readStateVersion == null || readStateVersion <= 0L || maxReadSeq == null || maxReadSeq < 0L
            ) return@synchronized null
            val scope =
                ReadStateV1Scope(
                    serverId = owner.serverId,
                    principalId = durablePrincipalId(owner),
                    channelId = channelId,
                )
            nextSelectionGeneration += 1L
            val candidate = Selection(owner, channelId, authority.issue(scope), nextSelectionGeneration)
            synchronized(lock) {
                selection?.let { SelectionDecision(it, newlySelected = false) }
                    ?: SelectionDecision(candidate.also { selection = it }, newlySelected = true)
            }.also { decision ->
                if (decision.selection !== candidate) engine.revoke(candidate.witness)
                if (decision.selection === candidate) {
                    diagnostics.recordSelection(
                        owner,
                        channelId,
                        candidate.generation,
                    )
                }
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
        nextSelectionGeneration += 1L
        val barrier =
            Selection(
                owner = revoked.owner,
                channelId = revoked.channelId,
                witness = authority.issue(revoked.witness.scope),
                generation = nextSelectionGeneration,
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
        diagnostics.recordSelection(
            barrier.owner,
            barrier.channelId,
            barrier.generation,
        )
        val requested =
            (requestOffBarrier(barrier) as? ReadStateV1GenerationResult.BaselineRequired)?.requested == true
        diagnostics.recordBarrier("snapshot_requested=$requested")
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
                (!isEnabledForServer(selected.owner, selected.channelId) || selected.releaseAfterSnapshotBarrier)
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
            diagnostics.recordBarrier("released")
        }
        return released
    }
}

private fun ReadStateV1WireCandidate.toFactOrNull(): ReadStateV1Fact? =
    if (channelId != null && readStateVersion != null && maxReadSeq != null) {
        ReadStateV1Fact(channelId, readStateVersion, maxReadSeq)
    } else {
        null
    }
