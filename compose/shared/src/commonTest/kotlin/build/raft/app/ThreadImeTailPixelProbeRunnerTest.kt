package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class ThreadImeTailPixelProbeRunnerTest {
    @Test
    fun stableRunnerSurvivesRestartedCallerAndRunsProbeOnce() = runPortableCoroutineTest {
        val stableJob = SupervisorJob()
        val runner =
            ThreadImeTailPixelProbeRunner(
                CoroutineScope(stableJob + Dispatchers.Unconfined)
            )
        val settlementStarted = CompletableDeferred<Unit>()
        val releaseSettlement = CompletableDeferred<Unit>()
        val scheduledJob = CompletableDeferred<Job>()
        val keepCallerAlive = CompletableDeferred<Unit>()
        var probes = 0

        val caller =
            launch {
                scheduledJob.complete(
                    runner.launch(
                        awaitSettlement = {
                            settlementStarted.complete(Unit)
                            releaseSettlement.await()
                        },
                        runProbe = { probes += 1 }
                    )
                )
                keepCallerAlive.await()
            }

        settlementStarted.await()
        caller.cancelAndJoin()
        releaseSettlement.complete(Unit)
        scheduledJob.await().join()

        assertEquals(1, probes)
        stableJob.cancel()
    }

    @Test
    fun leavingStableScopeCancelsPendingProbe() = runPortableCoroutineTest {
        val stableJob = SupervisorJob()
        val runner =
            ThreadImeTailPixelProbeRunner(
                CoroutineScope(stableJob + Dispatchers.Unconfined)
            )
        val settlementStarted = CompletableDeferred<Unit>()
        val releaseSettlement = CompletableDeferred<Unit>()
        var probes = 0

        val probeJob =
            runner.launch(
                awaitSettlement = {
                    settlementStarted.complete(Unit)
                    releaseSettlement.await()
                },
                runProbe = { probes += 1 }
            )

        settlementStarted.await()
        stableJob.cancel()
        releaseSettlement.complete(Unit)
        probeJob.join()

        assertEquals(0, probes)
    }
}
