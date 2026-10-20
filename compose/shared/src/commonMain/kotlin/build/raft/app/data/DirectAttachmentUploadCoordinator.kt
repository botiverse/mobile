package build.raft.app.data

import build.raft.app.model.Attachment
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Narrow seam for the Gallery N/N upload protocol.  Keeping this smaller than
 * [ConversationRepository] makes the compensation state machine deterministic
 * in common tests and prevents a legacy multipart implementation from being
 * accidentally accepted as an atomic uploader.
 */
internal interface DirectAttachmentUploadSessionGateway {
    suspend fun capabilities(): DataResult<AttachmentUploadCapabilities>

    /**
     * Resolve the file's real upload length before a reservation is created.  The server create
     * contract requires a positive [AttachmentUploadSessionCreateRequest.sizeBytes], while a
     * [PlatformFile.fileRef]-only descriptor intentionally carries no trustworthy caller length.
     * Implementations backed by a platform resolver must stat the ref and return a copy carrying
     * that verified length.  The default implementation is deliberately limited to in-memory
     * bytes so a test/legacy gateway cannot accidentally invent a size for a private ref.
     */
    suspend fun prepare(file: PlatformFile): DataResult<PlatformFile> =
        prepareDirectAttachmentUploadFile(file)

    suspend fun create(
        request: AttachmentUploadSessionCreateRequest
    ): DataResult<AttachmentUploadSessionCreateResult>
    suspend fun put(
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): DataResult<Unit>
    suspend fun complete(uploadId: String): DataResult<AttachmentUploadSessionCompleteResult>
    suspend fun cancel(uploadId: String): DataResult<AttachmentUploadSessionView>
    suspend fun status(uploadId: String): DataResult<AttachmentUploadSessionView>
}

/**
 * Conservative fallback used by gateways which do not own a platform file resolver.  In-memory
 * bytes are self-describing; a file reference is not.  In particular, never turn a nullable/zero
 * caller hint into a fabricated positive size merely to satisfy the server schema.
 */
internal fun prepareDirectAttachmentUploadFile(file: PlatformFile): DataResult<PlatformFile> {
    val normalizedRef = file.fileRef?.trim()?.takeIf { it.isNotBlank() }
    if (normalizedRef != null && file.bytes.isNotEmpty()) {
        return DataResult.Error(
            ScreenError(
                // Keep the same stable conflict key used by the repository/network preparation
                // seam.  This fallback is also exercised by legacy/test gateways, so leaking a
                // second spelling here would make presentation and retry policy disagree about
                // the exact same ambiguous input.
                code = "attachment_file_input_conflict",
                retryable = false,
            )
        )
    }
    if (normalizedRef != null) {
        return DataResult.Error(
            ScreenError(
                // A resolver-less gateway cannot establish a trustworthy fileRef length.  Use the
                // canonical size admission failure rather than inventing a local implementation
                // detail that callers cannot classify consistently.
                code = "attachment_size_unavailable",
                retryable = false,
            )
        )
    }
    if (file.bytes.isEmpty()) {
        return DataResult.Error(
            ScreenError(
                code = "attachment_size_unavailable",
                retryable = false,
            )
        )
    }

    val actualSizeBytes = file.bytes.size.toLong()
    val callerHint = file.sizeBytes
    // A non-positive hint is treated as absent (pickers commonly encode an unknown size as 0),
    // but a positive contradictory hint is never allowed to disagree with the bytes we will PUT.
    if (callerHint != null && callerHint > 0L && callerHint != actualSizeBytes) {
        return DataResult.Error(
            ScreenError(
                code = "attachment_size_mismatch",
                retryable = false,
            )
        )
    }
    return DataResult.Content(
        file.copy(
            fileRef = null,
            sizeBytes = actualSizeBytes,
        )
    )
}

/**
 * One authority-bound direct-upload operation.  The gateway and coordinator are intentionally
 * captured together: capability admission, every session mutation, presigned PUT, and any later
 * compensation all use this same bound instance.  Callers must not reconstruct a repository from
 * [authority] after a batch has started.
 */
internal class DirectAttachmentUploadOperation(
    val authority: RaftExpectedAuthBinding,
    private val coordinator: DirectAttachmentUploadCoordinator,
) {
    suspend fun capabilities(): DataResult<AttachmentUploadCapabilities> = coordinator.capabilities()

    suspend fun prepare(file: PlatformFile): DataResult<PlatformFile> = coordinator.prepare(file)

    suspend fun upload(
        channelId: ChannelId,
        files: List<PlatformFile>,
    ): DataResult<DirectAttachmentUploadBatch> = coordinator.upload(channelId, files)

    /**
     * Upload using the capability receipt already admitted by this operation.  Keeping the
     * receipt explicit prevents a second capability request from silently crossing an authority
     * transition between admission and the first reservation.
     */
    suspend fun upload(
        channelId: ChannelId,
        files: List<PlatformFile>,
        admittedCapabilities: AttachmentUploadCapabilities,
    ): DataResult<DirectAttachmentUploadBatch> =
        coordinator.uploadWithCapabilities(channelId, files, admittedCapabilities)

    suspend fun compensate(batch: DirectAttachmentUploadBatch): Boolean =
        withContext(NonCancellable) { coordinator.compensateBatch(batch) }

    suspend fun uploadOneThreadFile(
        channelId: ChannelId,
        file: PlatformFile,
        clientRequestId: String,
        onPhase: (ThreadComposerDirectUploadPhase) -> Unit = {},
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): DataResult<Attachment> =
        coordinator.uploadOneThreadFile(channelId, file, clientRequestId, onPhase, onUploadProgress)
}

private class ConversationRepositoryDirectAttachmentUploadSessionGateway(
    private val repository: ConversationRepository,
) : DirectAttachmentUploadSessionGateway {
    override suspend fun capabilities(): DataResult<AttachmentUploadCapabilities> =
        repository.getAttachmentUploadCapabilities()

    override suspend fun prepare(file: PlatformFile): DataResult<PlatformFile> =
        repository.prepareAttachmentUploadFile(file)

    override suspend fun create(
        request: AttachmentUploadSessionCreateRequest
    ): DataResult<AttachmentUploadSessionCreateResult> =
        repository.createAttachmentUploadSession(request)

    override suspend fun put(
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
    ): DataResult<Unit> {
        val hasFileRef = file.fileRef?.trim()?.isNotBlank() == true
        if (hasFileRef && file.bytes.isNotEmpty()) {
            // A caller must not smuggle an alternate body alongside a resolver-owned handle.
            // Choosing bytes here would bypass the stat/authority contract and could upload data
            // different from the object whose size was admitted at create time.
            return DataResult.Error(
                ScreenError(
                    code = "attachment_file_input_conflict",
                    retryable = false,
                )
            )
        }
        return when {
            hasFileRef -> repository.putAttachmentUploadFile(upload, file, onUploadProgress)
            file.bytes.isNotEmpty() -> repository.putAttachmentUploadBytes(upload, file.bytes, onUploadProgress)
            else ->
                DataResult.Error(
                    ScreenError(
                        code = "attachment_upload_file_empty",
                        retryable = false,
                    )
                )
        }
    }

    override suspend fun complete(uploadId: String): DataResult<AttachmentUploadSessionCompleteResult> =
        repository.completeAttachmentUploadSession(uploadId)

    override suspend fun cancel(uploadId: String): DataResult<AttachmentUploadSessionView> =
        repository.cancelAttachmentUploadSession(uploadId)

    override suspend fun status(uploadId: String): DataResult<AttachmentUploadSessionView> =
        repository.statusAttachmentUploadSession(uploadId)
}

/**
 * Capability-gated, compensating Gallery uploader.
 *
 * The server's direct-session API reserves an attachment before returning a
 * presigned URL.  Consequently a failed sibling cannot simply be represented
 * as a local tile failure: every already-created session must be canceled (or
 * observed until its terminal state is known) before the caller may publish
 * any attachment.  A consumed or otherwise unknown session is deliberately a
 * fail-closed result; no partial projection is returned.
 */
internal class DirectAttachmentUploadCoordinator(
    private val gateway: DirectAttachmentUploadSessionGateway,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    private companion object {
        /**
         * Explicit server contract codes whose complete operation is safe to retry once.  HTTP
         * status classes, transport text, and the `retryable` presentation bit are deliberately
         * not used here: a complete request may have committed even when the client saw a 5xx.
         */
        val COMPLETE_RETRY_SERVER_CODES =
            setOf(
                "upload_object_not_found",
                "upload_verification_in_progress",
            )
    }
    constructor(
        repository: ConversationRepository,
        diagnosticsLogger: (String) -> Unit = {},
    ) : this(
        gateway = ConversationRepositoryDirectAttachmentUploadSessionGateway(repository),
        diagnosticsLogger = diagnosticsLogger,
    )

    suspend fun upload(
        channelId: ChannelId,
        files: List<PlatformFile>,
    ): DataResult<DirectAttachmentUploadBatch> {
        val capabilityResult = safeCall("capabilities") { gateway.capabilities() }
        return when (capabilityResult) {
            is DataResult.Content -> uploadWithCapabilities(channelId, files, capabilityResult.data)
            else -> capabilityResult.asBatchFailure("attachment_upload_capabilities_unavailable")
        }
    }

    /** Upload after the caller has already admitted one capability receipt. */
    internal suspend fun uploadWithCapabilities(
        channelId: ChannelId,
        files: List<PlatformFile>,
        capabilities: AttachmentUploadCapabilities,
    ): DataResult<DirectAttachmentUploadBatch> {
        if (channelId.value.isBlank() || files.isEmpty()) {
            return DataResult.Error(
                ScreenError(
                    code = "attachment_upload_batch_invalid_request",
                    retryable = false,
                )
            )
        }

        // Do not infer direct support from the client route or from the
        // existence of the API methods.  The server capability is the sole
        // admission gate for an atomic Gallery batch.
        if (!capabilities.directUploadEnabled) {
            return DataResult.Error(
                ScreenError(
                    code = "attachment_direct_upload_unavailable",
                    retryable = false,
                )
            )
        }

        // Resolve every file before the first reservation.  A fileRef-only descriptor has no
        // trustworthy caller-provided length, so the gateway must stat it (under the same bound
        // authority) and return a positive verified size.  Preflighting the complete list also
        // prevents a late sibling stat failure from leaving earlier sessions to compensate.
        val normalizedFilesResult = prepareFiles(files, capabilities.maxBytes)
        val normalizedFiles =
            when (normalizedFilesResult) {
                is DataResult.Content -> normalizedFilesResult.data
                else -> return normalizedFilesResult.asBatchFailure("attachment_upload_batch_invalid_request")
            }
        // Keep reservation ids independently from the parsed response object. A create response
        // can be malformed after the server has already reserved an id; the id must still enter
        // this ledger before the response is rejected. If the response does not let us identify
        // the reservation unambiguously, the ledger records that uncertainty and the batch stays
        // fail-closed even when every known id is later retracted.
        val sessionLedger = AttachmentUploadSessionLedger()
        val completed = mutableListOf<DirectAttachmentUpload>()
        return try {
            for (file in normalizedFiles) {
                val createRequest =
                    AttachmentUploadSessionCreateRequest(
                        channelId = channelId,
                        filename = file.fileName,
                        mimeType = file.mimeType.orEmpty().ifBlank { "application/octet-stream" },
                        // [prepareFiles] guarantees this is a positive, resolver-verified
                        // value for refs or the exact byte-array length for in-memory files.
                        sizeBytes = file.sizeBytes
                            ?: error("prepared direct upload file has no verified size"),
                        clientRequestId = newAttachmentUploadClientRequestId(),
                    )
                val createAttempt =
                    // A server may accept/create the reservation before the caller is canceled.
                    // Keep this one request alive long enough to observe its response and record
                    // the upload id; otherwise cancellation can strand an unaddressable
                    // reservation.  If the transport itself reports cancellation before a
                    // response is decoded, replay the *same idempotency key* once while still
                    // non-cancellable.  The server create contract guarantees that replay either
                    // returns the original reservation or a typed conflict; both outcomes are
                    // recorded before the caller's cancellation is propagated.
                    createSessionNonCancellable(
                        request = createRequest,
                    )
                if (createAttempt.unknownReservation) {
                    sessionLedger.markUnknownReservation()
                }
                val createResult =
                    createAttempt.result
                        ?: throw requireNotNull(createAttempt.cancellation) {
                            "create cancellation recovery returned no result"
                        }
                val session =
                    when (createResult) {
                        is DataResult.Content -> {
                            val value = createResult.data
                            val uploadId = value.uploadId.trim()
                            val legalUploadId = isLegalAttachmentUploadSessionId(uploadId)
                            // Only a UUID-shaped id is safe to place in a cancel/status path. Keep
                            // legal ids even when another field is malformed so the reservation
                            // can still be compensated. An illegal or duplicate id is explicitly
                            // marked unknown: there may be a second reservation that cannot be
                            // safely addressed by this client.
                            val acceptedUploadId = sessionLedger.recordCreateResponseId(uploadId)
                            val duplicateUploadId = legalUploadId && !acceptedUploadId
                            val validPutContract =
                                runCatching {
                                    requireValidAttachmentUploadPresignedUrl(value.upload.url)
                                    requireValidAttachmentUploadPutHeaders(value.upload.headers)
                                }.isSuccess
                            if (
                                value.state != "pending" ||
                                !legalUploadId ||
                                duplicateUploadId ||
                                value.uploadId != uploadId ||
                                !isLegalAttachmentUploadSessionId(value.attachmentId.value) ||
                                value.upload.method != "PUT" ||
                                !validPutContract
                            ) {
                                createAttempt.cancellation?.let { throw it }
                                return failAfterCreated(
                                    sessionLedger,
                                    DataResult.Error(
                                        ScreenError("attachment_upload_session_contract_invalid", retryable = false)
                                    ),
                                )
                            }
                            CreatedSession(file = file, result = value.copy(uploadId = uploadId))
                        }
                        else -> {
                            // The repository carries any UUID-shaped upload id extracted from a
                            // malformed create body in structured recovery metadata.  Consume it
                            // before translating the primary result so a reserved session cannot
                            // be leaked just because JSON parsing failed.
                            val recoveryIds =
                                (createResult as? DataResult.Error)
                                    ?.error
                                    ?.recoverySessionIds
                                    .orEmpty()
                            sessionLedger.recordCreateFailure(recoveryIds)
                            createAttempt.cancellation?.let { throw it }
                            return failAfterCreated(
                                sessionLedger,
                                createResult.asBatchFailure("attachment_upload_batch_create_failed")
                            )
                        }
                    }

                // A gateway-level cancellation is terminal for the caller even when the replay
                // recovered a valid idempotent reservation.  The id is already in the ledger, so
                // the outer cancellation handler can retract it before rethrowing.
                createAttempt.cancellation?.let { throw it }

                currentCoroutineContext().ensureActive()
                val putResult = safeCall("put") { gateway.put(session.result.upload, file) }
                if (putResult !is DataResult.Content) {
                    return failAfterCreated(
                        sessionLedger,
                        putResult.asBatchFailure("attachment_upload_batch_put_failed")
                    )
                }

                val completeResult = completeWithWhitelistedRetry(session.result.uploadId)
                val attachment =
                    when (completeResult) {
                        is DataResult.Content -> {
                            val value = completeResult.data
                            if (
                                value.uploadId != session.result.uploadId ||
                                !isLegalAttachmentUploadSessionId(value.uploadId) ||
                                value.state != "completed" ||
                                value.attachment.id != session.result.attachmentId ||
                                !isLegalAttachmentUploadSessionId(value.attachment.id.value)
                            ) {
                                return failAfterCreated(
                                    sessionLedger,
                                    DataResult.Error(
                                        ScreenError("attachment_upload_session_contract_invalid", retryable = false)
                                    ),
                                )
                            }
                            value.attachment
                        }
                        else -> return failAfterCreated(
                            sessionLedger,
                            completeResult.asBatchFailure("attachment_upload_batch_complete_failed"),
                        )
                    }
                completed +=
                    DirectAttachmentUpload(
                        localId = file.id.trim(),
                        uploadId = session.result.uploadId,
                        attachment = attachment,
                    )
            }
            if (completed.size != normalizedFiles.size) {
                failAfterCreated(
                    sessionLedger,
                    DataResult.Error(ScreenError("attachment_upload_batch_incomplete", retryable = true)),
                )
            } else {
                DataResult.Content(
                    DirectAttachmentUploadBatch(
                        uploads = completed.toList(),
                        sessionIds = sessionLedger.ids.toList(),
                        compensationHandle = DirectAttachmentUploadCompensationHandle(this, sessionLedger),
                    )
                )
            }
        } catch (cancellation: CancellationException) {
            // Gallery callers normally enter this protocol from a NonCancellable
            // handoff, but preserve the same compensation guarantee for direct
            // callers before propagating cancellation.
            runCatching {
                withContext(NonCancellable) { compensate(sessionLedger) }
            }.onFailure { error ->
                safeDiagnostics(
                    "attachment_upload_direct_compensation_unexpected_failure " +
                        "error=${error::class.simpleName.orEmpty()}"
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            failAfterCreated(
                sessionLedger,
                DataResult.Error(
                    ScreenError(
                        code = "attachment_upload_batch_failed",
                        retryable = true,
                    )
                )
            )
        }
    }

    /** Read the capability through the same authority-bound gateway used by upload/compensation. */
    internal suspend fun capabilities(): DataResult<AttachmentUploadCapabilities> =
        safeCall("capabilities") { gateway.capabilities() }

    internal suspend fun prepare(file: PlatformFile): DataResult<PlatformFile> =
        safeCall("prepare") { gateway.prepare(file) }

    internal suspend fun uploadOneThreadFile(
        channelId: ChannelId,
        file: PlatformFile,
        clientRequestId: String,
        onPhase: (ThreadComposerDirectUploadPhase) -> Unit = {},
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): DataResult<Attachment> =
        gateway.uploadOneThreadFile(channelId, file, clientRequestId, onPhase, onUploadProgress)

    private suspend fun completeWithWhitelistedRetry(
        uploadId: String,
    ): DataResult<AttachmentUploadSessionCompleteResult> {
        val first = safeCall("complete") { gateway.complete(uploadId) }
        val firstError = (first as? DataResult.Error)?.error ?: return first
        val serverCode = firstError.serverCode?.trim()?.lowercase()
        if (serverCode !in COMPLETE_RETRY_SERVER_CODES) return first
        safeDiagnostics("attachment_upload_complete_retry serverCode=$serverCode")
        return safeCall("complete") { gateway.complete(uploadId) }
    }

    /**
     * Resolve a create response even when the first transport reports cancellation.
     *
     * The caller's cancellation cannot enter this block because it is [NonCancellable].  A
     * cancellation thrown by the gateway itself therefore represents an ambiguous network
     * outcome: the server may have committed the idempotent create before the response was lost.
     * Replaying the exact request (including [AttachmentUploadSessionCreateRequest.clientRequestId])
     * gives the server a chance to return the original reservation.  If the replay is also
     * canceled, the outer ledger marks the reservation unknown and fails closed; it never reports
     * an empty ledger as a safe cleanup.
     */
    private data class CreateSessionAttempt(
        val result: DataResult<AttachmentUploadSessionCreateResult>?,
        val cancellation: CancellationException? = null,
        val unknownReservation: Boolean = false,
    )

    private suspend fun createSessionNonCancellable(
        request: AttachmentUploadSessionCreateRequest,
    ): CreateSessionAttempt =
        withContext(NonCancellable) {
            try {
                CreateSessionAttempt(result = safeCall("create") { gateway.create(request) })
            } catch (firstCancellation: CancellationException) {
                safeDiagnostics(
                    "attachment_upload_direct_create_cancellation_recovery " +
                        "requestId=${request.clientRequestId.take(8)}"
                )
                val replay = try {
                    // Same idempotency key is intentional; generating a new request id here could
                    // create a second reservation while the first response is merely delayed.
                    safeCall("create") { gateway.create(request) }
                } catch (secondCancellation: CancellationException) {
                    safeDiagnostics(
                        "attachment_upload_compensation_unknown_reservation safe=false " +
                            "phase=create_cancellation_recovery requestId=${request.clientRequestId.take(8)}"
                    )
                    // Preserve cancellation semantics for the caller.  The outer catch performs
                    // the NonCancellable compensation pass before rethrowing this signal.
                    return@withContext CreateSessionAttempt(
                        result = null,
                        cancellation = secondCancellation,
                        unknownReservation = true,
                    )
                }
                // A non-content replay does not prove that the first request and the replay refer
                // to the same server reservation. Keep any recoverable UUIDs for best-effort
                // cancellation, but retain sticky unknown evidence so a successful cancel cannot
                // be misreported as a globally safe retract without a valid replay body.
                val replayUnknownReservation = replay !is DataResult.Content
                CreateSessionAttempt(
                    result = replay,
                    cancellation = firstCancellation,
                    unknownReservation = replayUnknownReservation,
                )
            }
        }

    private suspend fun failAfterCreated(
        sessionLedger: AttachmentUploadSessionLedger,
        primary: DataResult<Nothing>,
    ): DataResult<DirectAttachmentUploadBatch> {
        val compensated =
            runCatching {
                withContext(NonCancellable) { compensate(sessionLedger) }
            }.onFailure { error ->
                safeDiagnostics(
                    "attachment_upload_direct_compensation_unexpected_failure " +
                        "error=${error::class.simpleName.orEmpty()}"
                )
            }.getOrDefault(false)
        if (sessionLedger.hasUnknownReservation) {
            safeDiagnostics(
                "attachment_upload_compensation_unknown_reservation " +
                    "knownSessionCount=${sessionLedger.ids.size}"
            )
        }
        // Preserve the primary failure classification even when compensation succeeds.  The
        // ledger is diagnostic evidence, not a replacement error, and retaining it makes a
        // later support/cleanup decision auditable without masking the original phase.
        return primary.withCleanupEvidence(
            cleanupEvidence = sessionLedger.cleanupEvidence(),
            fallbackCode = "attachment_upload_batch_failed",
        )
    }

    /**
     * Compensate a previously returned batch.  The explicit session-id ledger is authoritative;
     * upload rows are only a defensive supplement for callers that received a partially malformed
     * batch object.  Keeping this operation public to the shared data-source seam lets Gallery
     * reject a post-upload draft/projection race without publishing any partial attachment.
     */
    internal suspend fun compensateBatch(batch: DirectAttachmentUploadBatch): Boolean {
        batch.compensationHandle?.let { handle ->
            return withContext(NonCancellable) { handle.compensate() }
        }
        val rawSessionIds =
            (batch.sessionIds + batch.uploads.map { upload -> upload.uploadId })
                .map(String::trim)
                .filter(String::isNotBlank)
        val sessionIds =
            rawSessionIds
                .filter(::isLegalAttachmentUploadSessionId)
                .distinctBy { id -> id.lowercase() }
        // A caller may hand us a malformed batch after a failed local contract check. Never put
        // an arbitrary value into the path-bearing gateway, but still attempt cleanup for every
        // legal id we can safely recover. The false result keeps the caller fail-closed.
        val allIdsLegal = rawSessionIds.all(::isLegalAttachmentUploadSessionId)
        return withContext(NonCancellable) {
            allIdsLegal && sessionIds.isNotEmpty() && compensate(AttachmentUploadSessionLedger(sessionIds))
        }
    }

    /**
     * Cancel every session even when one cancellation fails.  Any failed or
     * contract-ambiguous cancel is followed by a status read.  Only explicit
     * terminal states known to release the reservation count as compensation.
     */
    private suspend fun compensate(sessionLedger: AttachmentUploadSessionLedger): Boolean {
        if (sessionLedger.compensationAttempted) {
            return sessionLedger.compensationSucceeded
        }
        sessionLedger.compensationAttempted = true
        // A parse/transport failure may leave one or more reservations unidentified.  Even when
        // every UUID we can name is later canceled, that uncertainty is not proof of a safe
        // retract; keep the batch fail-closed for the caller and for its diagnostic evidence.
        var allSafe = !sessionLedger.hasUnknownReservation
        val orderedIds = sessionLedger.ids.toList()
        orderedIds.asReversed().forEachIndexed { reverseIndex, uploadId ->
            val cancelResult = safeCompensationCall("cancel") { gateway.cancel(uploadId) }
            val cancelView = (cancelResult as? DataResult.Content)?.data
            if (cancelView != null && isCompensated(cancelView, uploadId)) {
                sessionLedger.outcomes[uploadId] = "cancel:${cancelView.state}/${cancelView.reservationState ?: "none"}"
                return@forEachIndexed
            }

            val statusResult = safeCompensationCall("status") { gateway.status(uploadId) }
            val statusView = (statusResult as? DataResult.Content)?.data
            if (statusView != null && isCompensated(statusView, uploadId)) {
                sessionLedger.outcomes[uploadId] = "status:${statusView.state}/${statusView.reservationState ?: "none"}"
            } else {
                allSafe = false
                sessionLedger.outcomes[uploadId] =
                    "unknown:cancel=${cancelResult.evidenceName(cancelView)}," +
                        "status=${statusResult.evidenceName(statusView)}"
                safeDiagnostics(
                    "attachment_upload_compensation_unknown sessionOrdinal=${orderedIds.size - reverseIndex} " +
                        "cancel=${cancelResult.resultName()} status=${statusResult.resultName()}"
                )
            }
        }
        sessionLedger.compensationSucceeded = allSafe
        return allSafe
    }

    internal suspend fun compensateLedger(sessionLedger: AttachmentUploadSessionLedger): Boolean =
        compensate(sessionLedger)

    private fun isCompensated(view: AttachmentUploadSessionView, expectedUploadId: String): Boolean =
        view.uploadId.trim().equals(expectedUploadId, ignoreCase = true) &&
            when (view.state) {
            // The server may omit reservationState on non-completed canceled/expired views. A
            // pending value is not safe: it means the reservation may still be visible.
            "canceled", "expired" ->
                view.reservationState == null ||
                    view.reservationState == "canceled" ||
                    view.reservationState == "expired"
            // A completed session can still be safely compensated only when
            // the reservation itself is explicitly canceled/expired.  A
            // consumed reservation is immutable from the client's perspective.
            "completed" -> view.reservationState == "canceled" || view.reservationState == "expired"
            else -> false
        }

    private suspend fun prepareFiles(
        files: List<PlatformFile>,
        maxBytes: Long,
    ): DataResult<List<PlatformFile>> {
        if (maxBytes <= 0L) {
            return DataResult.Error(
                ScreenError(
                    code = "attachment_upload_batch_invalid_request",
                    retryable = false,
                )
            )
        }
        val ids = mutableSetOf<String>()
        val preparedFiles = mutableListOf<PlatformFile>()
        files.forEach { file ->
            val id = file.id.trim()
            val fileName = file.fileName.trim()
            val mimeType = file.mimeType?.trim()?.takeIf { it.isNotBlank() }
            val fileRef = file.fileRef?.trim()?.takeIf { it.isNotBlank() }
            if (
                id.isBlank() ||
                !ids.add(id) ||
                fileName.isBlank() ||
                mimeType == null ||
                (fileRef != null && file.bytes.isNotEmpty()) ||
                (fileRef == null && file.bytes.isEmpty())
            ) {
                return DataResult.Error(
                    ScreenError(
                        code = if (fileRef != null && file.bytes.isNotEmpty()) {
                        "attachment_file_input_conflict"
                        } else {
                            "attachment_upload_batch_invalid_request"
                        },
                        retryable = false,
                    )
                )
            }
            val normalized =
                file.copy(
                    id = id,
                    fileName = fileName,
                    mimeType = mimeType,
                    fileRef = fileRef,
                )
            val preparedResult = safeCall("prepare") { gateway.prepare(normalized) }
            val prepared =
                when (preparedResult) {
                    is DataResult.Content -> preparedResult.data
                    else -> return preparedResult.asBatchFailure("attachment_upload_batch_invalid_request")
                }
            val canonicalPrepared = prepared.normalizePreparedFile(normalized, maxBytes)
                ?: return DataResult.Error(
                    ScreenError(
                        code = "attachment_upload_file_preflight_invalid",
                        retryable = false,
                    )
                )
            preparedFiles += canonicalPrepared
        }
        return DataResult.Content(preparedFiles)
    }

    /**
     * Validate the preparer's output instead of trusting it blindly.  The identity/metadata must
     * remain the caller's values, and a resolver-backed ref must never be paired with an alternate
     * in-memory body.  For bytes-only files the declared size must exactly equal the bytes that
     * the gateway will send.
     */
    private fun PlatformFile.normalizePreparedFile(
        original: PlatformFile,
        maxBytes: Long,
    ): PlatformFile? {
        val normalizedId = id.trim()
        val normalizedName = fileName.trim()
        val normalizedMime = mimeType?.trim()?.takeIf { it.isNotBlank() }
        val normalizedRef = fileRef?.trim()?.takeIf { it.isNotBlank() }
        val size = sizeBytes
        if (
            normalizedId != original.id ||
            normalizedName != original.fileName ||
            normalizedMime != original.mimeType ||
            normalizedRef != original.fileRef ||
            previewUri != original.previewUri ||
            source != original.source ||
            !bytes.contentEquals(original.bytes) ||
            normalizedId.isBlank() ||
            normalizedName.isBlank() ||
            normalizedMime == null ||
            size == null ||
            size <= 0L ||
            size > maxBytes ||
            (normalizedRef != null && bytes.isNotEmpty()) ||
            (normalizedRef == null && bytes.isEmpty()) ||
            (normalizedRef == null && size != bytes.size.toLong())
        ) {
            return null
        }
        return copy(
            id = normalizedId,
            fileName = normalizedName,
            mimeType = normalizedMime,
            fileRef = normalizedRef,
            sizeBytes = size,
        )
    }

    private suspend fun <T> safeCall(
        phase: String,
        block: suspend () -> DataResult<T>,
    ): DataResult<T> =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            safeDiagnostics(
                "attachment_upload_direct_${phase}_failed error=${error::class.simpleName.orEmpty()}"
            )
            val recoverySessionIds =
                if (phase == "create") {
                    generateSequence(error) { it.cause }
                        .mapNotNull { cause ->
                            (cause as? AttachmentUploadSessionCreateParseException)?.recoveryUploadId
                        }
                        .filter(::isLegalAttachmentUploadSessionId)
                        .toSet()
                } else {
                    emptySet()
                }
            DataResult.Error(
                ScreenError(
                    code = "attachment_upload_batch_${phase}_failed",
                    retryable = true,
                    recoverySessionIds = recoverySessionIds,
                )
            )
        }

    /** Cleanup calls must never escape because a transport or logger throws. */
    private suspend fun <T> safeCompensationCall(
        phase: String,
        block: suspend () -> DataResult<T>,
    ): DataResult<T> =
        try {
            block()
        } catch (error: Throwable) {
            safeDiagnostics(
                "attachment_upload_direct_compensation_${phase}_failed " +
                    "error=${error::class.simpleName.orEmpty()}"
            )
            DataResult.Error(
                ScreenError(
                    code = "attachment_upload_compensation_${phase}_failed",
                    retryable = true,
                )
            )
        }

    private fun safeDiagnostics(message: String) {
        runCatching { diagnosticsLogger(message) }
    }

    private data class CreatedSession(
        val file: PlatformFile,
        val result: AttachmentUploadSessionCreateResult,
    )
}

/**
 * Reservation ledger used while a Gallery batch is in flight.  [hasUnknownReservation] is
 * intentionally sticky: once a create response is malformed or ambiguous, successfully
 * retracting the UUIDs that we can name does not prove that no other reservation exists.
 */
internal class AttachmentUploadSessionLedger(
    initialIds: Collection<String> = emptyList(),
) {
    val ids: LinkedHashSet<String> = linkedSetOf()
    var hasUnknownReservation: Boolean = false
    var compensationAttempted: Boolean = false
    var compensationSucceeded: Boolean = false
    val outcomes: LinkedHashMap<String, String> = linkedMapOf()

    init {
        initialIds.forEach { id ->
            if (!addLegalSessionId(id)) hasUnknownReservation = true
        }
    }

    /** Mark a reservation outcome as unknowable when create was canceled before an id was parsed. */
    fun markUnknownReservation() {
        hasUnknownReservation = true
    }

    /** Record the id from a content response; false means the response is ambiguous. */
    fun recordCreateResponseId(raw: String): Boolean {
        val id = raw.trim()
        val added = addLegalSessionId(id)
        if (!added) hasUnknownReservation = true
        return added
    }

    /** Record structured ids recovered from a failed create parse/transport. */
    fun recordCreateFailure(recoveryIds: Collection<String>) {
        if (recoveryIds.isEmpty()) {
            hasUnknownReservation = true
            return
        }
        recoveryIds.forEach { id ->
            if (!addLegalSessionId(id)) hasUnknownReservation = true
        }
    }

    private fun addLegalSessionId(raw: String): Boolean {
        val id = raw.trim()
        if (!isLegalAttachmentUploadSessionId(id)) return false
        if (ids.any { existing -> existing.equals(id, ignoreCase = true) }) return false
        ids += id
        return true
    }
}

private fun AttachmentUploadSessionLedger.cleanupEvidence(): String =
    buildString {
        append("attempted=")
        append(compensationAttempted)
        append(";safe=")
        append(compensationSucceeded)
        append(";unknownReservation=")
        append(hasUnknownReservation)
        append(";sessions=")
        append(ids.size)
        if (outcomes.isNotEmpty()) {
            append(';')
            append(
                outcomes.entries.joinToString(",") { (id, outcome) ->
                    // IDs are UUID-shaped and contain no delimiters beyond hyphens; retaining a
                    // short prefix keeps diagnostics bounded while still correlating a session.
                    "${id.take(8)}:$outcome"
                }
            )
        }
    }

private fun <T> DataResult<T>.withCleanupEvidence(
    cleanupEvidence: String,
    fallbackCode: String,
): DataResult<Nothing> =
    when (this) {
        is DataResult.Error -> DataResult.Error(error.copy(cleanupEvidence = cleanupEvidence))
        is DataResult.NoPermission -> DataResult.NoPermission(blocker.copy(cleanupEvidence = cleanupEvidence))
        is DataResult.Empty ->
            DataResult.Error(
                ScreenError(
                    code = fallbackCode,
                    retryable = true,
                    cleanupEvidence = cleanupEvidence,
                )
            )
        is DataResult.Content ->
            DataResult.Error(
                ScreenError(
                    code = fallbackCode,
                    retryable = false,
                    cleanupEvidence = cleanupEvidence,
                )
            )
    }

/** Process-local handle that keeps the captured gateway/ledger together for the whole batch. */
internal class DirectAttachmentUploadCompensationHandle(
    private val coordinator: DirectAttachmentUploadCoordinator,
    private val ledger: AttachmentUploadSessionLedger,
) {
    suspend fun compensate(): Boolean =
        coordinator.compensateLedger(ledger)
}

private fun <T> DataResult<T>.asBatchFailure(fallbackCode: String): DataResult<Nothing> =
    when (this) {
        is DataResult.Content -> DataResult.Error(ScreenError(fallbackCode, retryable = false))
        is DataResult.Empty -> DataResult.Error(ScreenError(fallbackCode, retryable = true))
        is DataResult.Error -> DataResult.Error(error)
        is DataResult.NoPermission -> DataResult.NoPermission(blocker)
    }

private fun DataResult<*>.resultName(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

private fun DataResult<*>.evidenceName(view: AttachmentUploadSessionView?): String =
    when (this) {
        is DataResult.Content ->
            view?.let { "content:${it.state}/${it.reservationState ?: "none"}" } ?: "content"
        is DataResult.Empty -> "empty"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

internal fun newAttachmentUploadClientRequestId(): String {
    // Kotlin/Native has no java.util.UUID in commonMain.  This is a version-4
    // UUID-shaped value, sufficient for the server's strict idempotency schema.
    val high = Random.nextLong().toULong()
    val low = Random.nextLong().toULong()
    val timeLow = (high shr 32).toString(16).padStart(8, '0')
    val timeMid = (high shr 16 and 0xffffuL).toString(16).padStart(4, '0')
    val timeHigh = ((high and 0x0fffuL) or 0x4000uL).toString(16).padStart(4, '0')
    val clock = ((low shr 48 and 0x3fffuL) or 0x8000uL).toString(16).padStart(4, '0')
    val node = (low and 0xffffffffffffuL).toString(16).padStart(12, '0')
    return "$timeLow-$timeMid-$timeHigh-$clock-$node"
}
