package build.raft.app.base

import build.raft.app.transfer.testTransfer

/** A host `progress` event as the bridge decodes it: the wire phase word picks the transfer phase. */
internal fun BridgeAttachmentDownloadProgress(
    downloadId: String = "",
    attemptToken: String = "",
    sequence: Long = 0L,
    recordVersion: Long? = null,
    phase: String = "receiving",
    status: String = "progress",
    percent: Int?,
    downloadedBytes: Long,
    totalBytes: Long?,
    bytesPerSecond: Long? = null
): BridgeAttachmentDownloadProgress =
    BridgeAttachmentDownloadProgress(
        downloadId = downloadId,
        attemptToken = attemptToken,
        sequence = sequence,
        recordVersion = recordVersion,
        phase = phase,
        status = status,
        transfer =
            testTransfer(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                percent = percent,
                bytesPerSecond = bytesPerSecond,
                phase = raftBridgeTransferPhase(phase, status, downloadedBytes)
            )
    )

/** [BridgeAttachmentDownloadProgress.copy] by the wire fields, re-decoded like a new host event. */
internal fun BridgeAttachmentDownloadProgress.copy(
    downloadId: String = this.downloadId,
    attemptToken: String = this.attemptToken,
    sequence: Long = this.sequence,
    recordVersion: Long? = this.recordVersion,
    phase: String = this.phase,
    status: String = this.status,
    percent: Int? = this.percent,
    downloadedBytes: Long = this.downloadedBytes,
    totalBytes: Long? = this.totalBytes,
    bytesPerSecond: Long? = this.bytesPerSecond
): BridgeAttachmentDownloadProgress =
    BridgeAttachmentDownloadProgress(
        downloadId = downloadId,
        attemptToken = attemptToken,
        sequence = sequence,
        recordVersion = recordVersion,
        phase = phase,
        status = status,
        percent = percent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond
    )

