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
    fun downloadStatusLabelShowsPausedState() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    status = "paused",
                    percent = 42,
                    downloadedBytes = 4L * 1024L * 1024L,
                    totalBytes = 10L * 1024L * 1024L,
                    bytesPerSecond = null
                )
            )

        assertEquals("Paused 42%", label)
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

    @Test
    fun activeDownloadStatusFallsBackToZeroPercentBeforeNativeProgress() {
        val label = slockFilePreviewActiveDownloadStatusLabel(progress = null, status = "downloading")

        assertEquals("Downloading 0%", label)
    }

    @Test
    fun pausedDownloadStatusFallsBackToZeroPercentBeforeNativeProgress() {
        val label = slockFilePreviewActiveDownloadStatusLabel(progress = null, status = "paused")

        assertEquals("Paused 0%", label)
    }

    @Test
    fun threadAttachmentOpeningStatusUsesVisibleZeroPercentFallback() {
        val label = ThreadAttachmentOpenUiState(status = "downloading").threadAttachmentStatusLabel()

        assertEquals("Downloading 0%", label)
    }

    @Test
    fun threadAttachmentPausedStatusUsesVisibleZeroPercentFallback() {
        val label = ThreadAttachmentOpenUiState(status = "paused").threadAttachmentStatusLabel()

        assertEquals("Paused 0%", label)
    }

    @Test
    fun primaryActionShowsDownloadBeforeCacheExists() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false
            )

        assertEquals("Download", label)
    }

    @Test
    fun primaryActionShowsOpenWhenCached() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = true
            )

        assertEquals("Open", label)
    }

    @Test
    fun primaryActionShowsInstallForCachedApk() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(
                    externalFileName = "raft.apk",
                    externalMimeType = "application/vnd.android.package-archive"
                ),
                cached = true
            )

        assertEquals("Install", label)
    }

    @Test
    fun overflowActionShowsDownloadAndOpenBeforeCacheExists() {
        val label =
            slockFilePreviewOverflowActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false
            )

        assertEquals("Download and open...", label)
    }
}
