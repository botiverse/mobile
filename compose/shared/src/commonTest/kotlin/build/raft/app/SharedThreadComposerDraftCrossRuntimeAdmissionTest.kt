package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MviPersistSlice
import build.raft.app.data.MviPersistSliceTransitionSnapshot
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * iOS crash c2d27ebd: every thread composer view model has its own MVI runtime but shares the
 * process-wide draft persist slice.  A send completion dispatched `CompleteSend` from the send
 * scope's background thread while the user typed; the main thread's `Change` then hit the
 * store-wide transition gate, threw `transition_conflict` out of `dispatch`, and aborted the app.
 * Sibling runtimes must wait for each other's transition instead.
 */
class SharedThreadComposerDraftCrossRuntimeAdmissionTest {
    @Test
    fun siblingRuntimeTransitionOnAnotherThreadIsWaitedForInsteadOfConflicting() = runBlocking {
        val storage = InMemoryRaftKeyValueStore()
        val sharedSlice = SharedThreadComposerDraftPersistSlice(SharedThreadComposerDraftPersistence(storage))
        val holding = atomic(false)
        val releaseHold = atomic(false)
        val holdingSlice =
            object : MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent> {
                override fun captureTransitionState(
                    previous: SharedThreadComposerDraftState,
                    intent: SharedThreadComposerDraftIntent,
                    reduced: SharedThreadComposerDraftState
                ): MviPersistSliceTransitionSnapshot? = null

                override fun applyTransition(
                    previous: SharedThreadComposerDraftState,
                    intent: SharedThreadComposerDraftIntent,
                    next: SharedThreadComposerDraftState,
                    snapshot: MviPersistSliceTransitionSnapshot?
                ) {
                    if (intent !is SharedThreadComposerDraftIntent.Change || intent.seedText != HoldText) return
                    // Keep the shared slice's durable transaction open, as a slow background
                    // transition would, until the sibling runtime has tried to admit its own.
                    holding.value = true
                    val started = TimeSource.Monotonic.markNow()
                    while (!releaseHold.value) {
                        check(started.elapsedNow() < 10.seconds) { "hold was never released" }
                    }
                }
            }
        val sendCompletionRuntime = mountStore(listOf(sharedSlice, holdingSlice), "send-completion")
        val typingRuntime = mountStore(listOf(sharedSlice), "typing")

        withTimeout(20.seconds) {
            val held =
                async(Dispatchers.Default) {
                    sendCompletionRuntime.dispatch(SharedThreadComposerDraftIntent.Change(seedText = HoldText))
                }
            while (!holding.value) delay(1)

            val typed =
                async(Dispatchers.Default) {
                    typingRuntime.dispatch(SharedThreadComposerDraftIntent.Change(seedText = TypedText))
                }
            delay(100)
            assertFalse(typed.isCompleted, "the sibling transition must wait for the open one")

            releaseHold.value = true
            held.await()
            typed.await()
        }

        assertEquals(TypedText, typingRuntime.state.value.seedText)
        assertEquals(HoldText, sendCompletionRuntime.state.value.seedText)
    }

    private fun mountStore(
        slices: List<MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent>>,
        instanceId: String
    ): SharedThreadComposerDraftStore =
        SharedThreadComposerDraftStore(persistSlices = slices).also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer =
                        RaftThreadComposerPayload(
                            instanceId = instanceId,
                            seedText = "",
                            seedAsTask = false
                        ),
                    draftKey = DraftKey
                )
            )
        }

    private companion object {
        const val DraftKey = "send=race-channel|parent=_|mention=race-channel"
        const val HoldText = "held by the send completion"
        const val TypedText = "typed meanwhile"
    }
}
