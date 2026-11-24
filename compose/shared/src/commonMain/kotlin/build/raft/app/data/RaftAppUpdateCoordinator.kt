package build.raft.app.data

import build.raft.app.network.RaftAppIdentityRegistry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Single source of truth for process-wide app update state and prompt decisions. */
data class RaftAppUpdateCoordinatorState(
    val snapshot: SharedSettingsUpdateCheckSnapshot? = null,
    val decision: RaftAppUpdatePromptDecision = RaftAppUpdatePromptDecision(),
    val loading: Boolean = false,
    val lastCheckedAtMs: Long = 0L,
    val errorCode: String? = null,
    val currentVersionCode: Long = 0L,
    val currentVersionLabel: String = "",
)

/**
 * Process-wide coordinator owning update check coalescing, device-local reminder persistence,
 * and derived [RaftAppUpdatePromptDecision] updates.
 */
class RaftAppUpdateCoordinator(
    private val store: RaftAppUpdatePromptStore,
    private val now: () -> Long = ::defaultNowMillis,
    private val minRefreshIntervalMs: Long = 60_000L,
    private val minBuildGateDecision: () -> RaftMinBuildGateDecision = { RaftMinBuildGateDecision.None },
) {
    private sealed interface InFlightOutcome {
        data class Completed(val result: DataResult<SharedSettingsUpdateCheckSnapshot>) : InFlightOutcome
        data object Cancelled : InFlightOutcome
    }

    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(RaftAppUpdateCoordinatorState())
    val state: StateFlow<RaftAppUpdateCoordinatorState> = _state.asStateFlow()

    private var inFlightRefresh: CompletableDeferred<InFlightOutcome>? = null
    private var lastPolledGateDecision: RaftMinBuildGateDecision? = null
    private var activeGateDecision: RaftMinBuildGateDecision = RaftMinBuildGateDecision.None

    fun recordAppLaunch(currentVersionCode: Long, currentVersionLabel: String) {
        synchronized(lock) {
            val persistence = store.recordAppLaunch(currentVersionCode)
            val current = _state.value
            val nextCode = currentVersionCode.takeIf { it > 0L } ?: current.currentVersionCode
            val nextLabel = currentVersionLabel.trim().ifBlank { current.currentVersionLabel }
            _state.value =
                current.copy(
                    currentVersionCode = nextCode,
                    currentVersionLabel = nextLabel,
                    decision =
                        decideRaftAppUpdatePrompt(
                            snapshot = current.snapshot,
                            minBuildGateDecision = resolveMinBuildGateDecisionLocked(),
                            persistence = persistence,
                            currentVersionCode = nextCode,
                            currentVersionLabel = nextLabel,
                            nowMs = now(),
                        ),
                )
        }
    }

    /**
     * Refreshes update status using [fetch].
     *
     * Concurrent callers share a single in-flight fetch. When [force] is false and a valid
     * snapshot was checked within [minRefreshIntervalMs], returns the cached snapshot without
     * calling [fetch] again.
     */
    suspend fun refresh(
        force: Boolean = false,
        fetch: suspend () -> DataResult<SharedSettingsUpdateCheckSnapshot>,
    ): DataResult<SharedSettingsUpdateCheckSnapshot> {
        while (true) {
            var join: CompletableDeferred<InFlightOutcome>? = null
            var own: CompletableDeferred<InFlightOutcome>? = null
            var cached: SharedSettingsUpdateCheckSnapshot? = null
            synchronized(lock) {
                val current = _state.value
                val active = inFlightRefresh
                val nowMs = now()
                when {
                    active != null -> join = active
                    !force &&
                        current.snapshot != null &&
                        current.lastCheckedAtMs > 0L &&
                        minRefreshIntervalMs > 0L &&
                        (nowMs - current.lastCheckedAtMs) in 0L until minRefreshIntervalMs ->
                        cached = current.snapshot
                    else -> {
                        val created = CompletableDeferred<InFlightOutcome>()
                        inFlightRefresh = created
                        own = created
                        _state.value = current.copy(loading = true)
                    }
                }
            }
            cached?.let { return DataResult.Content(it) }
            if (join != null) {
                when (val outcome = join.await()) {
                    is InFlightOutcome.Completed -> return outcome.result
                    InFlightOutcome.Cancelled -> continue
                }
            }
            val owned = own!!
            var completed = false
            try {
                val result = fetch()
                applyRefreshResult(result)
                completed = true
                synchronized(lock) {
                    if (inFlightRefresh === owned) inFlightRefresh = null
                }
                owned.complete(InFlightOutcome.Completed(result))
                return result
            } catch (cancellation: CancellationException) {
                synchronized(lock) {
                    if (inFlightRefresh === owned) {
                        inFlightRefresh = null
                        _state.value = _state.value.copy(loading = false)
                    }
                }
                owned.complete(InFlightOutcome.Cancelled)
                throw cancellation
            } catch (error: Throwable) {
                val failure =
                    DataResult.Error(
                        ScreenError(
                            code = error::class.simpleName ?: "app_update_refresh_failed",
                            retryable = true,
                        )
                    )
                applyRefreshResult(failure)
                completed = true
                synchronized(lock) {
                    if (inFlightRefresh === owned) inFlightRefresh = null
                }
                owned.complete(InFlightOutcome.Completed(failure))
                return failure
            } finally {
                if (!completed) {
                    synchronized(lock) {
                        if (inFlightRefresh === owned) {
                            inFlightRefresh = null
                            _state.value = _state.value.copy(loading = false)
                        }
                    }
                    owned.complete(InFlightOutcome.Cancelled)
                }
            }
        }
    }

    fun publishSnapshot(snapshot: SharedSettingsUpdateCheckSnapshot) {
        synchronized(lock) {
            applySnapshotLocked(snapshot, checkedAtMs = now())
        }
    }

    fun snoozeCurrentUpdate(durationMs: Long = RAFT_APP_UPDATE_SNOOZE_DURATION_MS) {
        synchronized(lock) {
            val nowMs = now()
            val targetVersionCode = _state.value.snapshot?.latestVersionCode ?: 0L
            val persistence =
                if (targetVersionCode > 0L) {
                    store.snoozeUpdate(
                        versionCode = targetVersionCode,
                        nowMs = nowMs,
                        durationMs = durationMs,
                    )
                } else {
                    store.readState()
                }
            recomputeLocked(persistence = persistence, nowMs = nowMs)
        }
    }

    fun markStrongPromptShown() {
        synchronized(lock) {
            val nowMs = now()
            val persistence = store.recordStrongPromptShown(nowMs)
            recomputeLocked(persistence = persistence, nowMs = nowMs)
        }
    }

    fun dismissPostUpdateNotice() {
        synchronized(lock) {
            val current = _state.value
            val versionCode =
                current.decision.postUpdateNotice?.versionCode
                    ?: current.currentVersionCode
            val persistence =
                if (versionCode > 0L) {
                    store.dismissPostUpdateNotice(versionCode)
                } else {
                    store.readState()
                }
            recomputeLocked(persistence = persistence, nowMs = now())
        }
    }

    fun onMinBuildGateDecisionChanged(decision: RaftMinBuildGateDecision) {
        synchronized(lock) {
            lastPolledGateDecision = minBuildGateDecision()
            activeGateDecision = decision
            recomputeLocked(persistence = store.readState(), nowMs = now())
        }
    }

    private fun applyRefreshResult(result: DataResult<SharedSettingsUpdateCheckSnapshot>) {
        synchronized(lock) {
            when (result) {
                is DataResult.Content -> applySnapshotLocked(result.data, checkedAtMs = now())
                is DataResult.Empty -> {
                    val current = _state.value
                    _state.value =
                        current.copy(
                            loading = false,
                            errorCode = null,
                            decision =
                                decideRaftAppUpdatePrompt(
                                    snapshot = current.snapshot,
                                    minBuildGateDecision = resolveMinBuildGateDecisionLocked(),
                                    persistence = store.readState(),
                                    currentVersionCode = current.currentVersionCode,
                                    currentVersionLabel = current.currentVersionLabel,
                                    nowMs = now(),
                                ),
                        )
                }
                is DataResult.Error -> {
                    val current = _state.value
                    _state.value =
                        current.copy(
                            loading = false,
                            errorCode = result.error.code.takeIf { it.isNotBlank() },
                            decision =
                                decideRaftAppUpdatePrompt(
                                    snapshot = current.snapshot,
                                    minBuildGateDecision = resolveMinBuildGateDecisionLocked(),
                                    persistence = store.readState(),
                                    currentVersionCode = current.currentVersionCode,
                                    currentVersionLabel = current.currentVersionLabel,
                                    nowMs = now(),
                                ),
                        )
                }
                is DataResult.NoPermission -> {
                    val current = _state.value
                    _state.value =
                        current.copy(
                            loading = false,
                            errorCode = result.blocker.code.takeIf { it.isNotBlank() },
                            decision =
                                decideRaftAppUpdatePrompt(
                                    snapshot = current.snapshot,
                                    minBuildGateDecision = resolveMinBuildGateDecisionLocked(),
                                    persistence = store.readState(),
                                    currentVersionCode = current.currentVersionCode,
                                    currentVersionLabel = current.currentVersionLabel,
                                    nowMs = now(),
                                ),
                        )
                }
            }
        }
    }

    private fun applySnapshotLocked(snapshot: SharedSettingsUpdateCheckSnapshot, checkedAtMs: Long) {
        val current = _state.value
        val nextCurrentCode =
            current.currentVersionCode.takeIf { it > 0L }
                ?: snapshot.currentVersionCode.takeIf { it > 0L }
                ?: 0L
        val nextCurrentLabel =
            current.currentVersionLabel.ifBlank { snapshot.currentVersionLabel.trim() }
        val offersNewerUpdate =
            snapshot.supported &&
                snapshot.updateAvailable &&
                snapshot.latestVersionCode > 0L &&
                (nextCurrentCode <= 0L || snapshot.latestVersionCode > nextCurrentCode) &&
                snapshot.transactionState != SharedSettingsUpdateTransactionState.Installed
        val persistence =
            if (offersNewerUpdate) {
                store.recordSeenUpdate(versionCode = snapshot.latestVersionCode, nowMs = checkedAtMs)
            } else {
                store.readState()
            }
        val decision =
            decideRaftAppUpdatePrompt(
                snapshot = snapshot,
                minBuildGateDecision = resolveMinBuildGateDecisionLocked(),
                persistence = persistence,
                currentVersionCode = nextCurrentCode,
                currentVersionLabel = nextCurrentLabel,
                nowMs = checkedAtMs,
            )
        _state.value =
            current.copy(
                snapshot = snapshot,
                decision = decision,
                loading = false,
                lastCheckedAtMs = checkedAtMs,
                errorCode = snapshot.errorCode.takeIf { it.isNotBlank() },
                currentVersionCode = nextCurrentCode,
                currentVersionLabel = nextCurrentLabel,
            )
    }

    private fun recomputeLocked(
        persistence: RaftAppUpdatePromptPersistenceState,
        nowMs: Long,
    ) {
        val current = _state.value
        _state.value =
            current.copy(
                decision =
                    decideRaftAppUpdatePrompt(
                        snapshot = current.snapshot,
                        minBuildGateDecision = resolveMinBuildGateDecisionLocked(),
                        persistence = persistence,
                        currentVersionCode = current.currentVersionCode,
                        currentVersionLabel = current.currentVersionLabel,
                        nowMs = nowMs,
                    ),
            )
    }

    private fun resolveMinBuildGateDecisionLocked(): RaftMinBuildGateDecision {
        val polled = minBuildGateDecision()
        if (lastPolledGateDecision == null || polled != lastPolledGateDecision) {
            lastPolledGateDecision = polled
            activeGateDecision = polled
        }
        return activeGateDecision
    }
}

/**
 * Delegates to [raftSharedPreferenceStore] whenever the app-scope store is installed, and falls
 * back to an in-memory store in unit tests or early bootstrap where MMKV is not initialized.
 */
private class LazySafeRaftAppUpdatePreferenceStore : SharedPreferenceStore {
    private val fallback = InMemoryRaftKeyValueStore()

    private fun backing(): SharedPreferenceStore =
        runCatching { raftSharedPreferenceStore() }.getOrElse { fallback }

    override fun getString(key: String): String? =
        runCatching { backing().getString(key) }.getOrElse { fallback.getString(key) }

    override fun putString(key: String, value: String?) {
        val written = runCatching { backing().putString(key, value) }.isSuccess
        if (!written) fallback.putString(key, value)
    }
}

/** Process-wide app update coordinator singleton. */
object RaftAppUpdates {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val coordinator: RaftAppUpdateCoordinator by lazy {
        RaftAppUpdateCoordinator(
            store = RaftAppUpdatePromptStore(LazySafeRaftAppUpdatePreferenceStore()),
            now = ::defaultNowMillis,
            minBuildGateDecision = { RaftMinBuildGate.decision.value },
        ).also { coord ->
            val identity = RaftAppIdentityRegistry.current()
            val buildCode = parseRaftBuildNumber(identity?.buildNumber) ?: 0L
            if (buildCode > 0L) {
                coord.recordAppLaunch(
                    currentVersionCode = buildCode,
                    currentVersionLabel = identity?.version.orEmpty(),
                )
            }
            processScope.launch {
                RaftMinBuildGate.decision.collect { decision ->
                    coord.onMinBuildGateDecisionChanged(decision)
                }
            }
        }
    }
}
