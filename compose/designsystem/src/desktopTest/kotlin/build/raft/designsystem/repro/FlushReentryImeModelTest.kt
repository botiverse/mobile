package build.raft.designsystem.repro

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.use
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Runnable

/**
 * Desktop model of the iOS crash path reported upstream as CMP-10674 (CMP 1.12.2 stack in its
 * last comment), applied to the Create agent name field:
 *
 * 1. a touch-down in the dialog layer: `BaseComposeScene.sendPointerEvent` ->
 *    `FrameRecomposer.performTrampolineDispatch` -> `FlushCoroutineDispatcher.flush` (outer flush,
 *    no frame guard is set: the guards are frame-scoped);
 * 2. the touch changed the field's selection, so the `snapshotFlow { request.stateSnapshot() }`
 *    collector of `UIKitTextInputService.startInputMethod` resumes inside that flush;
 * 3. `onTextFieldValueUpdated` -> `selectionWillChange` -> UIKit accepts the pending autocorrect /
 *    inline prediction (`acceptAutocorrection` -> `acceptWord` -> `unmarkText`) synchronously;
 * 4. `TextInputConnection.edit` writes the state, then `updateView` -> `FrameRecomposer.performFrame`
 *    -> `performTrampolineDispatch` -> nested `flush()` on the same dispatcher -> the outer batch is
 *    re-run (CoroutinesInternalError on iOS) / ConcurrentModificationException on the JVM.
 *
 * Step 3 is modelled by [FakeUiKit]: it only calls back while it holds a candidate (marked text),
 * which is what disabling autocorrect / inline prediction on the field removes.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FlushReentryImeModelTest {
    private class FakeUiKit(var hasPendingCandidate: Boolean) {
        var callbacks = 0
    }

    private class Outcome(val failure: Throwable?, val collectorRuns: Int, val callbacks: Int)

    private fun runTapWhileEditing(uiKit: FakeUiKit): Outcome {
        val state = TextFieldState("helloi")
        var scene: ImageComposeScene? = null
        var failure: Throwable? = null
        var collectorRuns = 0
        // Like iOS Dispatchers.Main: dispatched work waits for the run loop, so the scene's own
        // synchronous flushes are what drain it.
        val hostQueue = ArrayDeque<Runnable>()
        val host = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { hostQueue.add(block) }
        }
        val handler = CoroutineExceptionHandler { _, t -> if (failure == null) failure = t }
        ImageComposeScene(width = 20, height = 20, coroutineContext = host + handler) {
            LaunchedEffect(Unit) {
                var last: TextRange? = null
                // UIKitTextInputService.startInputMethod: snapshotFlow { request.stateSnapshot() }
                snapshotFlow { state.selection }.collect { selection ->
                    collectorRuns++
                    val changed = last != null && last != selection
                    last = selection
                    // onTextFieldValueUpdated -> selectionWillChange -> UIKit commits its candidate.
                    if (changed && uiKit.hasPendingCandidate) {
                        uiKit.hasPendingCandidate = false
                        uiKit.callbacks++
                        try {
                            // TextInputConnection.edit: editText (a global snapshot write) ...
                            state.edit { append(" ") }
                            // ... then updateView -> FrameRecomposer.performFrame.
                            scene!!.render(48_000_000)
                        } catch (t: Throwable) {
                            if (failure == null) failure = t
                            throw t
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // A tap in the field moves the caret.
                        state.edit { selection = TextRange(0) }
                    }
                }
            )
        }.use { s ->
            scene = s
            s.render(0)
            while (hostQueue.isNotEmpty()) hostQueue.removeFirst().run()
            s.render(16_000_000)
            while (hostQueue.isNotEmpty()) hostQueue.removeFirst().run()
            try {
                s.sendPointerEvent(PointerEventType.Press, Offset(5f, 5f))
                s.render(32_000_000)
                while (hostQueue.isNotEmpty()) hostQueue.removeFirst().run()
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
        }
        return Outcome(failure, collectorRuns, uiKit.callbacks)
    }

    /** The bug: a synchronous IME commit inside the touch-down flush re-enters flush() and throws. */
    @Test
    fun imeCommitInsideTouchDownFlushReentersFlush() {
        val outcome = runTapWhileEditing(FakeUiKit(hasPendingCandidate = true))
        assertEquals(1, outcome.callbacks, "the fake UIKit callback must have fired once")
        // Without re-entry the collector runs twice (initial value, the tap). The nested flush
        // either throws (CME on the JVM) or re-runs the collector's continuation.
        assertTrue(
            outcome.failure != null || outcome.collectorRuns > 2,
            "expected the re-entrant flush to fail or double-run; collectorRuns=${outcome.collectorRuns}",
        )
    }

    /**
     * Workaround model: no pending candidate (autocorrect and inline prediction off on the field),
     * so UIKit has nothing to commit from selectionWillChange and the same tap is harmless.
     */
    @Test
    fun noPendingCandidateMeansNoReentry() {
        val outcome = runTapWhileEditing(FakeUiKit(hasPendingCandidate = false))
        assertEquals(0, outcome.callbacks)
        assertNull(outcome.failure, "no IME callback, so no nested flush")
        assertEquals(2, outcome.collectorRuns)
    }
}

/**
 * The two drain loops on their own: CMP 1.12.2's swap-based `FlushCoroutineDispatcher.flush()`
 * (FlushCoroutineDispatcher.skiko.kt:89-105) and the per-task claim proposed in CMP-10674, driven by
 * a task that dispatches work and re-enters flush (what `updateView` does). Evidence for the
 * "patched compose-ui" workaround: the claim loop runs every task exactly once.
 */
class FlushDrainLoopModelTest {
    private class SwapDrain {
        private var immediateTasks = ArrayDeque<Runnable>()
        private var immediateTasksSwap = ArrayDeque<Runnable>()
        fun dispatch(block: Runnable) { immediateTasks.add(block) }
        fun flush() {
            while (true) {
                if (immediateTasks.isEmpty()) return
                val tmp = immediateTasksSwap
                immediateTasksSwap = immediateTasks
                immediateTasks = tmp
                immediateTasksSwap.forEach(Runnable::run)
                immediateTasksSwap.clear()
            }
        }
    }

    private class ClaimDrain {
        private val immediateTasks = ArrayDeque<Runnable>()
        fun dispatch(block: Runnable) { immediateTasks.add(block) }
        fun flush() {
            var task = immediateTasks.removeFirstOrNull()
            while (task != null) {
                task.run()
                task = immediateTasks.removeFirstOrNull()
            }
        }
    }

    private fun drive(dispatch: (Runnable) -> Unit, flush: () -> Unit): Pair<List<String>, Throwable?> {
        val executed = mutableListOf<String>()
        var reentered = false
        dispatch(Runnable {
            executed += "a"
            if (!reentered) {
                reentered = true
                dispatch(Runnable { executed += "x" })
                flush()
            }
        })
        dispatch(Runnable { executed += "b" })
        val failure = runCatching { flush() }.exceptionOrNull()
        return executed to failure
    }

    @Test
    fun swapDrainRerunsTheOuterBatchOnReentry() {
        val d = SwapDrain()
        val (executed, failure) = drive(d::dispatch, d::flush)
        // "a" runs twice (the nested call re-takes the outer batch); on the JVM the outer
        // iterator then fails with ConcurrentModificationException.
        assertTrue(
            executed.count { it == "a" } > 1 || failure is ConcurrentModificationException,
            "expected a double run or CME, got executed=$executed failure=$failure",
        )
    }

    @Test
    fun claimDrainRunsEveryTaskOnce() {
        val d = ClaimDrain()
        val (executed, failure) = drive(d::dispatch, d::flush)
        assertNull(failure)
        assertEquals(listOf("a", "b", "x"), executed)
    }
}
