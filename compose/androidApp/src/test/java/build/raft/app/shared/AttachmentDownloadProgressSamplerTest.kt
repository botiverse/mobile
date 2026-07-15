package build.raft.app.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentDownloadProgressSamplerTest {
    @Test
    fun responseStartAndZeroBytesStayIndeterminate() {
        val sampler = AttachmentDownloadProgressSampler()
        sampler.onResponseStart(nowMs = 1_000L)

        assertNull(sampler.sample(downloadedBytes = 0L, totalBytes = 10_000L, nowMs = 1_100L))
        assertNull(attachmentDownloadPercent(downloadedBytes = 0L, totalBytes = 10_000L))
    }

    @Test
    fun firstPositiveBytesEmitImmediatelyAndNeverRenderAsZeroPercent() {
        val sampler = AttachmentDownloadProgressSampler()
        sampler.onResponseStart(nowMs = 1_000L)

        val sample = assertNotNull(
            sampler.sample(downloadedBytes = 1L, totalBytes = 10_000L, nowMs = 1_010L)
        )

        assertEquals(1, sample.percent)
        assertNull(sample.bytesPerSecond)
    }

    @Test
    fun intermediateSpeedUsesTransferredByteWindowInsteadOfRequestWait() {
        val sampler = AttachmentDownloadProgressSampler()
        sampler.onResponseStart(nowMs = 1_000L)
        assertNotNull(sampler.sample(downloadedBytes = 64L * 1024L, totalBytes = 4L * 1024L * 1024L, nowMs = 8_000L))

        val sample = assertNotNull(
            sampler.sample(
                downloadedBytes = 640L * 1024L,
                totalBytes = 4L * 1024L * 1024L,
                nowMs = 8_500L
            )
        )

        assertEquals(15, sample.percent)
        assertTrue((sample.bytesPerSecond ?: 0L) > 1_000_000L)
    }

    @Test
    fun determinateProgressIsRateLimitedBetweenUiSamples() {
        val sampler = AttachmentDownloadProgressSampler()
        sampler.onResponseStart(nowMs = 1_000L)
        assertNotNull(sampler.sample(downloadedBytes = 100L, totalBytes = 10_000L, nowMs = 1_010L))

        assertNull(sampler.sample(downloadedBytes = 200L, totalBytes = 10_000L, nowMs = 1_100L))
        assertNotNull(sampler.sample(downloadedBytes = 300L, totalBytes = 10_000L, nowMs = 1_300L))
    }

    @Test
    fun unknownLengthEmitsBytesAndWindowSpeed() {
        val sampler = AttachmentDownloadProgressSampler()
        sampler.onResponseStart(nowMs = 1_000L)
        assertNotNull(sampler.sample(downloadedBytes = 32L * 1024L, totalBytes = null, nowMs = 1_010L))

        val sample = assertNotNull(
            sampler.sample(downloadedBytes = 288L * 1024L, totalBytes = null, nowMs = 1_600L)
        )

        assertNull(sample.percent)
        assertTrue((sample.bytesPerSecond ?: 0L) > 0L)
    }

    @Test
    fun longStallResetsSpeedWindow() {
        val sampler = AttachmentDownloadProgressSampler()
        sampler.onResponseStart(nowMs = 1_000L)
        assertNotNull(sampler.sample(downloadedBytes = 64L * 1024L, totalBytes = 1L * 1024L * 1024L, nowMs = 1_010L))
        assertNotNull(sampler.sample(downloadedBytes = 320L * 1024L, totalBytes = 1L * 1024L * 1024L, nowMs = 1_600L))

        val afterStall = assertNotNull(
            sampler.sample(downloadedBytes = 384L * 1024L, totalBytes = 1L * 1024L * 1024L, nowMs = 4_000L)
        )

        assertNull(afterStall.bytesPerSecond)
    }

    @Test
    fun completionAlwaysEmitsOneHundredPercentWhenLengthKnown() {
        val sampler = AttachmentDownloadProgressSampler()
        sampler.onResponseStart(nowMs = 1_000L)

        val complete = assertNotNull(
            sampler.sample(downloadedBytes = 10_000L, totalBytes = 10_000L, nowMs = 1_010L, complete = true)
        )

        assertEquals(100, complete.percent)
    }
}
