package build.raft.app

import build.raft.app.transfer.testTransfer

/** A file-page download view over the meter state a host would have reported. */
internal fun RaftFilePreviewDownloadView(
    phase: RaftFilePreviewDownloadPhase,
    percent: Int?,
    downloadedBytes: Long = 0L,
    totalBytes: Long? = null,
    bytesPerSecond: Long? = null,
    errorMessage: String? = null
): RaftFilePreviewDownloadView =
    RaftFilePreviewDownloadView(
        phase = phase,
        transfer =
            testTransfer(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                percent = percent,
                bytesPerSecond = bytesPerSecond
            ),
        errorMessage = errorMessage
    )
