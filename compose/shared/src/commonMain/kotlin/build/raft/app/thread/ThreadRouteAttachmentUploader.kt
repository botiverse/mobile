package build.raft.app

import build.raft.app.transfer.RaftTransferMeter
import build.raft.app.transfer.RaftTransferProgress
import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.RaftAttachmentUploadAttempt
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.DataResult
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.ThreadComposerDirectUploadDecision
import build.raft.app.data.ThreadComposerDirectUploadPhase
import build.raft.app.data.resolveThreadComposerDirectUpload
import build.raft.app.data.ThreadComposerDirectUploadClientRequestIds
import build.raft.app.data.newAttachmentUploadClientRequestId
import build.raft.app.format.RaftFormat
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.ChannelId
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Semaphore

/**
 * The composer's attachment upload pipeline: what used to be `uploadComposerAttachmentFiles` and its
 * helpers ([showAttachmentUploadFailure], [admittedComposerAttachmentFiles]), local functions of
 * `SharedThreadRouteContentContent`, moved verbatim.
 *
 * Built every composition from the values those functions captured (states as their [State] /
 * [MutableState], read and written when an upload runs, as the functions did); never remembered.
 * The draft's uploads, their semaphore and the remembered upload files stay the route's; they are
 * passed in. The route's own local functions the pipeline calls ([routeAuthRequired],
 * [localizedAttachmentUploadFailedDetail]) are function values behind members of the same signature.
 */
internal class ThreadRouteAttachmentUploader(
    val bridge: build.raft.app.base.BridgeModule,
    val dataSource: SharedThreadDataSource,
    val pager: Pager,
    val storeScope: RaftShellStoreScope,
    val labels: ThreadRouteLabels,
    val userFacingErrorLabels: ThreadUserFacingErrorLabels,
    val latestCurrentSessionState: State<StoredAuthSession?>,
    val composerDraftViewModel: SharedThreadComposerDraftViewModel,
    val composerSurface: ThreadComposerSurface,
    val attachmentUploads: RaftComposerServerUploads,
    val attachmentUploadOrchestrator: ThreadAttachmentUploadOrchestrator,
    val attachmentUploadSemaphore: Semaphore,
    val attachmentUploadFilesByLocalIdState: MutableState<Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>>,
    val threadDirectUploadClientRequestIds: ThreadComposerDirectUploadClientRequestIds,
    val routeAuthRequiredFn: (DataResult.NoPermission) -> Boolean,
    val localizedAttachmentUploadFailedDetailFn: (detail: String) -> String,
) {
    val latestCurrentSession by latestCurrentSessionState
    var attachmentUploadFilesByLocalId by attachmentUploadFilesByLocalIdState
    val attachmentLimitUnavailableLabel = labels.attachmentLimitUnavailableLabel
    val attachmentTooLargeTemplate = labels.attachmentTooLargeTemplate
    val genericThreadErrorLabel = labels.genericThreadErrorLabel
    val attachmentPickerUnavailableLabel = labels.attachmentPickerUnavailableLabel
    fun localizedAttachmentTooLarge(fileSize: Long, maxBytes: Long): String =
        attachmentTooLargeTemplate
            .replace(ThreadLocalizedFeedbackPlaceholder, RaftFormat.bytes(fileSize))
            .replace("__RAFT_ATTACHMENT_LIMIT__", RaftFormat.byteLimit(maxBytes))

    fun localizedAttachmentUploadFailedDetail(detail: String): String = localizedAttachmentUploadFailedDetailFn(detail)

    fun routeAuthRequired(result: DataResult.NoPermission): Boolean = routeAuthRequiredFn(result)

    /**
     * A draft-owned upload failed and its row now offers Retry: the upgrade dialog when the month's
     * uploads are used up (`FILE_UPLOAD_QUOTA_EXCEEDED`, web MessageInput `upload_monthly_quota`;
     * nothing opens on a paid plan) or the external channel is frozen (`external_limit_frozen`),
     * and the failure toast.
     */
    fun showAttachmentUploadFailure(errorCode: String, traceId: String? = null) {
        threadAttachmentUploadFailureUpgradeGate(errorCode)?.let { gate ->
            pager.openRaftUpgradeDialog(gate, storeScope)
        }
        bridge.errorToast(
            localizedAttachmentUploadFailedDetail(
                userFacingErrorLabels.forErrorCode(errorCode) ?: genericThreadErrorLabel
            ),
            traceId
        )
    }
    suspend fun admittedComposerAttachmentFiles(
        files: List<PlatformFile>,
        maxBytesOverride: Long? = null,
        skipClientSizeGate: Boolean = false,
    ): List<PlatformFile> {
        if (skipClientSizeGate) return files
        val maxBytes =
            maxBytesOverride ?: when (val capability = dataSource.attachmentUploadMaxBytes()) {
                is DataResult.Content -> capability.data
                is DataResult.NoPermission -> {
                    routeAuthRequired(capability)
                    bridge.errorToast(attachmentLimitUnavailableLabel, capability.blocker)
                    return emptyList()
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    bridge.errorToast(attachmentLimitUnavailableLabel, capability)
                    return emptyList()
                }
            }
        val sizeDecision = applyServerAttachmentUploadLimit(files, maxBytes)
        if (sizeDecision.rejected.isNotEmpty()) {
            val largest = sizeDecision.largestRejectedBytes ?: 0L
            bridge.toast(localizedAttachmentTooLarge(largest, maxBytes))
            // Free's per-file ceiling: the upgrade dialog too (web `upload_file_size`).
            if (raftUploadSizeLimitIsFree(maxBytes)) {
                pager.openRaftUpgradeDialog(RaftUpgradeGate.UploadFileSize, storeScope)
            }
            bridge.log(
                "thread_composer_attachment_size_rejected rejected=${sizeDecision.rejected.size} " +
                    "largestBytes=$largest maxBytes=$maxBytes"
            )
        }
        return sizeDecision.accepted
    }
    suspend fun uploadComposerAttachmentFiles(
        files: List<PlatformFile>,
        replaceAttachmentId: String? = null,
        requireAllFiles: Boolean = false,
        replaceAttachmentIdsByLocalId: Map<String, String> = emptyMap()
    ): Boolean {
        // slock.attachment.upload_finished: the clock of each upload starts as the picked files arrive.
        val uploadPickedAt = TimeSource.Monotonic.markNow()
        // ... and the event is filed under the server the upload started on, whatever the session
        // says by the time it ends.
        val uploadSessionServerId = latestCurrentSession?.currentServerId
        // Upload callbacks must retain the store-owned draft identity they were admitted under.
        // A route can remain mounted while the composer store transitions to another key; using
        // the key captured here makes every progress/success/failure publication fail closed.
        val draftKey = normalizeThreadAttachmentUploadDraftKey(composerDraftViewModel.state.value.draftKey)
            ?: return false
        val channelId = composerSurface.sendChannelId
        if (channelId == null) {
            bridge.toast(attachmentPickerUnavailableLabel)
            return false
        }
        // Capture one complete authority witness for the all-files/direct-session path and reuse
        // it for capabilities, upload, and compensation. This prevents a session/account/server
        // switch between those calls from silently falling back to an unbound repository.
        val directUploadOperation =
            dataSource.captureDirectAttachmentUploadOperation { message -> bridge.log(message) }
        if (requireAllFiles && directUploadOperation == null) {
            bridge.logWarn("thread_composer_attachment_batch_rejected reason=authority_unavailable")
            return false
        }
        val uploadServerId =
            directUploadOperation?.authority?.serverId?.takeIf { it.isNotBlank() } ?: uploadSessionServerId
        // Never await the capabilities request on the per-file pick path: on a weak network it
        // takes seconds and would hold the composer chip back.  Use the cached value if fresh;
        // otherwise the background upload task resolves it after the chip is admitted.  The
        // all-files (Gallery) path must have an explicit receipt before admission, so it awaits.
        var probeError: ScreenError? = null
        val directCapabilities: AttachmentUploadCapabilities? =
            if (directUploadOperation == null) {
                null
            } else if (requireAllFiles) {
                val probe = dataSource.attachmentUploadCapabilities(directUploadOperation)
                if (probe is DataResult.NoPermission) {
                    routeAuthRequired(probe)
                    bridge.errorToast(attachmentLimitUnavailableLabel, probe.blocker)
                    return false
                }
                if ((probe as? DataResult.Error)?.error?.code.equals("cancelled", ignoreCase = true)) {
                    return false
                }
                probeError = (probe as? DataResult.Error)?.error
                (probe as? DataResult.Content)?.data
            } else {
                dataSource.peekAttachmentUploadCapabilities(directUploadOperation)
            }
        // Per-file Thread path fail-opens to multipart when capabilities cannot be read.
        // Gallery N/N remains fail-closed: it cannot compensate without an explicit capability.
        if (requireAllFiles && directCapabilities == null) {
            val error = probeError
            if (error != null) {
                bridge.errorToast(attachmentLimitUnavailableLabel, error)
            } else {
                bridge.errorToast(attachmentLimitUnavailableLabel, traceId = null)
            }
            return false
        }
        val admittedFiles = admittedComposerAttachmentFiles(
            files = files,
            maxBytesOverride = directCapabilities?.maxBytes,
            // Caller sizeBytes is an untrusted hint. Direct and fail-open paths
            // decide TooLarge/threshold only after authority-bound prepare/stat
            // (or skip the gate entirely when capabilities cannot be read).
            skipClientSizeGate = true,
        )
        if (admittedFiles.isEmpty() || (requireAllFiles && admittedFiles.size != files.size)) {
            if (requireAllFiles && admittedFiles.size != files.size) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=size_gate " +
                        "expected=${files.size} admitted=${admittedFiles.size}"
                )
            }
            return false
        }
        if (requireAllFiles && admittedFiles.any { file -> file.id.isBlank() }) {
            bridge.logWarn("thread_composer_attachment_batch_rejected reason=blank_local_id")
            return false
        }
        val activeUploadFileRefs =
            attachmentUploadOrchestrator.activeAttemptIds(draftKey)
                .filter { id -> attachmentUploadOrchestrator.isActive(id, draftKey) }
                .mapNotNull {
                    id -> attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, draftKey)?.fileRef?.takeIf { it.isNotBlank() }
                }
                .toSet()
        val activeUploadIds = attachmentUploadOrchestrator.activeAttemptIds(draftKey)
        val acceptedFileRefs = mutableSetOf<String>()
        val acceptedLocalIds = mutableSetOf<String>()
        val acceptedFiles =
            admittedFiles.filter { file ->
                val fileRef = file.fileRef?.takeIf { it.isNotBlank() }
                val isDuplicate =
                    file.id in activeUploadIds ||
                        !acceptedLocalIds.add(file.id) ||
                    fileRef != null &&
                        (fileRef in activeUploadFileRefs || !acceptedFileRefs.add(fileRef))
                !isDuplicate
            }
        if (requireAllFiles && acceptedFiles.size != admittedFiles.size) {
            bridge.logWarn(
                "thread_composer_attachment_batch_rejected reason=duplicate_or_active " +
                    "expected=${admittedFiles.size} accepted=${acceptedFiles.size}"
            )
            return false
        }
        if (acceptedFiles.size != admittedFiles.size) {
            bridge.log(
                "thread_composer_attachment_duplicate_skip skipped=${admittedFiles.size - acceptedFiles.size} " +
                    "activeUploads=${activeUploadFileRefs.size}"
            )
        }
        val existingAttachmentsById =
            composerDraftViewModel.state.value.uploadedAttachments.associateBy { attachment -> attachment.id }
        val localAttachments =
            acceptedFiles
                .filter { file -> file.id.isNotBlank() }
                .map { file ->
                    existingAttachmentsById[file.id]
                        ?.copy(
                            fileName = file.fileName,
                            sizeBytes = file.sizeBytes ?: 0L,
                            mimeType = file.mimeType?.takeIf(String::isNotBlank),
                            previewUri = file.composerLocalPreviewUri(),
                            status = "uploading",
                            errorCode = null,
                            fileRef = file.fileRef,
                            source = file.source ?: existingAttachmentsById[file.id]?.source,
                            uploadTransfer = null
                        )
                        ?: SharedThreadComposerAttachment(
                            id = file.id,
                            fileName = file.fileName,
                            sizeLabel = "",
                            sizeBytes = file.sizeBytes ?: 0L,
                            mimeType = file.mimeType?.takeIf(String::isNotBlank),
                            previewUri = file.composerLocalPreviewUri(),
                            status = "uploading",
                            fileRef = file.fileRef,
                            source = file.source
                        )
                }
        if (requireAllFiles && localAttachments.size != acceptedFiles.size) {
            bridge.logWarn(
                "thread_composer_attachment_batch_rejected reason=local_projection " +
                    "expected=${acceptedFiles.size} actual=${localAttachments.size}"
            )
            return false
        }
        val replacementSlotsByLocalId =
            acceptedFiles.associate { file ->
                file.id to
                    threadComposerReplacementSlotId(
                        replaceAttachmentId =
                            replaceAttachmentIdsByLocalId[file.id] ?: replaceAttachmentId,
                        localAttachmentId = file.id
                    )
            }
        var admittedLocalAdmissions: Map<String, SharedThreadComposerAttachmentAdmission> = emptyMap()
        if (localAttachments.isNotEmpty()) {
            val keyedFiles =
                acceptedFiles
                    .filter { file -> file.id.isNotBlank() }
                    .mapNotNull { file ->
                        threadAttachmentUploadAttemptIdentity(file.id, draftKey)?.let { identity ->
                            identity to file
                        }
                    }
                    .toMap()
            attachmentUploadFilesByLocalId =
                attachmentUploadFilesByLocalId + keyedFiles
            val admissionReceipt =
                try {
                    withContext(NonCancellable) {
                        composerDraftViewModel.acceptQueued(
                            SharedThreadComposerDraftIntent.AddLocalAttachments(
                                attachments = localAttachments,
                                expectedDraftKey = draftKey,
                                replacementAttachmentIdsByLocalId = replacementSlotsByLocalId
                            )
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    bridge.logWarn(
                        "thread_composer_attachment_admission_exception " +
                            "error=${error::class.simpleName.orEmpty()}"
                    )
                    null
                }
            val admitted =
                admissionReceipt?.accepted == true &&
                    normalizeThreadAttachmentUploadDraftKey(admissionReceipt.state.draftKey) == draftKey &&
                    threadComposerAttachmentAdmissionReceiptIsValid(
                        accepted = admissionReceipt.accepted,
                        before = admissionReceipt.previousState,
                        after = admissionReceipt.state,
                        expectedDraftKey = draftKey,
                        localAttachments = localAttachments,
                        replacementAttachmentIdsByLocalId = replacementSlotsByLocalId
                    ) &&
                    localAttachments.all { expected ->
                        admissionReceipt.state.attachmentAdmissionsByLocalId[expected.id]
                            ?.replacementAttachmentId
                            ?.trim()
                            ?.ifBlank { expected.id } == replacementSlotsByLocalId[expected.id]
                    } &&
                    localAttachments.all { expected ->
                        admissionReceipt.state.uploadedAttachments.any { actual ->
                            actual.id == expected.id &&
                                actual.status == "uploading" &&
                                actual.fileRef?.trim() == expected.fileRef?.trim() &&
                                actual.source?.trim() == expected.source?.trim()
                        }
                    } &&
                    localAttachments.none { expected ->
                        expected.id in admissionReceipt.state.removedAttachmentIds
                    }
            if (!admitted) {
                attachmentUploadFilesByLocalId =
                    attachmentUploadFilesByLocalId.filterKeys { identity ->
                        !(identity.draftKey == draftKey && identity.localAttachmentId in acceptedLocalIds)
                    }
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=admission_receipt " +
                        "draftKey=$draftKey"
                )
                return false
            }
            admittedLocalAdmissions =
                localAttachments
                    .mapNotNull { attachment ->
                        admissionReceipt.state.attachmentAdmissionsByLocalId[attachment.id]
                            ?.let { admission -> attachment.id to admission }
                    }
                    .toMap()
            bridge.log(
                "thread_composer_attachment_local_queue fileCount=${localAttachments.size} " +
                    "ids=${localAttachments.joinToString(",") { it.id }}"
            )
        }

        if (requireAllFiles) {
            // Gallery handoff is an atomic N/N surface.  Do not launch one legacy multipart
            // attempt per file: a sibling can fail after an earlier server attachment has been
            // published, and the legacy route has no retract contract.  The direct-session
            // coordinator stages every member, compensates every created session on any failure,
            // and returns ready attachments only after the whole batch is complete.
            // Gallery on this train calls with requireAllFiles=false, so this branch stays dormant.
            val acceptedLocalIds = acceptedFiles.map(PlatformFile::id).toSet()
            suspend fun markGalleryBatchFailed(errorCode: String, traceId: String? = null): Boolean {
                val currentFailureState = composerDraftViewModel.state.value
                val markableIds =
                    acceptedLocalIds
                        .filter { id ->
                            normalizeThreadAttachmentUploadDraftKey(currentFailureState.draftKey) == draftKey &&
                                id !in currentFailureState.removedAttachmentIds &&
                                currentFailureState.attachmentAdmissionsByLocalId[id] == admittedLocalAdmissions[id] &&
                                currentFailureState.uploadedAttachments.any { attachment ->
                                    attachment.id == id && attachment.status == "uploading"
                                }
                        }
                        .toSet()
                val markableAdmissions =
                    markableIds.mapNotNull { id -> admittedLocalAdmissions[id]?.let { id to it } }.toMap()
                val receipt =
                    if (markableIds.isEmpty()) {
                        null
                    } else try {
                        withContext(NonCancellable) {
                            composerDraftViewModel.acceptQueued(
                                SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                                    attachmentIds = markableIds,
                                    errorCode = errorCode,
                                    expectedDraftKey = draftKey,
                                    expectedAdmissions = markableAdmissions
                                )
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "thread_composer_attachment_batch_failure_projection_exception " +
                                "error=${error::class.simpleName.orEmpty()}"
                        )
                        null
                    }
                val receiptState = receipt?.state
                val accepted =
                    markableIds.isEmpty() ||
                        (
                                receipt?.accepted == true &&
                                normalizeThreadAttachmentUploadDraftKey(receiptState?.draftKey) == draftKey &&
                                markableIds.all { id ->
                                    receiptState?.uploadedAttachments.orEmpty().any { attachment ->
                                        attachment.id == id && attachment.status == "failed" &&
                                            attachment.errorCode == errorCode
                                    }
                                }
                        )
                if (!accepted) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_failure_projection_rejected " +
                            "draftKey=$draftKey"
                    )
                }
                showAttachmentUploadFailure(errorCode, traceId)
                return accepted
            }

            suspend fun compensateGalleryBatch(
                batch: build.raft.app.data.DirectAttachmentUploadBatch,
                reason: String,
            ): Boolean {
                val compensated =
                    try {
                        withContext(NonCancellable) {
                            requireNotNull(directUploadOperation).compensate(batch)
                        }
                    } catch (cancelled: CancellationException) {
                        bridge.logWarn(
                            "thread_composer_attachment_batch_compensation_cancelled " +
                                "reason=$reason"
                        )
                        false
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "thread_composer_attachment_batch_compensation_exception " +
                                "reason=$reason error=${error::class.simpleName.orEmpty()}"
                        )
                        false
                    }
                if (!compensated) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_compensation_failed reason=$reason"
                    )
                }
                return compensated
            }

            val batchResult =
                try {
                    withContext(Dispatchers.Default) {
                        attachmentUploadSemaphore.withPermit {
                            requireNotNull(directUploadOperation).upload(
                                channelId = ChannelId(channelId),
                                files = acceptedFiles,
                                admittedCapabilities = requireNotNull(directCapabilities),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        markGalleryBatchFailed("attachment_upload_batch_cancelled")
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_upload_exception " +
                            "error=${error::class.simpleName.orEmpty()}"
                    )
                    markGalleryBatchFailed("attachment_upload_batch_failed")
                    return false
                }

            val batch =
                when (batchResult) {
                    is DataResult.Content -> batchResult.data
                    is DataResult.NoPermission -> {
                        routeAuthRequired(batchResult)
                        markGalleryBatchFailed(
                            resolveThreadAttachmentUploadFailureCode(
                                batchResult,
                                emptyCode = "attachment_upload_batch_empty",
                            ),
                            traceId = batchResult.blocker.traceId
                        )
                        return false
                    }
                    is DataResult.Error -> {
                        markGalleryBatchFailed(
                            resolveThreadAttachmentUploadFailureCode(
                                batchResult,
                                emptyCode = "attachment_upload_batch_empty",
                            ),
                            traceId = batchResult.error.traceId
                        )
                        return false
                    }
                    is DataResult.Empty -> {
                        markGalleryBatchFailed("attachment_upload_batch_empty")
                        return false
                    }
                }

            val uploads = batch.uploads
            val uploadIds = uploads.map { upload -> upload.uploadId }
            val uploadedAttachmentIds = uploads.map { upload -> upload.attachment.id.value }
            val batchContractValid =
                uploads.size == acceptedFiles.size &&
                    uploads.map { upload -> upload.localId }.toSet() == acceptedLocalIds &&
                    uploads.map { upload -> upload.localId }.size == acceptedLocalIds.size &&
                    uploadIds.toSet().size == uploadIds.size &&
                    batch.sessionIds.toSet() == uploadIds.toSet() &&
                    uploadedAttachmentIds.all { id -> id.isNotBlank() } &&
                    uploadedAttachmentIds.toSet().size == uploadedAttachmentIds.size
            if (!batchContractValid) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=contract " +
                        "expected=${acceptedFiles.size} actual=${uploads.size}"
                )
                // Compensation is cleanup evidence only. Preserve the primary contract failure
                // even when a cancel/status operation is incomplete; replacing it with a generic
                // cleanup code would hide the operation that actually rejected the batch.
                compensateGalleryBatch(batch, reason = "contract")
                markGalleryBatchFailed("attachment_upload_session_contract_invalid")
                return false
            }

            val fileByLocalId = acceptedFiles.associateBy(PlatformFile::id)
            val admittedAttachmentsById = localAttachments.associateBy { attachment -> attachment.id }
            val readyAttachments =
                uploads.mapNotNull { upload ->
                    val file = fileByLocalId[upload.localId] ?: return@mapNotNull null
                    val sourceAttachment = admittedAttachmentsById[upload.localId]
                    val serverAttachment = upload.attachment
                    SharedThreadComposerAttachment(
                        id = serverAttachment.id.value,
                        fileName = serverAttachment.name,
                        sizeLabel = serverAttachment.sizeLabel,
                        sizeBytes = serverAttachment.sizeBytes,
                        mimeType = serverAttachment.mimeType.takeIf { it.isNotBlank() },
                        previewUri = file.composerLocalPreviewUri(),
                        status = "ready",
                        fileRef =
                            sourceAttachment
                                ?.takeIf { source -> source.source in ThreadComposerOwnedTemporarySources }
                                ?.fileRef,
                        source = sourceAttachment?.source ?: file.source,
                        externalShareToken = sourceAttachment?.externalShareToken,
                        externalShareAccountId = sourceAttachment?.externalShareAccountId,
                        externalShareServerId = sourceAttachment?.externalShareServerId,
                        // The server confirmed the upload: the transfer is done.
                        uploadTransfer = sourceAttachment?.uploadTransfer?.completed(file.sizeBytes)
                    )
                }
            if (readyAttachments.size != acceptedFiles.size) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=local_mapping " +
                        "expected=${acceptedFiles.size} actual=${readyAttachments.size}"
                )
                compensateGalleryBatch(batch, reason = "local_mapping")
                markGalleryBatchFailed("attachment_upload_batch_incomplete")
                return false
            }

            val replacementsByUploadedId =
                uploads.associate { upload ->
                    upload.attachment.id.value to
                        threadComposerReplacementSlotId(
                            replaceAttachmentId =
                                replaceAttachmentIdsByLocalId[upload.localId]
                                    ?: replaceAttachmentId,
                            localAttachmentId = upload.localId
                        )
                }
            val expectedReplacementAttachmentIdsByLocalId =
                acceptedLocalIds.associateWith { localId ->
                    threadComposerReplacementSlotId(
                        replaceAttachmentId =
                            replaceAttachmentIdsByLocalId[localId]
                                ?: replaceAttachmentId,
                        localAttachmentId = localId
                    )
                }
            val expectedServerAttachmentIdsByLocalId =
                uploads
                    .groupBy { upload -> upload.localId }
                    .mapValues { (_, localUploads) ->
                        localUploads.map { upload -> upload.attachment.id.value }.toSet()
                    }
            // This is the sole Gallery projection/ownership publication.  No per-file callback
            // may transfer a temporary ref before this aggregate commit point.
            val commitReceipt =
                try {
                    composerDraftViewModel.acceptQueued(
                        SharedThreadComposerDraftIntent.AddUploadedAttachments(
                            attachments = readyAttachments,
                            replacedAttachmentIds = acceptedLocalIds,
                            replacedAttachmentIdsByUploadedId = replacementsByUploadedId,
                            expectedDraftKey = draftKey,
                            expectedAdmissions = admittedLocalAdmissions,
                            expectedServerAttachmentIdsByLocalId = expectedServerAttachmentIdsByLocalId,
                            expectedReplacementAttachmentIdsByLocalId = expectedReplacementAttachmentIdsByLocalId
                        )
                    )
                } catch (cancelled: CancellationException) {
                    compensateGalleryBatch(batch, reason = "aggregate_cancelled")
                    markGalleryBatchFailed("attachment_upload_batch_failed")
                    throw cancelled
                } catch (error: Throwable) {
                    bridge.logWarn(
                        "thread_composer_attachment_batch_projection_exception " +
                            "error=${error::class.simpleName.orEmpty()}"
                    )
                    compensateGalleryBatch(batch, reason = "aggregate_projection")
                    markGalleryBatchFailed("attachment_upload_batch_failed")
                    return false
                }
            val receiptState = commitReceipt.state
            val receiptPostconditionHolds =
                threadComposerAggregateReceiptIsValid(
                    accepted = commitReceipt.accepted,
                    before = commitReceipt.previousState,
                    after = receiptState,
                    expectedDraftKey = draftKey,
                    readyAttachments = readyAttachments,
                    expectedLocalAttachmentIds = acceptedLocalIds,
                    expectedReplacementAttachmentIds = expectedReplacementAttachmentIdsByLocalId.values
                )
            if (!receiptPostconditionHolds) {
                bridge.logWarn(
                    "thread_composer_attachment_batch_rejected reason=aggregate_receipt " +
                        "accepted=${commitReceipt.accepted} expected=${readyAttachments.size} " +
                        "actual=${receiptState.uploadedAttachments.size}"
                )
                compensateGalleryBatch(batch, reason = "aggregate_receipt")
                markGalleryBatchFailed("attachment_upload_batch_failed")
                return false
            }
            // The receipt is the exact serialized commit boundary.  Once accepted, a later draft
            // switch cannot retroactively make this projection rejectable; compensating here
            // would retract server reservations already referenced by committed durable state.
            // Ownership is retired only after this aggregate commit and there is no per-file
            // transfer/deletion callback on the Gallery path.
            attachmentUploadFilesByLocalId =
                attachmentUploadFilesByLocalId.filterKeys { identity ->
                    !(identity.draftKey == draftKey && identity.localAttachmentId in acceptedLocalIds)
                }
            runCatching {
                bridge.log(
                    "thread_composer_attachment_batch_upload_committed fileCount=${readyAttachments.size} " +
                        "ids=${readyAttachments.joinToString(",") { attachment -> attachment.id }}"
                )
            }
            return true
        }

        // Gallery on this train calls with requireAllFiles=false, so this remaining
        // per-file multipart path is the 1.10.0-shaped uploader. The aggregate
        // direct-session branch above stays dormant.
        var launchedCount = 0
        // The draft owns these uploads (process-wide), so they keep going when the user leaves
        // the conversation; results go to the draft's uploads and the composer showing this draft
        // applies them (see composerDraftUploadApplier).
        val draftUploads = attachmentUploads
        for (file in acceptedFiles) {
            val localAttachmentId = file.id.takeIf { it.isNotBlank() } ?: continue
            val admission = admittedLocalAdmissions[localAttachmentId] ?: continue
            val admittedSourceAttachment = localAttachments.firstOrNull { it.id == localAttachmentId } ?: continue
            val identity = threadAttachmentUploadAttemptIdentity(localAttachmentId, draftKey)
            val clientRequestId =
                if (identity != null) {
                    threadDirectUploadClientRequestIds.idFor(identity)
                } else {
                    newAttachmentUploadClientRequestId()
                }
            // 1 for the first upload of this picked file, 2 for its first Retry, ...
            val uploadAttemptNumber = identity?.let { threadDirectUploadClientRequestIds.nextAttempt(it) } ?: 1
            val attemptToken =
                draftUploads.launch(
                    draftKey = draftKey,
                    row = admittedSourceAttachment,
                    file = file,
                    launchAdmission = admission
                ) { uploadAttemptToken ->
                    fun publishTerminalFailure(errorCode: String, traceId: String? = null) {
                        draftUploads.fail(draftKey, localAttachmentId, uploadAttemptToken, errorCode, traceId)
                    }
                    // Where this attempt's time goes (RaftAttachmentUploadTelemetry.kt); the network
                    // layer fills its recorder in through the coroutine context. Reported once.
                    val uploadAttempt =
                        RaftAttachmentUploadAttempt(
                            serverId = uploadServerId,
                            attempt = uploadAttemptNumber,
                            pickedSizeBytes = file.sizeBytes,
                            elapsedMs = { uploadPickedAt.elapsedNow().inWholeMilliseconds },
                            log = { line -> bridge.log(line) },
                        )
                    try {
                        bridge.log(
                            "thread_composer_attachment_upload_start attachmentId=$localAttachmentId fileName=${file.fileName}"
                        )
                        // The chip is already on screen; resolve upload limits in the background
                        // (cache hit, or one shared request) and fail open to multipart on error.
                        var taskCapabilities = directCapabilities
                        if (taskCapabilities == null && directUploadOperation != null) {
                            // In the attempt's context: a fetch it makes is its capability probe (`control_ms`).
                            val fetched =
                                withContext(uploadAttempt.recorder) {
                                    dataSource.attachmentUploadCapabilities(directUploadOperation)
                                }
                            when (fetched) {
                                is DataResult.Content -> taskCapabilities = fetched.data
                                is DataResult.NoPermission -> routeAuthRequired(fetched)
                                is DataResult.Error,
                                is DataResult.Empty -> Unit
                            }
                        }
                        val uploadResult =
                            withContext(Dispatchers.Default + uploadAttempt.recorder) {
                                attachmentUploadSemaphore.withPermit {
                                    val resolvedUpload =
                                        when (
                                            val resolved =
                                                resolveThreadComposerDirectUpload(
                                                    file = file,
                                                    capabilities = taskCapabilities,
                                                    cancelled = false,
                                                    prepare = { candidate ->
                                                        val operation = directUploadOperation
                                                        if (operation == null) {
                                                            DataResult.Error(
                                                                ScreenError(
                                                                    code = "attachment_size_unavailable",
                                                                    retryable = true,
                                                                )
                                                            )
                                                        } else {
                                                            operation.prepare(candidate)
                                                        }
                                                    },
                                                )
                                        ) {
                                            is DataResult.Content -> resolved.data
                                            is DataResult.Error ->
                                                return@withPermit resolved
                                            is DataResult.NoPermission ->
                                                return@withPermit resolved
                                            is DataResult.Empty ->
                                                return@withPermit DataResult.Error(
                                                    ScreenError(
                                                        code = "attachment_size_unavailable",
                                                        retryable = false,
                                                    )
                                                )
                                        }
                                    val uploadFile = resolvedUpload.file
                                    val decision = resolvedUpload.decision
                                    // This attempt's upload meter. The engine reports the bytes it wrote; the percent stays
                                    // at 99 until the server confirmed the upload (the ready attachment is the 100%). Until the
                                    // first publish the chip says "Checking file…" (size, limits, upload session).
                                    val uploadMeter = RaftTransferMeter(bytesTotal = uploadFile.sizeBytes)
                                    val uploadMeterLock = SynchronizedObject()
                                    fun publishTransfer(step: RaftTransferMeter.() -> RaftTransferProgress) {
                                        val transfer = synchronized(uploadMeterLock) { uploadMeter.step() }
                                        draftUploads.progress(draftKey, localAttachmentId, uploadAttemptToken, transfer)
                                    }
                                    fun publishPhaseProgress(phase: ThreadComposerDirectUploadPhase) {
                                        when (phase) {
                                            // Still checking: the upload session is being created.
                                            ThreadComposerDirectUploadPhase.Creating -> Unit
                                            ThreadComposerDirectUploadPhase.Putting -> publishTransfer { total(uploadFile.sizeBytes) }
                                            // Every byte is with the storage; the server verifies before it confirms.
                                            ThreadComposerDirectUploadPhase.Verifying -> publishTransfer { finishing() }
                                        }
                                    }
                                    when (decision) {
                                        ThreadComposerDirectUploadDecision.Cancelled ->
                                            DataResult.Error(ScreenError(code = "cancelled", retryable = false))
                                        ThreadComposerDirectUploadDecision.TooLarge ->
                                            DataResult.Error(
                                                ScreenError(code = "UPLOAD_TOO_LARGE", retryable = false)
                                            )
                                        ThreadComposerDirectUploadDecision.Direct -> {
                                            val operation = directUploadOperation
                                            if (operation == null) {
                                                publishTransfer { total(uploadFile.sizeBytes) }
                                                dataSource.uploadAttachments(
                                                    channelId = channelId,
                                                    files = listOf(uploadFile),
                                                    onProgress = { progress -> publishTransfer { bytes(progress.bytesTransferred, progress.bytesTotal) } }
                                                )
                                            } else {
                                                when (
                                                    val one =
                                                        operation.uploadOneThreadFile(
                                                            channelId = ChannelId(channelId),
                                                            file = uploadFile,
                                                            clientRequestId = clientRequestId,
                                                            onPhase = ::publishPhaseProgress,
                                                            onUploadProgress = { progress -> publishTransfer { bytes(progress.bytesTransferred, progress.bytesTotal) } },
                                                        )
                                                ) {
                                                    is DataResult.Content ->
                                                        DataResult.Content(AttachmentUploadResult(listOf(one.data)))
                                                    is DataResult.Error -> one
                                                    is DataResult.NoPermission -> one
                                                    is DataResult.Empty -> one
                                                }
                                            }
                                        }
                                        ThreadComposerDirectUploadDecision.LegacyMultipart -> {
                                            publishTransfer { total(uploadFile.sizeBytes) }
                                            dataSource.uploadAttachments(
                                                channelId = channelId,
                                                files = listOf(uploadFile),
                                                onProgress = { progress -> publishTransfer { bytes(progress.bytesTransferred, progress.bytesTotal) } }
                                            )
                                        }
                                    }
                                }.also { settled ->
                                    // Inside the context: a scope cancelled as this returns cannot
                                    // turn a finished upload into a cancelled one.
                                    uploadAttempt.settle(settled)
                                }
                            }
                        uploadAttempt.report()
                        val published =
                            draftUploads.orchestrator.publishIfAccepted(
                                localAttachmentId,
                                draftKey,
                                uploadAttemptToken
                            ) {
                                when (uploadResult) {
                                    is DataResult.Content -> {
                                        val previewUri = file.composerLocalPreviewUri()
                                        val sourceAttachment = admittedSourceAttachment
                                        val uploaded =
                                            uploadResult.data.attachments.map { attachment ->
                                                SharedThreadComposerAttachment(
                                                    id = attachment.id.value,
                                                    fileName = attachment.name,
                                                    sizeLabel = attachment.sizeLabel,
                                                    sizeBytes = attachment.sizeBytes,
                                                    mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                                    previewUri = previewUri,
                                                    status = "ready",
                                                    fileRef =
                                                        sourceAttachment
                                                            ?.takeIf { source ->
                                                                source.source in ThreadComposerOwnedTemporarySources
                                                            }
                                                            ?.fileRef,
                                                    source = sourceAttachment?.source,
                                                    externalShareToken = sourceAttachment?.externalShareToken,
                                                    externalShareAccountId = sourceAttachment?.externalShareAccountId,
                                                    externalShareServerId = sourceAttachment?.externalShareServerId,
                                                    // The server confirmed the upload: the transfer is done.
                                                    uploadTransfer = sourceAttachment?.uploadTransfer?.completed()
                                                )
                                            }
                                        if (
                                            uploaded.isEmpty() ||
                                            uploaded.none { attachment ->
                                                attachment.id.isNotBlank() && !attachment.id.startsWith("picked-")
                                            }
                                        ) {
                                            publishTerminalFailure("empty_upload_result")
                                        } else {
                                            // The composer showing this draft (this page, or the
                                            // one that opens it after the user came back) puts
                                            // these in place of the local row.
                                            draftUploads.succeed(draftKey, localAttachmentId, uploadAttemptToken, uploaded)
                                        }
                                    }
                                    is DataResult.Error ->
                                        publishTerminalFailure(
                                            resolveThreadAttachmentUploadFailureCode(uploadResult),
                                            traceId = uploadResult.error.traceId
                                        )
                                    is DataResult.NoPermission -> {
                                        routeAuthRequired(uploadResult)
                                        publishTerminalFailure(
                                            resolveThreadAttachmentUploadFailureCode(uploadResult),
                                            traceId = uploadResult.blocker.traceId
                                        )
                                    }
                                    is DataResult.Empty ->
                                        publishTerminalFailure("empty_upload_result")
                                }
                            }
                        if (!published) {
                            bridge.log(
                                "thread_composer_attachment_upload_drop attachmentId=$localAttachmentId " +
                                    "attempt=$uploadAttemptToken reason=stale_or_terminal"
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        // The user removed this uploading tile; RemoveAttachment already hid it.
                        // A gallery caller still receives a false completion below, so its
                        // session cannot be committed as if cancellation were a transfer.
                        // The event says `cancelled` only when this upload's own job was cancelled
                        // (remove, draft sent or cleared, sign-out), not for a cancellation from below.
                        // Its `cancel_reason` is the RaftUploadCancelled the canceller passed to the
                        // job: on the exception that ended the task, or on the job itself.
                        uploadAttempt.reportTaskCancellation(cancellation)
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "thread_composer_attachment_upload_exception " +
                                "attachmentId=$localAttachmentId " +
                                "error=${error::class.simpleName.orEmpty()}"
                        )
                        uploadAttempt.report(failureCode = "attachment_upload_failed")
                        publishTerminalFailure("attachment_upload_failed")
                    }
                }
            if (attemptToken != null) {
                launchedCount += 1
            }
        }
        return acceptedFiles.isNotEmpty() && acceptedFiles.size == files.size && launchedCount == acceptedFiles.size
    }
}

/**
 * Extracts the most specific machine-readable failure code from an upload [DataResult] so
 * downstream presentation and upgrade gates preserve structured server codes (e.g.
 * `FILE_UPLOAD_QUOTA_EXCEEDED`, `external_limit_frozen`, `channel_read_only`) instead of
 * collapsing them into generic transport wrappers like `server_forbidden` or `http_403`.
 * Auth-session blockers (401) keep their canonical auth blocker code.
 */
internal fun resolveThreadAttachmentUploadFailureCode(
    result: DataResult<*>,
    emptyCode: String = "empty_upload_result",
): String =
    when (result) {
        is DataResult.Error ->
            result.error.serverCode?.trim()?.takeIf { it.isNotBlank() } ?: result.error.code
        is DataResult.NoPermission ->
            if (result.blocker.isAuthSessionBlocker()) {
                result.blocker.code
            } else {
                val structuredServerCode = result.blocker.serverCode?.trim()?.takeIf { it.isNotBlank() }
                val hasSpecificReason =
                    structuredServerCode != null &&
                        threadUserFacingErrorCodeReason(structuredServerCode).let {
                            it != null && it != ThreadUserFacingErrorReason.Generic
                        }
                if (hasSpecificReason) structuredServerCode else result.blocker.code
            }
        is DataResult.Empty,
        is DataResult.Content -> emptyCode
    }

/** Upgrade gate opened automatically when an attachment upload fails on a plan limit. */
internal fun threadAttachmentUploadFailureUpgradeGate(errorCode: String?): RaftUpgradeGate? =
    when (raftUpgradeGateForErrorCode(errorCode)) {
        RaftUpgradeGate.UploadMonthlyQuota -> RaftUpgradeGate.UploadMonthlyQuota
        RaftUpgradeGate.ExternalChannelFrozen -> RaftUpgradeGate.ExternalChannelFrozen
        else -> null
    }
