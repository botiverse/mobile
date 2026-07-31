package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_AUTHORIZATION
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.MultipartFileStreamOpener
import build.raft.app.network.SLOCK_UPLOAD_TOTAL_TIMEOUT_MILLIS
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.SlockRequestScheduler
import build.raft.app.network.hasStreamingFileRefParts
import build.raft.app.network.SlockRequestTimeoutException
import build.raft.app.network.createPlatformSlockHttpExecutor
import build.raft.app.network.toSafeDiagnosticsToken
import kotlin.time.TimeSource

internal const val SLOCK_UPLOAD_SCHEDULER_DISPATCH_MARGIN_MILLIS = 15_000L

/**
 * task #788: scheduler backstop for streaming fileRef uploads, checked-derived
 * from the authoritative budgets of the real call chain
 * `scheduler → authMiddleware(≤SLOCK_AUTH_MAX_REQUEST_ATTEMPTS upload attempts,
 * ≤AUTH_REFRESH_MAX_ATTEMPTS refresh waits) → transport(180s per attempt)`.
 * The NetworkKMM per-attempt 180s stays the single authoritative deadline
 * (product ruling: per-attempt semantics); this bound only fires if the
 * transport deadline machinery itself fails, so a Foreground semaphore permit
 * can never leak on an unbounded upload. 2×180s + 2×4s + 15s = 383s.
 */
internal const val SLOCK_UPLOAD_SCHEDULER_BACKSTOP_MILLIS =
    SLOCK_AUTH_MAX_REQUEST_ATTEMPTS * SLOCK_UPLOAD_TOTAL_TIMEOUT_MILLIS +
        AUTH_REFRESH_MAX_ATTEMPTS * AUTH_REFRESH_ATTEMPT_TIMEOUT_MILLIS +
        SLOCK_UPLOAD_SCHEDULER_DISPATCH_MARGIN_MILLIS

internal class SharedSlockNetworkClient(
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = SLOCK_API_BASE_URL,
    private val baseUrlProvider: () -> String = { baseUrl },
    private val warningLogger: (String) -> Unit = {},
    private val criticalWarningLogger: (String) -> Unit = warningLogger,
    private val terminalAuthLossReporter: suspend (StoredAuthSession, String) -> Unit = { _, _ -> },
    private val requestScheduler: SlockRequestScheduler = SlockRequestScheduler(),
    private val platformFileResolver: SlockPlatformFileResolver? = null,
    private val executor: SlockHttpExecutor = createPlatformSlockHttpExecutor(warningLogger)
) : SlockNetworkClient, AuthorityBoundSlockNetworkClient, ProgressiveAttachmentNetworkClient {
    private fun authMiddleware(): SlockNetworkAuthMiddleware =
        SlockNetworkAuthMiddleware(
            sessionStore = sessionStore,
            authApi = SlockAuthApiClient(SharedSlockAuthTransport(currentBaseUrl(), executor, warningLogger)),
            logger = warningLogger,
            warningLogger = warningLogger,
            criticalLogger = criticalWarningLogger,
            terminalAuthLossReporter = terminalAuthLossReporter
        )

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val category = path.networkDiagnosticsCategory()
        val normalizedPath = path.networkDiagnosticsPath()
        return try {
            requestScheduler.run(
                priority = priority.toNetworkPriority(),
                key = path,
                reason = method.uppercase()
            ) {
                val response =
                    authMiddleware().request(serverIdOverride, caller = "shared_network:$normalizedPath") { auth ->
                        executor.execute(
                            priority = priority.toNetworkPriority(),
                            url = absoluteUrl(path, query),
                            method = method.uppercase(),
                            headers =
                                buildMap {
                                    put(HEADER_AUTHORIZATION, auth.authorization)
                                    put(HEADER_SERVER_ID, auth.serverId)
                                },
                            bodyText = bodyText,
                            jsonBody = bodyText != null
                        ).toHomeNetworkResponse()
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
                response
            }
        } catch (timeout: SlockRequestTimeoutException) {
            timeout.toTimeoutNetworkResponse(
                category = category,
                path = normalizedPath,
                method = method.uppercase(),
                requestBytes = (bodyText?.encodeToByteArray()?.size ?: 0).toLong(),
                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                warningLogger = warningLogger
            )
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
        } catch (timeout: SlockRequestTimeoutException) {
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
        onProgress: (build.raft.app.network.SlockTransferProgress) -> Unit
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
        expectedAuthority: SlockExpectedAuthBinding
    ): HomeNetworkResponse =
        uploadAttachmentsInternal(priority, channelId, files, expectedAuthority, onProgress = null)

    private suspend fun uploadAttachmentsInternal(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>,
        expectedAuthority: SlockExpectedAuthBinding?,
        onProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)?
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
            val stat = resolver.statFile(fileRef)
            if (!stat.success) {
                return ResolvedPlatformFiles.Error(
                    HomeNetworkResponse(
                        code = 400,
                        bodyText = null,
                        errorBodyText =
                            platformFileReadErrorBody(
                                errorCode = stat.errorCode,
                                message = stat.message
                            )
                    )
                )
            }
            val actualSizeBytes = stat.sizeBytes
                ?: return ResolvedPlatformFiles.Error(
                    HomeNetworkResponse(
                        code = 400,
                        bodyText = null,
                        errorBodyText =
                            platformFileReadErrorBody(
                                errorCode = "attachment_size_unavailable",
                                message = "Attachment size unavailable"
                            )
                    )
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
        expectedAuthority: SlockExpectedAuthBinding? = null,
        onUploadProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)? = null
    ): HomeNetworkResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        // task #788: same files instance and same predicate the executor uses.
        // The transport's per-attempt 180s budget is the single authoritative
        // deadline; the scheduler carries only the strictly-higher derived
        // backstop covering the full legal auth-retry chain.
        val schedulerTimeoutMillis =
            if (files.hasStreamingFileRefParts()) SLOCK_UPLOAD_SCHEDULER_BACKSTOP_MILLIS else null
        return try {
            requestScheduler.run(
                priority = priority.toNetworkPriority(),
                key = key,
                reason = "multipart",
                timeoutMillis = schedulerTimeoutMillis
            ) {
                val response =
                    authMiddleware().request(
                        serverIdOverride = expectedAuthority?.serverId?.let(::ServerId),
                        expectedAuthority = expectedAuthority,
                        caller = "shared_network:$diagnosticPath"
                    ) { auth ->
                        executor.executeMultipart(
                            priority = priority.toNetworkPriority(),
                            url = absoluteUrl(path),
                            fields = fields,
                            files = files,
                            headers =
                                mapOf(
                                    HEADER_AUTHORIZATION to auth.authorization,
                                    HEADER_SERVER_ID to auth.serverId
                                ),
                            onUploadProgress = onUploadProgress
                        ).toHomeNetworkResponse()
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
        } catch (timeout: SlockRequestTimeoutException) {
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

    private fun absoluteUrl(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val url = "${currentBaseUrl().trimEnd('/')}/${path.trimStart('/')}"
        if (query.isEmpty()) return url
        return "$url?${query.joinToString("&") { (key, value) -> "${key.percentEncode()}=${value.percentEncode()}" }}"
    }
}

class SharedSlockAuthTransport internal constructor(
    private val baseUrlProvider: () -> String,
    private val executor: SlockHttpExecutor,
    private val warningLogger: (String) -> Unit = {},
    private val requestScheduler: SlockRequestScheduler = SlockRequestScheduler()
) : SlockAuthTransport {
    internal constructor(
        baseUrlProvider: () -> String,
        warningLogger: (String) -> Unit = {}
    ) : this(
        baseUrlProvider = baseUrlProvider,
        executor = createPlatformSlockHttpExecutor(warningLogger),
        warningLogger = warningLogger
    )

    internal constructor(
        baseUrl: String,
        executor: SlockHttpExecutor,
        warningLogger: (String) -> Unit = {},
        requestScheduler: SlockRequestScheduler = SlockRequestScheduler()
    ) : this(
        baseUrlProvider = { baseUrl },
        executor = executor,
        warningLogger = warningLogger,
        requestScheduler = requestScheduler
    )

    constructor(
        baseUrl: String = SLOCK_API_BASE_URL,
        warningLogger: (String) -> Unit = {}
    ) : this(
        baseUrl = baseUrl,
        executor = createPlatformSlockHttpExecutor(warningLogger),
        warningLogger = warningLogger
    )

    override suspend fun login(bodyText: String): AuthNetworkResponse =
        request(path = "auth/login", method = "POST", bodyText = bodyText)

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse =
        request(path = "auth/${provider.trim().lowercase()}/complete", method = "POST", bodyText = bodyText)

    override suspend fun startMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse =
        request(path = "auth/mobile/oauth/start", method = "POST", authorization = authorization, bodyText = bodyText)

    override suspend fun completeMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse =
        request(path = "auth/mobile/oauth/complete", method = "POST", authorization = authorization, bodyText = bodyText)

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

    override suspend fun register(bodyText: String): AuthNetworkResponse =
        request(path = "auth/register", method = "POST", bodyText = bodyText)

    override suspend fun forgotPassword(bodyText: String): AuthNetworkResponse =
        request(path = "auth/forgot-password", method = "POST", bodyText = bodyText)

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
                    SLOCK_AUTH_REFRESH_ATTEMPT_HEADER to attemptId,
                    SLOCK_AUTH_INSTALLATION_HEADER to installationId,
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
                    priority = SlockRequestPriority.Foreground,
                    key = path,
                    reason = normalizedMethod
                ) {
                    executor.execute(
                        priority = SlockRequestPriority.Foreground,
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
            } catch (timeout: SlockRequestTimeoutException) {
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

internal const val SLOCK_AUTH_REFRESH_ATTEMPT_HEADER = "X-Slock-Auth-Refresh-Attempt-Id"
internal const val SLOCK_AUTH_INSTALLATION_HEADER = "X-Slock-Auth-Installation-Id"

private fun SlockRequestTimeoutException.toTimeoutAuthNetworkResponse(
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

private fun SlockHttpExecutorResponse.toHomeNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = code,
        bodyText = bodyText,
        errorBodyText = errorBodyText,
        transportDiagnostics = transportDiagnostics,
        responseHeaders = responseHeaders
    )

private fun SlockRequestTimeoutException.toTimeoutNetworkResponse(
    category: String,
    path: String,
    method: String,
    requestBytes: Long,
    durationMs: Long,
    warningLogger: (String) -> Unit
): HomeNetworkResponse {
    val errorBody =
        """{"error":"network_timeout","message":"${message.orEmpty().jsonString()}"}"""
    warningLogger(
        "endpoint category=$category path=$path method=$method " +
            "requestBytes=$requestBytes responseBytes=0 " +
            "errorBytes=${errorBody.encodeToByteArray().size} " +
            "code=0 durationMs=$durationMs result=network_error"
    )
    return HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = errorBody)
}

private fun SlockHttpExecutorResponse.toAuthNetworkResponse(): AuthNetworkResponse =
    AuthNetworkResponse(
        code = code,
        bodyText = bodyText?.takeUnless { it.isBlank() },
        errorBodyText = errorBodyText?.takeUnless { it.isBlank() }
    )

private fun DataRequestPriority.toNetworkPriority(): SlockRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
        DataRequestPriority.Visible -> SlockRequestPriority.Visible
        DataRequestPriority.Background -> SlockRequestPriority.Background
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

private fun SlockHttpExecutorResponse.requestIdLogSuffix(): String =
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
