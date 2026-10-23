package build.raft.app.data

import build.raft.app.data.realtime.RealtimeBaselineHistoryRequests
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_AUTHORIZATION
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.MultipartFileStreamOpener
import build.raft.app.network.RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS
import build.raft.app.network.RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftHttpFileBody
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.network.hasStreamingFileRefParts
import build.raft.app.network.RaftRequestTimeoutException
import build.raft.app.network.createPlatformRaftHttpExecutor
import build.raft.app.network.toSafeDiagnosticsToken
import build.raft.app.network.redactPresignedUrlText
import build.raft.app.network.RaftByteStream
import kotlin.time.TimeSource

internal const val RAFT_UPLOAD_SCHEDULER_DISPATCH_MARGIN_MILLIS = 15_000L

/**
 * task #788: scheduler backstop for streaming fileRef uploads, checked-derived
 * from the authoritative budgets of the real call chain
 * `scheduler → authMiddleware(≤RAFT_AUTH_MAX_REQUEST_ATTEMPTS upload attempts,
 * ≤AUTH_REFRESH_MAX_ATTEMPTS refresh waits) → transport(180s per attempt)`.
 * The NetworkKMM per-attempt 180s stays the single authoritative deadline
 * (product ruling: per-attempt semantics); this bound only fires if the
 * transport deadline machinery itself fails, so a Foreground semaphore permit
 * can never leak on an unbounded upload. 2×180s + 2×4s + 15s = 383s.
 */
internal const val RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS =
    RAFT_AUTH_MAX_REQUEST_ATTEMPTS * RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS +
        AUTH_REFRESH_MAX_ATTEMPTS * AUTH_REFRESH_ATTEMPT_TIMEOUT_MILLIS +
        RAFT_UPLOAD_SCHEDULER_DISPATCH_MARGIN_MILLIS

internal class SharedRaftNetworkClient(
    private val sessionStore: RaftAuthSessionStore,
    private val baseUrl: String = RAFT_API_BASE_URL,
    private val baseUrlProvider: () -> String = { baseUrl },
    private val warningLogger: (String) -> Unit = {},
    private val criticalWarningLogger: (String) -> Unit = warningLogger,
    private val terminalAuthLossReporter: suspend (StoredAuthSession, String) -> Unit = { _, _ -> },
    private val requestScheduler: RaftRequestScheduler = RaftRequestScheduler(),
    private val platformFileResolver: RaftPlatformFileResolver? = null,
    private val executor: RaftHttpExecutor = createPlatformRaftHttpExecutor(warningLogger),
    // Task #150: shared across every client built on the same executor, like the scheduler.
    private val reachabilityGates: RaftHostReachabilityGates = RaftHostReachabilityGates(),
    // Sync horizon (slock #10982): a starting horizon is usable only if no baseline history read of its
    // server was sent before its ack arrived. Recorded here, right before the request leaves.
    private val onBaselineHistoryRequestSent: (serverId: String) -> Unit = RealtimeBaselineHistoryRequests::recordSent,
) : RaftNetworkClient, AuthorityBoundRaftNetworkClient, ProgressiveAttachmentNetworkClient {
    private fun authMiddleware(authBaseUrl: String = currentBaseUrl()): RaftNetworkAuthMiddleware =
        RaftNetworkAuthMiddleware(
            sessionStore = sessionStore,
            authApi = RaftAuthApiClient(SharedRaftAuthTransport(authBaseUrl, executor, warningLogger)),
            logger = warningLogger,
            warningLogger = warningLogger,
            criticalLogger = criticalWarningLogger,
            terminalAuthLossReporter = terminalAuthLossReporter,
            environmentIdProvider = { currentBaseUrl() },
        )

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        requestInternal(
            priority = priority,
            path = path,
            method = method,
            query = query,
            serverIdOverride = serverIdOverride,
            bodyText = bodyText,
            expectedAuthority = null
        )

    override suspend fun requestWithHeaders(
        priority: DataRequestPriority,
        path: String,
        headers: Map<String, String>,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        requestInternal(
            priority = priority,
            path = path,
            method = method,
            query = query,
            serverIdOverride = serverIdOverride,
            bodyText = bodyText,
            expectedAuthority = null,
            extraHeaders = headers
        )

    override suspend fun requestBound(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse =
        requestInternal(
            priority = priority,
            path = path,
            method = method,
            query = query,
            serverIdOverride = serverIdOverride,
            bodyText = bodyText,
            expectedAuthority = expectedAuthority
        )

    override suspend fun requestBoundWithHeaders(
        priority: DataRequestPriority,
        path: String,
        headers: Map<String, String>,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse =
        requestInternal(
            priority = priority,
            path = path,
            method = method,
            query = query,
            serverIdOverride = serverIdOverride,
            bodyText = bodyText,
            expectedAuthority = expectedAuthority,
            extraHeaders = headers
        )

    private suspend fun requestInternal(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
        expectedAuthority: RaftExpectedAuthBinding?,
        extraHeaders: Map<String, String> = emptyMap()
    ): HomeNetworkResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val category = path.networkDiagnosticsCategory()
        val normalizedPath = path.networkDiagnosticsPath()
        val requestBaseUrl = expectedAuthority?.environmentId?.normalizedEnvironmentId() ?: currentBaseUrl()
        if (!expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
            return routeEnvironmentChangedResponse()
        }
        val gate = reachabilityGates.forBaseUrl(requestBaseUrl)
        val probe =
            when (val admission = gate.admit()) {
                is RaftHostReachabilityGate.Admission.Blocked -> {
                    warningLogger(
                        "endpoint category=$category path=$normalizedPath method=${method.uppercase()} " +
                            "code=0 durationMs=0 result=network_error" +
                            admission.response.transportDiagnosticsLogSuffix()
                    )
                    return admission.response
                }
                is RaftHostReachabilityGate.Admission.Proceed -> admission.probe
            }
        var recorded = false
        return try {
            requestScheduler.run(
                priority = priority.toNetworkPriority(),
                key = path,
                reason = method.uppercase()
            ) scheduled@{
                if (!expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                    return@scheduled routeEnvironmentChangedResponse()
                }
                val transportResponse =
                    authMiddleware(requestBaseUrl).request(
                        serverIdOverride = serverIdOverride,
                        expectedAuthority = expectedAuthority,
                        caller = "shared_network:$normalizedPath"
                    ) authenticated@{ auth ->
                        if (!expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                            return@authenticated routeEnvironmentChangedResponse()
                        }
                        if (RealtimeBaselineHistoryRequests.isBaselineHistoryRequest(method, path)) {
                            onBaselineHistoryRequestSent(auth.serverId)
                        }
                        val response =
                            executor.execute(
                                priority = priority.toNetworkPriority(),
                                url = absoluteUrl(requestBaseUrl, path, query),
                                method = method.uppercase(),
                                headers =
                                    buildMap {
                                        putAll(extraHeaders)
                                        put(HEADER_AUTHORIZATION, auth.authorization)
                                        put(HEADER_SERVER_ID, auth.serverId)
                                    },
                                bodyText = bodyText,
                                jsonBody = bodyText != null
                            ).toHomeNetworkResponse()
                        if (expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                            response
                        } else {
                            routeEnvironmentChangedResponse()
                        }
                    }
                val response =
                    if (expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                        transportResponse
                    } else {
                        routeEnvironmentChangedResponse()
                    }
                val endpointDiagnostic =
                    "endpoint category=$category path=$normalizedPath method=${method.uppercase()} " +
                        "queryKeys=${query.networkDiagnosticsQueryKeys()} " +
                        "queryMeta=${query.networkDiagnosticsQueryMeta()} " +
                        "requestBytes=${bodyText?.encodeToByteArray()?.size ?: 0} " +
                        "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                        "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                        "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                        "result=${response.networkDiagnosticsResult()}" +
                        response.errorBodyLogSuffix() +
                        response.requestIdLogSuffix() +
                        response.transportDiagnosticsLogSuffix()
                if (response.code == 401) {
                    criticalWarningLogger(endpointDiagnostic)
                } else {
                    warningLogger(endpointDiagnostic)
                }
                gate.record(response, probe)
                recorded = true
                response
            }
        } catch (timeout: RaftRequestTimeoutException) {
            timeout.toTimeoutNetworkResponse(
                category = category,
                path = normalizedPath,
                method = method.uppercase(),
                requestBytes = (bodyText?.encodeToByteArray()?.size ?: 0).toLong(),
                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                warningLogger = warningLogger
            )
        } finally {
            // Cancelled, timed out or thrown: a probe that never reported must not keep the gate shut.
            if (!recorded) gate.abandon(probe)
        }
    }

    override suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?
    ): HomeNetworkResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val diagnosticPath = url.substringBefore('?')
        return try {
            requestScheduler.run(
                priority = priority.toNetworkPriority(),
                key = diagnosticPath,
                reason = "raw:${method.uppercase()}"
            ) {
                val response =
                    executor.execute(
                        priority = priority.toNetworkPriority(),
                        url = url,
                        method = method.uppercase(),
                        headers = headers,
                        bodyText = bodyText,
                        jsonBody = false
                    ).toHomeNetworkResponse()
                warningLogger(
                    "endpoint category=raw_absolute_url path=$diagnosticPath method=${method.uppercase()} " +
                        "requestBytes=${bodyText?.encodeToByteArray()?.size ?: 0} " +
                        "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                        "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                        "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                        "result=${response.networkDiagnosticsResult()}" +
                        response.requestIdLogSuffix()
                )
                response
            }
        } catch (timeout: RaftRequestTimeoutException) {
            timeout.toTimeoutNetworkResponse(
                category = "raw_absolute_url",
                path = diagnosticPath,
                method = method.uppercase(),
                requestBytes = (bodyText?.encodeToByteArray()?.size ?: 0).toLong(),
                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                warningLogger = warningLogger
            )
        }
    }

    override suspend fun createAttachmentUploadSession(
        priority: DataRequestPriority,
        request: AttachmentUploadSessionCreateRequest,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    override suspend fun completeAttachmentUploadSession(
        priority: DataRequestPriority,
        uploadId: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    override suspend fun cancelAttachmentUploadSession(
        priority: DataRequestPriority,
        uploadId: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    override suspend fun statusAttachmentUploadSession(
        priority: DataRequestPriority,
        uploadId: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    override suspend fun putAttachmentUploadBytes(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        bytes: ByteArray,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    override suspend fun putAttachmentUploadFile(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    override suspend fun prepareAttachmentUploadFile(
        file: PlatformFile,
    ): RaftPlatformFilePreparationResult =
        RaftPlatformFilePreparationResult.Rejected("auth_authority_binding_required")

    override suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse =
        uploadAttachmentsInternal(priority, channelId, files, expectedAuthority = null, onProgress = null)

    override suspend fun uploadAttachmentsWithProgress(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>,
        onProgress: (build.raft.app.network.RaftTransferProgress) -> Unit
    ): HomeNetworkResponse =
        uploadAttachmentsInternal(
            priority = priority,
            channelId = channelId,
            files = files,
            expectedAuthority = null,
            onProgress = onProgress
        )

    override suspend fun uploadAttachmentsBound(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse =
        uploadAttachmentsInternal(priority, channelId, files, expectedAuthority, onProgress = null)

    override suspend fun createAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        request: AttachmentUploadSessionCreateRequest,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse =
        requestInternal(
            priority = priority,
            path = "attachments/upload-sessions",
            method = "POST",
            query = emptyList(),
            serverIdOverride = ServerId(expectedAuthority.serverId),
            bodyText = encodeSharedAttachmentUploadSessionCreateRequest(request),
            expectedAuthority = expectedAuthority,
        )

    override suspend fun completeAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        uploadId: String,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse {
        val normalizedUploadId = uploadId.trim()
        if (!isLegalAttachmentUploadSessionId(normalizedUploadId)) {
            return invalidAttachmentUploadSessionIdResponse()
        }
        return requestInternal(
            priority = priority,
            path = "attachments/upload-sessions/$normalizedUploadId/complete",
            method = "POST",
            query = emptyList(),
            serverIdOverride = ServerId(expectedAuthority.serverId),
            bodyText = null,
            expectedAuthority = expectedAuthority,
        )
    }

    override suspend fun cancelAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        uploadId: String,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse {
        val normalizedUploadId = uploadId.trim()
        if (!isLegalAttachmentUploadSessionId(normalizedUploadId)) {
            return invalidAttachmentUploadSessionIdResponse()
        }
        return requestInternal(
            priority = priority,
            path = "attachments/upload-sessions/$normalizedUploadId",
            method = "DELETE",
            query = emptyList(),
            serverIdOverride = ServerId(expectedAuthority.serverId),
            bodyText = null,
            expectedAuthority = expectedAuthority,
        )
    }

    override suspend fun statusAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        uploadId: String,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse {
        val normalizedUploadId = uploadId.trim()
        if (!isLegalAttachmentUploadSessionId(normalizedUploadId)) {
            return invalidAttachmentUploadSessionIdResponse()
        }
        return requestInternal(
            priority = priority,
            path = "attachments/upload-sessions/$normalizedUploadId",
            method = "GET",
            query = emptyList(),
            serverIdOverride = ServerId(expectedAuthority.serverId),
            bodyText = null,
            expectedAuthority = expectedAuthority,
        )
    }

    override suspend fun putAttachmentUploadBytesBound(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        bytes: ByteArray,
        expectedAuthority: RaftExpectedAuthBinding,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
    ): HomeNetworkResponse =
        executePresignedPut(
            priority = priority,
            upload = upload,
            bodyBytes = bytes,
            bodyFile = null,
            expectedAuthority = expectedAuthority,
            onUploadProgress = onUploadProgress,
        )

    override suspend fun putAttachmentUploadFileBound(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        expectedAuthority: RaftExpectedAuthBinding,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
    ): HomeNetworkResponse {
        // Do not even stat a private file after the captured route authority has gone stale.
        // [executePresignedPut] repeats this check immediately before and after transport; this
        // earlier fence keeps local-file resolution in the same authority-bound operation.
        if (!expectedAuthority.isCurrentForPresignedPut()) {
            return routeEnvironmentChangedResponse()
        }
        val resolved =
            when (
                val result =
                    listOf(
                        MultipartFile(
                            name = "file",
                            fileName = file.fileName,
                            mimeType = file.mimeType ?: "application/octet-stream",
                            bytes = file.bytes,
                            fileRef = file.fileRef,
                            sizeBytes = file.sizeBytes,
                        )
                    ).preparePlatformFileStreams()
            ) {
                is ResolvedPlatformFiles.Content -> result.files.single()
                is ResolvedPlatformFiles.Error -> return result.response
            }
        val opener = resolved.streamOpener
        val ref = resolved.fileRef
        return if (opener != null && ref != null && resolved.bytes.isEmpty()) {
            // `executeFile` must receive the length obtained from the resolver's successful stat
            // operation.  Never fall back to the caller's optional hint (or zero): a stale/missing
            // hint would let the HTTP engine advertise a length which is not what the opened file
            // can produce, and some platforms reject that only after bytes have started flowing.
            val verifiedContentLength =
                resolved.sizeBytes?.takeIf { it > 0L }
                    ?: return invalidPlatformFileLengthResponse()
            executePresignedPut(
                priority = priority,
                upload = upload,
                bodyBytes = null,
                bodyFile = RaftHttpFileBody(
                    path = ref,
                    contentType = upload.headers["Content-Type"].orEmpty(),
                    contentLength = verifiedContentLength,
                    openStream = { opener.open() },
                ),
                expectedAuthority = expectedAuthority,
                onUploadProgress = onUploadProgress,
            )
        } else {
            executePresignedPut(
                priority = priority,
                upload = upload,
                bodyBytes = resolved.bytes,
                bodyFile = null,
                expectedAuthority = expectedAuthority,
                onUploadProgress = onUploadProgress,
            )
        }
    }

    override suspend fun prepareAttachmentUploadFileBound(
        file: PlatformFile,
        expectedAuthority: RaftExpectedAuthBinding,
    ): RaftPlatformFilePreparationResult {
        if (!expectedAuthority.isCurrentForPresignedPut()) {
            return RaftPlatformFilePreparationResult.Rejected(
                code = "auth_authority_changed",
                retryable = true,
            )
        }
        val ref = file.fileRef?.trim()?.takeIf { it.isNotBlank() }
        if (ref == null) {
            if (file.bytes.isEmpty()) {
                return RaftPlatformFilePreparationResult.Rejected(
                    code = "attachment_size_unavailable",
                    retryable = false,
                )
            }
            return RaftPlatformFilePreparationResult.Ready(file.bytes.size.toLong())
        }
        if (file.bytes.isNotEmpty()) {
            return RaftPlatformFilePreparationResult.Rejected(
                code = "attachment_file_input_conflict",
                retryable = false,
            )
        }
        val resolver = platformFileResolver
            ?: return RaftPlatformFilePreparationResult.Rejected(
                code = "file_ref_upload_requires_app_scope_file_resolver",
                retryable = false,
            )
        val stat = try {
            resolver.statFile(ref)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Cancellation is a caller-owned lifecycle signal, not a local stat failure.  Do
            // not turn it into a retryable admission error: the coordinator must propagate it
            // so its NonCancellable compensation path can run for any already-created sibling.
            throw cancellation
        } catch (_: Throwable) {
            return RaftPlatformFilePreparationResult.Rejected(
                code = "attachment_size_unavailable",
                retryable = true,
            )
        }
        if (!expectedAuthority.isCurrentForPresignedPut()) {
            return RaftPlatformFilePreparationResult.Rejected(
                code = "auth_authority_changed",
                retryable = true,
            )
        }
        val size = stat.sizeBytes?.takeIf { stat.code in 200..299 && it > 0L }
            ?: return RaftPlatformFilePreparationResult.Rejected(
                // Platform resolver codes/messages may contain implementation details or private
                // file paths. Direct-session admission exposes one canonical local error only.
                code = "attachment_size_unavailable",
                retryable = stat.code == 0 || stat.code in 500..599,
            )
        return RaftPlatformFilePreparationResult.Ready(size)
    }

    private suspend fun uploadAttachmentsInternal(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>,
        expectedAuthority: RaftExpectedAuthBinding?,
        onProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?
    ): HomeNetworkResponse {
        val resolvedFiles = files.preparePlatformFileStreams().getOrElse { return it }
        return uploadMultipart(
            priority = priority,
            path = "attachments/upload",
            category = "attachments_upload",
            diagnosticPath = "attachments/upload",
            fields = mapOf("channelId" to channelId.value),
            files = resolvedFiles,
            requestBytes = resolvedFiles.uploadByteEstimate(),
            key = "attachments/upload",
            expectedAuthority = expectedAuthority,
            onUploadProgress = onProgress
        )
    }

    /** Execute a presigned object PUT without exposing the bearer URL to diagnostics. */
    private suspend fun executePresignedPut(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        bodyBytes: ByteArray?,
        bodyFile: RaftHttpFileBody?,
        expectedAuthority: RaftExpectedAuthBinding,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
    ): HomeNetworkResponse {
        // A presigned URL is intentionally sent without Authorization, but it remains part of
        // the same captured route operation.  Fence both sides of the I/O against session,
        // account, server, and environment changes so a URL issued for A cannot be used after a
        // switch to B (or a runtime environment change).
        if (!expectedAuthority.isCurrentForPresignedPut()) {
            return routeEnvironmentChangedResponse()
        }
        // A presigned PUT has exactly one admitted body source. An empty in-memory body (or a
        // file body without a positive resolver-derived length) is never a valid attachment and
        // must fail before scheduler admission or transport can issue an empty object.
        if (bodyFile == null && (bodyBytes == null || bodyBytes.isEmpty())) {
            return invalidPlatformFileLengthResponse()
        }
        if (bodyFile != null && bodyFile.contentLength <= 0L) {
            return invalidPlatformFileLengthResponse()
        }
        if (upload.method != "PUT") {
            return HomeNetworkResponse(
                code = 400,
                bodyText = null,
                errorBodyText = "{\"error\":\"attachment_upload_method_invalid\"}",
            )
        }
        val contractError = runCatching {
            requireValidAttachmentUploadPresignedUrl(upload.url)
            requireValidAttachmentUploadPutHeaders(upload.headers)
        }.exceptionOrNull()
        if (contractError != null) {
            return HomeNetworkResponse(
                code = 400,
                bodyText = null,
                errorBodyText = "{\"error\":\"attachment_upload_contract_invalid\"}",
            )
        }
        val key = "attachments/upload-sessions/presigned-put"
        return try {
            val schedulerTimeoutMillis =
                if (bodyFile != null) {
                    RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS + RAFT_UPLOAD_SCHEDULER_DISPATCH_MARGIN_MILLIS
                } else {
                    RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS + RAFT_UPLOAD_SCHEDULER_DISPATCH_MARGIN_MILLIS
                }
            requestScheduler.run(
                priority = priority.toNetworkPriority(),
                key = key,
                reason = "PUT",
                timeoutMillis = schedulerTimeoutMillis,
            ) {
                if (!expectedAuthority.isCurrentForPresignedPut()) {
                    return@run routeEnvironmentChangedResponse()
                }
                var openedFileStream: RaftByteStream? = null
                val transportBody =
                    bodyFile?.copy(
                        openStream = {
                            bodyFile.openStream().also { openedFileStream = it }
                        }
                    )
                val response =
                    try {
                        if (transportBody != null) {
                            executor.executeFile(
                                priority = priority.toNetworkPriority(),
                                url = upload.url,
                                method = upload.method,
                                headers = upload.headers,
                                body = transportBody,
                                onUploadProgress = onUploadProgress,
                            )
                        } else {
                            executor.executeBytes(
                                priority = priority.toNetworkPriority(),
                                url = upload.url,
                                method = upload.method,
                                headers = upload.headers,
                                bodyBytes = bodyBytes!!,
                                onUploadProgress = onUploadProgress,
                            )
                        }
                    } catch (failure: Throwable) {
                        // The bottom executor owns normal stream consumption. If it opens the
                        // stream and then fails during request setup, reclaim that source here;
                        // RaftByteStream.cancel is idempotent with bounded stream cleanup.
                        openedFileStream?.cancel()
                        throw failure
                    }
                // Deliberately log only the stable category; the signed URL is never emitted.
                // The presigned URL is a bearer capability.  Redact URL-bearing transport error
                // text before it can reach repository error mapping or a user-facing message.
                val homeResponse = response.toHomeNetworkResponse(redactSensitive = true)
                warningLogger(
                    "endpoint category=attachment_presigned_put method=PUT " +
                        "bodyKind=${if (bodyFile == null) "bytes" else "file"} " +
                        "requestBytes=${bodyFile?.contentLength ?: bodyBytes?.size ?: 0} " +
                        "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                        "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                        "code=${response.code} result=${homeResponse.networkDiagnosticsResult()}" +
                        homeResponse.requestIdLogSuffix() + homeResponse.transportDiagnosticsLogSuffix()
                )
                if (expectedAuthority.isCurrentForPresignedPut()) {
                    homeResponse
                } else {
                    routeEnvironmentChangedResponse()
                }
            }
        } catch (timeout: RaftRequestTimeoutException) {
            timeout.toTimeoutNetworkResponse(
                category = "attachment_presigned_put",
                path = "attachments/upload-sessions/{uploadId}/put",
                method = "PUT",
                requestBytes = bodyFile?.contentLength ?: bodyBytes?.size?.toLong() ?: 0L,
                durationMs = 0L,
                safeMessage = "attachment_presigned_upload_timeout",
                warningLogger = warningLogger,
            )
        }
    }

    private suspend fun RaftExpectedAuthBinding.isCurrentForPresignedPut(): Boolean {
        val expected = this
        val environment = expected.environmentId.normalizedEnvironmentId()
        if (environment.isBlank() || environment != currentBaseUrl().normalizedEnvironmentId()) {
            return false
        }
        val session = sessionStore.readSession() ?: return false
        return session.matchesExpectedAuthority(
            expected = expected,
            effectiveServerId = expected.serverId,
            effectiveEnvironmentId = environment,
        ) && session.servers.any { server -> server.id.trim() == expected.serverId.trim() }
    }

    override suspend fun uploadAccountAvatar(
        priority: DataRequestPriority,
        file: MultipartFile
    ): HomeNetworkResponse {
        val resolvedFile = listOf(file).resolvePlatformFileRefs().getOrElse { return it }.single()
        return uploadMultipart(
            priority = priority,
            path = "auth/me/avatar",
            category = "account_avatar_upload",
            diagnosticPath = "auth/me/avatar",
            fields = emptyMap(),
            files = listOf(resolvedFile),
            requestBytes = resolvedFile.bytes.size.toLong(),
            key = "auth/me/avatar"
        )
    }

    override suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        agentId: MemberId,
        file: MultipartFile
    ): HomeNetworkResponse {
        val resolvedFile = listOf(file).resolvePlatformFileRefs().getOrElse { return it }.single()
        val path = "agents/${agentId.value}/avatar"
        return uploadMultipart(
            priority = priority,
            path = path,
            category = "agent_avatar_upload",
            diagnosticPath = path,
            fields = emptyMap(),
            files = listOf(resolvedFile),
            requestBytes = resolvedFile.bytes.size.toLong(),
            key = path
        )
    }

    override suspend fun uploadConnectedAppLogo(
        priority: DataRequestPriority,
        clientRecordId: String,
        file: MultipartFile
    ): HomeNetworkResponse {
        val resolvedFile = listOf(file).resolvePlatformFileRefs().getOrElse { return it }.single()
        val path = "integrations/clients/$clientRecordId/logo"
        return uploadMultipart(
            priority = priority,
            path = path,
            category = "connected_app_logo_upload",
            diagnosticPath = "integrations/clients/:id/logo",
            fields = emptyMap(),
            files = listOf(resolvedFile),
            requestBytes = resolvedFile.bytes.size.toLong(),
            key = path
        )
    }

    private suspend fun List<MultipartFile>.preparePlatformFileStreams(): ResolvedPlatformFiles {
        // A file reference and an in-memory body are two competing sources for one upload.  Do
        // not let the presence of bytes make the resolver predicate false and silently choose the
        // byte array: direct-session create admitted the reference's identity/size, while the PUT
        // would otherwise send unrelated bytes.  Reject the tuple before any stat, stream open,
        // scheduler, or transport work.
        if (any { it.fileRef?.trim()?.isNotBlank() == true && it.bytes.isNotEmpty() }) {
            return ResolvedPlatformFiles.Error(
                HomeNetworkResponse(
                    code = 400,
                    bodyText = null,
                    errorBodyText = "{\"error\":\"attachment_file_input_conflict\"}",
                )
            )
        }
        if (!requiresPlatformFileResolver()) return ResolvedPlatformFiles.Content(this)
        val resolver = platformFileResolver
            ?: return ResolvedPlatformFiles.Error(fileRefUnsupportedResponse())
        val resolved = mutableListOf<MultipartFile>()
        forEach { file ->
            val fileRef = file.fileRef
            if (fileRef == null || file.bytes.isNotEmpty()) {
                resolved += file
                return@forEach
            }
            val stat =
                try {
                    resolver.statFile(fileRef)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Resolver failures are local admission failures.  Do not expose exception
                    // text (which may contain a private path), open the source, or execute the
                    // request after a stat failure.
                    return ResolvedPlatformFiles.Error(invalidPlatformFileLengthResponse())
                }
            if (stat.code !in 200..299) {
                // Resolver diagnostics may contain private paths or platform-specific codes.
                // Direct-session admission has one stable local failure contract; do not let
                // those details cross the network/data boundary.
                return ResolvedPlatformFiles.Error(invalidPlatformFileLengthResponse())
            }
            val actualSizeBytes = stat.sizeBytes?.takeIf { it > 0L }
                ?: return ResolvedPlatformFiles.Error(
                    invalidPlatformFileLengthResponse()
                )
            resolved +=
                file.copy(
                    sizeBytes = actualSizeBytes,
                    streamOpener =
                        MultipartFileStreamOpener {
                            boundedPlatformFileNetworkStream(
                                expectedSizeBytes = actualSizeBytes,
                                source =
                                    resolver.openFileStream(
                                        fileRef = fileRef,
                                        expectedSizeBytes = actualSizeBytes
                                    )
                            )
                        }
                )
        }
        return ResolvedPlatformFiles.Content(resolved)
    }

    private suspend fun List<MultipartFile>.resolvePlatformFileRefs(): ResolvedPlatformFiles {
        if (!requiresPlatformFileResolver()) return ResolvedPlatformFiles.Content(this)
        val resolver = platformFileResolver
            ?: return ResolvedPlatformFiles.Error(fileRefUnsupportedResponse())
        val resolved = mutableListOf<MultipartFile>()
        forEach { file ->
            val fileRef = file.fileRef
            if (fileRef == null || file.bytes.isNotEmpty()) {
                resolved += file
                return@forEach
            }
            val response = resolver.readFile(fileRef)
            if (!response.success) {
                return ResolvedPlatformFiles.Error(
                    HomeNetworkResponse(
                        code = 400,
                        bodyText = null,
                        errorBodyText =
                            platformFileReadErrorBody(
                                errorCode = response.errorCode,
                                message = response.message
                            )
                    )
                )
            }
            resolved +=
                file.copy(
                    bytes = response.bytes,
                    fileRef = null,
                    sizeBytes = file.sizeBytes ?: response.sizeBytes ?: response.bytes.size.toLong()
                )
        }
        return ResolvedPlatformFiles.Content(resolved)
    }

    private suspend fun uploadMultipart(
        priority: DataRequestPriority,
        path: String,
        category: String,
        diagnosticPath: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        requestBytes: Long,
        key: String,
        expectedAuthority: RaftExpectedAuthBinding? = null,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null
    ): HomeNetworkResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val requestBaseUrl = expectedAuthority?.environmentId?.normalizedEnvironmentId() ?: currentBaseUrl()
        if (!expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
            return routeEnvironmentChangedResponse()
        }
        // task #788: same files instance and same predicate the executor uses.
        // The transport's per-attempt 180s budget is the single authoritative
        // deadline; the scheduler carries only the strictly-higher derived
        // backstop covering the full legal auth-retry chain.
        val schedulerTimeoutMillis =
            if (files.hasStreamingFileRefParts()) RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS else null
        return try {
            requestScheduler.run(
                priority = priority.toNetworkPriority(),
                key = key,
                reason = "multipart",
                timeoutMillis = schedulerTimeoutMillis
            ) scheduled@{
                if (!expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                    return@scheduled routeEnvironmentChangedResponse()
                }
                val transportResponse =
                    authMiddleware(requestBaseUrl).request(
                        serverIdOverride = expectedAuthority?.serverId?.let(::ServerId),
                        expectedAuthority = expectedAuthority,
                        caller = "shared_network:$diagnosticPath"
                    ) authenticated@{ auth ->
                        if (!expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                            return@authenticated routeEnvironmentChangedResponse()
                        }
                        val response =
                            executor.executeMultipart(
                                priority = priority.toNetworkPriority(),
                                url = absoluteUrl(requestBaseUrl, path),
                                fields = fields,
                                files = files,
                                headers =
                                    mapOf(
                                        HEADER_AUTHORIZATION to auth.authorization,
                                        HEADER_SERVER_ID to auth.serverId
                                    ),
                                onUploadProgress = onUploadProgress
                            ).toHomeNetworkResponse()
                        if (expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                            response
                        } else {
                            routeEnvironmentChangedResponse()
                        }
                    }
                val response =
                    if (expectedAuthority.matchesCurrentEnvironment(currentBaseUrl())) {
                        transportResponse
                    } else {
                        routeEnvironmentChangedResponse()
                    }
                warningLogger(
                    "endpoint category=$category path=$diagnosticPath method=POST " +
                        "fieldKeys=${fields.networkDiagnosticsFieldKeys()} " +
                        "fileCount=${files.size} fileFields=${files.networkDiagnosticsFileFields()} " +
                        "fileNameMeta=${files.networkDiagnosticsFileNameMeta()} requestBytes=$requestBytes " +
                        "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                        "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                        "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                        "result=${response.networkDiagnosticsResult()}" +
                        response.errorBodyLogSuffix() +
                        response.requestIdLogSuffix() +
                        response.transportDiagnosticsLogSuffix()
                )
                response
            }
        } catch (timeout: RaftRequestTimeoutException) {
            timeout.toTimeoutNetworkResponse(
                category = category,
                path = diagnosticPath,
                method = "POST",
                requestBytes = requestBytes,
                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                warningLogger = warningLogger
            )
        }
    }

    private fun currentBaseUrl(): String =
        baseUrlProvider().ifBlank { baseUrl }

    private fun absoluteUrl(path: String, query: List<Pair<String, String>> = emptyList()): String =
        absoluteUrl(currentBaseUrl(), path, query)

    private fun absoluteUrl(
        requestBaseUrl: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        val url = "${requestBaseUrl.trimEnd('/')}/${path.trimStart('/')}"
        if (query.isEmpty()) return url
        return "$url?${query.joinToString("&") { (key, value) -> "${key.percentEncode()}=${value.percentEncode()}" }}"
    }
}

private fun RaftExpectedAuthBinding?.matchesCurrentEnvironment(currentBaseUrl: String): Boolean {
    val expected = this?.environmentId?.normalizedEnvironmentId() ?: return true
    return expected.isNotBlank() && expected == currentBaseUrl.normalizedEnvironmentId()
}

private fun routeEnvironmentChangedResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 409,
        bodyText = null,
        errorBodyText = """{"error":"auth_authority_changed"}""",
    )

class SharedRaftAuthTransport internal constructor(
    private val baseUrlProvider: () -> String,
    private val executor: RaftHttpExecutor,
    private val warningLogger: (String) -> Unit = {},
    private val requestScheduler: RaftRequestScheduler = RaftRequestScheduler()
) : RaftAuthTransport {
    internal constructor(
        baseUrlProvider: () -> String,
        warningLogger: (String) -> Unit = {}
    ) : this(
        baseUrlProvider = baseUrlProvider,
        executor = createPlatformRaftHttpExecutor(warningLogger),
        warningLogger = warningLogger
    )

    internal constructor(
        baseUrl: String,
        executor: RaftHttpExecutor,
        warningLogger: (String) -> Unit = {},
        requestScheduler: RaftRequestScheduler = RaftRequestScheduler()
    ) : this(
        baseUrlProvider = { baseUrl },
        executor = executor,
        warningLogger = warningLogger,
        requestScheduler = requestScheduler
    )

    constructor(
        baseUrl: String = RAFT_API_BASE_URL,
        warningLogger: (String) -> Unit = {}
    ) : this(
        baseUrl = baseUrl,
        executor = createPlatformRaftHttpExecutor(warningLogger),
        warningLogger = warningLogger
    )

    override suspend fun serverVersion(): AuthNetworkResponse =
        request(path = "version", method = "GET")

    override suspend fun login(bodyText: String): AuthNetworkResponse =
        request(path = "auth/login", method = "POST", bodyText = bodyText)

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse =
        request(path = "auth/${provider.trim().lowercase()}/complete", method = "POST", bodyText = bodyText)

    override suspend fun socialAuthProviders(platform: String): AuthNetworkResponse {
        val normalizedPlatform = platform.trim().lowercase().let { if (it == "mobile") "mobile" else "web" }
        return request(path = "auth/providers?platform=$normalizedPlatform", method = "GET")
    }

    override suspend fun startAppLogin(bodyText: String): AuthNetworkResponse =
        request(path = "auth/app-login/start", method = "POST", bodyText = bodyText)

    override suspend fun completeAppLogin(bodyText: String): AuthNetworkResponse =
        request(path = "auth/app-login/complete", method = "POST", bodyText = bodyText)

    override suspend fun startNativeAppleMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse =
        request(
            path = "auth/mobile/oauth/apple/native/start",
            method = "POST",
            authorization = authorization,
            bodyText = bodyText
        )

    override suspend fun authorizeNativeAppleMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse =
        request(
            path = "auth/mobile/oauth/apple/native/authorize",
            method = "POST",
            authorization = authorization,
            bodyText = bodyText
        )

    override suspend fun startMobileOAuthLink(
        provider: String,
        bodyText: String,
        authorization: String
    ): AuthNetworkResponse =
        request(
            path = "auth/mobile/oauth/${provider.trim().lowercase()}/link/start",
            method = "POST",
            authorization = authorization,
            bodyText = bodyText
        )

    override suspend fun completeMobileOAuthLink(
        provider: String,
        bodyText: String,
        authorization: String
    ): AuthNetworkResponse =
        request(
            path = "auth/mobile/oauth/${provider.trim().lowercase()}/link/complete",
            method = "POST",
            authorization = authorization,
            bodyText = bodyText
        )

    override suspend fun resendVerification(authorization: String): AuthNetworkResponse =
        request(path = "auth/resend-verification", method = "POST", authorization = authorization)

    override suspend fun verifyEmail(bodyText: String): AuthNetworkResponse =
        request(path = "auth/verify-email", method = "POST", bodyText = bodyText)

    override suspend fun currentUser(authorization: String): AuthNetworkResponse =
        request(path = "auth/me", method = "GET", authorization = authorization)

    override suspend fun completeProfile(authorization: String, bodyText: String): AuthNetworkResponse =
        request(path = "auth/me/complete-profile", method = "POST", authorization = authorization, bodyText = bodyText)

    override suspend fun refresh(bodyText: String): AuthNetworkResponse =
        request(path = "auth/refresh", method = "POST", bodyText = bodyText)

    override suspend fun refreshWithAttempt(
        bodyText: String,
        attemptId: String,
        installationId: String,
    ): AuthNetworkResponse =
        request(
            path = "auth/refresh",
            method = "POST",
            bodyText = bodyText,
            extraHeaders =
                mapOf(
                    RAFT_AUTH_REFRESH_ATTEMPT_HEADER to attemptId,
                    RAFT_AUTH_INSTALLATION_HEADER to installationId,
                ),
        )

    override suspend fun logout(bodyText: String): AuthNetworkResponse =
        request(path = "auth/logout", method = "POST", bodyText = bodyText)

    override suspend fun servers(authorization: String): AuthNetworkResponse =
        request(path = "servers", method = "GET", authorization = authorization)

    override suspend fun serverOrder(authorization: String): AuthNetworkResponse =
        request(path = "servers/order", method = "GET", authorization = authorization)

    override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse =
        request(path = "servers", method = "POST", authorization = authorization, bodyText = bodyText)

    override suspend fun consumeQaBootstrap(bodyText: String): AuthNetworkResponse =
        request(path = "qa/session-bootstrap/consume", method = "POST", bodyText = bodyText)

    override suspend fun readyQaBootstrap(
        ticketDigest: String,
        authorization: String,
        bodyText: String
    ): AuthNetworkResponse =
        request(
            path = "qa/session-bootstrap/tickets/${ticketDigest.removePrefix("sha256:")}/ready",
            method = "POST",
            authorization = authorization,
            bodyText = bodyText
        )

    override suspend fun statusQaBootstrap(ticketDigest: String, authorization: String): AuthNetworkResponse =
        request(
            path = "qa/session-bootstrap/tickets/${ticketDigest.removePrefix("sha256:")}/status",
            method = "GET",
            authorization = authorization
        )

    private suspend fun request(
        path: String,
        method: String,
        authorization: String? = null,
        bodyText: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): AuthNetworkResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val normalizedMethod = method.uppercase()
        val requestBytes = (bodyText?.encodeToByteArray()?.size ?: 0).toLong()
        warningLogger(
            "slock_auth_transport_start path=$path method=$normalizedMethod " +
                "authorizationPresent=${authorization != null} requestBytes=$requestBytes"
        )
        val response =
            try {
                requestScheduler.run(
                    priority = RaftRequestPriority.Foreground,
                    key = path,
                    reason = normalizedMethod
                ) {
                    executor.execute(
                        priority = RaftRequestPriority.Foreground,
                        url = "${baseUrlProvider().trimEnd('/')}/${path.trimStart('/')}",
                        method = normalizedMethod,
                        headers =
                            buildMap {
                                authorization?.let { put(HEADER_AUTHORIZATION, it) }
                                putAll(extraHeaders)
                            },
                        bodyText = bodyText,
                        jsonBody = bodyText != null
                    )
                }
            } catch (timeout: RaftRequestTimeoutException) {
                return timeout.toTimeoutAuthNetworkResponse(
                    path = path,
                    method = normalizedMethod,
                    requestBytes = requestBytes,
                    durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                    warningLogger = warningLogger
                )
            }
        warningLogger(
            "slock_auth_transport_finish path=$path method=$normalizedMethod " +
                "authorizationPresent=${authorization != null} requestBytes=$requestBytes " +
                "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                (response.transportDiagnostics ?: "engine=unknown status=none kind=none rawCode=none detail=none") +
                response.requestIdLogSuffix()
        )
        if (response.code == 0) {
            warningLogger(
                "slock_auth_transport_failed path=$path method=$normalizedMethod " +
                    "requestBytes=$requestBytes " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    (response.transportDiagnostics ?: "engine=unknown status=none kind=none rawCode=none detail=none") +
                    response.requestIdLogSuffix()
            )
        }
        return response.toAuthNetworkResponse()
    }
}

internal const val RAFT_AUTH_REFRESH_ATTEMPT_HEADER = "X-Slock-Auth-Refresh-Attempt-Id"
internal const val RAFT_AUTH_INSTALLATION_HEADER = "X-Slock-Auth-Installation-Id"

private fun RaftRequestTimeoutException.toTimeoutAuthNetworkResponse(
    path: String,
    method: String,
    requestBytes: Long,
    durationMs: Long,
    warningLogger: (String) -> Unit
): AuthNetworkResponse {
    val errorBody =
        """{"error":"network_timeout","message":"${message.orEmpty().jsonString()}"}"""
    warningLogger(
        "slock_auth_transport_failed path=$path method=${method.uppercase()} " +
            "requestBytes=$requestBytes responseBytes=0 " +
            "errorBytes=${errorBody.encodeToByteArray().size} " +
            "durationMs=$durationMs engine=networkkmm status=none kind=timeout rawCode=FORCE_TIMEOUT detail=request_timeout"
    )
    return AuthNetworkResponse(code = 0, bodyText = null, errorBodyText = errorBody)
}

private fun RaftHttpExecutorResponse.toHomeNetworkResponse(
    redactSensitive: Boolean = false
): HomeNetworkResponse =
    HomeNetworkResponse(
        code = code,
        bodyText = if (redactSensitive) null else bodyText,
        errorBodyText =
            if (redactSensitive) {
                when {
                    code in 200..299 -> null
                    code == 0 -> "attachment_presigned_upload_transport_failed"
                    else -> "attachment_presigned_upload_http_$code"
                }
            } else {
                errorBodyText
            },
        transportDiagnostics = transportDiagnostics?.let { text -> if (redactSensitive) text.redactPresignedUrlText() else text },
        responseHeaders = if (redactSensitive) emptyMap() else responseHeaders
    )

private fun RaftRequestTimeoutException.toTimeoutNetworkResponse(
    category: String,
    path: String,
    method: String,
    requestBytes: Long,
    durationMs: Long,
    safeMessage: String = message.orEmpty(),
    warningLogger: (String) -> Unit
): HomeNetworkResponse {
    val errorBody =
        """{"error":"network_timeout","message":"${safeMessage.jsonString()}"}"""
    warningLogger(
        "endpoint category=$category path=$path method=$method " +
            "requestBytes=$requestBytes responseBytes=0 " +
            "errorBytes=${errorBody.encodeToByteArray().size} " +
            "code=0 durationMs=$durationMs result=network_error"
    )
    return HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = errorBody)
}

private fun RaftHttpExecutorResponse.toAuthNetworkResponse(): AuthNetworkResponse =
    AuthNetworkResponse(
        code = code,
        bodyText = bodyText?.takeUnless { it.isBlank() },
        errorBodyText = errorBodyText?.takeUnless { it.isBlank() }
    )

private fun DataRequestPriority.toNetworkPriority(): RaftRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> RaftRequestPriority.Foreground
        DataRequestPriority.Visible -> RaftRequestPriority.Visible
        DataRequestPriority.Background -> RaftRequestPriority.Background
    }

private fun List<MultipartFile>.requiresPlatformFileResolver(): Boolean =
    any { it.requiresPlatformFileResolver() }

private fun MultipartFile.requiresPlatformFileResolver(): Boolean =
    fileRef != null && bytes.isEmpty() && streamOpener == null

private fun fileRefUnsupportedResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 501,
        bodyText = null,
        errorBodyText = """{"error":"file_ref_upload_requires_app_scope_file_resolver"}"""
    )

private fun invalidPlatformFileLengthResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 400,
        bodyText = null,
        errorBodyText = """{"error":"attachment_size_unavailable"}"""
    )

private fun invalidAttachmentUploadSessionIdResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 400,
        bodyText = null,
        errorBodyText = """{"error":"attachment_upload_session_invalid_request"}"""
    )

private sealed interface ResolvedPlatformFiles {
    data class Content(val files: List<MultipartFile>) : ResolvedPlatformFiles
    data class Error(val response: HomeNetworkResponse) : ResolvedPlatformFiles
}

private inline fun ResolvedPlatformFiles.getOrElse(onError: (HomeNetworkResponse) -> Nothing): List<MultipartFile> =
    when (this) {
        is ResolvedPlatformFiles.Content -> files
        is ResolvedPlatformFiles.Error -> onError(response)
    }

private fun platformFileReadErrorBody(errorCode: String, message: String): String =
    """{"error":"${errorCode.ifBlank { "attachment_read_failed" }.jsonString()}","message":"${message.jsonString()}"}"""

private fun List<MultipartFile>.uploadByteEstimate(): Long =
    sumOf { file -> file.bytes.size.toLong().takeIf { it > 0L } ?: file.sizeBytes ?: 0L }

private fun String.jsonString(): String =
    buildString {
        this@jsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

private fun String.networkDiagnosticsCategory(): String {
    val normalized = trim().trimStart('/')
    return when {
        normalized.startsWith("channels/dm") -> "direct_messages"
        normalized.startsWith("channels/threads/followed") -> "followed_threads"
        normalized.startsWith("channels/threads/follow") -> "thread_follow"
        normalized.startsWith("channels/threads") -> "thread_actions"
        normalized.startsWith("channels/unread") -> "channel_unread_counts"
        normalized.startsWith("channels/inbox/read-all") -> "inbox_read_all"
        normalized.startsWith("channels/") && normalized.endsWith("/read-all") -> "channel_read_all"
        normalized.startsWith("channels/") && normalized.endsWith("/unread") -> "channel_unread"
        normalized.startsWith("channels/") && normalized.endsWith("/threads") -> "channel_threads"
        normalized.startsWith("channels/") && normalized.endsWith("/files") -> "channel_files"
        normalized.startsWith("messages/channel/") -> "messages_channel"
        normalized.startsWith("messages/context/") -> "messages_context"
        normalized.startsWith("messages/sync") -> "messages_sync"
        normalized.startsWith("channels") -> "channels"
        normalized.startsWith("servers/unread-summary") -> "server_unread_summary"
        normalized.startsWith("servers") -> "servers"
        normalized.startsWith("members") -> "members"
        normalized.startsWith("messages/search") -> "message_search"
        normalized.startsWith("messages") -> "messages"
        normalized.startsWith("saved") -> "saved_items"
        normalized.startsWith("attachments/upload") -> "attachments_upload"
        normalized.startsWith("tasks") -> "tasks"
        normalized.startsWith("settings") -> "settings"
        else -> normalized.substringBefore('/').ifBlank { "unknown" }
    }
}

private fun String.networkDiagnosticsPath(): String =
    trim().trimStart('/').ifBlank { "root" }

private fun List<Pair<String, String>>.networkDiagnosticsQueryKeys(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(",") { it.first }
    }

private fun List<Pair<String, String>>.networkDiagnosticsQueryMeta(): String {
    val query = firstOrNull { it.first == "q" }?.second ?: return "none"
    val trimmed = query.trim()
    val wordCount =
        trimmed
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
    val flags =
        buildList {
            if (query.any { it.isWhitespace() }) add("space")
            if ('+' in query) add("plus")
            if ('#' in query) add("hash")
            if (query.any { it.code > 127 }) add("nonAscii")
        }
            .ifEmpty { listOf("plain") }
            .joinToString("|")
    return "qLen=${trimmed.length};qWords=$wordCount;qFlags=$flags"
}

private fun HomeNetworkResponse.networkDiagnosticsResult(): String =
    when {
        code in 200..299 -> "success"
        code == 0 -> "network_error"
        code == 401 || code == 403 -> "auth_or_permission_error"
        else -> "http_error"
    }

private fun HomeNetworkResponse.transportDiagnosticsLogSuffix(): String {
    val diagnostics = transportDiagnostics?.takeIf { it.isNotBlank() } ?: return ""
    return " $diagnostics"
}

private fun HomeNetworkResponse.requestIdLogSuffix(): String =
    if (isSuccessful) {
        ""
    } else {
        responseHeaders.requestIdLogSuffix()
    }

private fun RaftHttpExecutorResponse.requestIdLogSuffix(): String =
    if (isSuccessful) {
        ""
    } else {
        responseHeaders.requestIdLogSuffix()
    }

private fun Map<String, List<String>>.requestIdLogSuffix(): String {
    val requestId =
        entries.firstOrNull { (name, _) -> name.equals("x-request-id", ignoreCase = true) }
            ?.value
            ?.firstOrNull { it.isNotBlank() }
            ?.toSafeDiagnosticsToken()
            ?: return ""
    return " requestId=$requestId"
}

private fun HomeNetworkResponse.errorBodyLogSuffix(): String {
    if (isSuccessful) return ""
    val error = errorBodyText?.takeIf { it.isNotBlank() } ?: return ""
    return " errorBody=${error.toSafeDiagnosticsToken()}"
}

private fun Map<String, String>.networkDiagnosticsFieldKeys(): String =
    keys
        .sorted()
        .joinToString("|")
        .ifBlank { "none" }
        .toSafeDiagnosticsToken()

private fun List<MultipartFile>.networkDiagnosticsFileFields(): String =
    map { it.name.ifBlank { "blank" } }
        .distinct()
        .sorted()
        .joinToString("|")
        .ifBlank { "none" }
        .toSafeDiagnosticsToken()

private fun List<MultipartFile>.networkDiagnosticsFileNameMeta(): String {
    if (isEmpty()) return "none"
    val extensions =
        map { file ->
            file.fileName
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()
                .ifBlank { "none" }
        }
            .distinct()
            .sorted()
            .joinToString("|")
            .toSafeDiagnosticsToken()
    return "named=${count { it.fileName.isNotBlank() }};ext=$extensions"
}

private fun String.percentEncode(): String {
    val bytes = encodeToByteArray()
    val builder = StringBuilder(bytes.size)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (
            char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '.' ||
                char == '_' ||
                char == '~'
        ) {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return builder.toString()
}
