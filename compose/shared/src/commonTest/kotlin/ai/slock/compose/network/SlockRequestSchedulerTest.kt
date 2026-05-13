package ai.slock.compose.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SlockRequestSchedulerTest {
    @Test
    fun foregroundStartsBeforeQueuedBackgroundRequests() = runBlocking {
        val scheduler = SlockRequestScheduler(maxBackgroundRequests = 1)
        val firstBackgroundStarted = CompletableDeferred<Unit>()
        val releaseFirstBackground = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val backgroundJobs =
                (0 until 3).map { index ->
                    async {
                        scheduler.run(SlockRequestPriority.Background, key = "bg-$index") {
                            events += "bg-$index-start"
                            if (index == 0) {
                                firstBackgroundStarted.complete(Unit)
                                releaseFirstBackground.await()
                            }
                            events += "bg-$index-end"
                        }
                    }
                }
        firstBackgroundStarted.await()

        val foreground =
                async {
                    scheduler.run(SlockRequestPriority.Foreground, key = "fg") {
                        events += "fg-start"
                        events += "fg-end"
                    }
                }
        foreground.await()
        releaseFirstBackground.complete(Unit)
        backgroundJobs.forEach { it.await() }

        assertTrue(events.indexOf("fg-start") < events.indexOf("bg-1-start"))
    }

    @Test
    fun activeForegroundDelaysNewBackgroundStart() = runBlocking {
        val scheduler = SlockRequestScheduler(maxBackgroundRequests = 1)
        val foregroundStarted = CompletableDeferred<Unit>()
        val releaseForeground = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val foreground =
                async {
                    scheduler.run(SlockRequestPriority.Foreground, key = "fg") {
                        events += "fg-start"
                        foregroundStarted.complete(Unit)
                        releaseForeground.await()
                        events += "fg-end"
                    }
                }
        foregroundStarted.await()

        val background =
                async {
                    scheduler.run(SlockRequestPriority.Background, key = "bg") {
                        events += "bg-start"
                    }
                }
        delay(75)
        assertFalse("bg-start" in events)

        releaseForeground.complete(Unit)
        foreground.await()
        background.await()
        assertTrue(events.indexOf("fg-end") < events.indexOf("bg-start"))
    }

    @Test
    fun backgroundLaneHonorsConcurrencyLimit() = runBlocking {
        val scheduler = SlockRequestScheduler(maxBackgroundRequests = 1)
        val mutex = Mutex()
        var active = 0
        var maxActive = 0

        val jobs =
                (0 until 5).map { index ->
                    async {
                        scheduler.run(SlockRequestPriority.Background, key = "bg-$index") {
                            mutex.withLock {
                                active += 1
                                maxActive = maxOf(maxActive, active)
                            }
                            delay(20)
                            mutex.withLock { active -= 1 }
                        }
                    }
                }
        jobs.forEach { it.await() }

        assertEquals(1, maxActive)
    }
}
