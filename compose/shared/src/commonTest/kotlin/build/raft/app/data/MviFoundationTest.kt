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
import kotlinx.serialization.json.JsonEncodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun noOpTransitionLoggerFailureIsDiagnosticAndKeepsPriorState() {
        val diagnostics = mutableListOf<String>()
        var loggerCalls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            MviStoreRuntime<CounterState, CounterIntent>(
                initialState = CounterState(),
                scope = scope,
                reduce = { state, intent ->
                    when (intent) {
                        CounterIntent.Increment -> state.copy(count = state.count + 1)
                        CounterIntent.Noop -> state
                    }
                },
                transitionLogger = {
                    loggerCalls += 1
                    throw IllegalStateException("noop logger failed")
                },
                completionFailureLogger = diagnostics::add
            )

        val accepted = runtime.accept(CounterIntent.Noop)

        assertFalse(accepted)
        assertEquals(CounterState(), runtime.state.value)
        assertEquals(1, loggerCalls)
        assertEquals(
            listOf(
                "mvi_transition_completion_failure phase=transition_logger_noop type=IllegalStateException"
            ),
            diagnostics
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

    @Test
    fun persistMiddlewareWritesPartialSnapshotAndHydratesBeforeNetworkState() {
        val storage = InMemoryRaftKeyValueStore()
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
            InMemoryRaftKeyValueStore(
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
    fun persistMiddlewareRollsBackStoreTransitionWhenSnapshotEncodingFails() {
        val previousSnapshot = """{"version":1,"snapshot":{"value":3}}"""
        val storage =
            InMemoryRaftKeyValueStore(
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

        val failure =
            runCatching { runtime.accept(PersistIntent.SetValue(7, "network")) }
                .exceptionOrNull()

        assertIs<MviPersistFailure>(failure)
        // kotlinx-serialization 1.12 wraps any exception a serializer throws while encoding in a
        // JsonEncodingException; the serializer's own exception stays the cause.
        val encodingFailure = assertIs<JsonEncodingException>(failure.cause)
        assertIs<NumberFormatException>(encodingFailure.cause)
        assertEquals(PersistState(value = 0, transientLabel = "initial"), runtime.state.value)
        assertEquals(previousSnapshot, storage.getString("persist:test"))
        assertTrue(
            diagnostics.any {
                it == "mvi_persist_transaction_rolled_back name=test key=persist:test"
            }
        )
        assertTrue(
            failures.any {
                it == "mvi_persist_failure name=test key=persist:test reason=encode_error type=JsonEncodingException"
            }
        )
        scope.cancel()
    }

    @Test
    fun persistMiddlewareRollsBackStoreTransitionWhenStorageWriteFails() {
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

        val failure =
            runCatching { runtime.accept(PersistIntent.SetValue(9, "network")) }
                .exceptionOrNull()

        assertIs<MviPersistFailure>(failure)
        assertEquals(PersistState(value = 0, transientLabel = "initial"), runtime.state.value)
        assertEquals(previousSnapshot, storage.getString("persist:test"))
        assertTrue(
            diagnostics.any {
                it == "mvi_persist_transaction_rolled_back name=test key=persist:test"
            }
        )
        assertTrue(
            failures.any {
                it == "mvi_persist_failure name=test key=persist:test reason=write_error type=IllegalStateException"
            }
        )
        scope.cancel()
    }

    @Test
    fun requiresApplyOnEquivalentFailureRollsBackCapturedSnapshots() {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices =
                    listOf(
                        recordingSlice(
                            events,
                            "predicate",
                            requiresApplyFailure = true,
                            rollbackFailure = true
                        )
                    )
            )

        val failure = runCatching { runtime.accept(LifecycleIntent.Apply) }.exceptionOrNull()

        assertEquals("predicate.requiresApplyOnEquivalent", failure?.message)
        assertEquals(LifecycleState(), runtime.state.value)
        assertEquals(
            listOf(
                "predicate.requiresApplyOnEquivalent",
                "predicate.rollback",
                "predicate.release"
            ),
            events
        )
        // `suppressedExceptions` is the common-source accessor; keep the strong contract that a
        // rollback failure is attached to (rather than silently replacing or dropping) the
        // primary transition failure.
        assertTrue(
            failure?.suppressedExceptions?.any { suppressed -> suppressed.message == "predicate.rollback" } == true
        )
        scope.cancel()
    }

    @Test
    fun equivalentFailureRollsBackCapturedSnapshotsBeforeRethrowing() {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices = listOf(recordingSlice(events, "equivalent")),
                equivalent = { _, _ -> throw IllegalArgumentException("equivalent failed") }
            )

        val failure = runCatching { runtime.accept(LifecycleIntent.Apply) }.exceptionOrNull()

        assertEquals("equivalent failed", failure?.message)
        assertEquals(LifecycleState(), runtime.state.value)
        assertEquals(
            listOf("equivalent.rollback", "equivalent.release"),
            events
        )
        scope.cancel()
    }

    @Test
    fun laterCommitFailureRollsBackEverySnapshotInReverseOrder() {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices =
                    listOf(
                        recordingSlice(events, "first"),
                        recordingSlice(events, "second", commitFailure = true),
                        recordingSlice(events, "third")
                    )
            )

        val failure = runCatching { runtime.accept(LifecycleIntent.Apply) }.exceptionOrNull()

        assertEquals("second.commit", failure?.message)
        assertEquals(LifecycleState(), runtime.state.value)
        assertEquals(
            listOf(
                "first.commit",
                "second.commit",
                "third.commit",
                "third.rollback",
                "third.release",
                "second.rollback",
                "second.release",
                "first.rollback",
                "first.release"
            ),
            events
        )
        scope.cancel()
    }

    @Test
    fun finalizeFailureAfterEarlierSuccessKeepsCommittedProjectionAndReleasesGate() = runBlocking {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var firstFinalizationAttempt = 0
        val firstSlice =
            object : MviPersistSlice<LifecycleState, LifecycleIntent> {
                override fun captureTransitionState(
                    previous: LifecycleState,
                    intent: LifecycleIntent,
                    reduced: LifecycleState
                ): MviPersistSliceTransitionSnapshot =
                    RecordingSnapshot(
                        name = "first",
                        events = events,
                        // Finalization runs in reverse registration order.  The second slice
                        // therefore finalizes successfully before this first slice throws.
                        finalizeFailure = firstFinalizationAttempt++ == 0
                    )
            }
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices = listOf(firstSlice, recordingSlice(events, "second")),
                completionFailureLogger = diagnostics::add
            )

        val firstReceipt = runtime.acceptQueuedWithState(LifecycleIntent.Apply)

        assertTrue(firstReceipt.accepted)
        assertEquals(LifecycleState(value = 1), firstReceipt.state)
        assertEquals(firstReceipt.state, runtime.state.value)
        assertEquals(
            listOf(
                "first.commit",
                "second.commit",
                "second.finalize",
                "first.finalize",
                "second.release",
                "first.release"
            ),
            events
        )
        assertTrue("first.rollback" !in events)
        assertTrue("second.rollback" !in events)
        assertEquals(
            listOf(
                "mvi_transition_completion_failure phase=finalize type=IllegalStateException"
            ),
            diagnostics
        )

        // A post-finalize failure must not strand the admission gate.  The next transition is
        // accepted and runs the complete/release phase normally once finalization succeeds.
        val retryReceipt = runtime.acceptQueuedWithState(LifecycleIntent.Apply)

        assertTrue(retryReceipt.accepted)
        assertEquals(LifecycleState(value = 2), retryReceipt.state)
        assertEquals(retryReceipt.state, runtime.state.value)
        assertEquals(
            listOf(
                "first.commit",
                "second.commit",
                "second.finalize",
                "first.finalize",
                "second.release",
                "first.release",
                "first.commit",
                "second.commit",
                "second.finalize",
                "first.finalize",
                "first.complete",
                "second.complete",
                "second.release",
                "first.release"
            ),
            events
        )
        scope.cancel()
    }

    @Test
    fun finalizeFailureContinuesReverseOrderForEverySibling() = runBlocking {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices =
                    listOf(
                        recordingSlice(events, "first", recordFinalizeMarker = true),
                        recordingSlice(
                            events,
                            "middle",
                            finalizeFailure = true,
                            recordFinalizeMarker = true
                        ),
                        recordingSlice(events, "third", recordFinalizeMarker = true)
                    ),
                completionFailureLogger = diagnostics::add
            )

        val receipt = runtime.acceptQueuedWithState(LifecycleIntent.Apply)

        assertTrue(receipt.accepted)
        assertEquals(LifecycleState(value = 1), receipt.state)
        assertEquals(receipt.state, runtime.state.value)
        assertEquals(
            listOf(
                "first.commit",
                "middle.commit",
                "third.commit",
                "third.markFinalizeAttempted",
                "third.finalize",
                "middle.markFinalizeAttempted",
                "middle.finalize",
                "first.markFinalizeAttempted",
                "first.finalize",
                "third.release",
                "middle.release",
                "first.release"
            ),
            events
        )
        assertEquals(
            listOf(
                "mvi_transition_completion_failure phase=finalize type=IllegalStateException"
            ),
            diagnostics
        )
        assertTrue("first.rollback" !in events)
        assertTrue("middle.rollback" !in events)
        assertTrue("third.rollback" !in events)
        scope.cancel()
    }

    @Test
    fun multipleFinalizeFailuresAreAggregatedAsSuppressedDiagnostics() = runBlocking {
        val events = mutableListOf<String>()
        val diagnosticMessages = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices =
                    listOf(
                        recordingSlice(events, "first", finalizeFailure = true, recordFinalizeMarker = true),
                        recordingSlice(events, "middle", finalizeFailure = true, recordFinalizeMarker = true),
                        recordingSlice(events, "third", recordFinalizeMarker = true)
                    ),
                completionFailureLogger = { message ->
                    diagnosticMessages += message
                }
            )

        val receipt = runtime.acceptQueuedWithState(LifecycleIntent.Apply)

        assertTrue(receipt.accepted)
        assertEquals(LifecycleState(value = 1), receipt.state)
        assertEquals(1, diagnosticMessages.size)
        assertEquals(
            "mvi_transition_completion_failure phase=finalize type=IllegalStateException",
            diagnosticMessages.single()
        )
        assertEquals(
            listOf(
                "first.commit",
                "middle.commit",
                "third.commit",
                "third.markFinalizeAttempted",
                "third.finalize",
                "middle.markFinalizeAttempted",
                "middle.finalize",
                "first.markFinalizeAttempted",
                "first.finalize",
                "third.release",
                "middle.release",
                "first.release"
            ),
            events
        )
        scope.cancel()
    }

    @Test
    fun finalizeAttemptMarkerFailureIsTerminalAndStillRunsOtherSiblings() = runBlocking {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices =
                    listOf(
                        recordingSlice(events, "first", recordFinalizeMarker = true),
                        recordingSlice(
                            events,
                            "marker",
                            markerFailure = true,
                            recordFinalizeMarker = true
                        ),
                        recordingSlice(events, "third", recordFinalizeMarker = true)
                    ),
                completionFailureLogger = diagnostics::add
            )

        val receipt = runtime.acceptQueuedWithState(LifecycleIntent.Apply)

        assertTrue(receipt.accepted)
        assertEquals(LifecycleState(value = 1), receipt.state)
        assertEquals(
            listOf(
                "first.commit",
                "marker.commit",
                "third.commit",
                "third.markFinalizeAttempted",
                "third.finalize",
                "marker.markFinalizeAttempted",
                "first.markFinalizeAttempted",
                "first.finalize",
                "third.release",
                "marker.release",
                "first.release"
            ),
            events
        )
        assertEquals(
            listOf(
                "mvi_transition_completion_failure phase=finalize type=IllegalStateException"
            ),
            diagnostics
        )
        assertTrue("marker.finalize" !in events)
        assertTrue("first.rollback" !in events)
        assertTrue("marker.rollback" !in events)
        assertTrue("third.rollback" !in events)
        scope.cancel()
    }

    @Test
    fun transitionLoggerFailureAfterFinalizeKeepsCommittedProjectionAndCompletesSnapshots() =
        runBlocking {
            val events = mutableListOf<String>()
            val diagnostics = mutableListOf<String>()
            var loggerCalls = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val runtime =
                lifecycleRuntime(
                    scope = scope,
                    slices = listOf(recordingSlice(events, "only")),
                    transitionLogger = {
                        loggerCalls += 1
                        throw IllegalStateException("logger.failed")
                    },
                    completionFailureLogger = diagnostics::add
                )

            val receipt = runtime.acceptQueuedWithState(LifecycleIntent.Apply)

            assertTrue(receipt.accepted)
            assertEquals(LifecycleState(value = 1), receipt.state)
            assertEquals(receipt.state, runtime.state.value)
            assertEquals(1, loggerCalls)
            assertEquals(
                listOf("only.commit", "only.finalize", "only.complete", "only.release"),
                events
            )
            assertTrue("only.rollback" !in events)
            assertEquals(
                listOf(
                    "mvi_transition_completion_failure phase=transition_logger type=IllegalStateException"
                ),
                diagnostics
            )
            scope.cancel()
        }

    @Test
    fun completionAndReleaseFailuresAreDiagnosticsAfterCommit() = runBlocking {
        val events = mutableListOf<String>()
        val completionDiagnostics = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices =
                    listOf(
                        recordingSlice(events, "first"),
                        recordingSlice(
                            events,
                            "second",
                            completeFailure = true,
                            releaseFailure = true
                        )
                    ),
                completionFailureLogger = completionDiagnostics::add
            )

        val receipt = runtime.acceptQueuedWithState(LifecycleIntent.Apply)

        assertTrue(receipt.accepted)
        assertEquals(LifecycleState(value = 1), receipt.state)
        assertEquals(receipt.state, runtime.state.value)
        assertTrue("first.rollback" !in events)
        assertTrue("second.rollback" !in events)
        assertEquals(
            listOf(
                "first.commit",
                "second.commit",
                "second.finalize",
                "first.finalize",
                "first.complete",
                "second.complete",
                "second.release",
                "first.release"
            ),
            events
        )
        assertEquals(
            listOf(
                "mvi_transition_completion_failure phase=complete type=IllegalStateException",
                "mvi_transition_completion_failure phase=release type=IllegalStateException"
            ),
            completionDiagnostics
        )
        scope.cancel()
    }

    @Test
    fun rollbackFailureIsSuppressedUnderThePrimaryTransitionFailure() {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime =
            lifecycleRuntime(
                scope = scope,
                slices =
                    listOf(
                        recordingSlice(events, "first", rollbackFailure = true),
                        recordingSlice(events, "second", commitFailure = true)
                    )
            )

        val failure = runCatching { runtime.accept(LifecycleIntent.Apply) }.exceptionOrNull()

        assertEquals("second.commit", failure?.message)
        assertTrue(
            failure?.suppressedExceptions?.any { suppressed -> suppressed.message == "first.rollback" } == true
        )
        assertEquals(LifecycleState(), runtime.state.value)
        assertTrue("first.release" in events)
        assertTrue("second.release" in events)
        scope.cancel()
    }

    @Test
    fun aFailedTransitionReleasesAdmissionForTheNextTransition() {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var transition = 0
        val slice =
            object : MviPersistSlice<LifecycleState, LifecycleIntent> {
                override fun captureTransitionState(
                    previous: LifecycleState,
                    intent: LifecycleIntent,
                    reduced: LifecycleState
                ): MviPersistSliceTransitionSnapshot {
                    transition += 1
                    return RecordingSnapshot(
                        name = "transition$transition",
                        events = events,
                        commitFailure = transition == 1
                    )
                }
            }
        val runtime = lifecycleRuntime(scope = scope, slices = listOf(slice))

        assertEquals("transition1.commit", runCatching { runtime.accept(LifecycleIntent.Apply) }.exceptionOrNull()?.message)
        assertTrue(runtime.accept(LifecycleIntent.Apply))
        assertEquals(LifecycleState(value = 1), runtime.state.value)
        assertTrue("transition1.release" in events)
        assertTrue("transition2.release" in events)
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

    private fun lifecycleRuntime(
        scope: CoroutineScope,
        slices: List<MviPersistSlice<LifecycleState, LifecycleIntent>>,
        transitionLogger: (MviTransition<LifecycleIntent>) -> Unit = {},
        completionFailureLogger: (String) -> Unit = {},
        equivalent: (LifecycleState, LifecycleState) -> Boolean = { previous, next -> previous == next }
    ): MviStoreRuntime<LifecycleState, LifecycleIntent> =
        MviStoreRuntime(
            initialState = LifecycleState(),
            scope = scope,
            reduce = { state, intent ->
                when (intent) {
                    LifecycleIntent.Apply -> state.copy(value = state.value + 1)
                    LifecycleIntent.Noop -> state
                }
            },
            persistSlices = slices,
            transitionLogger = transitionLogger,
            completionFailureLogger = completionFailureLogger,
            equivalent = equivalent
        )

    private fun recordingSlice(
        events: MutableList<String>,
        name: String,
        commitFailure: Boolean = false,
        finalizeFailure: Boolean = false,
        completeFailure: Boolean = false,
        releaseFailure: Boolean = false,
        rollbackFailure: Boolean = false,
        markerFailure: Boolean = false,
        recordFinalizeMarker: Boolean = false,
        requiresApplyFailure: Boolean = false
    ): MviPersistSlice<LifecycleState, LifecycleIntent> =
        object : MviPersistSlice<LifecycleState, LifecycleIntent> {
            override fun captureTransitionState(
                previous: LifecycleState,
                intent: LifecycleIntent,
                reduced: LifecycleState
            ): MviPersistSliceTransitionSnapshot =
                RecordingSnapshot(
                    name = name,
                    events = events,
                    commitFailure = commitFailure,
                    finalizeFailure = finalizeFailure,
                    completeFailure = completeFailure,
                    releaseFailure = releaseFailure,
                    rollbackFailure = rollbackFailure,
                    markerFailure = markerFailure,
                    recordFinalizeMarker = recordFinalizeMarker,
                    requiresApplyFailure = requiresApplyFailure
                )
        }

    private data class LifecycleState(val value: Int = 0)

    private enum class LifecycleIntent {
        Apply,
        Noop
    }

    private class RecordingSnapshot(
        private val name: String,
        private val events: MutableList<String>,
        private val commitFailure: Boolean = false,
        private val finalizeFailure: Boolean = false,
        private val completeFailure: Boolean = false,
        private val releaseFailure: Boolean = false,
        private val rollbackFailure: Boolean = false,
        private val markerFailure: Boolean = false,
        private val recordFinalizeMarker: Boolean = false,
        private val requiresApplyFailure: Boolean = false
    ) : MviPersistSliceTransitionSnapshot {
        override fun commit() {
            events += "$name.commit"
            if (commitFailure) throw IllegalStateException("$name.commit")
        }

        override fun rollback() {
            events += "$name.rollback"
            if (rollbackFailure) throw IllegalStateException("$name.rollback")
        }

        override fun markFinalizeAttempted() {
            if (recordFinalizeMarker) events += "$name.markFinalizeAttempted"
            if (markerFailure) throw IllegalStateException("$name.markFinalizeAttempted")
        }

        override fun finalize() {
            events += "$name.finalize"
            if (finalizeFailure) throw IllegalStateException("$name.finalize")
        }

        override fun complete() {
            events += "$name.complete"
            if (completeFailure) throw IllegalStateException("$name.complete")
        }

        override fun release() {
            events += "$name.release"
            if (releaseFailure) throw IllegalStateException("$name.release")
        }

        override fun requiresApplyOnEquivalent(): Boolean {
            if (requiresApplyFailure) {
                events += "$name.requiresApplyOnEquivalent"
                throw IllegalStateException("$name.requiresApplyOnEquivalent")
            }
            return false
        }
    }

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
        private var failNextWrite = true

        override fun getString(key: String): String? = value

        override fun putString(key: String, value: String?) {
            if (value != null && failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("write failed")
            }
            this.value = value
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
