package build.raft.app.base

import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BridgeAttachmentDownloadProgressTest {
    @Test
    fun knownLengthResponseStartDoesNotInventZeroPercent() {
        val progress =
            JSONObject(
                """{"downloadId":"download-1","attemptToken":"attempt-1","sequence":1,"phase":"response_wait","downloadedBytes":0,"totalBytes":10000}"""
            ).toBridgeAttachmentDownloadProgress()

        assertNull(progress.percent)
        assertEquals("response_wait", progress.phase)
        assertEquals("attempt-1", progress.attemptToken)
    }

    @Test
    fun theBridgeKeepsTheHostMetersPercentAndNeverDerivesOne() {
        data class Case(val json: String, val percent: Int?, val why: String)
        val cases =
            listOf(
                Case("""{"downloadId":"a","sequence":2,"downloadedBytes":1,"totalBytes":10000,"percent":0}""", 1, "first byte reads 1%"),
                Case("""{"downloadId":"a","sequence":2,"downloadedBytes":5000,"totalBytes":10000}""", null, "no percent sent: none invented"),
                Case("""{"downloadId":"a","sequence":2,"downloadedBytes":10000,"totalBytes":10000,"percent":100}""", 99, "100 before Done is capped"),
                Case("""{"downloadId":"a","sequence":2,"phase":"complete","downloadedBytes":10000,"totalBytes":10000,"percent":100}""", 99, "staged, not committed")
            )
        for (case in cases) {
            val progress = JSONObject(case.json).toBridgeAttachmentDownloadProgress()
            assertEquals(case.percent, progress.percent, case.why)
        }
        assertEquals("receiving", JSONObject(cases.first().json).toBridgeAttachmentDownloadProgress().phase)
    }

    @Test
    fun reducerRejectsDuplicateSequenceAndByteRegression() {
        val current =
            BridgeAttachmentDownloadProgress(
                downloadId = "attempt-1",
                sequence = 4,
                phase = "receiving",
                percent = 40,
                downloadedBytes = 400,
                totalBytes = 1_000,
                bytesPerSecond = 800
            )

        val duplicate = current.copy(percent = 45, downloadedBytes = 450)
        val regressed = current.copy(sequence = 5, percent = 30, downloadedBytes = 300)

        assertSame(current, reduceAttachmentDownloadProgress(current, duplicate, "attempt-1"))
        assertSame(current, reduceAttachmentDownloadProgress(current, regressed, "attempt-1"))
    }

    @Test
    fun reducerRejectsPreviousAttemptAndAcceptsNextMonotonicSample() {
        val current =
            BridgeAttachmentDownloadProgress(
                downloadId = "attempt-2",
                sequence = 2,
                phase = "receiving",
                percent = 20,
                downloadedBytes = 200,
                totalBytes = 1_000,
                bytesPerSecond = 500
            )
        val previousAttempt = current.copy(downloadId = "attempt-1", sequence = 99, downloadedBytes = 900, percent = 90)
        val next = current.copy(sequence = 3, downloadedBytes = 300, percent = 30, bytesPerSecond = 600)

        assertSame(current, reduceAttachmentDownloadProgress(current, previousAttempt, "attempt-2"))
        assertEquals(next, reduceAttachmentDownloadProgress(current, next, "attempt-2"))
    }

    @Test
    fun reducerAcceptsOnlyTheResponseStartOfANewSameIdAttempt() {
        val current =
            BridgeAttachmentDownloadProgress(
                downloadId = "download-1",
                attemptToken = "attempt-old",
                sequence = 4,
                phase = "receiving",
                percent = 40,
                downloadedBytes = 400,
                totalBytes = 1_000
            )
        val invalidNewChunk =
            current.copy(
                attemptToken = "attempt-new",
                sequence = 2,
                downloadedBytes = 100,
                percent = 10
            )
        val validNewStart =
            current.copy(
                attemptToken = "attempt-new",
                sequence = 1,
                phase = "response_wait",
                downloadedBytes = 0,
                percent = null
            )

        assertSame(current, reduceAttachmentDownloadProgress(current, invalidNewChunk, "download-1"))
        assertEquals(validNewStart, reduceAttachmentDownloadProgress(current, validNewStart, "download-1"))
    }

    @Test
    fun reducerAcceptsExportInsertAsTheNextSameIdLifecycle() {
        val completedTransfer =
            BridgeAttachmentDownloadProgress(
                downloadId = "download-1",
                attemptToken = "network-attempt",
                sequence = 6,
                phase = "complete",
                percent = 100,
                downloadedBytes = 1_000,
                totalBytes = 1_000
            )
        val exportStart =
            completedTransfer.copy(
                attemptToken = "export-attempt",
                sequence = 1,
                phase = "export_insert",
                percent = null,
                downloadedBytes = 0
            )

        assertEquals(exportStart, reduceAttachmentDownloadProgress(completedTransfer, exportStart, "download-1"))
    }
}
