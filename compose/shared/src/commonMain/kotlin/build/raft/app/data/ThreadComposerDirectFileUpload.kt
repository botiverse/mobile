package build.raft.app.data

import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.ThreadAttachmentUploadAttemptIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ThreadComposerDirectUploadDecision {
    Direct,
    LegacyMultipart,
    TooLarge,
    Cancelled,
}

internal fun shouldUseDirectAttachmentUpload(
    capabilities: AttachmentUploadCapabilities,
    sizeBytes: Long,
): Boolean {
    val threshold = capabilities.directUploadThresholdBytes ?: return false
    return capabilities.directUploadEnabled && sizeBytes >= threshold
}

internal fun decideThreadComposerDirectUpload(
    capabilities: AttachmentUploadCapabilities?,
    sizeBytes: Long,
    cancelled: Boolean,
): ThreadComposerDirectUploadDecision {
    if (cancelled) return ThreadComposerDirectUploadDecision.Cancelled
    if (capabilities == null) return ThreadComposerDirectUploadDecision.LegacyMultipart
    if (sizeBytes > capabilities.maxBytes) return ThreadComposerDirectUploadDecision.TooLarge
    return if (shouldUseDirectAttachmentUpload(capabilities, sizeBytes)) {
        ThreadComposerDirectUploadDecision.Direct
    } else {
        ThreadComposerDirectUploadDecision.LegacyMultipart
    }
}

internal data class ThreadComposerResolvedUpload(
    val file: PlatformFile,
    val decision: ThreadComposerDirectUploadDecision,
)

internal class ThreadComposerDirectUploadClientRequestIds {
    private val mutex = Mutex()
    private val ids = mutableMapOf<ThreadAttachmentUploadAttemptIdentity, String>()

    suspend fun idFor(identity: ThreadAttachmentUploadAttemptIdentity): String =
        mutex.withLock {
            ids.getOrPut(identity) { newAttachmentUploadClientRequestId() }
        }
}

/**
 * Capabilities miss fail-opens to multipart without stating. When capabilities are
 * present, size must come from the same authority-bound prepare/stat as the later PUT.
 */
internal suspend fun resolveThreadComposerDirectUpload(
    file: PlatformFile,
    capabilities: AttachmentUploadCapabilities?,
    cancelled: Boolean,
    prepare: suspend (PlatformFile) -> DataResult<PlatformFile>,
): DataResult<ThreadComposerResolvedUpload> {
    if (cancelled) {
        return DataResult.Content(
            ThreadComposerResolvedUpload(file, ThreadComposerDirectUploadDecision.Cancelled),
        )
    }
    if (capabilities == null) {
        return DataResult.Content(
            ThreadComposerResolvedUpload(file, ThreadComposerDirectUploadDecision.LegacyMultipart),
        )
    }
    val prepared =
        when (val preparedResult = prepare(file.copy(sizeBytes = null))) {
            is DataResult.Content -> preparedResult.data
            is DataResult.Error -> return preparedResult
            is DataResult.NoPermission -> return preparedResult
            is DataResult.Empty ->
                return DataResult.Error(ScreenError(code = "attachment_size_unavailable", retryable = false))
        }
    val sizeBytes = prepared.sizeBytes
    if (sizeBytes == null || sizeBytes <= 0L) {
        return DataResult.Error(ScreenError(code = "attachment_size_unavailable", retryable = false))
    }
    return DataResult.Content(
        ThreadComposerResolvedUpload(
            file = prepared,
            decision = decideThreadComposerDirectUpload(capabilities, sizeBytes, cancelled = false),
        ),
    )
}

internal enum class ThreadComposerDirectUploadPhase {
    Creating,
    Putting,
    Verifying,
}

private val PUT_UNCERTAIN_CODES =
    setOf(
        "network_request_failed",
        "attachment_presigned_upload_timeout",
        "attachment_upload_session_put_failed",
    )

private val COMPLETE_RETRY_CODES =
    setOf(
        "upload_object_not_found",
        "upload_verification_in_progress",
    )

/**
 * Per-file Thread composer direct upload. Independent of the Gallery N/N compensating
 * batch: one file can fail without retracting siblings, and PUT uncertainty stays on
 * the same session/clientRequestId.
 */
internal suspend fun DirectAttachmentUploadSessionGateway.uploadOneThreadFile(
    channelId: ChannelId,
    file: PlatformFile,
    clientRequestId: String,
    onPhase: (ThreadComposerDirectUploadPhase) -> Unit = {},
    onUploadProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)? = null,
): DataResult<Attachment> {
    val prepared =
        when (val preparedResult = prepare(file.copy(sizeBytes = null))) {
            is DataResult.Content -> preparedResult.data
            is DataResult.Error -> return preparedResult
            is DataResult.NoPermission -> return preparedResult
            is DataResult.Empty ->
                return DataResult.Error(ScreenError(code = "attachment_size_unavailable", retryable = false))
        }
    val sizeBytes =
        prepared.sizeBytes
            ?: return DataResult.Error(ScreenError(code = "attachment_size_unavailable", retryable = false))
    currentCoroutineContext().ensureActive()
    onPhase(ThreadComposerDirectUploadPhase.Creating)
    val createRequest =
        AttachmentUploadSessionCreateRequest(
            channelId = channelId,
            filename = prepared.fileName,
            mimeType = prepared.mimeType.orEmpty().ifBlank { "application/octet-stream" },
            sizeBytes = sizeBytes,
            clientRequestId = clientRequestId,
        )
    val created =
        when (val createResult = create(createRequest)) {
            is DataResult.Content -> createResult.data
            is DataResult.Error -> return createResult
            is DataResult.NoPermission -> return createResult
            is DataResult.Empty ->
                return DataResult.Error(ScreenError(code = "attachment_upload_session_create_failed", retryable = true))
        }
    return try {
        currentCoroutineContext().ensureActive()
        onPhase(ThreadComposerDirectUploadPhase.Putting)
        val putResult = put(created.upload, prepared, onUploadProgress)
        if (putResult !is DataResult.Content) {
            val recovered =
                recoverSameSessionAfterUncertainPut(
                    expectedUploadId = created.uploadId,
                    expectedAttachmentId = created.attachmentId,
                    putResult = putResult,
                )
            if (recovered != null) {
                if (recovered is DataResult.Error &&
                    recovered.error.code == "attachment_upload_session_contract_invalid"
                ) {
                    cancelCreatedSession(created.uploadId)
                }
                return recovered
            }
            cancelCreatedSession(created.uploadId)
            return putResult.asAttachmentFailure("attachment_upload_session_put_failed")
        }
        currentCoroutineContext().ensureActive()
        onPhase(ThreadComposerDirectUploadPhase.Verifying)
        val completed =
            completeThreadFile(
                expectedUploadId = created.uploadId,
                expectedAttachmentId = created.attachmentId,
            )
        if (completed is DataResult.Error &&
            completed.error.code == "attachment_upload_session_contract_invalid"
        ) {
            cancelCreatedSession(created.uploadId)
        }
        completed
    } catch (cancellation: CancellationException) {
        cancelCreatedSession(created.uploadId)
        throw cancellation
    }
}

private suspend fun DirectAttachmentUploadSessionGateway.cancelCreatedSession(uploadId: String) {
    withContext(NonCancellable) {
        runCatching { cancel(uploadId) }
    }
}

private suspend fun DirectAttachmentUploadSessionGateway.recoverSameSessionAfterUncertainPut(
    expectedUploadId: String,
    expectedAttachmentId: AttachmentId,
    putResult: DataResult<*>,
): DataResult<Attachment>? {
    val error = (putResult as? DataResult.Error)?.error ?: return null
    val cancelled = error.code.equals("cancelled", ignoreCase = true)
    if (cancelled) return null
    val uncertain =
        error.retryable ||
            error.code.lowercase() in PUT_UNCERTAIN_CODES ||
            error.serverCode?.lowercase() in PUT_UNCERTAIN_CODES
    if (!uncertain) return null
    val statusResult = status(expectedUploadId)
    val statusView = (statusResult as? DataResult.Content)?.data
    if (statusView?.state == "completed") {
        return boundCompletedAttachment(
            expectedUploadId = expectedUploadId,
            expectedAttachmentId = expectedAttachmentId,
            uploadId = statusView.uploadId,
            state = statusView.state,
            attachment = statusView.attachment,
        )
    }
    return when (
        val completed =
            completeThreadFile(
                expectedUploadId = expectedUploadId,
                expectedAttachmentId = expectedAttachmentId,
            )
    ) {
        is DataResult.Content -> completed
        is DataResult.Error -> {
            val code = completed.error.serverCode?.lowercase() ?: completed.error.code.lowercase()
            if (code == "upload_object_not_found") {
                DataResult.Error(
                    ScreenError(
                        code = "UPLOAD_OBJECT_PUT_UNCERTAIN",
                        retryable = true,
                        serverCode = "UPLOAD_OBJECT_PUT_UNCERTAIN",
                    ),
                )
            } else {
                completed
            }
        }
        else -> completed.asAttachmentFailure("attachment_upload_session_complete_failed")
    }
}

private suspend fun DirectAttachmentUploadSessionGateway.completeThreadFile(
    expectedUploadId: String,
    expectedAttachmentId: AttachmentId,
): DataResult<Attachment> {
    var last: DataResult<AttachmentUploadSessionCompleteResult>? = null
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        val result = complete(expectedUploadId)
        last = result
        val error = (result as? DataResult.Error)?.error
        val serverCode = error?.serverCode?.trim()?.lowercase() ?: error?.code?.lowercase()
        if (result is DataResult.Content) {
            return boundCompletedAttachment(
                expectedUploadId = expectedUploadId,
                expectedAttachmentId = expectedAttachmentId,
                uploadId = result.data.uploadId,
                state = result.data.state,
                attachment = result.data.attachment,
            )
        }
        if (serverCode !in COMPLETE_RETRY_CODES || attempt == 2) {
            return result.asAttachmentFailure("attachment_upload_session_complete_failed")
        }
    }
    return last.asAttachmentFailure("attachment_upload_session_complete_failed")
}

private fun boundCompletedAttachment(
    expectedUploadId: String,
    expectedAttachmentId: AttachmentId,
    uploadId: String,
    state: String,
    attachment: Attachment?,
): DataResult<Attachment> {
    if (
        state != "completed" ||
        uploadId != expectedUploadId ||
        !isLegalAttachmentUploadSessionId(uploadId) ||
        attachment == null ||
        attachment.id != expectedAttachmentId ||
        !isLegalAttachmentUploadSessionId(attachment.id.value)
    ) {
        return DataResult.Error(
            ScreenError(code = "attachment_upload_session_contract_invalid", retryable = false),
        )
    }
    return DataResult.Content(attachment)
}

private fun DataResult<*>?.asAttachmentFailure(fallbackCode: String): DataResult<Attachment> =
    when (this) {
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
        else -> DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
    }
