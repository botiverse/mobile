package ai.slock.compose.network.sync

import ai.slock.compose.network.model.BackfillScope
import ai.slock.compose.network.model.NetworkIssue
import ai.slock.compose.network.model.OutboxOperation
import ai.slock.compose.network.model.OutboxOperationStatus

interface OutboxStore {
    suspend fun pendingOperations(): List<OutboxOperation>
    suspend fun upsert(operation: OutboxOperation)
    suspend fun markSending(operationId: String)
    suspend fun markFailed(operationId: String, issue: NetworkIssue)
    suspend fun markSynced(operationId: String)
}

enum class SyncRecoveryPhase {
    Idle,
    Syncing,
    Backfilling,
    Failed
}

data class SyncRecoveryState(
    val phase: SyncRecoveryPhase = SyncRecoveryPhase.Idle,
    val pendingBackfills: List<BackfillScope> = emptyList(),
    val activeBackfill: BackfillScope? = null,
    val lastIssue: NetworkIssue? = null,
    val lastCompletedAtMillis: Long? = null
)

sealed class SyncRecoveryEvent {
    data class ResumeRequested(
        val serverId: String?,
        val visibleThreadId: String?,
        val afterSeq: Long?
    ) : SyncRecoveryEvent()

    data class GapDetected(
        val serverId: String?,
        val visibleThreadId: String?,
        val reason: String
    ) : SyncRecoveryEvent()

    data class BackfillStarted(val scope: BackfillScope) : SyncRecoveryEvent()
    data class BackfillSucceeded(val scope: BackfillScope, val nowMillis: Long) : SyncRecoveryEvent()
    data class BackfillFailed(val scope: BackfillScope, val issue: NetworkIssue) : SyncRecoveryEvent()
    data class OutboxLoaded(val operations: List<OutboxOperation>) : SyncRecoveryEvent()
}

sealed class SyncRecoveryIntent {
    data class ResumeRealtime(val afterSeq: Long) : SyncRecoveryIntent()
    data class Backfill(val scope: BackfillScope) : SyncRecoveryIntent()
    data object DrainOutbox : SyncRecoveryIntent()
}

data class SyncRecoveryResult(
    val state: SyncRecoveryState,
    val intents: List<SyncRecoveryIntent> = emptyList()
)

class SyncRecoveryCoordinator {
    fun reduce(state: SyncRecoveryState, event: SyncRecoveryEvent): SyncRecoveryResult =
        when (event) {
            is SyncRecoveryEvent.ResumeRequested -> {
                val scopes = defaultBackfillScopes(event.serverId, event.visibleThreadId)
                SyncRecoveryResult(
                    state = state.copy(
                        phase = if (scopes.isEmpty()) SyncRecoveryPhase.Syncing else SyncRecoveryPhase.Backfilling,
                        pendingBackfills = (state.pendingBackfills + scopes).distinct(),
                        lastIssue = null
                    ),
                    intents = buildList {
                        event.afterSeq?.let { add(SyncRecoveryIntent.ResumeRealtime(it)) }
                        scopes.forEach { add(SyncRecoveryIntent.Backfill(it)) }
                    }
                )
            }

            is SyncRecoveryEvent.GapDetected -> {
                val scopes = defaultBackfillScopes(event.serverId, event.visibleThreadId)
                SyncRecoveryResult(
                    state = state.copy(
                        phase = SyncRecoveryPhase.Backfilling,
                        pendingBackfills = (state.pendingBackfills + scopes).distinct()
                    ),
                    intents = scopes.map { SyncRecoveryIntent.Backfill(it) }
                )
            }

            is SyncRecoveryEvent.BackfillStarted ->
                SyncRecoveryResult(
                    state = state.copy(
                        phase = SyncRecoveryPhase.Backfilling,
                        activeBackfill = event.scope,
                        pendingBackfills = state.pendingBackfills.filterNot { it == event.scope }
                    )
                )

            is SyncRecoveryEvent.BackfillSucceeded -> {
                val remaining = state.pendingBackfills.filterNot { it == event.scope }
                SyncRecoveryResult(
                    state = state.copy(
                        phase = if (remaining.isEmpty()) SyncRecoveryPhase.Idle else SyncRecoveryPhase.Backfilling,
                        activeBackfill = null,
                        pendingBackfills = remaining,
                        lastIssue = null,
                        lastCompletedAtMillis = event.nowMillis
                    ),
                    intents = remaining.firstOrNull()?.let { listOf(SyncRecoveryIntent.Backfill(it)) }
                        ?: emptyList()
                )
            }

            is SyncRecoveryEvent.BackfillFailed ->
                SyncRecoveryResult(
                    state = state.copy(
                        phase = SyncRecoveryPhase.Failed,
                        activeBackfill = event.scope,
                        lastIssue = event.issue
                    )
                )

            is SyncRecoveryEvent.OutboxLoaded -> {
                val hasPending =
                    event.operations.any {
                        it.status == OutboxOperationStatus.Pending ||
                            it.status == OutboxOperationStatus.Failed
                    }
                SyncRecoveryResult(
                    state = state,
                    intents = if (hasPending) listOf(SyncRecoveryIntent.DrainOutbox) else emptyList()
                )
            }
        }

    private fun defaultBackfillScopes(serverId: String?, visibleThreadId: String?): List<BackfillScope> =
        buildList {
            serverId?.let { add(BackfillScope.Home(it)) }
            visibleThreadId?.let { add(BackfillScope.Thread(it)) }
        }
}
