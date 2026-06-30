package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val equivalent: (State, State) -> Boolean = { previous, next -> previous == next }
) {
    private val reducerMutex = Mutex()
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    fun dispatch(intent: Intent) {
        scope.launch {
            acceptQueued(intent)
        }
    }

    suspend fun acceptQueued(intent: Intent): Boolean =
        reducerMutex.withLock {
            accept(intent)
        }

    fun accept(intent: Intent): Boolean {
        val previous = _state.value
        val reduced = reduce(previous, intent)
        val next =
            persistSlices.fold(reduced) { state, slice ->
                slice.reduce(previous = previous, intent = intent, reduced = state)
            }
        if (equivalent(previous, next)) {
            transitionLogger(MviTransition(intent = intent, emitted = false, reason = "unchanged"))
            return false
        }
        _state.value = next
        persistSlices.forEach { slice ->
            slice.onTransition(previous = previous, intent = intent, next = next)
        }
        transitionLogger(MviTransition(intent = intent, emitted = true))
        return true
    }
}

interface MviPersistSlice<State, Intent> {
    fun reduce(previous: State, intent: Intent, reduced: State): State = reduced

    fun onTransition(previous: State, intent: Intent, next: State) = Unit
}

class MviStateHolder<UiState, UiAction, StoreIntent, UiEvent>(
    initialState: UiState,
    private val scope: CoroutineScope,
    reduce: (UiState, StoreIntent) -> UiState,
    private val handleAction: suspend MviActionScope<StoreIntent, UiEvent>.(UiAction) -> Unit,
    persistSlices: List<MviPersistSlice<UiState, StoreIntent>> = emptyList(),
    transitionLogger: (MviTransition<StoreIntent>) -> Unit = {},
    equivalent: (UiState, UiState) -> Boolean = { previous, next -> previous == next }
) : SharedStateHolder<UiState, UiAction, UiEvent> {
    private val runtime =
        MviStoreRuntime(
            initialState = initialState,
            scope = scope,
            reduce = reduce,
            persistSlices = persistSlices,
            transitionLogger = transitionLogger,
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
    val reason: String? = null
)

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
