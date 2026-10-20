package build.raft.app.core.data.diagnostics

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsCallerPipelineConnectedTest {
    @Test
    fun measuresCallerAndFrameCostAgainstNoOpControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        RaftAndroidLocalDiagnostics.init(context)
        assertTrue(RaftAndroidLocalDiagnostics.flush())

        Debug.startAllocCounting()
        val control: ProbeResult
        val diagnostics: ProbeResult
        try {
            control = measureSeries(instrumentation, label = "control", afterSeries = { true }) { }
            SystemClock.sleep(250L)
            diagnostics =
                measureSeries(
                    instrumentation,
                    label = "diagnostics",
                    afterSeries = RaftAndroidLocalDiagnostics::flush,
                ) { index ->
                    RaftAndroidLocalDiagnostics.recordLog(
                        level = "debug",
                        tag = "Task272Probe",
                        message =
                            "diagnostics_probe index=$index " +
                                "authorization=Bearer secret-$index " +
                                "access_token=access-$index email=user$index@example.com " +
                                "durationMs=${3_000L + index} result=success"
                    )
                }
        } finally {
            Debug.stopAllocCounting()
        }

        assertTrue(RaftAndroidLocalDiagnostics.flush())
        val recentJsonl = RaftGeneralLogRing.exportRecentJsonl()
        val probeSequences =
            recentJsonl.lineSequence()
                .filter { it.contains("diagnostics_probe") }
                .map { JSONObject(it).getLong("seq") }
                .toList()

        instrumentation.runOnMainSync {
            RaftAndroidLocalDiagnostics.recordCriticalLog(
                level = "debug",
                tag = "Task272AuthProbe",
                message = "task272_auth_probe refresh_failed authorization=Bearer auth-secret",
                includeNonCritical = false,
            )
        }
        assertTrue(RaftAndroidLocalDiagnostics.flush())
        val authProbe =
            RaftAuthCriticalJournal.recentEntries(context)
                .lastOrNull { it.message.contains("task272_auth_probe") }

        Log.i(ProbeTag, control.logLine())
        Log.i(ProbeTag, diagnostics.logLine())
        Log.i(
            ProbeTag,
            "DIAGNOSTICS_AB_DELTA " +
                "callerCpuMs=${"%.3f".format(diagnostics.callerCpuMs - control.callerCpuMs)} " +
                "callerWallMs=${"%.3f".format(diagnostics.callerWallMs - control.callerWallMs)} " +
                "callerAlloc=${diagnostics.callerAllocatedBytes - control.callerAllocatedBytes} " +
                "processAlloc=${diagnostics.processAllocatedBytes - control.processAllocatedBytes} " +
                "gcCount=${diagnostics.gcCount - control.gcCount} " +
                "gcTimeMs=${diagnostics.gcTimeMs - control.gcTimeMs} " +
                "frameP50Ms=${"%.3f".format(diagnostics.frameP50Ms - control.frameP50Ms)} " +
                "frameP95Ms=${"%.3f".format(diagnostics.frameP95Ms - control.frameP95Ms)} " +
                "frameMaxMs=${"%.3f".format(diagnostics.frameMaxMs - control.frameMaxMs)}"
        )

        assertEquals(MeasuredFrames, control.frameSamples)
        assertEquals(MeasuredFrames, diagnostics.frameSamples)
        assertTrue(diagnostics.callerCpuMs >= 0.0)
        assertTrue(diagnostics.callerWallMs >= 0.0)
        assertTrue(recentJsonl.contains("diagnostics_probe"))
        assertTrue(recentJsonl.contains("\"thread\":\"main\""))
        assertTrue(probeSequences.isNotEmpty())
        assertTrue(probeSequences.zipWithNext().all { (previous, next) -> next > previous })
        assertFalse(recentJsonl.contains("secret-"))
        assertFalse(recentJsonl.contains("access-"))
        assertFalse(recentJsonl.contains("@example.com"))
        assertEquals("refresh_failed", authProbe?.event)
        assertFalse(authProbe?.message.orEmpty().contains("auth-secret"))
    }

    private fun measureSeries(
        instrumentation: android.app.Instrumentation,
        label: String,
        afterSeries: () -> Boolean,
        action: (Int) -> Unit,
    ): ProbeResult {
        val runtimeBefore = RuntimeStats.snapshot()
        val completion = CountDownLatch(1)
        val frameDurationsMs = mutableListOf<Double>()
        var callerCpuNanos = 0L
        var callerWallNanos = 0L
        var callerAllocatedBytes = 0L

        instrumentation.runOnMainSync {
            val choreographer = Choreographer.getInstance()
            var frameIndex = 0
            var previousFrameTimeNanos = 0L
            val callback =
                object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (previousFrameTimeNanos != 0L && frameIndex > WarmupFrames) {
                            frameDurationsMs += (frameTimeNanos - previousFrameTimeNanos) / 1_000_000.0
                        }
                        previousFrameTimeNanos = frameTimeNanos

                        val cpuBefore = Debug.threadCpuTimeNanos()
                        val wallBefore = System.nanoTime()
                        val allocatedBefore = Debug.getThreadAllocCount()
                        repeat(LogsPerFrame) { offset ->
                            action(frameIndex * LogsPerFrame + offset)
                        }
                        val allocatedAfter = Debug.getThreadAllocCount()
                        callerWallNanos += System.nanoTime() - wallBefore
                        callerCpuNanos += (Debug.threadCpuTimeNanos() - cpuBefore).coerceAtLeast(0L)
                        if (allocatedBefore >= 0L && allocatedAfter >= allocatedBefore) {
                            callerAllocatedBytes += allocatedAfter - allocatedBefore
                        }

                        frameIndex += 1
                        if (frameIndex < TotalFrames) {
                            choreographer.postFrameCallback(this)
                        } else {
                            completion.countDown()
                        }
                    }
                }
            choreographer.postFrameCallback(callback)
        }

        assertTrue("$label frame probe timed out", completion.await(30L, TimeUnit.SECONDS))
        assertTrue("$label diagnostics drain timed out", afterSeries())
        val runtimeAfter = RuntimeStats.snapshot()
        return ProbeResult(
            label = label,
            callerCpuMs = callerCpuNanos / 1_000_000.0,
            callerWallMs = callerWallNanos / 1_000_000.0,
            callerAllocatedBytes = callerAllocatedBytes,
            processAllocatedBytes = runtimeAfter.allocatedBytes - runtimeBefore.allocatedBytes,
            gcCount = runtimeAfter.gcCount - runtimeBefore.gcCount,
            gcTimeMs = runtimeAfter.gcTimeMs - runtimeBefore.gcTimeMs,
            frameP50Ms = frameDurationsMs.percentile(0.50),
            frameP95Ms = frameDurationsMs.percentile(0.95),
            frameMaxMs = frameDurationsMs.maxOrNull() ?: 0.0,
            frameSamples = frameDurationsMs.size,
        )
    }

    private data class ProbeResult(
        val label: String,
        val callerCpuMs: Double,
        val callerWallMs: Double,
        val callerAllocatedBytes: Long,
        val processAllocatedBytes: Long,
        val gcCount: Long,
        val gcTimeMs: Long,
        val frameP50Ms: Double,
        val frameP95Ms: Double,
        val frameMaxMs: Double,
        val frameSamples: Int,
    ) {
        fun logLine(): String =
            "DIAGNOSTICS_AB label=$label logs=${TotalFrames * LogsPerFrame} " +
                "callerCpuMs=${"%.3f".format(callerCpuMs)} " +
                "callerWallMs=${"%.3f".format(callerWallMs)} " +
                "callerAlloc=$callerAllocatedBytes processAlloc=$processAllocatedBytes " +
                "gcCount=$gcCount gcTimeMs=$gcTimeMs frameSamples=$frameSamples " +
                "frameP50Ms=${"%.3f".format(frameP50Ms)} " +
                "frameP95Ms=${"%.3f".format(frameP95Ms)} " +
                "frameMaxMs=${"%.3f".format(frameMaxMs)}"
    }

    private fun List<Double>.percentile(percentile: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private data class RuntimeStats(
        val allocatedBytes: Long,
        val gcCount: Long,
        val gcTimeMs: Long,
    ) {
        companion object {
            fun snapshot(): RuntimeStats =
                RuntimeStats(
                    allocatedBytes = Debug.getRuntimeStat("art.gc.bytes-allocated").toLongOrZero(),
                    gcCount = Debug.getRuntimeStat("art.gc.gc-count").toLongOrZero(),
                    gcTimeMs = Debug.getRuntimeStat("art.gc.gc-time").toLongOrZero(),
                )

            private fun String?.toLongOrZero(): Long = this?.toLongOrNull() ?: 0L
        }
    }

    private companion object {
        private const val ProbeTag = "Task272Probe"
        private const val WarmupFrames = 10
        private const val MeasuredFrames = 90
        private const val TotalFrames = WarmupFrames + MeasuredFrames + 1
        private const val LogsPerFrame = 8
    }
}
