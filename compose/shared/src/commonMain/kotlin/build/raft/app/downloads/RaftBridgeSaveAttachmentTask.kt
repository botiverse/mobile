package build.raft.app.downloads

import build.raft.app.AttachmentOwnerLease
import build.raft.app.ProcessAttachmentAuthScopes
import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.base.BridgeModule
import build.raft.app.base.raftAttachmentHostDownloadId

/**
 * Download-centre task that saves one attachment through the host's `saveAttachment` (Android:
 * the public Downloads folder; iOS: the Files export; Desktop: the system save panel). The bridge
 * callback is retained after the starting page is destroyed, so the result still reaches the centre.
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
    private var onPageProgress: ((BridgeAttachmentDownloadProgress) -> Unit)? = null
) : RaftDownloadTask {
    private var hostDownloadId: String? = null
    private var attemptToken: String? = null
    private var recordVersion: Long? = null

    override suspend fun run(attempt: Int, onProgress: (RaftDownloadProgressUpdate) -> Unit): RaftDownloadOutcome {
        try {
            if (!ProcessAttachmentAuthScopes.allows(owner)) {
                return RaftDownloadOutcome.Cancelled()
            }
            val requestedDownloadId = downloadIdForAttempt(attempt)
            // BridgeModule scopes the id to the principal before the host sees it; control uses that.
            hostDownloadId = raftAttachmentHostDownloadId(owner, requestedDownloadId)
            attemptToken = null
            recordVersion = null
            val url = if (attempt <= 1) initialUrl else resolveUrl().orEmpty()
            if (!ProcessAttachmentAuthScopes.allows(owner)) {
                return RaftDownloadOutcome.Cancelled()
            }
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
            val errorKind = result?.optString("errorKind")?.takeIf { it.isNotBlank() }
            val traceId = result?.optString("traceId")?.takeIf { it.isNotBlank() }
            return when (val outcome = raftDownloadOutcomeForSaveStatus(status, message, errorKind, traceId)) {
                is RaftDownloadOutcome.Completed ->
                    outcome.copy(
                        localUri = result?.optString("uri")?.takeIf { it.isNotBlank() },
                        sizeBytes = result?.optString("sizeBytes")?.toLongOrNull()
                    )
                else -> outcome
            }
        } finally {
            if (attempt <= 1) onPageProgress = null
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
internal fun raftDownloadOutcomeForSaveStatus(
    status: String,
    message: String?,
    errorKind: String? = null,
    traceId: String? = null,
): RaftDownloadOutcome =
    when (status) {
        "saved", "exported" -> RaftDownloadOutcome.Completed(hostStatus = status)
        "cancelled" -> RaftDownloadOutcome.Cancelled(hostStatus = status)
        else ->
            RaftDownloadOutcome.Failed(
                hostStatus = status.ifBlank { "failed" },
                message = message,
                errorKind = errorKind,
                traceId = traceId,
            )
    }

internal fun BridgeAttachmentDownloadProgress.toCenterProgress(): RaftDownloadProgressUpdate =
    RaftDownloadProgressUpdate(transfer = transfer, saving = phase.startsWith("export_"))
