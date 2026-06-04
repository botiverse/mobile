package ai.slock.compose.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MviFoundationTest {
    @Test
    fun acceptEmitsChangedStateAndLogsTransition() {
        val transitions = mutableListOf<MviTransition<CounterIntent>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = counterRuntime(scope, transitions)

        val emitted = runtime.accept(CounterIntent.Increment)

        assertTrue(emitted)
        assertEquals(CounterState(count = 1), runtime.state.value)
        assertEquals(
            MviTransition<CounterIntent>(intent = CounterIntent.Increment, emitted = true),
            transitions.single()
        )
        scope.cancel()
    }

    @Test
    fun acceptSkipsEquivalentStateAndLogsNoop() {
        val transitions = mutableListOf<MviTransition<CounterIntent>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = counterRuntime(scope, transitions)

        val emitted = runtime.accept(CounterIntent.Noop)

        assertFalse(emitted)
        assertEquals(CounterState(count = 0), runtime.state.value)
        assertEquals(
            MviTransition<CounterIntent>(intent = CounterIntent.Noop, emitted = false, reason = "unchanged"),
            transitions.single()
        )
        scope.cancel()
    }

    @Test
    fun dispatchRunsThroughQueuedReducer() {
        val transitions = mutableListOf<MviTransition<CounterIntent>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = counterRuntime(scope, transitions)

        runtime.dispatch(CounterIntent.Increment)
        runtime.dispatch(CounterIntent.Increment)

        assertEquals(CounterState(count = 2), runtime.state.value)
        assertEquals(2, transitions.count { it.emitted })
        scope.cancel()
    }

    @Test
    fun acceptQueuedSerializesReducerCalls() = runBlocking {
        val transitions = mutableListOf<MviTransition<CounterIntent>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = counterRuntime(scope, transitions)

        val first = runtime.acceptQueued(CounterIntent.Increment)
        val second = runtime.acceptQueued(CounterIntent.Noop)

        assertTrue(first)
        assertFalse(second)
        assertEquals(CounterState(count = 1), runtime.state.value)
        assertEquals(listOf(true, false), transitions.map { it.emitted })
        scope.cancel()
    }

    @Test
    fun stateHolderDispatchesActionThroughUseCaseAndReducesState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val useCase = DemoUseCase(DataResult.Content("loaded-42"))
        val holder =
            MviStateHolder<DemoState, DemoAction, DemoIntent, DemoEvent>(
                initialState = DemoState(),
                scope = scope,
                reduce = { state, intent ->
                    when (intent) {
                        is DemoIntent.SetLabel -> state.copy(label = intent.label)
                    }
                },
                handleAction = { action ->
                    when (action) {
                        is DemoAction.Load -> {
                            when (val result = useCase.execute(action.id)) {
                                is DataResult.Content -> reduce(DemoIntent.SetLabel(result.data))
                                else -> emit(DemoEvent.Failed(result.failureCodeOrNull() ?: "unknown"))
                            }
                        }
                    }
                }
            )

        holder.dispatch(DemoAction.Load(42))

        assertEquals(DemoState(label = "loaded-42"), holder.uiState.value)
        scope.cancel()
    }

    @Test
    fun stateHolderEmitsUiEventForUseCaseFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val useCase = DemoUseCase(DataResult.Error(ScreenError("demo_failed")))
        val holder =
            MviStateHolder<DemoState, DemoAction, DemoIntent, DemoEvent>(
                initialState = DemoState(),
                scope = scope,
                reduce = { state, intent ->
                    when (intent) {
                        is DemoIntent.SetLabel -> state.copy(label = intent.label)
                    }
                },
                handleAction = { action ->
                    when (action) {
                        is DemoAction.Load -> {
                            when (val result = useCase.execute(action.id)) {
                                is DataResult.Content -> reduce(DemoIntent.SetLabel(result.data))
                                else -> emit(DemoEvent.Failed(result.failureCodeOrNull() ?: "unknown"))
                            }
                        }
                    }
                }
            )
        val events = mutableListOf<DemoEvent>()
        val eventJob =
            launch(Dispatchers.Unconfined) {
                holder.uiEvents.take(1).collect { event ->
                    events += event
                }
            }

        holder.dispatch(DemoAction.Load(404))

        assertEquals(listOf<DemoEvent>(DemoEvent.Failed("demo_failed")), events)
        eventJob.cancel()
        scope.cancel()
    }

    @Test
    fun inFlightGuardRejectsDuplicateUntilFinished() {
        val guard = MviInFlightGuard()
        val key = MviActionKey("settings:server:delete")

        assertTrue(guard.tryStart(key))
        assertTrue(guard.isActive(key))
        assertFalse(guard.tryStart(key))

        guard.finish(key)

        assertFalse(guard.isActive(key))
        assertTrue(guard.tryStart(key))
    }

    @Test
    fun effectEmitterAcceptsOneOffEffectsWithoutChangingState() {
        val emitter = MviEffectEmitter<String>(extraBufferCapacity = 1)

        assertTrue(emitter.emit("navigate:thread"))
    }

    @Test
    fun staleTokenOnlyAcceptsCurrentToken() {
        val current = MviStaleToken(2)

        assertFalse(MviStaleToken.Initial.isCurrent(current))
        assertTrue(MviStaleToken(2).isCurrent(current))
    }

    @Test
    fun dataResultFailureCodeMapsTypedOutcomes() {
        assertNull(DataResult.Content("ok").failureCodeOrNull())
        assertEquals("NoTasks", DataResult.Empty(EmptyReason.NoTasks).failureCodeOrNull())
        assertEquals(
            "network_failed",
            DataResult.Error(ScreenError("network_failed")).failureCodeOrNull()
        )
        assertEquals(
            "requires_admin",
            DataResult.NoPermission(PermissionBlocker("requires_admin")).failureCodeOrNull()
        )
    }

    private fun counterRuntime(
        scope: CoroutineScope,
        transitions: MutableList<MviTransition<CounterIntent>>
    ): MviStoreRuntime<CounterState, CounterIntent> =
        MviStoreRuntime(
            initialState = CounterState(),
            scope = scope,
            reduce = { state, intent ->
                when (intent) {
                    CounterIntent.Increment -> state.copy(count = state.count + 1)
                    CounterIntent.Noop -> state
                }
            },
            transitionLogger = transitions::add
        )

    private data class CounterState(val count: Int = 0)

    private sealed interface CounterIntent {
        data object Increment : CounterIntent
        data object Noop : CounterIntent
    }

    private data class DemoState(val label: String = "idle")

    private sealed interface DemoAction {
        data class Load(val id: Int) : DemoAction
    }

    private sealed interface DemoIntent {
        data class SetLabel(val label: String) : DemoIntent
    }

    private sealed interface DemoEvent {
        data class Failed(val code: String) : DemoEvent
    }

    private class DemoUseCase(
        private val result: DataResult<String>
    ) {
        suspend fun execute(request: Int): DataResult<String> = result
    }
}
