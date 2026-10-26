package build.raft.designsystem.repro

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.use
import kotlin.test.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertTrue

/**
 * Scratch repro: a trampoline task (LaunchedEffect body) that synchronously re-enters the
 * scene (here sendPointerEvent -> performTrampolineDispatch -> FlushCoroutineDispatcher.flush)
 * while the outer flush is still iterating its swapped queue.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FlushReentrancyReproTest {
    @Test
    fun reentrantFlushFromATrampolineTask() {
        var trigger by mutableStateOf(0)
        var scene: ImageComposeScene? = null
        var failure: Throwable? = null
        val ran = mutableListOf<String>()
        // Like iOS Dispatchers.Main: dispatched work waits for the run loop, so the scene's own
        // performTrampolineDispatch is what drains it (FlushCoroutineDispatcher.flush).
        val hostQueue = ArrayDeque<Runnable>()
        val host = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { hostQueue.add(block) }
        }
        ImageComposeScene(width = 10, height = 10, coroutineContext = host) {
            LaunchedEffect(trigger) {
                if (trigger > 0) {
                    ran += "a"
                    // Any work queued on the trampoline before the re-entry (a child launch,
                    // a snapshot apply notification) puts a task in the other deque.
                    launch { ran += "d" }
                    try {
                        scene!!.sendPointerEvent(PointerEventType.Move, Offset(2f, 2f))
                    } catch (t: Throwable) { failure = t; throw t }
                }
            }
            LaunchedEffect(trigger) { if (trigger > 0) ran += "b" }
            LaunchedEffect(trigger) { if (trigger > 0) ran += "c" }
        }.use { s ->
            scene = s
            s.render(0)
            while (hostQueue.isNotEmpty()) hostQueue.removeFirst().run()
            trigger = 1
            var outer: Throwable? = null
            try {
                s.render(16_000_000)
                s.sendPointerEvent(PointerEventType.Move, Offset(1f, 1f))
                s.render(32_000_000)
                while (hostQueue.isNotEmpty()) hostQueue.removeFirst().run()
            } catch (t: Throwable) {
                outer = t; t.printStackTrace(System.out)
            }
            println("REPRO ran=$ran outer=$outer inner=$failure")
            assertTrue(outer == null && failure == null, "re-entrant flush threw: outer=$outer inner=$failure ran=$ran")
        }
    }
}
