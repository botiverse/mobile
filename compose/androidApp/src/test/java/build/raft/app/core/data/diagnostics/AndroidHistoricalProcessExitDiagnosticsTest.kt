package build.raft.app.core.data.diagnostics

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHistoricalProcessExitDiagnosticsTest {
    @Test
    fun selectsNewestExitAfterInstallAndPreviouslyReportedCutoffs() {
        val selected =
            newestEligibleHistoricalProcessExit(
                exits =
                    listOf(
                        snapshot(timestampMillis = 800L),
                        snapshot(timestampMillis = 1_200L),
                        snapshot(timestampMillis = 1_500L),
                        snapshot(timestampMillis = 1_400L)
                    ),
                packageInstalledAtMillis = 1_000L,
                lastReportedAtMillis = 1_200L
            )

        assertEquals(1_500L, selected?.timestampMillis)
    }

    @Test
    fun returnsNoExitWhenHistoryIsPreInstallOrAlreadyReported() {
        assertNull(
            newestEligibleHistoricalProcessExit(
                exits = listOf(snapshot(900L), snapshot(1_200L)),
                packageInstalledAtMillis = 1_000L,
                lastReportedAtMillis = 1_200L
            )
        )
    }

    @Test
    fun formatsOnlyRedactedExitMetadataWithDeterministicReason() {
        val formatted =
            formatHistoricalProcessExit(
                HistoricalProcessExitSnapshot(
                    timestampMillis = 1_500L,
                    reasonCode = ApplicationExitInfo.REASON_CRASH_NATIVE,
                    status = 11,
                    importance = 100,
                    pssKb = 2_048L,
                    rssKb = 4_096L,
                    traceAvailable = true
                )
            )

        assertEquals(
            "previous_process_exit timestampMillis=1500 reason=crash_native reasonCode=5 " +
                "status=11 importance=100 pssKb=2048 rssKb=4096 traceAvailable=true",
            formatted
        )
        assertFalse(formatted.contains("description", ignoreCase = true))
        assertFalse(formatted.contains("tracePayload", ignoreCase = true))
        assertTrue(historicalProcessExitReasonLabel(Int.MAX_VALUE) == "unknown")
    }

    private fun snapshot(timestampMillis: Long): HistoricalProcessExitSnapshot =
        HistoricalProcessExitSnapshot(
            timestampMillis = timestampMillis,
            reasonCode = ApplicationExitInfo.REASON_UNKNOWN,
            status = 0,
            importance = 0,
            pssKb = 0L,
            rssKb = 0L,
            traceAvailable = false
        )
}
