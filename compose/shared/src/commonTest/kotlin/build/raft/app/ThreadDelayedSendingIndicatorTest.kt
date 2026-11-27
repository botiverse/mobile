package build.raft.app

import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadMessageDeliverySent
import build.raft.app.thread.ThreadSendingIndicatorDelayMs
import build.raft.app.thread.key
import build.raft.app.thread.runDelayedSendingVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The delayed sending indicator (artin: no spinner unless a send is still in flight after 5 s;
 * Lagoon: a failed send shows at once). [rememberDelayedSendingVisible] relaunches
 * [runDelayedSendingVisibility] whenever (local id, sending) changes; `collectLatest` over a
 * StateFlow of that key reproduces the same keyed relaunch on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadDelayedSendingIndicatorTest {
    private data class SendKey(val localId: String, val state: String)

    private class Harness(scope: TestScope, initial: SendKey) {
        val key = MutableStateFlow(initial)
        var visible: Boolean? = null

        init {
            scope.backgroundScope.launch {
                key.collectLatest { current ->
                    runDelayedSendingVisibility(
                        isSending = current.state == ThreadMessageDeliverySending,
                        delayMs = ThreadSendingIndicatorDelayMs,
                    ) { visible = it }
                }
            }
        }
    }

    @Test
    fun sendingIndicatorHiddenAtStartAndShowsOnlyAfterFiveSecondsOfSending() = runTest {
        val harness = Harness(this, SendKey("local-1", ThreadMessageDeliverySending))
        runCurrent()
        assertEquals(false, harness.visible, "no spinner at t=0")

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(false, harness.visible, "still hidden at t=${currentTime}")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(true, harness.visible, "visible once sending reached 5 s, t=${currentTime}")
    }

    @Test
    fun slowSendHidesSpinnerAsSoonAsItIsSent() = runTest {
        val harness = Harness(this, SendKey("local-1", ThreadMessageDeliverySending))
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(true, harness.visible)

        harness.key.value = SendKey("local-1", ThreadMessageDeliverySent)
        runCurrent()
        assertEquals(false, harness.visible, "sent hides the spinner without delay")
    }
}
