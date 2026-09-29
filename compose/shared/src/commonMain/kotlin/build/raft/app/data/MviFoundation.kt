package build.raft.app.data

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface SharedStateHolder<UiState, UiAction, UiEvent> {
    val uiState: StateFlow<UiState>
    val uiEvents: Flow<UiEvent>

    fun dispatch(action: UiAction)
}

/**
 * Lightweight shared MVI runtime used by feature stores. It intentionally avoids
 * a heavy base-class hierarchy: business stores keep their own state shape and
 * reducer, while this helper owns the common dispatch/no-op/transition contract.
 */
class MviStoreRuntime<State, Intent>(
    initialState: State,
    private val scope: CoroutineScope,
    private val reduce: (State, Intent) -> State,
    private val persistSlices: List<MviPersistSlice<State, Intent>> = emptyList(),
    private val transitionLogger: (MviTransition<Intent>) -> Unit = {},
    private val equivalent: (State, State) -> Boolean = { previous, next -> previous == next },
    /**
     * Receives failures from the post-commit completion/release phase.  This phase is deliberately
     * non-throwing to callers: the reducer state and durable projection have already committed,
     * so surfacing an exception would make resource owners (for example Gallery) compensate a
     * projection which is already visible.  Implementations should persist/forward this
     * diagnostic, but must not throw from the callback.
     */
    private val completionFailureLogger: (String) -> Unit = {},
) {
    // A re-entrant common lock covers both the synchronous compatibility API and queued
    // coroutine admission.  A coroutine-only Mutex would leave direct [accept] calls outside the
    // serialization domain and let a draft switch race an aggregate commit receipt.
    private val reducerLock = reentrantLock()
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    fun dispatch(intent: Intent) {
        scope.launch {
            acceptQueued(intent)
        }
    }

    suspend fun acceptQueued(intent: Intent): Boolean =
        acceptQueuedWithState(intent).accepted

    /**
     * Serialized reducer admission receipt.  Callers which own an external resource (for
     * example a direct-upload reservation) must not infer acceptance from [dispatch] returning;
     * this method keeps the reducer lock through the reversible transition hooks and the
     * post-commit completion/release phase.  Failures before irreversible finalization are
     * propagated after restoring the previous snapshot.  Once a finalizer has been invoked,
     * however, the outcome is committed/unknown: rolling back could restore a projection which
     * points at already-retired native bytes.  Such failures are reported diagnostically, the
     * post-transition state is retained, and the caller receives an accepted receipt so it does
     * not compensate an external resource which may already be referenced durably.
     */
    suspend fun acceptQueuedWithState(intent: Intent): MviDispatchReceipt<State> =
        acceptWithState(intent)

    fun accept(intent: Intent): Boolean = acceptWithState(intent).accepted

    /**
     * Synchronous serialized admission receipt for callers which already own a callback lane.
     * Keep this internal: the receipt is an implementation seam for shared stores that must bind
     * an external-resource publication to the exact post-reducer state, not a public UI API.
     */
    internal fun acceptWithState(intent: Intent): MviDispatchReceipt<State> =
        reducerLock.withLock { acceptWithStateLocked(intent) }

    /** The caller must hold [reducerLock]. */
    private fun acceptWithStateLocked(intent: Intent): MviDispatchReceipt<State> {
        val previous = _state.value
        // A slice is allowed to keep a tiny amount of process-local bookkeeping (for example a
        // debounced draft write or a staged attachment).  Capture that bookkeeping *before*
        // reduce: a reducer must remain observationally pure from the runtime's point of view,
        // but older slices predate this contract and still consume a staging slot while reducing.
        // The snapshot is also the persistence undo record.  It lets a later slice failure put
        // both memory and durable storage back to the exact pre-transition image, including an
        // absent key versus a key containing an empty string.
        val preReduceSnapshots = mutableListOf<MviPersistSliceTransitionSnapshot?>()
        fun rollbackSlices(primary: Throwable? = null): Throwable? {
            var firstRollbackFailure: Throwable? = null
            preReduceSnapshots.asReversed().forEach { snapshot ->
                if (snapshot == null) return@forEach
                try {
                    snapshot.rollback()
                } catch (rollback: Throwable) {
                    if (firstRollbackFailure == null) {
                        firstRollbackFailure = rollback
                    } else {
                        firstRollbackFailure?.addSuppressed(rollback)
                    }
                } finally {
                    // A rollback implementation normally releases its own gate in a finally
                    // block.  Invoke the explicit idempotent release hook as a second line of
                    // defence: a faulty rollback must not strand a process-wide admission gate,
                    // and release failures are retained as cleanup evidence below.
                    try {
                        snapshot.release()
                    } catch (release: Throwable) {
                        if (firstRollbackFailure == null) {
                            firstRollbackFailure = release
                        } else {
                            firstRollbackFailure?.addSuppressed(release)
                        }
                    }
                }
            }
            firstRollbackFailure?.let { rollback ->
                if (primary != null) {
                    // Never replace the transition's primary failure with a cleanup failure.  A
                    // caller needs the operation that failed in order to choose compensation;
                    // the rollback error remains visible as suppressed evidence.
                    primary.addSuppressed(rollback)
                }
            }
            return firstRollbackFailure
        }
        val reducedAndNext =
            try {
                val reduced = reduce(previous, intent)
                persistSlices.forEach { slice ->
                    preReduceSnapshots +=
                        slice.captureTransitionState(
                            previous = previous,
                            intent = intent,
                            reduced = reduced
                        )
                }
                persistSlices.foldIndexed(reduced) { index, state, slice ->
                    slice.reduceWithTransition(
                        previous = previous,
                        intent = intent,
                        reduced = state,
                        snapshot = preReduceSnapshots.getOrNull(index)
                    )
                }
            } catch (error: Throwable) {
                rollbackSlices(primary = error)
                throw error
            }
        val next = reducedAndNext
        val durableOnlyMutationAndEquivalent =
            try {
                // These callbacks are supplied by feature code and are part of transition
                // admission.  They may inspect (or defensively repair) a snapshot, so an
                // exception here must use the same undo path as reducer/slice failures rather
                // than strand the captured transaction.
                val durableOnlyMutation = preReduceSnapshots.any { snapshot ->
                    snapshot?.requiresApplyOnEquivalent() == true
                }
                durableOnlyMutation to equivalent(previous, next)
            } catch (error: Throwable) {
                rollbackSlices(primary = error)
                throw error
            }
        val durableOnlyMutation = durableOnlyMutationAndEquivalent.first
        val equivalentState = durableOnlyMutationAndEquivalent.second
        if (equivalentState && !durableOnlyMutation) {
            // A no-op must not consume a staged attachment, cancel a pending write, or delete a
            // malformed persisted snapshot as an unobservable side effect.
            rollbackSlices()?.let { rollback ->
                throw MviTransitionRollbackFailure(rollback)
            }
            try {
                transitionLogger(MviTransition(intent = intent, emitted = false, reason = "unchanged"))
            } catch (logger: Throwable) {
                // No-op logging is diagnostic only.  The prior state and durable image have
                // already been restored; do not leak a logger exception to an owner which could
                // mistake it for a rejected external operation requiring compensation.
                reportCompletionFailure(phase = "transition_logger_noop", failure = logger)
            }
            return MviDispatchReceipt(
                accepted = false,
                state = previous,
                previousState = previous
            )
        }
        _state.value = next
        // From this point until all finalizers return, failures in apply/commit are still safely
        // reversible.  Keep that region separate from finalization: a finalizer may retire a
        // native temporary file before throwing, so ordinary rollback is no longer sound.
        try {
            persistSlices.forEachIndexed { index, slice ->
                slice.applyTransition(
                    previous = previous,
                    intent = intent,
                    next = next,
                    snapshot = preReduceSnapshots.getOrNull(index)
                )
            }
            // Durable side effects which are safe to defer (notably temporary-file ownership)
            // are committed only after every slice has accepted the transition.
            var firstCommitFailure: Throwable? = null
            preReduceSnapshots.forEach { snapshot ->
                if (snapshot == null) return@forEach
                try {
                    // `commit` is the reversible prepare phase.  Irreversible work (for example
                    // quarantining a temporary file) belongs in `finalize`, after every slice
                    // has accepted the transition.  Keeping this distinction here means a
                    // later slice failure can still roll back an earlier slice's durable write.
                    snapshot.commit()
                } catch (commit: Throwable) {
                    if (firstCommitFailure == null) {
                        firstCommitFailure = commit
                    } else {
                        firstCommitFailure?.addSuppressed(commit)
                    }
                }
            }
            firstCommitFailure?.let { throw it }
        } catch (error: Throwable) {
            // Reducer state is a projection, not an acknowledgement, but this region has not
            // crossed the irreversible boundary.  Restore both memory and durable/slice state so
            // an owner can safely compensate the external operation and retry.
            rollbackSlices(primary = error)
            _state.value = previous
            throw error
        }

        var firstFinalizeFailure: Throwable? = null
        // Finalize in reverse registration order.  Feature slices are conventionally registered
        // before generic persistence middleware, so ownership retirement is kept as late as
        // possible relative to other reversible commits.  Mark the boundary *before* invoking a
        // finalizer: a handler can retire some refs and then throw, making even its failure path
        // an unknown outcome.  Every sibling is still attempted after a failure; a single broken
        // retirement callback must not silently skip another snapshot's cleanup.
        for (snapshot in preReduceSnapshots.asReversed()) {
            if (snapshot == null) continue
            try {
                snapshot.markFinalizeAttempted()
            } catch (marker: Throwable) {
                // The marker is the terminal fence itself.  If setting it fails, the snapshot's
                // outcome is already unknown, so do not invoke its finalizer without a proven
                // fence; continue with all siblings and never roll back this transition.
                if (firstFinalizeFailure == null) {
                    firstFinalizeFailure = marker
                } else {
                    firstFinalizeFailure?.addSuppressed(marker)
                }
                continue
            }
            try {
                snapshot.finalize()
            } catch (finalize: Throwable) {
                if (firstFinalizeFailure == null) {
                    firstFinalizeFailure = finalize
                } else {
                    firstFinalizeFailure?.addSuppressed(finalize)
                }
            }
        }

        if (firstFinalizeFailure != null) {
            // Do not call rollback after any finalizer invocation.  A successful sibling may have
            // retired bytes while the failing sibling's callback was only partially complete;
            // restoring the old durable image would then create a dangling fileRef.  Release the
            // process-wide admission gate without marking the transaction rejected, retain the
            // committed projection, and leave an explicit unknown-outcome diagnostic.
            reportPostCommitFailure(
                phase = "finalize",
                failure = firstFinalizeFailure,
                snapshots = preReduceSnapshots
            )
            return MviDispatchReceipt(
                accepted = true,
                state = next,
                previousState = previous
            )
        }

        // Every finalizer has succeeded, so transition logging is also post-commit.  A faulty
        // logger must not make an external owner compensate a reservation already referenced by
        // the durable projection; retain state and continue completion/release instead.
        try {
            transitionLogger(MviTransition(intent = intent, emitted = true))
        } catch (logger: Throwable) {
            reportPostCommitFailure(
                phase = "transition_logger",
                failure = logger,
                snapshots = emptyList()
            )
        }

        // Completion is deliberately outside the rollback region.  It runs after the transition
        // has been logged as applied and may release a process-wide admission gate.  If one
        // snapshot has completed and a later one reports an error, rolling the earlier snapshot
        // back would be impossible (and would reset the in-memory projection to a lie).  Finish
        // every completion attempt, then invoke the separate, idempotent release hook so a
        // misbehaving snapshot cannot strand a gate.  The state and durable writes remain the
        // committed post-transition image.  Completion/release failures are diagnostics only;
        // they must never escape to an owner which could mistake the committed projection for a
        // rejected operation and compensate an already-published external resource.
        preReduceSnapshots.forEach { snapshot ->
            if (snapshot == null) return@forEach
            try {
                snapshot.complete()
            } catch (completion: Throwable) {
                reportCompletionFailure(phase = "complete", completion)
            }
        }
        preReduceSnapshots.asReversed().forEach { snapshot ->
            if (snapshot == null) return@forEach
            try {
                snapshot.release()
            } catch (release: Throwable) {
                reportCompletionFailure(phase = "release", release)
            }
        }
        return MviDispatchReceipt(
            accepted = true,
            state = next,
            previousState = previous
        )
    }

    /**
     * Handle an irreversible-finalization failure without entering the ordinary rollback path.
     * The snapshots are intentionally only released: a failed finalizer may have retired a
     * subset of its refs, and [complete] can require a successful finalization on the remaining
     * snapshots.  Release is idempotent and is the only safe common operation for all snapshots
     * in this committed/unknown state.
     */
    private fun reportPostCommitFailure(
        phase: String,
        failure: Throwable?,
        snapshots: List<MviPersistSliceTransitionSnapshot?>
    ) {
        failure?.let { reportCompletionFailure(phase = phase, failure = it) }
        snapshots.asReversed().forEach { snapshot ->
            if (snapshot == null) return@forEach
            try {
                snapshot.release()
            } catch (release: Throwable) {
                reportCompletionFailure(phase = "${phase}_release", failure = release)
            }
        }
    }

    private fun reportCompletionFailure(phase: String, failure: Throwable) {
        // The callback is an observation seam, not another transition gate.  Catch a faulty
        // reporter as well so a logging implementation cannot recreate the post-commit exception
        // path this contract is designed to remove.
        runCatching {
            completionFailureLogger(
                "mvi_transition_completion_failure phase=$phase type=${failure.mviTypeName()}"
            )
        }
    }
}

private fun Throwable.mviTypeName(): String =
    this::class.simpleName.orEmpty().ifBlank { "Throwable" }

/**
 * Raised when a transition had no primary failure (normally an equivalent/no-op transition) but
 * one of its reversible side effects could not be rolled back.  Suppressing that error would
 * leave the caller with a false no-op receipt and an unobservable durable mutation.
 */
class MviTransitionRollbackFailure(
    cause: Throwable
) : IllegalStateException("mvi_transition_rollback_failed", cause)

/** Result of a serialized MVI transition, including the exact post-transition snapshot. */
data class MviDispatchReceipt<State>(
    val accepted: Boolean,
    val state: State,
    /** Exact reducer state observed immediately before this serialized transition. */
    val previousState: State = state,
)

interface MviPersistSlice<State, Intent> {
    fun reduce(previous: State, intent: Intent, reduced: State): State = reduced

    /**
     * Variant of [reduce] which receives the transition's captured undo record.  The default
     * keeps existing slices source-compatible; persistence middleware uses the record to defer
     * malformed-hydration cleanup until the transactional apply phase without a process-global
     * side channel.
     */
    fun reduceWithTransition(
        previous: State,
        intent: Intent,
        reduced: State,
        snapshot: MviPersistSliceTransitionSnapshot?
    ): State = reduce(previous = previous, intent = intent, reduced = reduced)

    /**
     * Capture a reversible transition state after the base reducer has run but before this
     * slice's [reduce] hook runs.  The reduced snapshot is supplied so a persistence slice can
     * derive a key from identity introduced by the intent (for example a newly selected server).
     */
    fun captureTransitionState(
        previous: State,
        intent: Intent,
        reduced: State
    ): MviPersistSliceTransitionSnapshot? = null

    fun onTransition(previous: State, intent: Intent, next: State) = Unit

    /**
     * Apply a transition after the next state has been reduced.  The captured undo record is
     * passed back to the slice so shared slice instances can safely serve more than one runtime.
     * The default preserves the old callback API.
     */
    fun applyTransition(
        previous: State,
        intent: Intent,
        next: State,
        snapshot: MviPersistSliceTransitionSnapshot?
    ) {
        onTransition(previous = previous, intent = intent, next = next)
    }
}

/**
 * An MVI slice's pre-transition undo record.  Both operations must be idempotent: a commit can
 * fail after an earlier slice committed, and the runtime consequently asks every slice to roll
 * back.  Implementations should retain their pre-transition snapshot until [commit] returns.
 */
interface MviPersistSliceTransitionSnapshot {
    /**
     * Reversible prepare/commit phase.  Implementations must not make an operation impossible to
     * undo here; the runtime may still call [rollback] if another slice rejects the transition.
     */
    fun commit()

    fun rollback()

    /**
     * Mark the irreversible-finalization boundary immediately before [finalize] is invoked.
     * The default is intentionally a no-op for source compatibility with existing slices; a
     * resource-owning snapshot may override it to fence rollback even when its finalizer throws.
     * Once this method has been attempted, the runtime treats the transition as committed/unknown
     * and will never restore the pre-transition projection.
     */
    fun markFinalizeAttempted() = Unit

    /**
     * Final phase for side effects which can only be performed after all slices have committed.
     * The default keeps existing snapshots source-compatible.
     */
    fun finalize() = Unit

    /**
     * Complete a successfully finalized transaction.  Completion is outside the rollback
     * region: an implementation may report a failure, but the runtime will keep the committed
     * state and continue completion/release for every sibling snapshot.
     */
    fun complete() = Unit

    /**
     * Release any process-wide admission gate after completion was attempted.  This hook is
     * idempotent and should be non-throwing; the runtime still catches a violation so a faulty
     * implementation cannot trigger rollback after another snapshot has completed.
     */
    fun release() = Unit

    /**
     * Some transitions intentionally change only durable state (for example, removing a
     * malformed hydration envelope while the in-memory reducer remains equal).  Such a snapshot
     * must still pass through apply/commit even though the UI state is structurally unchanged.
     */
    fun requiresApplyOnEquivalent(): Boolean = false
}

class MviStateHolder<UiState, UiAction, StoreIntent, UiEvent>(
    initialState: UiState,
    private val scope: CoroutineScope,
    reduce: (UiState, StoreIntent) -> UiState,
    private val handleAction: suspend MviActionScope<StoreIntent, UiEvent>.(UiAction) -> Unit,
    persistSlices: List<MviPersistSlice<UiState, StoreIntent>> = emptyList(),
    transitionLogger: (MviTransition<StoreIntent>) -> Unit = {},
    equivalent: (UiState, UiState) -> Boolean = { previous, next -> previous == next },
    completionFailureLogger: (String) -> Unit = {}
) : SharedStateHolder<UiState, UiAction, UiEvent> {
    private val runtime =
        MviStoreRuntime(
            initialState = initialState,
            scope = scope,
            reduce = reduce,
            persistSlices = persistSlices,
            transitionLogger = transitionLogger,
            completionFailureLogger = completionFailureLogger,
            equivalent = equivalent
        )
    private val effectEmitter = MviEffectEmitter<UiEvent>()

    override val uiState: StateFlow<UiState> = runtime.state
    override val uiEvents: Flow<UiEvent> = effectEmitter.effects

    override fun dispatch(action: UiAction) {
        scope.launch {
            MviActionScope(runtime::acceptQueued, effectEmitter::emit).handleAction(action)
        }
    }

    fun accept(intent: StoreIntent): Boolean = runtime.accept(intent)
}

class MviActionScope<StoreIntent, UiEvent>(
    private val acceptIntent: suspend (StoreIntent) -> Boolean,
    private val emitEvent: (UiEvent) -> Boolean
) {
    suspend fun reduce(intent: StoreIntent): Boolean = acceptIntent(intent)

    fun emit(event: UiEvent): Boolean = emitEvent(event)
}

data class MviTransition<Intent>(
    val intent: Intent,
    val emitted: Boolean,
    val reason: String? = null,
    val outcome: MviTransitionOutcome = if (emitted) MviTransitionOutcome.Applied else MviTransitionOutcome.Noop
)

enum class MviTransitionOutcome(val wireValue: String) {
    Applied("applied"),
    Noop("noop"),
    Conflict("conflict")
}

class MviEffectEmitter<Effect>(
    extraBufferCapacity: Int = 64
) {
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = extraBufferCapacity)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun emit(effect: Effect): Boolean = _effects.tryEmit(effect)
}

data class MviStaleToken(
    val value: Long
) {
    fun isCurrent(current: MviStaleToken): Boolean = value == current.value

    companion object {
        val Initial = MviStaleToken(0)
    }
}

/**
 * Store-facing key for idempotent mutations or loads. It is deliberately a tiny
 * value object so features can decide the business-specific key granularity.
 */
data class MviActionKey(val value: String)

class MviInFlightGuard {
    private val activeKeys = mutableSetOf<MviActionKey>()

    fun tryStart(key: MviActionKey): Boolean = activeKeys.add(key)

    fun finish(key: MviActionKey) {
        activeKeys.remove(key)
    }

    fun isActive(key: MviActionKey): Boolean = key in activeKeys
}

fun DataResult<*>.failureCodeOrNull(): String? =
    when (this) {
        is DataResult.Content -> null
        is DataResult.Empty -> reason.name
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
    }
