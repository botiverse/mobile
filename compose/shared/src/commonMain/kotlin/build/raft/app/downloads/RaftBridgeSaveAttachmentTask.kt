package build.raft.app.downloads

import build.raft.app.AttachmentOwnerLease
import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.base.BridgeModule

/**
 * Download-centre task that saves one attachment through the host's `saveAttachment` (Android:
 * the public Downloads folder; iOS: the Files export). The bridge callback is retained after the
 * starting page is destroyed, so the result still reaches the centre.
 *
 * [resolveUrl] gives a fresh short-lived URL for a retry; the first run uses [initialUrl].
 * [onPageProgress] mirrors samples to the page that started the download while it lives.
 */
internal class RaftBridgeSaveAttachmentTask(
    private val bridge: BridgeModule,
    private val owner: AttachmentOwnerLease,
    private val attachmentId: String,
    private val fileName: String,
    private val mimeType: String?,
    private val initialUrl: String,
    private val downloadIdForAttempt: (Int) -> String,
    private val resolveUrl: suspend () -> String?,
    private val onPageProgress: ((BridgeAttachmentDownloadProgress) -> Unit)? = null
) : RaftDownloadTask {
    private var hostDownloadId: String? = null
    private var attemptToken: String? = null
    private var recordVersion: Long? = null

    override suspend fun run(attempt: Int, onProgress: (RaftDownloadProgressUpdate) -> Unit): RaftDownloadOutcome {
        val requestedDownloadId = downloadIdForAttempt(attempt)
        // BridgeModule scopes the id to the principal before the host sees it; control uses that.
        hostDownloadId = "${owner.principalKey.value}:$requestedDownloadId"
        attemptToken = null
        recordVersion = null
        val url = if (attempt <= 1) initialUrl else resolveUrl() ?: initialUrl
        if (url.isBlank()) return RaftDownloadOutcome.Failed(hostStatus = "url_unavailable")
        val result =
            bridge.saveAttachment(
                url = url,
                attachmentId = attachmentId,
                fileName = fileName,
                owner = owner,
                mimeType = mimeType,
                downloadId = requestedDownloadId,
                retainAfterPageDestroyed = true,
                onProgress = { progress ->
                    progress.downloadId.takeIf { it.isNotBlank() }?.let { hostDownloadId = it }
                    progress.attemptToken.takeIf { it.isNotBlank() }?.let { attemptToken = it }
                    progress.recordVersion?.let { recordVersion = it }
                    onProgress(progress.toCenterProgress())
                    if (attempt <= 1) onPageProgress?.invoke(progress)
                }
            )
        val status = result?.optString("status").orEmpty()
        val message = result?.optString("message")?.takeIf { it.isNotBlank() }
        return when (val outcome = raftDownloadOutcomeForSaveStatus(status, message)) {
            is RaftDownloadOutcome.Completed ->
                outcome.copy(
                    localUri = result?.optString("uri")?.takeIf { it.isNotBlank() },
                    sizeBytes = result?.optString("sizeBytes")?.toLongOrNull()
                )
            else -> outcome
        }
    }

    override fun control(action: RaftDownloadControl) {
        val downloadId = hostDownloadId ?: return
        bridge.attachmentDownloadControlDetached(
            downloadId = downloadId,
            owner = owner,
            expectedAttemptToken = attemptToken,
            expectedRecordVersion = recordVersion,
            action =
                when (action) {
                    RaftDownloadControl.Pause -> "pause"
                    RaftDownloadControl.Resume -> "resume"
                    RaftDownloadControl.Cancel -> "cancel"
                }
        )
    }
}

/** Host `saveAttachment` status word to a centre outcome ("saved"/"exported" succeed). */
internal fun raftDownloadOutcomeForSaveStatus(status: String, message: String?): RaftDownloadOutcome =
    when (status) {
        "saved", "exported" -> RaftDownloadOutcome.Completed(hostStatus = status)
        "cancelled" -> RaftDownloadOutcome.Cancelled(hostStatus = status)
        else -> RaftDownloadOutcome.Failed(hostStatus = status.ifBlank { "failed" }, message = message)
    }

internal fun BridgeAttachmentDownloadProgress.toCenterProgress(): RaftDownloadProgressUpdate =
    RaftDownloadProgressUpdate(
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        paused = status == "paused",
        saving = phase.startsWith("export_")
    )
