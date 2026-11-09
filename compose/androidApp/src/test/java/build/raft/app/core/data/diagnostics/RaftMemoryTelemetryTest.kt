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
    fun snapshotRoundTripsThroughItsPersistedString() {
        assertEquals(sample, MemoryTelemetrySnapshot.decode(sample.encode()))
        val unsampled = MemoryTelemetrySnapshot(7, 1L, 2L, false, 3L)
        assertEquals(unsampled, MemoryTelemetrySnapshot.decode(unsampled.encode()))
    }

    @Test
    fun corruptOrOlderFormatValuesDecodeToNothing() {
        assertNull(MemoryTelemetrySnapshot.decode(null))
        assertNull(MemoryTelemetrySnapshot.decode(""))
        assertNull(MemoryTelemetrySnapshot.decode("1," + sample.encode().substringAfter(',')))
        assertNull(MemoryTelemetrySnapshot.decode(sample.encode() + ",1"))
        assertNull(MemoryTelemetrySnapshot.decode(sample.encode().replace("4321", "x")))
    }

    @Test
    fun ringKeepsOneEntryPerProcessNewestFirstAndSkipsCorruptEntries() {
        val processes = (1..8).map { i -> MemoryTelemetrySnapshot(i, i * 1_000L, i * 1_000L, false, i * 1_000L) }
        var ring = emptyList<MemoryTelemetrySnapshot>()
        processes.forEach { ring = MemoryTelemetryRing.merge(ring, it) }
        assertEquals((3..8).toList(), ring.map { it.pid })

        // The same process writing again replaces its entry.
        val updated = processes.last().copy(foreground = true, writtenAtMillis = 9_999L)
        ring = MemoryTelemetryRing.merge(ring, updated)
        assertEquals(MemoryTelemetryRing.Capacity, ring.size)
        assertEquals(updated, ring.last())

        val decoded = MemoryTelemetryRing.decode(MemoryTelemetryRing.encode(ring) + ";garbage;;")
        assertEquals(ring, decoded)
        assertTrue(MemoryTelemetryRing.decode(null).isEmpty())
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

    @Test
    fun trimLevelsHaveStableLabels() {
        assertEquals("none", trimMemoryLevelLabel(0))
        assertEquals("running_critical", trimMemoryLevelLabel(15))
        assertEquals("ui_hidden", trimMemoryLevelLabel(20))
        assertEquals("complete", trimMemoryLevelLabel(80))
        assertEquals("level_7", trimMemoryLevelLabel(7))
    }

    @Test
    fun pssIsReadOnTrimsTheFirstSampleAndEveryFiveMinutesOnly() {
        val interval = RaftMemoryTelemetry.PssIntervalMillis
        assertTrue(shouldSamplePss(nowMillis = 0L, lastPssMillis = -1L, intervalMillis = interval, trim = false))
        assertFalse(shouldSamplePss(30_000L, 0L, interval, trim = false))
        assertTrue(shouldSamplePss(30_000L, 0L, interval, trim = true))
        assertTrue(shouldSamplePss(interval, 0L, interval, trim = false))
    }

    @Test
    fun statmResidentPagesAreTheSecondField() {
        assertEquals(36_912L, parseStatmResidentPages("1234567 36912 12000 5 0 400000 0\n"))
        assertEquals(-1L, parseStatmResidentPages(""))
        assertEquals(-1L, parseStatmResidentPages("12 x"))
    }

    @Test
    fun writeGateRateLimitsPeriodicSamplesButNotForegroundChangesOrHigherTrims() {
        val gate = MemoryTelemetryWriteGate(minPeriodicIntervalMillis = 15_000L)

        assertTrue(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Periodic, 0L))
        assertFalse(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Periodic, 14_999L))
        assertTrue(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.ForegroundChange, 15_000L))
        assertFalse(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Periodic, 20_000L))
        assertTrue(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Periodic, 30_000L))

        assertTrue(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Trim, 30_001L, trimLevel = 5))
        assertFalse(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Trim, 30_002L, trimLevel = 5))
        assertTrue(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Trim, 30_003L, trimLevel = 15))
        assertFalse(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Trim, 30_004L, trimLevel = 10))
        assertTrue(gate.shouldWrite(MemoryTelemetryWriteGate.Cause.Trim, 45_003L, trimLevel = 10))
    }

    @Test
    fun uiHiddenAndBackgroundTrimLevelsMeanNoUiIsShown() {
        assertFalse(trimMeansUiHidden(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(trimMeansUiHidden(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(trimMeansUiHidden(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(trimMeansUiHidden(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(trimMeansUiHidden(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
