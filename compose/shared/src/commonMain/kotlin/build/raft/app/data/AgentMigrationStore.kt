package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface AgentMigrationDataSource {
    suspend fun migrationStatus(agentId: MemberId, priority: DataRequestPriority): DataResult<AgentMigrationStatusSnapshot>
    suspend fun startMigration(agentId: MemberId, target: AgentMigrationTarget): DataResult<String>
    suspend fun cancelMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int): DataResult<Unit>
    suspend fun confirmHostedMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int): DataResult<Unit>
    suspend fun billingPlan(): DataResult<RaftBillingPlanSnapshot>
}

/** A refused migration request: the server `code` (when sent) and its text. */
internal data class AgentMigrationActionError(val code: String, val serverMessage: String? = null)

internal sealed interface AgentMigrationEffect {
    /** The server says the plan does not include migration: show the upgrade sheet, not the error. */
    data object ProRequired : AgentMigrationEffect
    data object Started : AgentMigrationEffect
    /** A watched migration completed or switched to the cloud: the agent's computer changed. */
    data object AgentPlacementChanged : AgentMigrationEffect
}

internal data class AgentMigrationState(
    val agentId: MemberId? = null,
    val notice: AgentMigrationNotice? = null,
    val loaded: Boolean = false,
    val statusError: AgentMigrationActionError? = null,
    val starting: Boolean = false,
    val startError: AgentMigrationActionError? = null,
    val canceling: Boolean = false,
    val cancelError: AgentMigrationActionError? = null,
    val confirming: Boolean = false,
    val confirmError: AgentMigrationActionError? = null,
    val effect: AgentMigrationEffect? = null,
    /** Advisory plan for the Pro gate; null when unknown (not readable by this viewer). */
    val billingPlan: String? = null,
    val billingChecking: Boolean = false
)

internal sealed interface AgentMigrationIntent {
    /** Begin watching one agent: read now, then poll while a migration is active. */
    data class Watch(val agentId: MemberId, val checkPlan: Boolean = true) : AgentMigrationIntent
    data object StopWatching : AgentMigrationIntent
    /** The page became visible / hidden (covered or app backgrounded): the poll pauses while hidden. */
    data class SetPageVisible(val visible: Boolean) : AgentMigrationIntent
    data object Refresh : AgentMigrationIntent
    data class Start(val target: AgentMigrationTarget) : AgentMigrationIntent
    data object Cancel : AgentMigrationIntent
    data object ConfirmHosted : AgentMigrationIntent
    data object ClearStartError : AgentMigrationIntent
    data object ClearCancelError : AgentMigrationIntent
    data object ConsumeEffect : AgentMigrationIntent
}

/**
 * Migration status and actions for the agent detail page. Mirrors web
 * `createAgentMigrationRealtimeSync`: one read on open, then a 2s poll while
 * the latest migration is active (stops when it ends); reads are
 * single-flight with the latest request winning. Actions re-read after they
 * land. Cancel retries on a stale revision like web `AgentMigrationCancelDialog`.
 *
 * The poll only runs while the page and the app host are visible
 * ([isHostVisible], [AgentMigrationIntent.SetPageVisible]); a poll due while
 * hidden is held and replaced by one read when the page shows again. Nothing
 * reads after [AgentMigrationIntent.StopWatching], including action completions.
 */
internal class AgentMigrationStore(
    private val dataSource: AgentMigrationDataSource,
    private val scope: CoroutineScope,
    private val pollMs: Long = AGENT_MIGRATION_POLL_MS,
    /** The app host's visibility (e.g. `isRaftHostVisible(shellInstanceId)`). */
    private val isHostVisible: () -> Boolean = { true }
) {
    private val mutableState = MutableStateFlow(AgentMigrationState())
    val state: StateFlow<AgentMigrationState> = mutableState.asStateFlow()

    private var readJob: Job? = null
    private var pollJob: Job? = null
    private var readGeneration = 0L
    private var watchedActiveRef: String? = null
    private var watchedHostedSwitchRef: String? = null
    private var watching = false
    private var pageVisible = true
    private var pollHeldWhileHidden = false

    fun dispatch(intent: AgentMigrationIntent) {
        when (intent) {
            is AgentMigrationIntent.Watch -> watch(intent.agentId, intent.checkPlan)
            AgentMigrationIntent.StopWatching -> stopWatching()
            is AgentMigrationIntent.SetPageVisible -> setPageVisible(intent.visible)
            AgentMigrationIntent.Refresh -> refresh(DataRequestPriority.Visible)
            is AgentMigrationIntent.Start -> start(intent.target)
            AgentMigrationIntent.Cancel -> cancel()
            AgentMigrationIntent.ConfirmHosted -> confirmHosted()
            AgentMigrationIntent.ClearStartError -> update { it.copy(startError = null) }
            AgentMigrationIntent.ClearCancelError -> update { it.copy(cancelError = null) }
            AgentMigrationIntent.ConsumeEffect -> update { it.copy(effect = null) }
        }
    }

    private fun update(transform: (AgentMigrationState) -> AgentMigrationState) {
        val next = transform(mutableState.value)
        if (next != mutableState.value) mutableState.value = next
    }

    private fun watch(agentId: MemberId, checkPlan: Boolean) {
        if (watching && mutableState.value.agentId == agentId) return
        stopWatching()
        watching = true
        mutableState.value = AgentMigrationState(agentId = agentId, billingChecking = checkPlan)
        watchedActiveRef = null
        watchedHostedSwitchRef = null
        refresh(DataRequestPriority.Visible)
        if (checkPlan) {
            scope.launch {
                val plan = (guarded { dataSource.billingPlan() } as? DataResult.Content)?.data?.plan
                if (mutableState.value.agentId == agentId) {
                    update { it.copy(billingPlan = plan, billingChecking = false) }
                }
            }
        }
    }

    private fun stopWatching() {
        watching = false
        pollHeldWhileHidden = false
        readGeneration += 1
        readJob?.cancel()
        pollJob?.cancel()
        readJob = null
        pollJob = null
    }

    private fun setPageVisible(visible: Boolean) {
        pageVisible = visible
        if (!visible) return
        // Also covers the host coming back while the page stayed "visible": the page
        // re-sends SetPageVisible(true) on every resume tick.
        if (watching && pollHeldWhileHidden && isHostVisible()) {
            pollHeldWhileHidden = false
            refresh(DataRequestPriority.Visible)
        }
    }

    private fun visibleNow(): Boolean = pageVisible && isHostVisible()

    private fun refresh(priority: DataRequestPriority) {
        if (!watching) return
        val agentId = mutableState.value.agentId ?: return
        pollHeldWhileHidden = false
        pollJob?.cancel()
        readJob?.cancel()
        readGeneration += 1
        val generation = readGeneration
        readJob =
            scope.launch {
                val result = guarded { dataSource.migrationStatus(agentId, priority) }
                if (generation != readGeneration || mutableState.value.agentId != agentId) return@launch
                when (result) {
                    is DataResult.Content -> applySnapshot(result.data.migration?.takeIf { it.agentId == agentId.value })
                    else -> update { it.copy(loaded = true, statusError = result.toMigrationActionError()) }
                }
                val active = mutableState.value.notice?.isActive == true
                if (active) schedulePoll(generation)
            }
    }

    private fun schedulePoll(generation: Long) {
        pollJob =
            scope.launch {
                delay(pollMs)
                if (generation != readGeneration || !watching) return@launch
                if (visibleNow()) {
                    refresh(DataRequestPriority.Background)
                } else {
                    // Hidden: hold the poll; the page's next SetPageVisible(true) reads once.
                    pollHeldWhileHidden = true
                }
            }
    }

    private fun applySnapshot(notice: AgentMigrationNotice?) {
        var placementChanged = false
        if (notice != null) {
            if (
                notice.isHosted && notice.flippedAt != null &&
                watchedActiveRef == notice.migrationRef && watchedHostedSwitchRef != notice.migrationRef
            ) {
                watchedHostedSwitchRef = notice.migrationRef
                placementChanged = true
            }
            if (notice.isActive) {
                watchedActiveRef = notice.migrationRef
            } else if (notice.isCompleted && watchedActiveRef == notice.migrationRef) {
                watchedActiveRef = null
                placementChanged = true
            }
        }
        update {
            it.copy(
                notice = notice,
                loaded = true,
                statusError = null,
                effect = if (placementChanged) AgentMigrationEffect.AgentPlacementChanged else it.effect
            )
        }
    }

    private fun start(target: AgentMigrationTarget) {
        val current = mutableState.value
        val agentId = current.agentId ?: return
        if (current.starting || current.notice?.isActive == true) return
        update { it.copy(starting = true, startError = null) }
        scope.launch {
            val result = guarded { dataSource.startMigration(agentId, target) }
            if (mutableState.value.agentId != agentId) return@launch
            when {
                result is DataResult.Content -> {
                    update { it.copy(starting = false, effect = AgentMigrationEffect.Started) }
                    refresh(DataRequestPriority.Foreground)
                }
                result.isProPlanRequired() -> {
                    update { it.copy(starting = false, effect = AgentMigrationEffect.ProRequired) }
                    // The plan changed under us (web reloads billing): re-read it for the gate.
                    scope.launch {
                        val plan = (guarded { dataSource.billingPlan() } as? DataResult.Content)?.data?.plan
                        if (plan != null && mutableState.value.agentId == agentId) update { it.copy(billingPlan = plan) }
                    }
                }
                else -> update { it.copy(starting = false, startError = result.toMigrationActionError()) }
            }
        }
    }

    private fun cancel() {
        val current = mutableState.value
        val agentId = current.agentId ?: return
        val notice = current.notice ?: return
        if (current.canceling || !notice.canCancel()) return
        update { it.copy(canceling = true, cancelError = null) }
        scope.launch {
            var revision = notice.revision
            var attempt = 1
            var failure: AgentMigrationActionError? = null
            while (true) {
                val result = guarded { dataSource.cancelMigration(agentId, notice.migrationRef, revision) }
                if (result is DataResult.Content) break
                val error = result.toMigrationActionError()
                if (error.code == AgentMigrationErrorCodes.RevisionStale && attempt < MAX_CANCEL_ATTEMPTS) {
                    val latest =
                        (guarded { dataSource.migrationStatus(agentId, DataRequestPriority.Foreground) } as? DataResult.Content)
                            ?.data?.migration
                    if (latest != null && latest.migrationRef == notice.migrationRef && latest.canCancel()) {
                        revision = latest.revision
                        attempt += 1
                        continue
                    }
                }
                failure = error
                break
            }
            if (mutableState.value.agentId != agentId) return@launch
            update { it.copy(canceling = false, cancelError = failure) }
            refresh(DataRequestPriority.Foreground)
        }
    }

    private fun confirmHosted() {
        val current = mutableState.value
        val agentId = current.agentId ?: return
        val notice = current.notice ?: return
        if (current.confirming || !notice.isHostedReviewPending()) return
        update { it.copy(confirming = true, confirmError = null) }
        scope.launch {
            val result = guarded { dataSource.confirmHostedMigration(agentId, notice.migrationRef, notice.revision) }
            if (mutableState.value.agentId != agentId) return@launch
            update {
                it.copy(
                    confirming = false,
                    confirmError = if (result is DataResult.Content) null else result.toMigrationActionError()
                )
            }
            refresh(DataRequestPriority.Foreground)
        }
    }

    private suspend fun <T> guarded(block: suspend () -> DataResult<T>): DataResult<T> =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError))
        }

    companion object {
        const val AGENT_MIGRATION_POLL_MS = 2_000L
        private const val MAX_CANCEL_ATTEMPTS = 3
    }
}

private fun DataResult<*>.isProPlanRequired(): Boolean =
    this is DataResult.Error && error.serverCode == AgentMigrationErrorCodes.ProPlanRequired

internal fun DataResult<*>.toMigrationActionError(): AgentMigrationActionError =
    when (this) {
        is DataResult.Error -> AgentMigrationActionError(error.serverCode ?: error.code, error.serverMessage)
        is DataResult.NoPermission -> AgentMigrationActionError(blocker.code, blocker.serverMessage)
        is DataResult.Empty -> AgentMigrationActionError(reason.name)
        is DataResult.Content -> AgentMigrationActionError("unexpected_content")
    }
