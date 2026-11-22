package build.raft.app.core.data.diagnostics

import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftMemoryTelemetryTest {
    private val sample =
        MemoryTelemetrySnapshot(
            pid = 4321,
            processStartMillis = 1_000L,
            writtenAtMillis = 30_000L,
            foreground = true,
            foregroundChangedAtMillis = 2_000L,
            sampledAtMillis = 29_000L,
            rssKb = 450_000L,
            pssKb = 400_000L,
            pssSampledAtMillis = 10_000L,
            javaHeapUsedKb = 90_000L,
            javaHeapMaxKb = 262_144L,
            nativeHeapKb = 70_000L,
            systemAvailKb = 500_000L,
            systemThresholdKb = 216_000L,
            systemLowMemory = true,
            maxTrimLevel = 15,
            lastTrimLevel = 5,
            lastTrimAtMillis = 25_000L
        )

    @Test
    fun aRingWrittenByThePreviousBuildStillDecodesWithoutAVersion() {
        // Encoding 2: the 19 numeric fields, no build.
        val v2 = "2," + sample.encode().substringAfter(',').split(',').take(18).joinToString(",")
        val decoded = MemoryTelemetrySnapshot.decode(v2)
        assertEquals(sample, decoded)
        assertEquals(-1L, decoded?.versionCode)
        assertEquals("", decoded?.versionName)
    }

    @Test
    fun exitRecordCarriesTheLastSampleAndTrimLevelForTheSamePid() {
        val exit =
            HistoricalProcessExitSnapshot(
                timestampMillis = 31_000L,
                reasonCode = ApplicationExitInfo.REASON_OTHER,
                status = 0,
                importance = 400,
                pssKb = 0L,
                rssKb = 0L,
                traceAvailable = false,
                pid = 4321
            )

        assertEquals(
            "previous_process_exit timestampMillis=31000 reason=other reasonCode=13 status=0 importance=400 " +
                "pssKb=0 rssKb=0 traceAvailable=false pid=4321 telemetry=matched sinceLaunchMs=30000 " +
                "foreground=true foregroundChangeAgeMs=29000 sampleAgeMs=2000 lastRssKb=450000 lastPssKb=400000 " +
                "pssAgeMs=21000 heapUsedKb=90000 heapMaxKb=262144 nativeHeapKb=70000 systemAvailKb=500000 " +
                "systemThresholdKb=216000 systemLowMemory=true trimLevel=5 trim=running_moderate " +
                "maxTrimLevel=15 trimAgeMs=6000",
            formatHistoricalProcessExit(exit, sample)
        )
        assertTrue(
            formatHistoricalProcessExit(exit.copy(pid = 9), sample)
                .endsWith("traceAvailable=false pid=9 telemetry=pid_mismatch telemetryPid=4321")
        )
    }
}
