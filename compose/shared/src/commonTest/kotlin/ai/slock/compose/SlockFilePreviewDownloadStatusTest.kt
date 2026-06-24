package ai.slock.compose

import ai.slock.compose.base.BridgeAttachmentDownloadProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockFilePreviewDownloadStatusTest {
    @Test
    fun downloadStatusLabelIncludesPercentAndSpeed() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    percent = 42,
                    downloadedBytes = 4L * 1024L * 1024L,
                    totalBytes = 10L * 1024L * 1024L,
                    bytesPerSecond = 512L * 1024L
                )
            )

        assertEquals("Downloading 42% · 512 KB/s", label)
    }

    @Test
    fun downloadStatusLabelFallsBackToDownloadedBytesWhenPercentUnknown() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    percent = null,
                    downloadedBytes = 1536L,
                    totalBytes = null,
                    bytesPerSecond = null
                )
            )

        assertEquals("Downloading 1 KB", label)
    }

    @Test
    fun initialDownloadStatusShowsProgressImmediately() {
        val label = slockFilePreviewDownloadStatusLabel(SlockFilePreviewInitialDownloadProgress)

        assertEquals("Downloading 0%", label)
    }
}
