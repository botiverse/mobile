package build.raft.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SlockBoundedNativeLogTest {
    @Test
    fun blockedWriterDoesNotBlockProducerAndOverflowIsReported() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val produced = CountDownLatch(1)
        val drained = CountDownLatch(4)
        val output = CopyOnWriteArrayList<String>()
        val log = SlockBoundedNativeLog(scope, 2, 32, "warn") { _: String, text ->
            if (text == "blocked") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            output.add(text)
            drained.countDown()
        }
        try {
            log.enqueue("info", "blocked")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val producer = Thread {
                log.enqueue("info", "second")
                log.enqueue("info", "third")
                log.enqueue("info", "overflow")
                produced.countDown()
            }
            producer.start()
            assertTrue(produced.await(1, TimeUnit.SECONDS), "producer waited for blocked sink")
            release.countDown()
            producer.join(1000)
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("blocked", "knoi_async_log_loss dropped=1 writeFailures=0", "second", "third"), output.toList())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun writerFailureDoesNotStopConsumerAndMessagesStayLiteralAndBounded() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val done = CountDownLatch(3)
        val output = CopyOnWriteArrayList<Pair<String, String>>()
        val log = SlockBoundedNativeLog(scope, 8, 8, "warn") { level: String, text ->
            if (text == "fail") error("simulated writer failure")
            output.add(level to text)
            done.countDown()
        }
        try {
            log.enqueue("error", "fail")
            log.enqueue("debug", "%s%{x}")
            log.enqueue("info", "123456789abcdef")
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(
                "warn" to "knoi_async_log_loss dropped=0 writeFailures=1",
                "debug" to "%s%{x}",
                "info" to "12345678 [truncated]"
            ), output.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentProducersAreDrainedWithoutFurtherEnqueue() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val done = CountDownLatch(400)
        val output = CopyOnWriteArrayList<String>()
        val log = SlockBoundedNativeLog(scope, 512, 32, "warn") { _: String, text ->
            output.add(text)
            done.countDown()
        }
        try {
            val producers = (0 until 4).map { owner ->
                Thread { repeat(100) { log.enqueue("info", "$owner:$it") } }.also { it.start() }
            }
            producers.forEach { it.join(5000) }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(400, output.toSet().size)
        } finally {
            scope.cancel()
        }
    }
}
