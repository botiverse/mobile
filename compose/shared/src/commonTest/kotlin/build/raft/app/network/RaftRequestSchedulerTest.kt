package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class RaftRequestSchedulerTest {
    @Test
    fun defaultDirectGateDoesNotDelayBackgroundBehindActiveForeground() = runBlocking {
        val scheduler = RaftRequestScheduler()
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
}
