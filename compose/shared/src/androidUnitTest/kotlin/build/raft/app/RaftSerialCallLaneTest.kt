package build.raft.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #317: the desktop app froze for good when its UI thread made a WebView call that waits for the
 * macOS main thread while that thread waited for the UI thread. The lane those calls now go
 * through (desktop `RaftDesktopWryCalls`) must never make its caller wait, whatever the call
 * blocks on. Real threads with latches: the property is about which thread waits, which virtual
 * time cannot express.
 */
class RaftSerialCallLaneTest {
    private val delivered = LinkedBlockingQueue<Runnable>()
    private val lane = RaftSerialCallLane("raft-test-lane") { delivered.put(it) }

    @Test
    fun aCallThatBlocksNeverMakesTheCallerWaitAndItsAnswerComesBackThroughDelivery() {
        val callStarted = CountDownLatch(1)
        val mainThreadFree = CountDownLatch(1)
        val caller = Thread.currentThread()
        var callThread: Thread? = null
        var answer: Result<String>? = null

        // Stands in for Wry's evaluate_javascript waiting for a main thread that waits for us.
        lane.call<String>(
            work = {
                callThread = Thread.currentThread()
                callStarted.countDown()
                check(mainThreadFree.await(5, TimeUnit.SECONDS)) { "the test never freed the main thread" }
                "\"https://raft.build/\""
            },
            onResult = { answer = it },
        )

        // Back here while the call still waits: the caller was never blocked.
        assertTrue(callStarted.await(5, TimeUnit.SECONDS), "the lane never started the call")
        assertNotEquals(caller, callThread, "the call ran on the caller's thread")
        assertNull(delivered.poll(), "an answer arrived before the call finished")

        mainThreadFree.countDown()
        val delivery = assertNotNull(delivered.poll(5, TimeUnit.SECONDS), "the answer never came back")
        delivery.run()
        assertEquals("\"https://raft.build/\"", answer?.getOrNull())
    }

    @Test
    fun callsRunInOrderAndAFailedOneDoesNotStopTheNext() {
        val answers = mutableListOf<String>()

        // A WebView that is gone makes Wry throw; the next call still runs and answers.
        lane.call<String>(work = { error("webview 7 not found") }, onResult = { answers += "failed: ${it.exceptionOrNull()?.message}" })
        lane.post { error("no answer wanted") }
        lane.call<String>(work = { "1" }, onResult = { answers += "answered: ${it.getOrNull()}" })

        repeat(2) {
            val delivery = assertNotNull(delivered.poll(5, TimeUnit.SECONDS), "answer ${it + 1} never came back, got $answers")
            delivery.run()
        }
        assertEquals(listOf("failed: webview 7 not found", "answered: 1"), answers)
    }
}
