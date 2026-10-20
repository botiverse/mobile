package build.raft.app.data

import build.raft.app.optInt
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftEventBusConcurrencyTest {
    @Test
    fun workerPostDoesNotHoldRegistryLockWhileListenerRuns() {
        val eventBus = RaftEventBuses.inProcess()
        val eventName = "test.workerPostDoesNotHoldRegistryLockWhileListenerRuns"
        val listenerStarted = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        val subscription =
            eventBus.observe(eventName) {
                listenerStarted.countDown()
                releaseListener.await()
            }
        val postThread =
            thread(name = "slock-event-worker", isDaemon = true) {
                eventBus.post(eventName, buildJsonObject {})
            }

        assertTrue(listenerStarted.await(5, TimeUnit.SECONDS))
        val closeExecutor = Executors.newSingleThreadExecutor()
        try {
            closeExecutor.submit { subscription.close() }.get(1, TimeUnit.SECONDS)
        } finally {
            releaseListener.countDown()
            postThread.join(5_000)
            closeExecutor.shutdownNow()
        }
        assertTrue(!postThread.isAlive)
    }

    @Test
    fun concurrentWorkerPostsDeliverSeriallyInQueueOrder() {
        val eventBus = RaftEventBuses.inProcess()
        val eventName = "test.concurrentWorkerPostsDeliverSeriallyInQueueOrder"
        val firstListenerStarted = CountDownLatch(1)
        val releaseFirstListener = CountDownLatch(1)
        val received = CopyOnWriteArrayList<Int>()
        val subscription =
            eventBus.observe(eventName) { data ->
                val value = data?.optInt("value") ?: return@observe
                received += value
                if (value == 1) {
                    firstListenerStarted.countDown()
                    releaseFirstListener.await()
                }
            }
        val firstPost =
            thread(name = "slock-event-first", isDaemon = true) {
                eventBus.post(eventName, buildJsonObject { put("value", 1) })
            }

        assertTrue(firstListenerStarted.await(5, TimeUnit.SECONDS))
        val secondPost =
            thread(name = "slock-event-second", isDaemon = true) {
                eventBus.post(eventName, buildJsonObject { put("value", 2) })
            }
        secondPost.join(5_000)
        assertTrue(!secondPost.isAlive)
        releaseFirstListener.countDown()
        firstPost.join(5_000)
        subscription.close()

        assertTrue(!firstPost.isAlive)
        assertTrue(received == listOf(1, 2))
    }
}
