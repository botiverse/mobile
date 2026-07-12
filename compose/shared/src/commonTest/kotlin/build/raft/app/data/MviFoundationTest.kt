package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        assertEquals(MviTransitionOutcome.Applied, transitions.single().outcome)
        assertEquals("applied", transitions.single().outcome.wireValue)
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
        assertEquals(MviTransitionOutcome.Noop, transitions.single().outcome)
        assertEquals("noop", transitions.single().outcome.wireValue)
        assertEquals("conflict", MviTransitionOutcome.Conflict.wireValue)
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

    @Test
    fun persistMiddlewareWritesPartialSnapshotAndHydratesBeforeNetworkState() {
        val storage = InMemorySlockKeyValueStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstRuntime =
            persistRuntime(
                scope = scope,
                storage = storage,
                initialState = PersistState(value = 0, transientLabel = "initial")
            )

        firstRuntime.accept(PersistIntent.SetValue(7, "network"))

        val stored = assertNotNull(storage.getString("persist:test"))
        assertTrue(stored.contains("\"version\":1"))
        assertTrue(stored.contains("\"value\":7"))
        assertFalse(stored.contains("network"))

        val secondRuntime =
            persistRuntime(
                scope = scope,
                storage = storage,
                initialState = PersistState(value = 0, transientLabel = "cold")
            )

        secondRuntime.accept(PersistIntent.Start)

        assertEquals(PersistState(value = 7, transientLabel = "refreshing"), secondRuntime.state.value)
        scope.cancel()
    }

    @Test
    fun persistMiddlewareDropsUnsupportedVersionWithoutMigration() {
        val storage =
            InMemorySlockKeyValueStore(
                mapOf("persist:test" to """{"version":99,"snapshot":{"value":42}}""")
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            persistRuntime(
                scope = scope,
                storage = storage,
                initialState = PersistState(value = 0, transientLabel = "cold")
            )

        runtime.accept(PersistIntent.Start)

        assertEquals(PersistState(value = 0, transientLabel = "refreshing"), runtime.state.value)
        assertNull(storage.getString("persist:test"))
        scope.cancel()
    }

    @Test
    fun persistMiddlewareKeepsStoreTransitionAliveWhenSnapshotEncodingFails() {
        val previousSnapshot = """{"version":1,"snapshot":{"value":3}}"""
        val storage =
            InMemorySlockKeyValueStore(
                mapOf("persist:test" to previousSnapshot)
            )
        val diagnostics = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            persistRuntime(
                scope = scope,
                storage = storage,
                initialState = PersistState(value = 0, transientLabel = "initial"),
                persistSnapshotSerializer = NumberFormatFailingPersistSnapshotSerializer,
                diagnosticsLogger = diagnostics::add,
                failureLogger = failures::add
            )

        assertTrue(runtime.accept(PersistIntent.SetValue(7, "network")))

        assertEquals(PersistState(value = 7, transientLabel = "network"), runtime.state.value)
        assertEquals(previousSnapshot, storage.getString("persist:test"))
        assertTrue(diagnostics.isEmpty())
        assertTrue(
            failures.any {
                it == "mvi_persist_skip name=test key=persist:test reason=encode_error type=NumberFormatException"
            }
        )
        scope.cancel()
    }

    @Test
    fun persistMiddlewareKeepsStoreTransitionAliveWhenStorageWriteFails() {
        val previousSnapshot = """{"version":1,"snapshot":{"value":3}}"""
        val storage = ThrowingWritePreferenceStore(previousSnapshot)
        val diagnostics = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            persistRuntime(
                scope = scope,
                storage = storage,
                initialState = PersistState(value = 0, transientLabel = "initial"),
                diagnosticsLogger = diagnostics::add,
                failureLogger = failures::add
            )

        assertTrue(runtime.accept(PersistIntent.SetValue(9, "network")))

        assertEquals(PersistState(value = 9, transientLabel = "network"), runtime.state.value)
        assertEquals(previousSnapshot, storage.getString("persist:test"))
        assertTrue(diagnostics.isEmpty())
        assertTrue(
            failures.any {
                it == "mvi_persist_skip name=test key=persist:test reason=write_error type=IllegalStateException"
            }
        )
        scope.cancel()
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

    private data class PersistState(
        val value: Int,
        val transientLabel: String
    )

    private sealed interface PersistIntent {
        data object Start : PersistIntent
        data class SetValue(val value: Int, val label: String) : PersistIntent
    }

    @Serializable
    private data class PersistSnapshot(val value: Int)

    private fun persistRuntime(
        scope: CoroutineScope,
        storage: SharedPreferenceStore,
        initialState: PersistState,
        persistSnapshotSerializer: KSerializer<PersistSnapshot> = PersistSnapshot.serializer(),
        diagnosticsLogger: (String) -> Unit = {},
        failureLogger: (String) -> Unit = diagnosticsLogger
    ): MviStoreRuntime<PersistState, PersistIntent> =
        MviStoreRuntime(
            initialState = initialState,
            scope = scope,
            reduce = { state, intent ->
                when (intent) {
                    PersistIntent.Start -> state.copy(transientLabel = "refreshing")
                    is PersistIntent.SetValue -> PersistState(intent.value, intent.label)
                }
            },
            persistSlices =
                listOf(
                    MviPersistMiddleware(
                        storage = SharedPreferencePersistStorage(storage),
                        diagnosticsLogger = diagnosticsLogger,
                        failureLogger = failureLogger,
                        spec =
                            object : MviPersistSpec<PersistState, PersistIntent, PersistSnapshot> {
                                override val name: String = "test"
                                override val version: Int = 1
                                override val snapshotSerializer = persistSnapshotSerializer

                                override fun key(
                                    previous: PersistState,
                                    intent: PersistIntent,
                                    reduced: PersistState
                                ): String = "persist:test"

                                override fun partialize(state: PersistState): PersistSnapshot =
                                    PersistSnapshot(state.value)

                                override fun mergeHydrated(
                                    previous: PersistState,
                                    intent: PersistIntent,
                                    reduced: PersistState,
                                    snapshot: PersistSnapshot,
                                    metadata: MviPersistMetadata
                                ): PersistState = reduced.copy(value = snapshot.value)

                                override fun shouldHydrate(
                                    previous: PersistState,
                                    intent: PersistIntent,
                                    reduced: PersistState
                                ): Boolean = intent is PersistIntent.Start

                                override fun shouldPersist(
                                    previous: PersistState,
                                    intent: PersistIntent,
                                    next: PersistState
                                ): Boolean = intent is PersistIntent.SetValue
                            }
                    )
                )
        )

    private object NumberFormatFailingPersistSnapshotSerializer :
        KSerializer<PersistSnapshot> by PersistSnapshot.serializer() {
        override fun serialize(encoder: Encoder, value: PersistSnapshot) {
            throw NumberFormatException("invalid numeric literal")
        }
    }

    private class ThrowingWritePreferenceStore(initialValue: String) : SharedPreferenceStore {
        private var value: String? = initialValue

        override fun getString(key: String): String? = value

        override fun putString(key: String, value: String?) {
            if (value != null) {
                throw IllegalStateException("write failed")
            }
            this.value = null
        }
    }

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
