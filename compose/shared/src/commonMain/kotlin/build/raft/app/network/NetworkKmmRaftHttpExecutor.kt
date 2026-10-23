package build.raft.app.network

import build.raft.app.data.featureFlagClientOs
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkMultipartPart
import com.tencent.kmm.network.export.NetworkPriority
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkProgressCallbacks
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkStreamTimeoutPolicy
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.IVBPBLog
import com.tencent.kmm.network.export.VBTransportInitConfig
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.VBTransportInitHelper
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * artin 2026-10-23: app HTTP runs on Ktor ([KtorRaftHttpExecutor]: OkHttp on Android, NSURLSession on
 * iOS). The NetworkKMM executor below (curl over HTTP/2) is reached only through the Developer
 * override, picked per request so a flip applies to the next request.
 */
internal fun createPlatformRaftHttpExecutor(
    warningLogger: (String) -> Unit
): RaftHttpExecutor =
    RaftTransportSelectingHttpExecutor(
        ktor = KtorRaftHttpExecutor(warningLogger),
        curl = lazy { NetworkKmmRaftHttpExecutor(warningLogger) },
        useCurl = ::raftCurlTransportActive
    )

/** True only while the Developer curl override is on and curl is ready (the controller's active transport). */
internal fun raftCurlTransportActive(): Boolean {
    val controller = currentRaftNetworkTransportController()
    return if (controller != null) {
        controller.getPreferenceSync().activeTransport == RaftHttpTransport.Curl
    } else {
        RaftHttpTransportPolicy.curlOverrideRequested()
    }
}

/** Routes each request to Ktor, or to the NetworkKMM curl executor while [useCurl] says so. */
internal class RaftTransportSelectingHttpExecutor(
    private val ktor: RaftHttpExecutor,
    private val curl: Lazy<RaftHttpExecutor>,
    private val useCurl: () -> Boolean,
) : RaftHttpExecutor {
    private fun current(): RaftHttpExecutor =
        if (runCatching(useCurl).getOrDefault(false)) curl.value else ktor

    override suspend fun execute(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): RaftHttpExecutorResponse = current().execute(priority, url, method, headers, bodyText, jsonBody)

    override suspend fun executeMultipart(
        priority: RaftRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?
    ): RaftHttpExecutorResponse = current().executeMultipart(priority, url, fields, files, headers, onUploadProgress)

    override suspend fun executeBytes(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse = current().executeBytes(priority, url, method, headers, bodyBytes, onUploadProgress)

    override suspend fun executeFile(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: RaftHttpFileBody,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse = current().executeFile(priority, url, method, headers, body, onUploadProgress)
}

private const val RAFT_REQUEST_TIMEOUT_MILLIS = 30_000L

/**
 * task #1138: the connect-phase budget for ordinary requests (login, lists,
 * plain GET/POST). Before this constant existed those requests passed no
 * connect timeout and silently inherited the NetworkKMM library default,
 * which moved from 3s to 10s in `0.1.0-raft.38` without any app-side
 * change or telemetry. The value is deliberately equal to today's library
 * default so behaviour is unchanged; what changes is that the number now has
 * ONE app-side source, is logged as `configConnectTimeoutMillis`, and a
 * library bump can no longer move it unnoticed.
 */
// artin 2026-10-23 (Ktor switch): aligned with the Ktor transport's 5 s connect
// ([RAFT_KTOR_CONNECT_TIMEOUT_MILLIS]) so binary downloads and the curl override fail a dead route as
// fast as API calls. Presigned uploads keep their own 10 s budget.
internal const val RAFT_CONNECT_TIMEOUT_MILLIS = RAFT_KTOR_CONNECT_TIMEOUT_MILLIS

/**
 * The single constructor for every [NetworkRequestPolicy] the app hands to
 * NetworkKMM. Call sites must not build the policy inline: a policy built
 * without [NetworkStreamTimeoutPolicy.connectTimeoutMillis] silently falls
 * back to the library default, which is exactly the regression task #1138
 * closes. Presigned uploads pass their own [connectTimeoutMillis]; everything
 * else takes [RAFT_CONNECT_TIMEOUT_MILLIS].
 */
internal fun raftNetworkRequestPolicy(
    totalTimeoutMillis: Long,
    priority: NetworkPriority = NetworkPriority.NORMAL,
    connectTimeoutMillis: Long = RAFT_CONNECT_TIMEOUT_MILLIS,
): NetworkRequestPolicy =
    NetworkRequestPolicy(
        timeoutMillis = totalTimeoutMillis,
        priority = priority,
        streamTimeouts = NetworkStreamTimeoutPolicy(connectTimeoutMillis = connectTimeoutMillis),
    )

/**
 * task #783 (human ruling in `#proj-chat:8b673a88`): streaming fileRef
 * uploads get a 3-minute total budget instead of the generic 30s request
 * deadline — a healthy 190 MiB upload legitimately outlives 30s. Ordinary
 * requests and in-memory bytes multipart keep [RAFT_REQUEST_TIMEOUT_MILLIS];
 * on timeout the existing failed-attachment terminal UI (Upload failed /
 * Tap to retry, draft preserved, zero send side effects) stays as-is.
 */
internal const val RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS = 180_000L

/**
 * task #1135: keep connect at 10s and leave the total upload budget
 * unchanged. This presigned PUT path is normally pinned to curl via
 * http1Only, but Android's allowedEngines can fall back to Ktor; Ktor has no
 * Happy Eyeballs, so a connect timeout is the decision to abandon the whole
 * address family, and 30s would drag an IPv6-blackholed user's cold connect
 * to 30s.
 */
private const val RAFT_UPLOAD_CONNECT_TIMEOUT_MILLIS = 10_000L

/**
 * In-memory bytes presigned PUTs keep the ordinary 30s total budget, so their
 * connect phase must stay well below that bound or a slow connect consumes the
 * whole deadline and connection failures become indistinguishable from
 * transfer failures.
 */
private const val RAFT_BYTES_CONNECT_TIMEOUT_MILLIS = 10_000L

/**
 * task #321 / #783 follow-up (human ruling by @artin on 2026-09-11 in
 * `#proj-mobile:aaad615a`): the presigned direct-upload PUT carries the same
 * file stream as multipart, but a 160 MiB cross-region PUT can legitimately
 * outlive the multipart 3-minute budget. Direct-upload streaming file bodies
 * therefore get a 30-minute total budget; multipart streaming keeps
 * [RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS] unchanged.
 */
internal const val RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS = 1_800_000L

/**
 * Only multipart requests that stream a fileRef body get the upload budget;
 * a part without both an opener and a fileRef maps to NetworkBody.Bytes and
 * keeps the ordinary bound.
 */
internal fun List<MultipartFile>.hasStreamingFileRefParts(): Boolean =
    any { it.streamOpener != null && it.fileRef != null }

@Volatile
private var networkKmmInitialized = false

internal fun ensureNetworkKmmInitialized(warningLogger: (String) -> Unit) {
    if (networkKmmInitialized) return
    try {
        installPlatformNetworkKmmAppIdentity()
        VBTransportInitHelper.init(
            VBTransportInitConfig(
                logImpl = createNetworkKmmLog(warningLogger)
            )
        )
        networkKmmInitialized = true
    } catch (error: Exception) {
        warningLogger("slock_networkkmm_init_failed ${error.safeDiagnosticsSummary()}")
    }
}

internal fun createNetworkKmmLog(warningLogger: (String) -> Unit): IVBPBLog =
    object : IVBPBLog {
        override fun d(tag: String?, content: String?) = Unit

        override fun i(tag: String?, content: String?) = Unit

        override fun e(tag: String?, content: String?, throwable: Throwable?) {
            warningLogger(
                "slock_networkkmm_error tag=${tag.orEmpty()} " +
                    content.orEmpty().toSafeNetworkKmmLogMessage() +
                    throwable?.let { " ${it.safeDiagnosticsSummary()}" }.orEmpty()
            )
        }
    }

internal class NetworkKmmRaftHttpExecutor(
    private val warningLogger: (String) -> Unit = {},
    /** Test seam only: production always routes through [client]. */
    private val multipartTransportOverride: (suspend (NetworkRequest) -> NetworkResponse)? = null,
    private val requestLifetimeCoordinator: RaftNetworkRequestLifetimeCoordinator =
        RaftProcessNetworkRequestLifetime.coordinator,
    /** Test seam only: production uses the callback API on [client]. */
    private val requestStarterOverride: RaftNetworkRequestStarter? = null
) : RaftHttpExecutor {

    private val client: NetworkClient by lazy {
        ensureNetworkKmmInitialized(warningLogger)
        NetworkClient(
            raftNetworkClientConfig(
                defaultPolicy = raftNetworkRequestPolicy(RAFT_REQUEST_TIMEOUT_MILLIS),
                diagnosticsLogger = warningLogger
            )
        )
    }

    override suspend fun execute(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): RaftHttpExecutorResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val path = url.substringBefore('?')
        val traceHeaders = networkKmmTraceHeaders(headers)
        val clientRequestId = traceHeaders.getValue(NETWORK_KMM_REQUEST_ID_HEADER)
        val requestBytes = bodyText?.encodeToByteArray()?.size ?: 0
        warningLogger(
            "slock_networkkmm_execute_start path=$path method=${method.uppercase()} " +
                "priority=$priority jsonBody=$jsonBody requestBytes=$requestBytes " +
                "configTotalTimeoutMillis=$RAFT_REQUEST_TIMEOUT_MILLIS " +
                "configConnectTimeoutMillis=$RAFT_CONNECT_TIMEOUT_MILLIS " +
                "clientRequestId=$clientRequestId headers=${traceHeaders.toSafeHeaderDiagnostics()}"
        )
        return try {
            val request =
                NetworkRequest(
                    method = method.toTransportMethod(),
                    url = url,
                    body =
                        when {
                            bodyText == null -> NetworkBody.Empty
                            jsonBody -> NetworkBody.Json(bodyText)
                            else -> NetworkBody.Text(bodyText)
                        },
                    policy =
                        raftNetworkRequestPolicy(
                            totalTimeoutMillis = RAFT_REQUEST_TIMEOUT_MILLIS,
                            priority = priority.toNetworkKmmPriority()
                        )
                )
            withRaftApiClientHeaders(traceHeaders).forEach { (name, value) -> request.setHeader(name, value) }
            val response =
                executeNetworkRequest(
                    request = request,
                    methodClass =
                        raftNetworkMethodClass(
                            method = method,
                            url = url,
                            serverScoped = headers.keys.any { name -> name.equals(HEADER_SERVER_ID, ignoreCase = true) }
                        ),
                    timeoutMillis = RAFT_REQUEST_TIMEOUT_MILLIS
                )
            warningLogger(
                "slock_networkkmm_execute_finish path=$path method=${method.uppercase()} " +
                    "priority=$priority jsonBody=$jsonBody requestBytes=$requestBytes " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} clientRequestId=$clientRequestId " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    (response.transportDiagnostics ?: "engine=networkkmm status=none kind=none rawCode=none detail=none")
            )
            response
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_networkkmm_execute_failed path=$path method=${method.uppercase()} " +
                    "priority=$priority jsonBody=$jsonBody requestBytes=$requestBytes " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} ${error.safeDiagnosticsSummary()}"
            )
            RaftHttpExecutorResponse(
                code = 0,
                bodyText = null,
                errorBodyText = error.message?.redactNetworkErrorText()
            )
        }
    }

    override suspend fun executeMultipart(
        priority: RaftRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?
    ): RaftHttpExecutorResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val path = url.substringBefore('?')
        val traceHeaders = networkKmmTraceHeaders(headers)
        val clientRequestId = traceHeaders.getValue(NETWORK_KMM_REQUEST_ID_HEADER)
        val requestBytes = files.sumOf { file -> file.bytes.size.toLong().takeIf { it > 0L } ?: file.sizeBytes ?: 0L }
        val totalTimeoutMillis =
            if (files.hasStreamingFileRefParts()) RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS
            else RAFT_REQUEST_TIMEOUT_MILLIS
        warningLogger(
            "slock_networkkmm_multipart_start path=$path method=POST priority=$priority " +
                "fields=${fields.size} files=${files.size} requestBytes=$requestBytes " +
                "totalTimeoutMs=$totalTimeoutMillis " +
                "configConnectTimeoutMillis=$RAFT_CONNECT_TIMEOUT_MILLIS " +
                "clientRequestId=$clientRequestId headers=${traceHeaders.toSafeHeaderDiagnostics()}"
        )
        return try {
            val request =
                NetworkRequest(
                    method = VBTransportMethod.POST,
                    url = url,
                    body =
                        NetworkBody.Multipart(
                            parts =
                                fields.map { (name, value) ->
                                    NetworkMultipartPart(name = name, body = NetworkBody.Text(value))
                                } +
                                    files.map { file ->
                                        NetworkMultipartPart(
                                            name = file.name,
                                            fileName = file.fileName.sanitizeMultipartFileName(),
                                            body = file.toNetworkMultipartBody(),
                                            headers = mapOf("Content-Type" to file.mimeType)
                                        )
                                    }
                        ),
                    policy =
                        raftNetworkRequestPolicy(
                            totalTimeoutMillis = totalTimeoutMillis,
                            priority = priority.toNetworkKmmPriority()
                        )
                )
            withRaftApiClientHeaders(traceHeaders).forEach { (name, value) -> request.setHeader(name, value) }
            request.progress =
                NetworkProgressCallbacks(
                    uploadProgress = { progress ->
                        onUploadProgress?.invoke(
                            RaftTransferProgress(
                                bytesTransferred = progress.bytesTransferred,
                                bytesTotal = progress.bytesTotal
                            )
                        )
                    }
                )
            val response =
                multipartTransportOverride
                    ?.invoke(request)
                    ?.toExecutorResponse()
                    ?: executeNetworkRequest(
                        request = request,
                        methodClass = RaftNetworkMethodClass.ProcessMutation,
                        timeoutMillis = totalTimeoutMillis
                    )
            warningLogger(
                "slock_networkkmm_multipart_finish path=$path method=POST priority=$priority " +
                    "fields=${fields.size} files=${files.size} requestBytes=$requestBytes " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} clientRequestId=$clientRequestId " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    (response.transportDiagnostics ?: "engine=networkkmm status=none kind=none rawCode=none detail=none")
            )
            response
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_networkkmm_multipart_failed path=$path method=POST priority=$priority " +
                    "fields=${fields.size} files=${files.size} requestBytes=$requestBytes " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} ${error.safeDiagnosticsSummary()}"
            )
            RaftHttpExecutorResponse(
                code = 0,
                bodyText = null,
                errorBodyText = error.message?.redactNetworkErrorText()
            )
        }
    }

    override suspend fun executeBytes(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse =
        executeBinary(
            priority = priority,
            url = url,
            method = method,
            headers = headers,
            body = NetworkBody.Bytes(bodyBytes),
            requestBytes = bodyBytes.size.toLong(),
            totalTimeoutMillis = RAFT_REQUEST_TIMEOUT_MILLIS,
            connectTimeoutMillis = RAFT_BYTES_CONNECT_TIMEOUT_MILLIS,
            bodyKind = "bytes",
            http1Only = true,
            onUploadProgress = onUploadProgress,
        )

    override suspend fun executeFile(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: RaftHttpFileBody,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse =
        executeBinary(
            priority = priority,
            url = url,
            method = method,
            headers = headers,
            body = NetworkBody.FileRef(
                path = body.path,
                contentType = body.contentType,
                contentLength = body.contentLength,
                openStreamBlock = { _ -> body.openStream() },
            ),
            requestBytes = body.contentLength,
            totalTimeoutMillis = RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS,
            connectTimeoutMillis = RAFT_UPLOAD_CONNECT_TIMEOUT_MILLIS,
            bodyKind = "file",
            http1Only = true,
            onUploadProgress = onUploadProgress,
        )

    private suspend fun executeBinary(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: NetworkBody,
        requestBytes: Long,
        totalTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        bodyKind: String,
        http1Only: Boolean = false,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        // Presigned URLs are bearer capabilities.  Never include the URL (or its query) in
        // diagnostics; only a fixed category and safe header names are emitted.
        val traceHeaders = networkKmmTraceHeaders(headers)
        val clientRequestId = traceHeaders.getValue(NETWORK_KMM_REQUEST_ID_HEADER)
        warningLogger(
            "slock_networkkmm_binary_start category=presigned_upload bodyKind=$bodyKind " +
                "method=${method.uppercase()} priority=$priority requestBytes=$requestBytes " +
                "configTotalTimeoutMillis=$totalTimeoutMillis " +
                "configConnectTimeoutMillis=$connectTimeoutMillis " +
                "clientRequestId=$clientRequestId headers=${traceHeaders.toSafeHeaderDiagnostics()}"
        )
        return try {
            val request = NetworkRequest(
                method = method.toTransportMethod(),
                url = url,
                body = body,
                policy = raftNetworkRequestPolicy(
                    totalTimeoutMillis = totalTimeoutMillis,
                    priority = priority.toNetworkKmmPriority(),
                    connectTimeoutMillis = connectTimeoutMillis
                )
            )
            if (http1Only) {
                request.flagHttp1Only(warningLogger)
            }
            traceHeaders.forEach { (name, value) -> request.setHeader(name, value) }
            if (onUploadProgress != null) {
                request.progress =
                    NetworkProgressCallbacks(
                        uploadProgress = { progress ->
                            onUploadProgress(
                                RaftTransferProgress(
                                    bytesTransferred = progress.bytesTransferred,
                                    bytesTotal = progress.bytesTotal,
                                )
                            )
                        }
                    )
            }
            val response = executeNetworkRequest(
                request = request,
                methodClass = RaftNetworkMethodClass.ProcessMutation,
                timeoutMillis = totalTimeoutMillis,
                redactSensitiveResponse = true
            )
            warningLogger(
                "slock_networkkmm_binary_finish category=presigned_upload bodyKind=$bodyKind " +
                    "method=${method.uppercase()} priority=$priority requestBytes=$requestBytes " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} clientRequestId=$clientRequestId " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    (response.transportDiagnostics ?: "engine=networkkmm status=none kind=none rawCode=none detail=none")
            )
            response
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_networkkmm_binary_failed category=presigned_upload bodyKind=$bodyKind " +
                    "method=${method.uppercase()} priority=$priority requestBytes=$requestBytes " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} ${error.safeDiagnosticsSummary()}"
            )
            RaftHttpExecutorResponse(
                code = 0,
                bodyText = null,
                errorBodyText = "attachment_presigned_upload_transport_failed"
            )
        }
    }

    private suspend fun executeNetworkRequest(
        request: NetworkRequest,
        methodClass: RaftNetworkMethodClass,
        timeoutMillis: Long,
        redactSensitiveResponse: Boolean = false
    ): RaftHttpExecutorResponse {
        val outcome =
            requestLifetimeCoordinator.execute<NetworkResponse>(
                methodClass = methodClass,
                timeoutMillis = timeoutMillis
            ) { onComplete ->
                requestStarterOverride?.start(request, onComplete)
                    ?: client.execute(request, onComplete).let { call ->
                        RaftNetworkRequestCancelHandle(call::cancel)
                    }
            }
        return when (outcome) {
            is RaftNetworkRequestLifetimeOutcome.Completed ->
                outcome.value.toExecutorResponse(redactSensitive = redactSensitiveResponse)
            is RaftNetworkRequestLifetimeOutcome.Retired -> outcome.toExecutorResponse()
        }
    }
}

internal fun MultipartFile.toNetworkMultipartBody(): NetworkBody {
    val opener = streamOpener
    val ref = fileRef
    return if (opener != null && ref != null) {
        NetworkBody.FileRef(
            path = ref,
            contentType = mimeType,
            contentLength = sizeBytes,
            openStreamBlock = { opener.open() }
        )
    } else {
        NetworkBody.Bytes(bytes, contentType = mimeType)
    }
}

private fun String.toSafeNetworkKmmLogMessage(): String {
    if (isBlank()) return "blank"
    return SensitiveNetworkKmmLogPatterns.fold(this) { current, pattern ->
        current.replace(pattern, "$1[redacted]")
    }.replace(Regex("""\s+"""), " ")
}

private val SensitiveNetworkKmmLogPatterns =
    listOf(
        Regex("""(\bauthorization\s*[:=]\s*bearer\s+)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bbearer\s+)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bcookie\s*[:=]\s*)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bset-cookie\s*[:=]\s*)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bx-api-key\s*[:=]\s*)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bx-auth-token\s*[:=]\s*)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\baccess[_-]?token\s*[:=]\s*)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\brefresh[_-]?token\s*[:=]\s*)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bpassword\s*[:=]\s*)[^\s,;}]+""", RegexOption.IGNORE_CASE),
        Regex("""((?:"|')?(?:access[_-]?token|refresh[_-]?token|password)(?:"|')?\s*:\s*(?:"|'))[^"',}]+""", RegexOption.IGNORE_CASE),
        Regex("""\b((?:eyJ)[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.)[A-Za-z0-9_-]+\b"""),
        Regex("""\b([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""")
    )

private const val NETWORK_KMM_REQUEST_ID_HEADER = "x-request-id"
private const val NETWORK_KMM_USER_AGENT_HEADER = "User-Agent"

/**
 * Raft API requests send the app User-Agent with its device token ([currentRaftNetworkUserAgent]):
 * the server reads the product-event `$device_type` from it. A caller's own User-Agent wins.
 */
internal fun withRaftNetworkUserAgent(
    headers: Map<String, String>,
    userAgent: () -> String = ::currentRaftNetworkUserAgent,
): Map<String, String> {
    if (headers.keys.any { it.equals(NETWORK_KMM_USER_AGENT_HEADER, ignoreCase = true) }) return headers
    val value = runCatching(userAgent).getOrNull()?.takeIf { it.isNotBlank() } ?: return headers
    return headers + (NETWORK_KMM_USER_AGENT_HEADER to value)
}

internal const val RAFT_CLIENT_PLATFORM_HEADER = "X-Raft-Client-Platform"

/**
 * Raft API requests name the native app they come from: `android` or `ios` ([featureFlagClientOs],
 * the app's one platform name). The server records it as the `account_created` platform (botiverse/slock
 * `signupClientPlatform`, which accepts exactly those lowercase values). Every API request carries it,
 * so no signup or sign-in route can miss it. A caller's own value wins.
 */
internal fun withRaftClientPlatform(
    headers: Map<String, String>,
    platform: () -> String = ::featureFlagClientOs,
): Map<String, String> {
    if (headers.keys.any { it.equals(RAFT_CLIENT_PLATFORM_HEADER, ignoreCase = true) }) return headers
    val value = runCatching(platform).getOrNull()?.takeIf { it.isNotBlank() } ?: return headers
    return headers + (RAFT_CLIENT_PLATFORM_HEADER to value)
}

/** The app-identity headers every Raft API request ([execute] and multipart) carries. */
internal fun withRaftApiClientHeaders(headers: Map<String, String>): Map<String, String> =
    withRaftClientPlatform(withRaftNetworkUserAgent(headers))
private const val NETWORK_KMM_TRACEPARENT_HEADER = "traceparent"

internal fun networkKmmTraceHeaders(
    headers: Map<String, String>,
    randomBytes: (Int) -> ByteArray = { size -> Random.nextBytes(size) }
): Map<String, String> {
    val requestIdEntry = headers.entries.firstOrNull { it.key.equals(NETWORK_KMM_REQUEST_ID_HEADER, true) }
    val traceparentEntry = headers.entries.firstOrNull { it.key.equals(NETWORK_KMM_TRACEPARENT_HEADER, true) }
    if (requestIdEntry != null && traceparentEntry != null) return headers

    val traceId = randomBytes(16).toNonZeroLowerHex()
    val spanId = randomBytes(8).toNonZeroLowerHex()
    return buildMap {
        putAll(headers)
        if (requestIdEntry == null) put(NETWORK_KMM_REQUEST_ID_HEADER, "mobile-$traceId")
        if (traceparentEntry == null) put(NETWORK_KMM_TRACEPARENT_HEADER, "00-$traceId-$spanId-01")
    }
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    byte.toUByte().toString(radix = 16).padStart(2, '0')
}

private fun ByteArray.toNonZeroLowerHex(): String {
    if (any { it != 0.toByte() }) return toLowerHex()
    return copyOf().apply { this[lastIndex] = 1 }.toLowerHex()
}

private fun Map<String, String>.toSafeHeaderDiagnostics(): String {
    if (isEmpty()) return "none"
    return entries
        .sortedBy { it.key.lowercase() }
        .joinToString(",") { (name, value) ->
            val normalized = name.lowercase()
            when (normalized) {
                "authorization",
                "cookie",
                "set-cookie",
                "x-api-key",
                "x-auth-token" -> "$name=present"
                "content-type",
                "user-agent",
                "accept",
                "x-server-id" -> "$name=${value.toSafeDiagnosticsToken()}"
                else -> "$name=present"
            }
        }
}

private fun String.toTransportMethod(): VBTransportMethod =
    when (uppercase()) {
        "GET" -> VBTransportMethod.GET
        "POST" -> VBTransportMethod.POST
        "PUT" -> VBTransportMethod.PUT
        "PATCH" -> VBTransportMethod.PATCH
        "DELETE" -> VBTransportMethod.DELETE
        "HEAD" -> VBTransportMethod.HEAD
        "OPTIONS" -> VBTransportMethod.OPTIONS
        else -> VBTransportMethod.GET
    }

private fun RaftRequestPriority.toNetworkKmmPriority(): NetworkPriority =
    when (this) {
        RaftRequestPriority.Foreground -> NetworkPriority.HIGH
        RaftRequestPriority.Visible -> NetworkPriority.NORMAL
        RaftRequestPriority.Background -> NetworkPriority.LOW
    }

private fun NetworkResponse.toExecutorResponse(redactSensitive: Boolean = false): RaftHttpExecutorResponse {
    val status = statusCode
    val diagnostics = networkKmmDiagnostics(redactSensitive = redactSensitive)
    if (status == null) {
        return RaftHttpExecutorResponse(
            code = 0,
            bodyText = null,
            errorBodyText =
                if (redactSensitive) {
                    "attachment_presigned_upload_transport_failed"
                } else {
                    error?.message?.redactNetworkErrorText() ?: "network_kmm_no_status"
                },
            transportDiagnostics = diagnostics,
            responseHeaders = if (redactSensitive) emptyMap() else headers
        )
    }
    val text = body.text()
    return if (status in 200..299) {
        RaftHttpExecutorResponse(
            code = status,
            bodyText = if (redactSensitive) null else text,
            errorBodyText = null,
            transportDiagnostics = diagnostics,
            responseHeaders = if (redactSensitive) emptyMap() else headers
        )
    } else {
        RaftHttpExecutorResponse(
            code = status,
            bodyText = null,
            errorBodyText =
                if (redactSensitive) {
                    "attachment_presigned_upload_http_$status"
                } else if (isEdgeBlockedErrorBody(status, headers, text)) {
                    // An edge layer (CDN/proxy/WAF) answered 403 with an HTML
                    // page instead of the API's JSON envelope. Re-encode it as
                    // the canonical marker so route adapters surface
                    // serverMessage="edge_blocked" and the UI shows the
                    // network-blocked copy instead of "no permission".
                    """{"error":"edge_blocked"}"""
                } else {
                    (text ?: error?.message)?.redactNetworkErrorText()
                },
            transportDiagnostics = diagnostics,
            responseHeaders = if (redactSensitive) emptyMap() else headers
        )
    }
}

/**
 * True when a 403 response body is not the API's JSON error envelope but an
 * HTML block page produced by an edge layer (CDN/WAF/corporate proxy).
 *
 * Two signals, either is sufficient:
 * - a `Content-Type: text/html` response header, or
 * - a body that starts with `<` after whitespace — the API never emits XML/HTML
 *   payloads, and JSON bodies always start with `{` or `[`.
 */
internal fun isEdgeBlockedErrorBody(
    status: Int,
    headers: Map<String, List<String>>,
    bodyText: String?
): Boolean {
    if (status != 403) return false
    val contentType =
        headers.entries
            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
    if (contentType != null && contentType.equals("text/html", ignoreCase = true)) return true
    return bodyText?.trimStart()?.startsWith("<") == true
}

private fun RaftNetworkRequestLifetimeOutcome.Retired.toExecutorResponse(): RaftHttpExecutorResponse {
    val outcome = if (mutationOutcomeUnknown) "unknown" else "cancelled"
    val detail = if (mutationOutcomeUnknown) "mutation_outcome_unknown" else "lifecycle_retired"
    return RaftHttpExecutorResponse(
        code = 0,
        bodyText = null,
        errorBodyText = "network_request_$detail",
        transportDiagnostics =
            "engine=networkkmm status=none kind=CANCELLED rawCode=CANCELED detail=$detail " +
                "requestGeneration=${descriptor.generation} " +
                "lifecycleGeneration=$replacementGeneration " +
                "methodClass=${descriptor.methodClass.name.lowercase()} " +
                "outcome=$outcome replay=false"
    )
}

internal fun NetworkResponse.networkKmmDiagnostics(redactSensitive: Boolean = false): String {
    val rawCode = error?.rawCode ?: rawResponse?.errorCode
    val rawCodeLabel =
        when (rawCode) {
            VBTransportResultCode.CODE_OK -> "OK"
            VBTransportResultCode.CODE_CANCELED -> "CANCELED"
            VBTransportResultCode.CODE_FORCE_TIMEOUT -> "FORCE_TIMEOUT"
            VBTransportResultCode.CODE_NETWORK_ERROR -> "NETWORK_ERROR"
            null -> "none"
            else -> rawCode.toString()
        }
    val detail =
        if (redactSensitive) {
            listOfNotNull(error?.kind?.toString(), rawCodeLabel)
                .firstOrNull { it.isNotBlank() }
                ?.toSafeDiagnosticsToken()
        } else {
            listOfNotNull(error?.message, rawResponse?.errorMessage)
            .firstOrNull { it.isNotBlank() }
            ?.toSafeDiagnosticsToken()
        }
            ?: "none"
    return "engine=networkkmm status=${statusCode ?: "none"} " +
        "kind=${error?.kind ?: "none"} rawCode=$rawCodeLabel detail=$detail " +
        "http3Requested=${request.curlHttp3EnabledOverride ?: "none"} " +
        "bodyBytes=${body.bytes?.size ?: 0} errorStatus=${error?.statusCode ?: "none"} " +
        "transportId=${timing.transportRequestId ?: "none"} protocol=${timing.protocol ?: "none"} " +
        "reusedConnection=${timing.reusedConnection ?: "none"} " +
        "clientQueueMs=${timing.clientQueueTimeMs.toLong()} " +
        "prepareMs=${timing.requestPreparationTimeMs.toLong()} " +
        "transportQueueMs=${timing.transportQueueTimeMs.toLong()} " +
        "dispatcherQueueMs=${timing.dispatcherQueueTimeMs.toLong()} " +
        "dnsMs=${timing.nameLookupTimeMs.toLong()} connectTotalMs=${timing.connectTimeMs.toLong()} " +
        "tlsMs=${timing.sslCostTimeMs.toLong()} requestHeadersMs=${timing.requestHeadersTimeMs.toLong()} " +
        "requestBodyMs=${timing.requestBodyTimeMs.toLong()} " +
        "responseWaitMs=${timing.responseWaitTimeMs.toLong()} recvMs=${timing.recvTime.toLong()} " +
        "connectionAttempts=${timing.connectionAttemptCount} totalMs=${timing.totalTimeMs.toLong()} " +
        "staleH2Detected=${timing.staleH2Detected} " +
        "connectionOrigin=${timing.connectionOrigin?.toSafeDiagnosticsToken() ?: "none"} " +
        "connectionShard=${timing.connectionShard ?: "none"} " +
        "connectionGeneration=${timing.connectionGeneration ?: "none"} " +
        "connectionIdentity=${timing.connectionIdentity?.toSafeDiagnosticsToken() ?: "none"} " +
        "connectionDraining=${timing.connectionDraining} " +
        "connectionRolloverRateLimited=${timing.connectionRolloverRateLimited} " +
        "freshRetry=${timing.freshRetry} " +
        "freshRetryResult=${timing.freshRetryResult?.toSafeDiagnosticsToken() ?: "none"} " +
        "noResponseHeadersDurationMs=${timing.noResponseHeadersDurationMs.toLong()} " +
        "staleH2ConcurrentRequestCount=${timing.staleH2ConcurrentRequestCount} " +
        "curlCompletionInfoVersion=${timing.curlCompletionInfoVersion ?: "none"} " +
        "curlNameLookupTimingAvailable=${timing.curlNameLookupTimingAvailable ?: "none"} " +
        "curlConnectTimingAvailable=${timing.curlConnectTimingAvailable ?: "none"} " +
        "curlPreTransferTimingAvailable=${timing.curlPreTransferTimingAvailable ?: "none"} " +
        "curlStartTransferTimingAvailable=${timing.curlStartTransferTimingAvailable ?: "none"} " +
        "curlTotalTimingAvailable=${timing.curlTotalTimingAvailable ?: "none"} " +
        "curlTlsTimingState=${timing.curlTlsTimingState?.toSafeDiagnosticsToken() ?: "none"} " +
        // task #372 / #1135: connect budget actually handed to the engine after the
        // library's total/whole-transfer clamp (0.1.0-raft.38). 0 means the engine
        // route did not report it; surface that as none rather than a fake zero.
        "effectiveConnectTimeoutMillis=${timing.effectiveConnectTimeoutMillis.takeIf { it > 0 }?.toString() ?: "none"}"
}

internal fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")
