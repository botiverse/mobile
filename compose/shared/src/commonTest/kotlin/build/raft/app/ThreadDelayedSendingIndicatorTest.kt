package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun delayIsFiveSeconds() {
        assertEquals(5_000L, ThreadSendingIndicatorDelayMs)
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
    fun sendThatLandsBeforeFiveSecondsNeverShowsSpinner() = runTest {
        val harness = Harness(this, SendKey("local-1", ThreadMessageDeliverySending))
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        harness.key.value = SendKey("local-1", ThreadMessageDeliverySent)
        runCurrent()
        assertEquals(false, harness.visible)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(false, harness.visible, "a sent message never shows the spinner, t=${currentTime}")
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

    @Test
    fun failedSendNeverWaitsOnTheSendingDelay() = runTest {
        val harness = Harness(this, SendKey("local-1", ThreadMessageDeliverySending))
        advanceTimeBy(1_000)
        runCurrent()
        // The failure row is driven by the failed state itself; the sending spinner just goes away.
        harness.key.value = SendKey("local-1", ThreadMessageDeliveryFailed)
        runCurrent()
        assertEquals(false, harness.visible, "failed: no sending spinner, failure row shows at once")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(false, harness.visible)
    }

    @Test
    fun retryAfterFailureRestartsTheFiveSecondWait() = runTest {
        val harness = Harness(this, SendKey("local-1", ThreadMessageDeliverySending))
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(true, harness.visible)

        harness.key.value = SendKey("local-1", ThreadMessageDeliveryFailed)
        runCurrent()
        assertEquals(false, harness.visible)

        harness.key.value = SendKey("local-1", ThreadMessageDeliverySending)
        runCurrent()
        assertEquals(false, harness.visible, "retry starts hidden again")
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(false, harness.visible, "retry still inside the wait, t=${currentTime}")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(true, harness.visible, "retry shows after a fresh 5 s, t=${currentTime}")
    }

    @Test
    fun anotherLocalMessageStartsItsOwnWait() = runTest {
        val harness = Harness(this, SendKey("local-1", ThreadMessageDeliverySending))
        advanceTimeBy(4_000)
        runCurrent()
        harness.key.value = SendKey("local-2", ThreadMessageDeliverySending)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(harness.visible == true, "local-2 has only been sending 1.5 s, t=${currentTime}")
        advanceTimeBy(3_500)
        runCurrent()
        assertTrue(harness.visible == true, "local-2 reached 5 s, t=${currentTime}")
    }
}
