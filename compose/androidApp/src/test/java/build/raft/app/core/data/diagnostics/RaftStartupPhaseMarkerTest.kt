package build.raft.app.core.data.diagnostics

import android.app.ApplicationExitInfo
import build.raft.app.core.data.diagnostics.RaftStartupPhaseMarker.Phase
import build.raft.app.shared.RaftBuildStamp
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftStartupPhaseMarkerTest {
    private fun record(pid: Int, start: Long, phase: Phase = Phase.AttachBaseContext, at: Long = start + 10L, name: String = "build.raft.app") =
        StartupPhaseRecord(pid = pid, processStartMillis = start, processName = name, phase = phase, phaseAtMillis = at)

    private fun exit(pid: Int, at: Long, summaryState: ExitSummaryState? = null) =
        SystemKillExit(
            pid = pid,
            timestampMillis = at,
            reasonCode = ApplicationExitInfo.REASON_SIGNALED,
            status = 9,
            importance = 100,
            pssKb = 0L,
            rssKb = 0L,
            build = null,
            summaryState = summaryState,
        )

    @Test
    fun recordRoundTripsAndRejectsGarbage() {
        val original = record(pid = 33637, start = 1_000L, phase = Phase.FirstFrame, at = 2_500L, name = "build.raft.app:a|b")
        assertEquals(original, StartupPhaseRecord.decode(original.encode()))
        assertNull(StartupPhaseRecord.decode(""))
        assertNull(StartupPhaseRecord.decode("2|1|2|attach_base_context|3|x"))
        assertNull(StartupPhaseRecord.decode("1|0|2|attach_base_context|3|x"))
        assertNull(StartupPhaseRecord.decode("1|5|2|no_such_phase|3|x"))
    }

    @Test
    fun ringKeepsTheNewestEightByProcessStart() {
        val records = (1..12).map { record(pid = 100 + it, start = it * 1_000L) }.shuffled()
        val kept = newestStartupRecords(records, RaftStartupPhaseMarker.MaxRecords)
        assertEquals((12 downTo 5).map { 100 + it }, kept.map { it.pid })
    }

    @Test
    fun exitJoinsItsOwnProcessByPidNotAReusedOne() {
        val old = record(pid = 500, start = 1_000L, phase = Phase.FirstFrame)
        val reused = record(pid = 500, start = 50_000L, phase = Phase.ApplicationCreated)
        val other = record(pid = 501, start = 2_000L)
        val records = listOf(old, reused, other)
        // An exit at 10 s belongs to the process that started at 1 s, not to the later one with the same pid.
        assertEquals(old, startupRecordForExit(records, pid = 500, exitTimestampMillis = 10_000L))
        assertEquals(reused, startupRecordForExit(records, pid = 500, exitTimestampMillis = 60_000L))
        // No marker for that pid: nothing is guessed.
        assertNull(startupRecordForExit(records, pid = 999, exitTimestampMillis = 60_000L))
        // A process that started after the exit is never its process.
        assertNull(startupRecordForExit(listOf(reused), pid = 500, exitTimestampMillis = 10_000L))
    }

    @Test
    fun extrasSayHowFarTheProcessGotAndTheUpdateGap() {
        val reporter = SystemKillReporterBuild("1.27.0", 12700001L, packageUpdatedMillis = 4_000L, nowMillis = 100_000L)
        val startup = record(pid = 7, start = 5_000L, phase = Phase.ActivityCreated, at = 5_800L)
        val extras = systemKillExtras(exit(pid = 7, at = 9_000L, ExitSummaryState.Empty), telemetry = null, reporter = reporter, startup = startup)
        assertEquals("matched", extras["crash_startup_marker"])
        assertEquals("activity_created", extras["crash_startup_phase"])
        assertEquals(4_000L, extras["crash_ms_from_process_start"])
        assertEquals(3_200L, extras["crash_startup_phase_age_ms"])
        assertEquals(4_000L, extras["crash_package_updated_at"])
        assertEquals(5_000L, extras["crash_update_to_exit_ms"])
        assertEquals("empty", extras["crash_exit_summary"])
        val message = systemKillMessage(exit(pid = 7, at = 9_000L), telemetry = null, startup = startup)
        assertTrue(message, message.contains("startup reached activity_created"))
        assertTrue(message, message.contains("4000 ms after process start"))
    }

    @Test
    fun missingMarkerIsReportedAsNoneWithoutPhaseFields() {
        val extras = systemKillExtras(exit(pid = 7, at = 9_000L), telemetry = null, reporter = null, startup = null)
        assertEquals("none", extras["crash_startup_marker"])
        assertFalse(extras.containsKey("crash_startup_phase"))
        assertFalse(extras.containsKey("crash_ms_from_process_start"))
        assertFalse(extras.containsKey("crash_update_to_exit_ms"))
        assertFalse(extras.containsKey("crash_exit_summary"))
    }

    @Test
    fun exitSummaryStateTellsEmptyFromForeignBytesFromOurStamp() {
        assertEquals(ExitSummaryState.Empty, ExitSummaryState.of(null))
        assertEquals(ExitSummaryState.Empty, ExitSummaryState.of(ByteArray(0)))
        assertEquals(ExitSummaryState.Unrecognised, ExitSummaryState.of("hands-build:1\n1\nx".toByteArray()))
        assertEquals(ExitSummaryState.Stamp, ExitSummaryState.of(RaftBuildStamp.encodeStamp("1.27.0", 12700001L)))
    }

    @Test
    fun readAllAndPruneKeepTheNewestRecordsAndThisProcess() {
        val filesDir = Files.createTempDirectory("startup-phases").toFile()
        try {
            val dir = File(filesDir, RaftStartupPhaseMarker.DirName).apply { mkdirs() }
            (1..10).forEach { i -> File(dir, (100 + i).toString()).writeText(record(pid = 100 + i, start = i * 1_000L).encode()) }
            File(dir, "999").writeText("garbage")
            File(dir, "102.tmp").writeText("partial")
            // This process (pid 101) is the oldest but must survive the prune.
            assertEquals(10, RaftStartupPhaseMarker.readAll(filesDir).size)
            RaftStartupPhaseMarker.prune(filesDir, keep = 8, selfPid = 101)
            val left = dir.list()!!.toSet()
            assertEquals(((103..110).map { it.toString() } + "101").toSet(), left)
            assertEquals(9, RaftStartupPhaseMarker.readAll(filesDir).size)
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
