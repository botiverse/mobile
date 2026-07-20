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
    data class Selected(val result: ReadStateV1GenerationResult) : ReadStateV1ProductionDisposition
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
                    selection?.takeIf { it.witness.scope == scope }?.owner
                }?.let { owner -> onProjection(owner, fact) }
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
            selection?.takeIf { it.owner == owner }?.also { selection = null }
        } ?: return
        engine.revoke(revoked.witness)
        logger("revoke serverId=${owner.serverId} channelId=${revoked.channelId}")
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
    ): ReadStateV1ProductionDisposition {
        val decision =
            select(owner, channelId, readStateVersion, maxReadSeq)
                ?: return ReadStateV1ProductionDisposition.Legacy
        val selected = decision.selection
        if (selected.owner != owner || selected.channelId != channelId) {
            return ReadStateV1ProductionDisposition.Legacy
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
                    return ReadStateV1ProductionDisposition.Selected(restored)
                }
            }
            val result =
                if (source == ReadStateV1FactSource.Hydrate) {
                    engine.applySnapshot(selected.witness, candidate)
                } else {
                    engine.applyIncremental(selected.witness, candidate)
                }
            logger(
                "apply source=${source.name.lowercase()} serverId=${owner.serverId} channelId=$channelId " +
                    "result=${result::class.simpleName.orEmpty()}"
            )
            ReadStateV1ProductionDisposition.Selected(result)
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
        if (!isEligible(owner, channelId) || !isEnabledForServer(owner.serverId)) return null
        return synchronized(lock) {
            selection?.let { SelectionDecision(it, newlySelected = false) }
                ?: if (readStateVersion == null || readStateVersion <= 0L || maxReadSeq == null || maxReadSeq < 0L) {
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
}
