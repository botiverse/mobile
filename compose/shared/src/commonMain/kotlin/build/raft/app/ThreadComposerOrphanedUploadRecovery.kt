package build.raft.app

import build.raft.app.data.MviDispatchReceipt

/**
 * Turns composer rows that no upload attempt can finish into failed `upload_interrupted` rows, so
 * the chip offers Retry and the send gate stops waiting for them.
 *
 * Upload attempts belong to the page that started them and publish only against the admission
 * minted in that page's draft store.  When the conversation is left and reopened while an upload
 * is in flight, the recreated page restores the row from the durable draft without an admission:
 * the old attempt's success or failure can no longer land, and the row stayed "uploading" forever
 * (Hands 9cf9735f).  Restored local files are registered for Retry by the caller.
 *
 * An attempt of this page whose row lost its admission (a remount with another composer instance)
 * is equally unable to publish; it is stopped so Retry is not refused as already uploading.  The
 * admission is re-read right before that: a Retry mints a new admission before it launches, so a
 * row without one has no newer attempt to cancel.
 *
 * Returns the ids marked failed.
 */
internal fun recoverOrphanedThreadComposerUploads(
    draftKey: String?,
    state: SharedThreadComposerDraftState,
    acceptWithState: (SharedThreadComposerDraftIntent) -> MviDispatchReceipt<SharedThreadComposerDraftState>,
    currentState: () -> SharedThreadComposerDraftState,
    orchestrator: ThreadAttachmentUploadOrchestrator,
    log: (String) -> Unit
): Set<String> {
    val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return emptySet()
    if (normalizeThreadAttachmentUploadDraftKey(state.draftKey) != normalizedDraftKey) return emptySet()
    val candidates = state.orphanedThreadComposerUploadIds()
    if (candidates.isEmpty()) return emptySet()
    val receipt =
        acceptWithState(
            SharedThreadComposerDraftIntent.MarkOrphanedAttachmentsInterrupted(
                attachmentIds = candidates,
                expectedDraftKey = normalizedDraftKey
            )
        )
    val interrupted =
        if (!receipt.accepted) {
            emptySet()
        } else {
            candidates.filter { id ->
                receipt.previousState.uploadedAttachments.any { it.id == id && it.status != "failed" } &&
                    receipt.state.uploadedAttachments.any { row ->
                        row.id == id &&
                            row.status == "failed" &&
                            row.errorCode == ThreadComposerUploadInterruptedErrorCode
                    }
            }.toSet()
        }
    if (interrupted.isEmpty()) {
        log(
            "thread_composer_attachment_upload_interrupt_skipped draftKey=$normalizedDraftKey " +
                "ids=${candidates.sorted().joinToString(",")} accepted=${receipt.accepted}"
        )
        return emptySet()
    }
    val admissionsNow = currentState().attachmentAdmissionsByLocalId
    val stoppedAttempts =
        interrupted.filter { id ->
            id !in admissionsNow && orchestrator.isActive(id, normalizedDraftKey)
        }
    stoppedAttempts.forEach { id -> orchestrator.remove(id, normalizedDraftKey) }
    val previousStatuses =
        receipt.previousState.uploadedAttachments
            .filter { row -> row.id in interrupted }
            .sortedBy { row -> row.id }
            .joinToString(",") { row -> "${row.id}:${row.status}" }
    log(
        "thread_composer_attachment_upload_interrupted draftKey=$normalizedDraftKey " +
            "reason=no_upload_attempt attachments=$previousStatuses " +
            "stoppedAttempts=${stoppedAttempts.sorted().joinToString(",")}"
    )
    return interrupted
}

/** `id:status[:errorCode][:removed]:admitted|unadmitted` for [ids] (all rows when null), for upload diagnostics. */
internal fun SharedThreadComposerDraftState.threadComposerAttachmentStatusSummary(
    ids: Set<String>? = null
): String =
    uploadedAttachments
        .filter { row -> ids == null || row.id in ids }
        .joinToString(",") { row ->
            buildString {
                append(row.id).append(':').append(row.status)
                row.errorCode?.takeIf(String::isNotBlank)?.let { code -> append(':').append(code) }
                if (row.id in removedAttachmentIds) append(":removed")
                append(if (row.id in attachmentAdmissionsByLocalId) ":admitted" else ":unadmitted")
            }
        }
        .ifEmpty { "none" }

/** `id:status[:errorCode]` of every pending attachment, for the `thread_composer_send_gate` line. */
internal fun List<RaftPendingAttachmentItem>.threadComposerSendGateAttachmentStates(): String =
    joinToString(",") { attachment ->
        buildString {
            append(attachment.id).append(':').append(attachment.status)
            attachment.errorCode?.takeIf(String::isNotBlank)?.let { code -> append(':').append(code) }
        }
    }.ifEmpty { "none" }
