package build.raft.app.data

import build.raft.app.model.Attachment
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
): DataResult<Attachment> {
    val prepared =
        when (val preparedResult = prepare(file)) {
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
        val putResult = put(created.upload, prepared)
        if (putResult !is DataResult.Content) {
            val recovered = recoverSameSessionAfterUncertainPut(created.uploadId, putResult)
            if (recovered != null) return recovered
            runCatching { cancel(created.uploadId) }
            return putResult.asAttachmentFailure("attachment_upload_session_put_failed")
        }
        currentCoroutineContext().ensureActive()
        onPhase(ThreadComposerDirectUploadPhase.Verifying)
        completeThreadFile(created.uploadId)
    } catch (cancellation: CancellationException) {
        runCatching { cancel(created.uploadId) }
        throw cancellation
    }
}

private suspend fun DirectAttachmentUploadSessionGateway.recoverSameSessionAfterUncertainPut(
    uploadId: String,
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
    val statusResult = status(uploadId)
    val statusView = (statusResult as? DataResult.Content)?.data
    if (statusView?.state == "completed" && statusView.attachment != null) {
        return DataResult.Content(statusView.attachment)
    }
    return when (val completed = completeThreadFile(uploadId)) {
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
    uploadId: String,
): DataResult<Attachment> {
    var last: DataResult<AttachmentUploadSessionCompleteResult>? = null
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        val result = complete(uploadId)
        last = result
        val error = (result as? DataResult.Error)?.error
        val serverCode = error?.serverCode?.trim()?.lowercase() ?: error?.code?.lowercase()
        if (result is DataResult.Content) {
            val value = result.data
            if (value.state == "completed") {
                return DataResult.Content(value.attachment)
            }
            return DataResult.Error(ScreenError(code = "attachment_upload_session_contract_invalid", retryable = false))
        }
        if (serverCode !in COMPLETE_RETRY_CODES || attempt == 2) {
            return result.asAttachmentFailure("attachment_upload_session_complete_failed")
        }
    }
    return last.asAttachmentFailure("attachment_upload_session_complete_failed")
}

private fun DataResult<*>?.asAttachmentFailure(fallbackCode: String): DataResult<Attachment> =
    when (this) {
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
        else -> DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
    }
