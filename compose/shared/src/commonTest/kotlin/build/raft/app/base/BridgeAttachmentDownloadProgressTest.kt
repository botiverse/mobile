package build.raft.app.base

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BridgeAttachmentDownloadProgressTest {
    @Test
    fun knownLengthResponseStartDoesNotInventZeroPercent() {
        val progress =
            JSONObject(
                """{"downloadId":"attempt-1","sequence":1,"phase":"response_wait","downloadedBytes":0,"totalBytes":10000}"""
            ).toBridgeAttachmentDownloadProgress()

        assertNull(progress.percent)
        assertEquals("response_wait", progress.phase)
    }

    @Test
    fun firstPositiveBytesFallbackToAtLeastOnePercent() {
        val progress =
            JSONObject(
                """{"downloadId":"attempt-1","sequence":2,"downloadedBytes":1,"totalBytes":10000}"""
            ).toBridgeAttachmentDownloadProgress()

        assertEquals(1, progress.percent)
        assertEquals("receiving", progress.phase)
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
}
