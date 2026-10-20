package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class RaftRequestSchedulerTest {
    @Test
    fun defaultLanesUseMobileHttpConcurrencyCaps() = runBlocking {
        assertDefaultLaneLimit(RaftRequestPriority.Foreground, RAFT_DEFAULT_MAX_FOREGROUND_REQUESTS)
        assertDefaultLaneLimit(RaftRequestPriority.Visible, RAFT_DEFAULT_MAX_VISIBLE_REQUESTS)
        assertDefaultLaneLimit(RaftRequestPriority.Background, RAFT_DEFAULT_MAX_BACKGROUND_REQUESTS)
    }

    @Test
    fun requestTimeoutReleasesLanePermit() = runBlocking {
        val scheduler =
                RaftRequestScheduler(
                        maxVisibleRequests = 1,
                        requestTimeoutMillis = 50,
                        priorityGateEnabled = true
                )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first =
                async {
                    assertFailsWith<RaftRequestTimeoutException> {
                        scheduler.run(RaftRequestPriority.Visible, key = "slow") {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                    }
                }
        firstStarted.await()
        first.await()

        var secondStarted = false
        scheduler.run(RaftRequestPriority.Visible, key = "next") {
            secondStarted = true
        }

        assertTrue(secondStarted)
    }

    @Test
    fun defaultDirectGateDoesNotDelayBackgroundBehindActiveForeground() = runBlocking {
        val scheduler = RaftRequestScheduler(maxBackgroundRequests = 1)
        val foregroundStarted = CompletableDeferred<Unit>()
        val releaseForeground = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val foreground =
                async {
                    scheduler.run(RaftRequestPriority.Foreground, key = "fg") {
                        events += "fg-start"
                        foregroundStarted.complete(Unit)
                        releaseForeground.await()
                        events += "fg-end"
                    }
                }
        foregroundStarted.await()

        val background =
                async {
                    scheduler.run(RaftRequestPriority.Background, key = "bg") {
                        events += "bg-start"
                    }
                }
        background.await()
        releaseForeground.complete(Unit)
        foreground.await()

        assertTrue(events.indexOf("bg-start") < events.indexOf("fg-end"))
    }

    @Test
    fun foregroundStartsBeforeQueuedBackgroundRequests() = runBlocking {
        val scheduler = RaftRequestScheduler(maxBackgroundRequests = 1, priorityGateEnabled = true)
        val firstBackgroundStarted = CompletableDeferred<Unit>()
        val releaseFirstBackground = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val backgroundJobs =
                (0 until 3).map { index ->
                    async {
                        scheduler.run(RaftRequestPriority.Background, key = "bg-$index") {
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
                    scheduler.run(RaftRequestPriority.Foreground, key = "fg") {
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
        val scheduler = RaftRequestScheduler(maxBackgroundRequests = 1, priorityGateEnabled = true)
        val foregroundStarted = CompletableDeferred<Unit>()
        val releaseForeground = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val foreground =
                async {
                    scheduler.run(RaftRequestPriority.Foreground, key = "fg") {
                        events += "fg-start"
                        foregroundStarted.complete(Unit)
                        releaseForeground.await()
                        events += "fg-end"
                    }
                }
        foregroundStarted.await()

        val background =
                async {
                    scheduler.run(RaftRequestPriority.Background, key = "bg") {
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
    fun waitingForegroundStartsBeforeBackgroundRequests() = runBlocking {
        val scheduler =
                RaftRequestScheduler(
                        maxForegroundRequests = 1,
                        maxBackgroundRequests = 1,
                        priorityGateEnabled = true
                )
        val firstForegroundStarted = CompletableDeferred<Unit>()
        val releaseFirstForeground = CompletableDeferred<Unit>()
        val secondForegroundStarted = CompletableDeferred<Unit>()
        val releaseSecondForeground = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val firstForeground =
                async {
                    scheduler.run(RaftRequestPriority.Foreground, key = "fg-1") {
                        events += "fg-1-start"
                        firstForegroundStarted.complete(Unit)
                        releaseFirstForeground.await()
                        events += "fg-1-end"
                    }
                }
        firstForegroundStarted.await()

        val secondForeground =
                async {
                    scheduler.run(RaftRequestPriority.Foreground, key = "fg-2") {
                        events += "fg-2-start"
                        secondForegroundStarted.complete(Unit)
                        releaseSecondForeground.await()
                        events += "fg-2-end"
                    }
                }
        delay(75)

        val background =
                async {
                    scheduler.run(RaftRequestPriority.Background, key = "bg") {
                        events += "bg-start"
                    }
                }
        delay(75)
        assertFalse("bg-start" in events)

        releaseFirstForeground.complete(Unit)
        secondForegroundStarted.await()
        delay(75)
        assertFalse("bg-start" in events)

        releaseSecondForeground.complete(Unit)
        firstForeground.await()
        secondForeground.await()
        background.await()

        assertTrue(events.indexOf("fg-2-start") < events.indexOf("bg-start"))
        assertTrue(events.indexOf("fg-2-end") < events.indexOf("bg-start"))
    }

    @Test
    fun backgroundLaneHonorsConcurrencyLimit() = runBlocking {
        val scheduler = RaftRequestScheduler(maxBackgroundRequests = 1, priorityGateEnabled = true)
        val mutex = Mutex()
        var active = 0
        var maxActive = 0

        val jobs =
                (0 until 5).map { index ->
                    async {
                        scheduler.run(RaftRequestPriority.Background, key = "bg-$index") {
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

    private suspend fun assertDefaultLaneLimit(priority: RaftRequestPriority, expectedLimit: Int) = coroutineScope {
        val scheduler = RaftRequestScheduler(requestTimeoutMillis = 5_000, priorityGateEnabled = true)
        val releaseRequests = CompletableDeferred<Unit>()
        val reachedExpectedLimit = CompletableDeferred<Unit>()
        val mutex = Mutex()
        var active = 0
        var maxActive = 0

        val jobs =
                (0..expectedLimit).map { index ->
                    async {
                        scheduler.run(priority, key = "${priority.name.lowercase()}-$index") {
                            mutex.withLock {
                                active += 1
                                maxActive = maxOf(maxActive, active)
                                if (active == expectedLimit && !reachedExpectedLimit.isCompleted) {
                                    reachedExpectedLimit.complete(Unit)
                                }
                            }
                            releaseRequests.await()
                            mutex.withLock { active -= 1 }
                        }
                    }
                }

        withTimeout(1_000) { reachedExpectedLimit.await() }
        delay(75)
        assertEquals(expectedLimit, maxActive)

        releaseRequests.complete(Unit)
        jobs.forEach { it.await() }
    }
}
