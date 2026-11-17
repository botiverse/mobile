package build.raft.app

import build.raft.app.data.MviDispatchReceipt
import build.raft.app.data.PlatformFile
import build.raft.app.transfer.RaftTransferProgress
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore

/** How many composer attachments upload at once, across every draft of the process. */
internal const val RaftComposerDraftUploadConcurrencyLimit = 12

/** Where one draft-owned upload is. */
internal sealed interface RaftComposerDraftUploadOutcome {
    data object InFlight : RaftComposerDraftUploadOutcome

    /** The server confirmed the upload: [attachments] are the ready rows that replace the local one. */
    data class Succeeded(val attachments: List<SharedThreadComposerAttachment>) : RaftComposerDraftUploadOutcome

    data class Failed(val errorCode: String) : RaftComposerDraftUploadOutcome
}

/**
 * One composer attachment upload, owned by its draft rather than by the conversation page that
 * started it (artin: "不能背后上传吗"). The page that picked the file may be gone by the time the
 * upload finishes; whichever composer shows this draft next picks up [transfer] and [outcome].
 */
internal data class RaftComposerDraftUpload(
    val localAttachmentId: String,
    val attemptToken: Long,
    /** The row as it was admitted ("uploading"), to show it again if the draft lost it. */
    val row: SharedThreadComposerAttachment,
    /**
     * The picked file, so a composer that adopts the upload can offer Retry. Dropped (null) once a
     * composer took the ready attachments: nothing retries a finished upload.
     */
    val file: PlatformFile?,
    /** The admission minted by the composer store that launched the upload. */
    val launchAdmission: SharedThreadComposerAttachmentAdmission,
    val transfer: RaftTransferProgress? = null,
    val outcome: RaftComposerDraftUploadOutcome = RaftComposerDraftUploadOutcome.InFlight,
    /** A composer store took the terminal [outcome] (and persisted it with the draft). */
    val delivered: Boolean = false,
) {
    val inFlight: Boolean get() = outcome == RaftComposerDraftUploadOutcome.InFlight
}

/**
 * The composer attachment uploads of one account on one server, keyed by draft key (the conversation's channel,
 * thread or DM draft). Lives for the process (see [RaftComposerDraftUploads]); pages subscribe to
 * [byDraft] and never own the jobs.
 *
 * Jobs and their attempt fence are the [orchestrator]'s; this adds the state a composer needs to
 * show the upload again after the page that started it went away. Cancelled only by [remove] (the
 * user removed the tile), [retireDraft] (sent or cleared) and [dispose] (sign-out, server lost).
 */
internal class RaftComposerServerUploads(
    val accountId: String,
    val serverId: String,
    processScope: CoroutineScope,
    /** Shared by every server: the process-wide concurrency bound. */
    val semaphore: Semaphore,
) {
    val orchestrator = ThreadAttachmentUploadOrchestrator(processScope)
    private val state = MutableStateFlow<Map<String, Map<String, RaftComposerDraftUpload>>>(emptyMap())

    /** Every draft's uploads (draft key -> local attachment id -> upload). */
    val byDraft: StateFlow<Map<String, Map<String, RaftComposerDraftUpload>>> = state.asStateFlow()

    fun uploads(draftKey: String?): Map<String, RaftComposerDraftUpload> =
        normalizeThreadAttachmentUploadDraftKey(draftKey)?.let { key -> state.value[key] }.orEmpty()

    fun upload(draftKey: String?, localAttachmentId: String): RaftComposerDraftUpload? =
        uploads(draftKey)[localAttachmentId.trim()]

    /**
     * Whether something still owns this row's upload, so the orphan safety net must leave the row
     * alone: the attempt has a result for a composer to apply, or its job is still running. A record
     * left in flight with no live job (it never reached a result) is not owned: the row is failed as
     * `upload_interrupted` and offers Retry.
     */
    fun ownsUpload(draftKey: String?, localAttachmentId: String): Boolean {
        val key = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return false
        val upload = upload(key, localAttachmentId) ?: return false
        return !upload.inFlight || orchestrator.isActive(upload.localAttachmentId, key)
    }

    /**
     * Starts [block] for [row] of [draftKey] on the process scope. The upload is recorded before the
     * block runs, so a composer that opens this draft meanwhile sees it as in flight. Null when the
     * draft identity is not admissible (or the owner was disposed).
     */
    fun launch(
        draftKey: String,
        row: SharedThreadComposerAttachment,
        file: PlatformFile,
        launchAdmission: SharedThreadComposerAttachmentAdmission,
        block: suspend (attemptToken: Long) -> Unit,
    ): Long? {
        val key = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return null
        val id = row.id.trim().takeIf(String::isNotBlank) ?: return null
        return orchestrator.launch(localAttachmentId = id, draftKey = key) { attemptToken ->
            val recorded =
                orchestrator.publishIfAccepted(id, key, attemptToken) {
                    put(
                        key,
                        RaftComposerDraftUpload(
                            localAttachmentId = id,
                            attemptToken = attemptToken,
                            row = row,
                            file = file,
                            launchAdmission = launchAdmission,
                        ),
                    )
                }
            if (recorded) {
                try {
                    block(attemptToken)
                } finally {
                    // The block ended without a result (cancelled by something other than the
                    // user's remove, or it returned early): the attempt can never finish, so the
                    // tile offers Retry instead of staying "uploading". No-op once the attempt has
                    // an outcome, was removed, or a newer attempt of the same id replaced it.
                    fail(key, id, attemptToken, ThreadComposerUploadInterruptedErrorCode)
                }
            }
        }
    }

    /** The attempt's latest meter state; a superseded or removed attempt is ignored. */
    fun progress(draftKey: String, localAttachmentId: String, attemptToken: Long, transfer: RaftTransferProgress) {
        updateAttempt(draftKey, localAttachmentId, attemptToken) { upload ->
            if (!upload.inFlight) {
                upload
            } else {
                upload.copy(transfer = upload.transfer?.followedBy(transfer) ?: transfer)
            }
        }
    }

    fun succeed(
        draftKey: String,
        localAttachmentId: String,
        attemptToken: Long,
        attachments: List<SharedThreadComposerAttachment>,
    ) {
        updateAttempt(draftKey, localAttachmentId, attemptToken) { upload ->
            if (!upload.inFlight) upload else upload.copy(outcome = RaftComposerDraftUploadOutcome.Succeeded(attachments))
        }
    }

    fun fail(draftKey: String, localAttachmentId: String, attemptToken: Long, errorCode: String) {
        updateAttempt(draftKey, localAttachmentId, attemptToken) { upload ->
            if (!upload.inFlight) upload else upload.copy(outcome = RaftComposerDraftUploadOutcome.Failed(errorCode))
        }
    }

    /** A composer store took the terminal outcome of this attempt. */
    fun markDelivered(draftKey: String, localAttachmentId: String, attemptToken: Long) {
        updateAttempt(draftKey, localAttachmentId, attemptToken) { upload ->
            when (upload.outcome) {
                RaftComposerDraftUploadOutcome.InFlight -> upload
                // The ready attachments are in the draft: the picked file is not needed any more.
                is RaftComposerDraftUploadOutcome.Succeeded -> upload.copy(delivered = true, file = null)
                is RaftComposerDraftUploadOutcome.Failed -> upload.copy(delivered = true)
            }
        }
    }

    /**
     * The user removed the tile: cancel its upload and forget it. [attachmentId] is the tile's id:
     * the local picked id while uploading, or the server attachment id once the upload's ready row
     * replaced it (the upload is still recorded under the local id).
     */
    fun remove(draftKey: String?, attachmentId: String) {
        val key = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return
        val id = attachmentId.trim()
        if (id.isBlank()) return
        orchestrator.remove(id, key)
        state.update { current ->
            val draft = current[key] ?: return@update current
            val removed =
                draft.filter { (localId, upload) ->
                    localId == id ||
                        (upload.outcome as? RaftComposerDraftUploadOutcome.Succeeded)
                            ?.attachments
                            ?.any { attachment -> attachment.id == id } == true
                }.keys
            if (removed.isEmpty()) return@update current
            val remaining = draft - removed
            if (remaining.isEmpty()) current - key else current + (key to remaining)
        }
    }

    /** The draft was sent or cleared: cancel what is left of its uploads and forget them all. */
    fun retireDraft(draftKey: String?): Set<String> {
        val key = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return emptySet()
        val retired = orchestrator.retireDraft(key)
        state.update { current -> current - key }
        return retired
    }

    /** Sign-out or lost server: cancel every upload of this server. The owner refuses new ones. */
    fun dispose() {
        orchestrator.dispose()
        state.value = emptyMap()
    }

    private fun put(draftKey: String, upload: RaftComposerDraftUpload) {
        state.update { current ->
            current + (draftKey to (current[draftKey].orEmpty() + (upload.localAttachmentId to upload)))
        }
    }

    private fun updateAttempt(
        draftKey: String,
        localAttachmentId: String,
        attemptToken: Long,
        transform: (RaftComposerDraftUpload) -> RaftComposerDraftUpload,
    ) {
        val key = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return
        val id = localAttachmentId.trim()
        state.update { current ->
            val draft = current[key] ?: return@update current
            val upload = draft[id]?.takeIf { it.attemptToken == attemptToken } ?: return@update current
            val next = transform(upload)
            if (next == upload) current else current + (key to (draft + (id to next)))
        }
    }
}

/**
 * The process owner of composer attachment uploads: one [RaftComposerServerUploads] per account
 * and server,
 * held by the shared app graph (`RaftSharedStores.composerDraftUploads`), so an upload outlives
 * the conversation page that started it. In memory only: a process death loses in-flight uploads
 * and the restored row turns into Retry (`upload_interrupted`).
 */
internal class RaftComposerDraftUploads(
    private val processScope: CoroutineScope,
    concurrencyLimit: Int = RaftComposerDraftUploadConcurrencyLimit,
) {
    private val lock = SynchronizedObject()
    private val owners = mutableMapOf<Pair<String, String>, RaftComposerServerUploads>()
    private val semaphore = Semaphore(concurrencyLimit)

    /**
     * The uploads of [accountId] on [serverId], the conversation page's own account and server.
     * Keyed by the account too: a session that changes identity without a sign-out (no terminal
     * cleanup, so no [cancelAll]) must never show the previous account's uploads. A blank part (not
     * known yet) has its own bucket.
     */
    fun forOwner(accountId: String?, serverId: String?): RaftComposerServerUploads {
        val account = accountId?.trim().orEmpty()
        val server = serverId?.trim().orEmpty()
        return synchronized(lock) {
            owners.getOrPut(account to server) { RaftComposerServerUploads(account, server, processScope, semaphore) }
        }
    }

    /** Sign-out: every account's and server's uploads are cancelled and forgotten. */
    fun cancelAll() {
        val disposed = synchronized(lock) { owners.values.toList().also { owners.clear() } }
        disposed.forEach(RaftComposerServerUploads::dispose)
    }

    /** The account lost [serverId] (removed, server deleted): its uploads are cancelled. */
    fun dropServer(serverId: String) {
        val server = serverId.trim()
        val disposed =
            synchronized(lock) {
                owners.keys.filter { (_, ownerServer) -> ownerServer == server }.mapNotNull { key -> owners.remove(key) }
            }
        disposed.forEach(RaftComposerServerUploads::dispose)
    }
}

/**
 * Brings one composer store up to date with its draft's process-owned uploads: shows progress,
 * puts the ready attachments in place of the local row, or fails the row (Retry). A store that did
 * not launch an upload (the page was recreated after the user left and came back) adopts it with a
 * fresh admission first; a row the restored draft lost is shown again.
 *
 * One instance per composer store: it remembers the admissions it minted for adopted uploads.
 * Idempotent: it only dispatches what the store does not show yet.
 */
internal class RaftComposerDraftUploadApplier(
    private val log: (String) -> Unit = {},
) {
    private val adoptedAdmissions = mutableMapOf<String, Pair<Long, SharedThreadComposerAttachmentAdmission>>()

    fun apply(
        draftKey: String?,
        uploads: Map<String, RaftComposerDraftUpload>,
        currentState: () -> SharedThreadComposerDraftState,
        acceptWithState: (SharedThreadComposerDraftIntent) -> MviDispatchReceipt<SharedThreadComposerDraftState>,
        onAdopted: (RaftComposerDraftUpload) -> Unit = {},
        onSucceeded: (RaftComposerDraftUpload) -> Unit = {},
        onFailed: (RaftComposerDraftUpload, errorCode: String) -> Unit = { _, _ -> },
        onDelivered: (RaftComposerDraftUpload) -> Unit = {},
    ) {
        val key = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return
        uploads.values.sortedBy { it.attemptToken }.forEach { upload ->
            applyOne(key, upload, currentState, acceptWithState, onAdopted, onSucceeded, onFailed, onDelivered)
        }
    }

    private fun applyOne(
        draftKey: String,
        upload: RaftComposerDraftUpload,
        currentState: () -> SharedThreadComposerDraftState,
        acceptWithState: (SharedThreadComposerDraftIntent) -> MviDispatchReceipt<SharedThreadComposerDraftState>,
        onAdopted: (RaftComposerDraftUpload) -> Unit,
        onSucceeded: (RaftComposerDraftUpload) -> Unit,
        onFailed: (RaftComposerDraftUpload, String) -> Unit,
        onDelivered: (RaftComposerDraftUpload) -> Unit,
    ) {
        val id = upload.localAttachmentId
        val state = currentState()
        if (normalizeThreadAttachmentUploadDraftKey(state.draftKey) != draftKey) return
        if (id in state.removedAttachmentIds) return
        val row = state.uploadedAttachments.firstOrNull { it.id == id }
        val admission =
            when {
                // The draft lost the row (it was never written before the page went away): show it
                // again, unless a composer already took this upload's result.
                row == null ->
                    if (upload.delivered) return else adopt(draftKey, upload, upload.row, state, acceptWithState, onAdopted)
                row.status != "uploading" && row.status != "queued" -> return
                else -> {
                    val current = state.attachmentAdmissionsByLocalId[id]
                    when {
                        current == null -> adopt(draftKey, upload, row, state, acceptWithState, onAdopted)
                        current == upload.launchAdmission -> current
                        adoptedAdmissions[id] == (upload.attemptToken to current) -> current
                        // Another attempt of this store owns the row (a Retry about to launch).
                        else -> null
                    }
                }
            } ?: return
        when (val outcome = upload.outcome) {
            RaftComposerDraftUploadOutcome.InFlight -> {
                val transfer = upload.transfer ?: return
                val shown = currentState().uploadedAttachments.firstOrNull { it.id == id }?.uploadTransfer
                if (shown != null && shown.followedBy(transfer) == shown) return
                acceptWithState(
                    SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress(
                        attachmentId = id,
                        transfer = transfer,
                        expectedDraftKey = draftKey,
                        expectedAdmission = admission,
                    )
                )
            }
            is RaftComposerDraftUploadOutcome.Succeeded -> {
                val shownTransfer = currentState().uploadedAttachments.firstOrNull { it.id == id }?.uploadTransfer
                val uploaded =
                    outcome.attachments.map { attachment ->
                        attachment.copy(uploadTransfer = (shownTransfer ?: upload.transfer)?.completed())
                    }
                val slot = admission.replacementAttachmentId.trim().ifBlank { id }
                val receipt =
                    acceptWithState(
                        SharedThreadComposerDraftIntent.AddUploadedAttachments(
                            attachments = uploaded,
                            replacedAttachmentIds = setOf(id),
                            replacedAttachmentIdsByUploadedId = uploaded.associate { attachment -> attachment.id to slot },
                            expectedDraftKey = draftKey,
                            expectedAdmissions = mapOf(id to admission),
                            expectedServerAttachmentIdsByLocalId = mapOf(id to uploaded.map { it.id }.toSet()),
                            expectedReplacementAttachmentIdsByLocalId = mapOf(id to slot),
                        )
                    )
                val landed =
                    receipt.accepted &&
                        uploaded.all { attachment -> receipt.state.uploadedAttachments.any { it.id == attachment.id } }
                if (landed) {
                    adoptedAdmissions.remove(id)
                    onSucceeded(upload)
                    onDelivered(upload)
                } else {
                    log(
                        "thread_composer_attachment_result_rejected result=success draftKey=$draftKey " +
                            "stateDraftKey=${receipt.state.draftKey.orEmpty()} " +
                            "attachments=${receipt.state.threadComposerAttachmentStatusSummary(setOf(id))}"
                    )
                    markFailed(draftKey, upload, admission, "attachment_upload_projection_rejected", acceptWithState, onFailed, onDelivered)
                }
            }
            is RaftComposerDraftUploadOutcome.Failed ->
                markFailed(draftKey, upload, admission, outcome.errorCode, acceptWithState, onFailed, onDelivered)
        }
    }

    private fun markFailed(
        draftKey: String,
        upload: RaftComposerDraftUpload,
        admission: SharedThreadComposerAttachmentAdmission,
        errorCode: String,
        acceptWithState: (SharedThreadComposerDraftIntent) -> MviDispatchReceipt<SharedThreadComposerDraftState>,
        onFailed: (RaftComposerDraftUpload, String) -> Unit,
        onDelivered: (RaftComposerDraftUpload) -> Unit,
    ) {
        val id = upload.localAttachmentId
        val receipt =
            acceptWithState(
                SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                    attachmentIds = setOf(id),
                    errorCode = errorCode,
                    expectedDraftKey = draftKey,
                    expectedAdmissions = mapOf(id to admission),
                )
            )
        val marked = receipt.state.uploadedAttachments.any { row -> row.id == id && row.status == "failed" && row.errorCode == errorCode }
        if (!marked) {
            log(
                "thread_composer_attachment_result_rejected result=failure errorCode=$errorCode draftKey=$draftKey " +
                    "stateDraftKey=${receipt.state.draftKey.orEmpty()} " +
                    "attachments=${receipt.state.threadComposerAttachmentStatusSummary(setOf(id))}"
            )
            return
        }
        adoptedAdmissions.remove(id)
        onFailed(upload, errorCode)
        onDelivered(upload)
    }

    /** Mints this store's admission for an upload it did not launch, showing [row] as uploading. */
    private fun adopt(
        draftKey: String,
        upload: RaftComposerDraftUpload,
        row: SharedThreadComposerAttachment,
        state: SharedThreadComposerDraftState,
        acceptWithState: (SharedThreadComposerDraftIntent) -> MviDispatchReceipt<SharedThreadComposerDraftState>,
        onAdopted: (RaftComposerDraftUpload) -> Unit,
    ): SharedThreadComposerAttachmentAdmission? {
        val id = upload.localAttachmentId
        val launchSlot = upload.launchAdmission.replacementAttachmentId.trim()
        // An editor upload replaces the remote row it was opened from, when the draft still has it.
        val slot =
            launchSlot.takeIf { candidate ->
                candidate.isNotBlank() &&
                    candidate != id &&
                    candidate !in state.removedAttachmentIds &&
                    state.uploadedAttachments.any { it.id == candidate }
            } ?: id
        val shownRow =
            row.copy(
                status = "uploading",
                errorCode = null,
                uploadTransfer = row.uploadTransfer ?: upload.transfer,
            )
        val receipt =
            acceptWithState(
                SharedThreadComposerDraftIntent.AddLocalAttachments(
                    attachments = listOf(shownRow),
                    expectedDraftKey = draftKey,
                    replacementAttachmentIdsByLocalId = mapOf(id to slot),
                )
            )
        val admission =
            receipt.state.attachmentAdmissionsByLocalId[id]
                ?.takeIf { minted ->
                    normalizeThreadAttachmentUploadDraftKey(receipt.state.draftKey) == draftKey &&
                        minted != receipt.previousState.attachmentAdmissionsByLocalId[id]
                }
        if (admission == null) {
            log("thread_composer_attachment_upload_adopt_rejected draftKey=$draftKey attachmentId=$id")
            return null
        }
        adoptedAdmissions[id] = upload.attemptToken to admission
        log(
            "thread_composer_attachment_upload_adopted draftKey=$draftKey attachmentId=$id " +
                "attempt=${upload.attemptToken} outcome=${upload.outcome.diagnosticsLabel()} rowRestored=${state.uploadedAttachments.any { it.id == id }}"
        )
        onAdopted(upload)
        return admission
    }
}

private fun RaftComposerDraftUploadOutcome.diagnosticsLabel(): String =
    when (this) {
        RaftComposerDraftUploadOutcome.InFlight -> "in_flight"
        is RaftComposerDraftUploadOutcome.Succeeded -> "succeeded"
        is RaftComposerDraftUploadOutcome.Failed -> "failed:$errorCode"
    }
