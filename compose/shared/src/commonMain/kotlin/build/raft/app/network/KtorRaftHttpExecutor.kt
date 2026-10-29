package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * artin 2026-10-23 ("直接换ktor吧"): the default app HTTP transport. Ktor's OkHttp engine on Android
 * and Darwin (NSURLSession) engine on iOS, configured in [createPlatformRaftKtorClientProvider].
 *
 * The [RaftHttpExecutor] contract (the one the removed NetworkKMM executor had): transport failures map to
 * code 0 with an errorBodyText and a `kind=` diagnostics token, HTTP errors never throw, cancellation
 * is rethrown, and every attempt runs under [RaftNetworkRequestLifetimeCoordinator] so a process
 * visibility edge retires reads exactly as before. The executor itself never retries; the engine
 * may silently re-run a GET/HEAD on a fresh connection when a pooled one turns out dead
 * ([RaftKtorCallKind.Idempotent]), never a POST/PUT/PATCH/DELETE.
 */
internal class KtorRaftHttpExecutor(
    private val warningLogger: (String) -> Unit = {},
    private val clients: RaftKtorClientProvider = RaftKtorProcessClients.provider,
    private val requestLifetimeCoordinator: RaftNetworkRequestLifetimeCoordinator =
        RaftProcessNetworkRequestLifetime.coordinator,
    private val callScope: CoroutineScope = RaftKtorCallScope,
    private val transportFacts: (requestId: String) -> String? = ::takeRaftKtorTransportFacts,
    private val engineLabel: String = raftKtorEngineLabel(),
    /** False where the engine's response version is a constant rather than the wire protocol (Darwin). */
    private val engineReportsProtocol: Boolean = raftKtorEngineReportsProtocol(),
    /** Where upload progress is measured: the platform's network layer when it can, else the body writer. */
    private val uploadProgress: RaftUploadProgressTracker = platformRaftUploadProgressTracker(),
) : RaftHttpExecutor {

    override suspend fun execute(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): RaftHttpExecutorResponse {
        val normalizedMethod = method.trim().uppercase()
        val traceHeaders = raftTraceHeaders(headers)
        val (requestHeaders, contentTypeOverride) = withRaftApiClientHeaders(traceHeaders).splitContentHeaders()
        val content =
            bodyText?.let { text ->
                TextContent(
                    text = text,
                    contentType =
                        contentTypeOverride
                            ?: if (jsonBody) ContentType.Application.Json else ContentType.Text.Plain
                )
            }
        return perform(
            KtorCallSpec(
                logPrefix = "slock_http_execute",
                path = url.substringBefore('?'),
                url = url,
                method = normalizedMethod,
                priority = priority,
                kind = RaftKtorCallKind.forMethod(normalizedMethod),
                headers = requestHeaders,
                content = { content },
                requestBytes = bodyText?.encodeToByteArray()?.size?.toLong() ?: 0L,
                totalTimeoutMillis = RAFT_KTOR_REQUEST_TIMEOUT_MILLIS,
                connectTimeoutMillis = RAFT_KTOR_CONNECT_TIMEOUT_MILLIS,
                socketTimeoutMillis = null,
                methodClass =
                    raftNetworkMethodClass(
                        method = normalizedMethod,
                        url = url,
                        serverScoped = headers.keys.any { name -> name.equals(HEADER_SERVER_ID, ignoreCase = true) }
                    ),
                redactSensitive = false,
                extraLogFields = "jsonBody=$jsonBody",
            )
        )
    }

    override suspend fun executeMultipart(
        priority: RaftRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>,
        onUploadProgress: ((RaftWireTransferProgress) -> Unit)?
    ): RaftHttpExecutorResponse {
        val traceHeaders = raftTraceHeaders(headers)
        val (requestHeaders, _) = withRaftApiClientHeaders(traceHeaders).splitContentHeaders()
        val streaming = files.hasStreamingFileRefParts()
        val boundary = "RaftBoundary" + Random.nextBytes(12).joinToString("") { byte ->
            byte.toUByte().toString(radix = 16).padStart(2, '0')
        }
        val progress = uploadProgress.route(onUploadProgress)
        val spec =
            KtorCallSpec(
                logPrefix = "slock_http_multipart",
                path = url.substringBefore('?'),
                url = url,
                method = "POST",
                priority = priority,
                kind = RaftKtorCallKind.MultipartUpload,
                headers = requestHeaders,
                content = {
                    RaftMultipartContent(
                        boundary = boundary,
                        fields = fields,
                        files = files,
                        onProgress = progress.bodyWriterProgress,
                    )
                },
                requestBytes = files.sumOf { file -> file.bytes.size.toLong().takeIf { it > 0L } ?: file.sizeBytes ?: 0L },
                totalTimeoutMillis = if (streaming) RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS else RAFT_KTOR_REQUEST_TIMEOUT_MILLIS,
                connectTimeoutMillis = RAFT_KTOR_CONNECT_TIMEOUT_MILLIS,
                socketTimeoutMillis = RAFT_KTOR_UPLOAD_SOCKET_TIMEOUT_MILLIS,
                methodClass = RaftNetworkMethodClass.ProcessMutation,
                redactSensitive = false,
                extraLogFields = "fields=${fields.size} files=${files.size} streaming=$streaming",
                progress = progress,
            )
        val first = perform(spec)
        if (!first.isRetryableUploadReset()) return first
        // The connection was reset after the body may already have reached the server (a VPN or
        // proxy cutting the stream). Re-send once on a fresh attempt; the legacy endpoint does not
        // dedupe, so a rare extra stored copy is accepted (Tenny 2026-10-29). Each attempt reopens
        // the file streams through RaftMultipartContent.
        warningLogger(
            "slock_http_multipart_retry path=${spec.path} reason=connection_reset " +
                "firstError=${first.errorBodyText?.take(80)?.toSafeHeaderValue()}"
        )
        return perform(spec)
    }

    override suspend fun executeBytes(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        onUploadProgress: ((RaftWireTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse {
        val traceHeaders = raftTraceHeaders(headers)
        val (requestHeaders, contentType) = traceHeaders.splitContentHeaders()
        val progress = uploadProgress.route(onUploadProgress)
        return perform(
            presignedSpec(
                bodyKind = "bytes",
                progress = progress,
                url = url,
                method = method,
                priority = priority,
                headers = requestHeaders,
                requestBytes = bodyBytes.size.toLong(),
                totalTimeoutMillis = RAFT_KTOR_REQUEST_TIMEOUT_MILLIS,
                content = {
                    RaftStreamingBodyContent(
                        declaredContentType = contentType,
                        length = bodyBytes.size.toLong(),
                        onProgress = progress.bodyWriterProgress,
                        writeBody = { sink -> sink.writeChunked(bodyBytes) },
                    )
                },
            )
        )
    }

    override suspend fun executeFile(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: RaftHttpFileBody,
        onUploadProgress: ((RaftWireTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse {
        val traceHeaders = raftTraceHeaders(headers)
        val (requestHeaders, contentType) = traceHeaders.splitContentHeaders()
        val progress = uploadProgress.route(onUploadProgress)
        return perform(
            presignedSpec(
                bodyKind = "file",
                progress = progress,
                url = url,
                method = method,
                priority = priority,
                headers = requestHeaders,
                requestBytes = body.contentLength,
                totalTimeoutMillis = RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS,
                content = {
                    RaftStreamingBodyContent(
                        declaredContentType = contentType ?: body.contentType.toContentTypeOrNull(),
                        length = body.contentLength,
                        onProgress = progress.bodyWriterProgress,
                        writeBody = { sink ->
                            body.openStream().pipeChunksTo(sink)
                        },
                    )
                },
            )
        )
    }

    /**
     * Presigned object-store PUTs. The URL is a bearer capability: it never reaches the logs, and the
     * response body is dropped. Android sends them on their own HTTP/1.1 connection
     * ([RaftKtorCallKind.PresignedUpload]) so a long upload never shares the API's HTTP/2 connection.
     */
    private fun presignedSpec(
        bodyKind: String,
        progress: RaftUploadProgressRoute,
        url: String,
        method: String,
        priority: RaftRequestPriority,
        headers: Map<String, String>,
        requestBytes: Long,
        totalTimeoutMillis: Long,
        content: suspend () -> OutgoingContent,
    ): KtorCallSpec =
        KtorCallSpec(
            logPrefix = "slock_http_binary",
            path = "category=presigned_upload",
            url = url,
            method = method.trim().uppercase(),
            priority = priority,
            kind = RaftKtorCallKind.PresignedUpload,
            headers = headers,
            content = content,
            requestBytes = requestBytes,
            totalTimeoutMillis = totalTimeoutMillis,
            connectTimeoutMillis = RAFT_KTOR_UPLOAD_CONNECT_TIMEOUT_MILLIS,
            socketTimeoutMillis = RAFT_KTOR_UPLOAD_SOCKET_TIMEOUT_MILLIS,
            methodClass = RaftNetworkMethodClass.ProcessMutation,
            redactSensitive = true,
            extraLogFields = "bodyKind=$bodyKind",
            progress = progress,
        )

    private suspend fun perform(spec: KtorCallSpec): RaftHttpExecutorResponse =
        try {
            performTracked(spec)
        } finally {
            spec.progress?.release?.invoke()
        }

    private suspend fun performTracked(spec: KtorCallSpec): RaftHttpExecutorResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val clientRequestId = spec.headers.entries
            .firstOrNull { it.key.equals(KTOR_REQUEST_ID_HEADER, ignoreCase = true) }
            ?.value
            .orEmpty()
        val pathField = if (spec.redactSensitive) spec.path else "path=${spec.path}"
        val common =
            "$pathField method=${spec.method} priority=${spec.priority} ${spec.extraLogFields} " +
                "requestBytes=${spec.requestBytes} engine=$engineLabel lane=${spec.kind.logValue(spec.priority)}"
        warningLogger(
            "${spec.logPrefix}_start $common " +
                "configTotalTimeoutMillis=${spec.totalTimeoutMillis} " +
                "configConnectTimeoutMillis=${spec.connectTimeoutMillis} " +
                "clientRequestId=$clientRequestId headers=${spec.headers.toSafeKtorHeaderDiagnostics()}"
        )
        return try {
            val outcome =
                requestLifetimeCoordinator.execute<RaftHttpExecutorResponse>(
                    methodClass = spec.methodClass,
                    timeoutMillis = spec.totalTimeoutMillis
                ) { onComplete ->
                    val job = callScope.launch { onComplete(runCall(spec, clientRequestId, startedAt)) }
                    RaftNetworkRequestCancelHandle { job.cancel() }
                }
            val response =
                when (outcome) {
                    is RaftNetworkRequestLifetimeOutcome.Completed -> outcome.value
                    is RaftNetworkRequestLifetimeOutcome.Retired -> outcome.toKtorExecutorResponse(engineLabel)
                }
            warningLogger(
                "${spec.logPrefix}_finish $common " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} clientRequestId=$clientRequestId " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    (response.transportDiagnostics ?: "engine=$engineLabel status=none kind=none rawCode=none detail=none")
            )
            response
        } catch (cancellation: CancellationException) {
            // The caller went away (screen closed, gap catch-up abandoned, scope cancelled). Log a
            // finish like curl did, so a cancel never looks like a hang in diagnostics.
            warningLogger(
                "${spec.logPrefix}_finish $common responseBytes=0 errorBytes=0 code=0 " +
                    "clientRequestId=$clientRequestId durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "engine=$engineLabel status=none kind=CANCELLED rawCode=CANCELED detail=caller_cancelled"
            )
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "${spec.logPrefix}_failed $common " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} ${error.safeDiagnosticsSummary()}"
            )
            RaftHttpExecutorResponse(
                code = 0,
                bodyText = null,
                errorBodyText =
                    if (spec.redactSensitive) "attachment_presigned_upload_transport_failed"
                    else error.message?.redactNetworkErrorText()
            )
        }
    }

    /**
     * The call, plus at most one more attempt: an idempotent call that timed out while the platform
     * replaced its lane's connections (a stuck reused HTTP/2 connection on iOS, Hands 43837e84) runs
     * once more on the fresh ones, with a shorter idle timeout so the retry fits the call's budget.
     */
    private suspend fun runCall(
        spec: KtorCallSpec,
        clientRequestId: String,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
    ): RaftHttpExecutorResponse {
        val generation = clients.generation(spec.priority)
        val first = runAttempt(spec, clientRequestId, startedAt, socketTimeoutOverrideMillis = null)
        clients.recordOutcome(spec.priority, generation, timedOut = first.timedOut)
        val freshGeneration = clients.generation(spec.priority)
        if (!first.timedOut || spec.kind != RaftKtorCallKind.Idempotent || freshGeneration == generation) {
            return first.response
        }
        warningLogger(
            "${spec.logPrefix}_retry_fresh_session lane=${spec.kind.logValue(spec.priority)} " +
                "clientRequestId=$clientRequestId generation=$freshGeneration " +
                "elapsedMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        val retry =
            runAttempt(spec, clientRequestId, startedAt, socketTimeoutOverrideMillis = RAFT_KTOR_FRESH_SESSION_RETRY_SOCKET_TIMEOUT_MILLIS)
        clients.recordOutcome(spec.priority, freshGeneration, timedOut = retry.timedOut)
        return retry.response
    }

    private class KtorAttempt(val response: RaftHttpExecutorResponse, val timedOut: Boolean)

    /** One transport attempt. Never throws except for cancellation of its own job. */
    private suspend fun runAttempt(
        spec: KtorCallSpec,
        clientRequestId: String,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        socketTimeoutOverrideMillis: Long?,
    ): KtorAttempt =
        try {
            val content = spec.content()
            // Ktor 3 reads the whole body inside request(), so the response is consumed within this
            // call's scope (no channel outlives it).
            val response: HttpResponse =
                clients.client(spec.priority, spec.kind).request(spec.url) {
                    method = HttpMethod.parse(spec.method)
                    headers {
                        spec.headers.forEach { (name, value) -> append(name, value.toSafeHeaderValue()) }
                        spec.progress?.networkHeader?.let { (name, value) -> append(name, value) }
                    }
                    if (content != null) setBody(content)
                    timeout {
                        requestTimeoutMillis =
                            if (socketTimeoutOverrideMillis == null) spec.totalTimeoutMillis
                            else (spec.totalTimeoutMillis - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(1_000L)
                        connectTimeoutMillis = spec.connectTimeoutMillis
                        (socketTimeoutOverrideMillis ?: spec.socketTimeoutMillis)?.let { socketTimeoutMillis = it }
                    }
                }
            val outcome =
                RaftHttpOutcome(
                    status = response.status.value,
                    headers = response.headers.entries().associate { (name, values) -> name to values },
                    bodyText = if (spec.redactSensitive) null else response.bodyAsText(),
                    protocol = if (engineReportsProtocol) response.version.toString() else RAFT_KTOR_PROTOCOL_UNREPORTED,
                )
            val status = outcome.status
            val text = if (spec.redactSensitive) null else outcome.bodyText
            val responseHeaders = if (spec.redactSensitive) emptyMap() else outcome.headers
            val diagnostics =
                ktorDiagnostics(
                    status = status,
                    kind = null,
                    detail = null,
                    protocol = outcome.protocol,
                    bodyBytes = text?.encodeToByteArray()?.size ?: 0,
                    totalMs = startedAt.elapsedNow().inWholeMilliseconds,
                    facts = transportFacts(clientRequestId),
                )
            val result = when {
                status in 200..299 ->
                    RaftHttpExecutorResponse(
                        code = status,
                        bodyText = text,
                        errorBodyText = null,
                        transportDiagnostics = diagnostics,
                        responseHeaders = responseHeaders
                    )
                spec.redactSensitive ->
                    RaftHttpExecutorResponse(
                        code = status,
                        bodyText = null,
                        errorBodyText = "attachment_presigned_upload_http_$status",
                        transportDiagnostics = diagnostics,
                        responseHeaders = responseHeaders
                    )
                else ->
                    RaftHttpExecutorResponse(
                        code = status,
                        bodyText = null,
                        errorBodyText =
                            if (isEdgeBlockedErrorBody(status, responseHeaders, text)) {
                                """{"error":"edge_blocked"}"""
                            } else {
                                text?.redactNetworkErrorText()
                            },
                        transportDiagnostics = diagnostics,
                        responseHeaders = responseHeaders
                    )
            }
            KtorAttempt(result, timedOut = false)
        } catch (error: Throwable) {
            val kind = classifyRaftKtorFailure(error)
            // A Ktor request timeout is a CancellationException subtype on some paths; only a real
            // cancellation of this attempt's job (caller gone or lifecycle retirement) is rethrown.
            if (kind == RaftTransportFailureKind.CANCELLED && error is CancellationException) throw error
            val response = RaftHttpExecutorResponse(
                code = 0,
                bodyText = null,
                errorBodyText =
                    if (spec.redactSensitive) "attachment_presigned_upload_transport_failed"
                    else "${kind.errorToken}: ${error.message.orEmpty()}".redactNetworkErrorText(),
                transportDiagnostics =
                    ktorDiagnostics(
                        status = null,
                        kind = kind,
                        detail = if (spec.redactSensitive) kind.name else error.safeDiagnosticsSummary(),
                        protocol = null,
                        bodyBytes = 0,
                        totalMs = startedAt.elapsedNow().inWholeMilliseconds,
                        facts = transportFacts(clientRequestId),
                    )
            )
            // A socket / connect timeout says the connection gave nothing; the call's own total budget
            // running out (HttpRequestTimeoutException) does not, and leaves nothing to retry with.
            KtorAttempt(response, timedOut = kind == RaftTransportFailureKind.TIMEOUT && error !is HttpRequestTimeoutException)
        }

    private fun ktorDiagnostics(
        status: Int?,
        kind: RaftTransportFailureKind?,
        detail: String?,
        protocol: String?,
        bodyBytes: Int,
        totalMs: Long,
        facts: String?,
    ): String =
        "engine=$engineLabel status=${status ?: "none"} kind=${kind?.name ?: "none"} rawCode=none " +
            "detail=${detail?.toSafeDiagnosticsToken() ?: "none"} " +
            "protocol=${protocol?.toSafeDiagnosticsToken() ?: "none"} bodyBytes=$bodyBytes totalMs=$totalMs" +
            (facts?.let { " $it" } ?: "")
}

/** Ordinary requests: 30 s total like before, but a 5 s connect so a dead route fails fast. */
internal const val RAFT_KTOR_REQUEST_TIMEOUT_MILLIS = 30_000L
internal const val RAFT_KTOR_CONNECT_TIMEOUT_MILLIS = 5_000L

/**
 * Idle timeout of the one retry on a lane's fresh connections ([RaftKtorClientProvider.generation]):
 * the first attempt already spent its 20 s, so the retry must answer well inside the 30 s total.
 */
internal const val RAFT_KTOR_FRESH_SESSION_RETRY_SOCKET_TIMEOUT_MILLIS = 8_000L

/** Uploads keep their long total budgets; a stalled socket (no byte either way) still fails after 60 s. */
internal const val RAFT_KTOR_UPLOAD_SOCKET_TIMEOUT_MILLIS = 60_000L
internal const val RAFT_KTOR_UPLOAD_CONNECT_TIMEOUT_MILLIS = 10_000L

private const val KTOR_REQUEST_ID_HEADER = "x-request-id"

/** Which engine client a call runs on. Lanes ([RaftRequestPriority]) are orthogonal. */
internal enum class RaftKtorCallKind {
    /** GET / HEAD / OPTIONS: the engine may re-run it once on a fresh connection after a connection failure. */
    Idempotent,

    /** Everything else: never re-sent by the engine. */
    NonIdempotent,

    /** Presigned object-store PUT: never re-sent, separate HTTP/1.1 connection on Android. */
    PresignedUpload,

    /**
     * Multipart upload to the API: never re-sent by the engine, and on Android its own HTTP/1.1
     * connection, like [PresignedUpload]. A long upload on the API's shared HTTP/2 connection can be
     * reset mid-stream by a VPN or proxy (`RST_STREAM PROTOCOL_ERROR`, Hands a5eae49e, artin 10-29);
     * HTTP/1.1 is what such middleboxes handle best, and the upload stops sharing a stream with the
     * app's other calls.
     */
    MultipartUpload;

    fun logValue(priority: RaftRequestPriority): String =
        "${priority.name.lowercase()}_${name.lowercase()}"

    companion object {
        fun forMethod(method: String): RaftKtorCallKind =
            when (method.trim().uppercase()) {
                "GET", "HEAD", "OPTIONS" -> Idempotent
                else -> NonIdempotent
            }
    }
}

/** One Ktor [HttpClient] per lane and call kind; clients of one platform share one connection pool. */
internal fun interface RaftKtorClientProvider {
    fun client(priority: RaftRequestPriority, kind: RaftKtorCallKind): HttpClient

    /**
     * Which transport generation of [priority]'s lane [client] currently hands out. It changes when the
     * platform replaces the lane's connections ([recordOutcome]); Android never does (OkHttp pings and
     * health-checks its HTTP/2 connections), iOS does (NSURLSession has neither, Hands 43837e84).
     */
    fun generation(priority: RaftRequestPriority): Long = 0L

    /**
     * A call that ran on [generation] of [priority]'s lane finished; [timedOut] when it got no answer
     * before its timeout. The platform may replace the lane's connections in response.
     */
    fun recordOutcome(priority: RaftRequestPriority, generation: Long, timedOut: Boolean) = Unit
}

internal object RaftKtorProcessClients {
    val provider: RaftKtorClientProvider by lazy { createPlatformRaftKtorClientProvider() }
}

internal class RaftHttpOutcome(
    val status: Int,
    val headers: Map<String, List<String>>,
    val bodyText: String?,
    val protocol: String,
)

/** Calls outlive their caller's frame only until the lifetime coordinator cancels them. */
private val RaftKtorCallScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** The transport error kinds diagnostics and the reachability gate already understand (`kind=`). */
/**
 * A multipart upload ended by the connection, not by the server or a timeout: no status, an
 * unclassified network error (stream reset, connection reset or closed mid-body).
 */
internal fun RaftHttpExecutorResponse.isRetryableUploadReset(): Boolean =
    code == 0 && errorBodyText?.startsWith(RaftTransportFailureKind.UNKNOWN.errorToken) == true

internal enum class RaftTransportFailureKind(val errorToken: String) {
    TIMEOUT("network_timeout"),
    DNS("network_dns"),
    CONNECT("network_connect"),
    TLS("network_tls"),
    CANCELLED("network_cancelled"),
    UNKNOWN("network_error")
}

/** Platform exception classes first (OkHttp / java.net, NSURLError), then Ktor's common ones. */
internal fun classifyRaftKtorFailure(error: Throwable): RaftTransportFailureKind {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 6) {
        // Ktor's own timeouts first: on the JVM ConnectTimeoutException is a java.net.ConnectException.
        if (current is HttpRequestTimeoutException) return RaftTransportFailureKind.TIMEOUT
        val name = current::class.simpleName.orEmpty()
        if (name == "ConnectTimeoutException" || name == "SocketTimeoutException") return RaftTransportFailureKind.TIMEOUT
        classifyPlatformTransportFailure(current)?.let { return it }
        current = current.cause.takeIf { it !== current }
        depth += 1
    }
    return if (error is CancellationException) RaftTransportFailureKind.CANCELLED else RaftTransportFailureKind.UNKNOWN
}

/** Maps platform-specific transport exceptions; null when the throwable is not one this platform knows. */
internal expect fun classifyPlatformTransportFailure(error: Throwable): RaftTransportFailureKind?

/** "okhttp" / "darwin", reported as `engine=ktor_<label>`. */
internal expect fun raftKtorEngineLabel(): String

/**
 * Whether Ktor's `HttpResponse.version` is the protocol the call really used. OkHttp: yes. Darwin: no,
 * Ktor 3.6 hardcodes HTTP/1.1 for every NSURLSession response (DarwinTaskHandler.toResponseData), so
 * iOS logged `protocol=HTTP/1.1` for HTTP/2 and HTTP/3 calls alike (Hands 1b6dd022).
 */
internal expect fun raftKtorEngineReportsProtocol(): Boolean

/** `protocol=` when the engine does not report it; never a guessed version. */
internal const val RAFT_KTOR_PROTOCOL_UNREPORTED = "unreported"

/** Builds the process clients: Android OkHttp lanes, iOS NSURLSession lanes. */
internal expect fun createPlatformRaftKtorClientProvider(): RaftKtorClientProvider

/**
 * Connection facts the engine recorded for the request carrying this `x-request-id` (Android:
 * OkHttp EventListener; iOS: none). Each entry is returned once.
 */
internal expect fun takeRaftKtorTransportFacts(requestId: String): String?

private class KtorCallSpec(
    val logPrefix: String,
    val path: String,
    val url: String,
    val method: String,
    val priority: RaftRequestPriority,
    val kind: RaftKtorCallKind,
    val headers: Map<String, String>,
    val content: suspend () -> OutgoingContent?,
    val requestBytes: Long,
    val totalTimeoutMillis: Long,
    val connectTimeoutMillis: Long,
    val socketTimeoutMillis: Long?,
    val methodClass: RaftNetworkMethodClass,
    val redactSensitive: Boolean,
    val extraLogFields: String,
    val progress: RaftUploadProgressRoute? = null,
)

/**
 * Ktor owns Content-Type / Content-Length (setting them as plain headers throws), so they move onto
 * the body. Returns the remaining headers and the caller's content type, if it gave one.
 */
internal fun Map<String, String>.splitContentHeaders(): Pair<Map<String, String>, ContentType?> {
    var contentType: ContentType? = null
    val rest = LinkedHashMap<String, String>()
    forEach { (name, value) ->
        when {
            name.equals("Content-Type", ignoreCase = true) -> contentType = value.toContentTypeOrNull()
            name.equals("Content-Length", ignoreCase = true) -> Unit
            name.equals("Transfer-Encoding", ignoreCase = true) -> Unit
            else -> rest[name] = value
        }
    }
    return rest to contentType
}

private fun String.toContentTypeOrNull(): ContentType? =
    takeIf { it.isNotBlank() }?.let { runCatching { ContentType.parse(it) }.getOrNull() }

/** OkHttp rejects non-ASCII header values that curl passed through; never fail a request over it. */
private fun String.toSafeHeaderValue(): String =
    if (all { it == '\t' || it.code in 0x20..0x7e }) this
    else map { if (it == '\t' || it.code in 0x20..0x7e) it else '?' }.joinToString("")

private fun Map<String, String>.toSafeKtorHeaderDiagnostics(): String {
    if (isEmpty()) return "none"
    return entries
        .sortedBy { it.key.lowercase() }
        .joinToString(",") { (name, value) ->
            when (name.lowercase()) {
                "content-type", "user-agent", "accept", "x-server-id" -> "$name=${value.toSafeDiagnosticsToken()}"
                else -> "$name=present"
            }
        }
}

private fun RaftNetworkRequestLifetimeOutcome.Retired.toKtorExecutorResponse(engineLabel: String): RaftHttpExecutorResponse {
    val outcome = if (mutationOutcomeUnknown) "unknown" else "cancelled"
    val detail = if (mutationOutcomeUnknown) "mutation_outcome_unknown" else "lifecycle_retired"
    return RaftHttpExecutorResponse(
        code = 0,
        bodyText = null,
        errorBodyText = "network_request_$detail",
        transportDiagnostics =
            "engine=$engineLabel status=none kind=CANCELLED rawCode=CANCELED detail=$detail " +
                "requestGeneration=${descriptor.generation} " +
                "lifecycleGeneration=$replacementGeneration " +
                "methodClass=${descriptor.methodClass.name.lowercase()} " +
                "outcome=$outcome replay=false"
    )
}

private const val RAFT_KTOR_BODY_CHUNK_BYTES = 64 * 1024

/** Streams a platform file stream into [sink]; the stream is cancelled if writing fails. */
private suspend fun RaftByteStream.pipeChunksTo(sink: suspend (ByteArray) -> Unit) {
    try {
        readChunks(
            object : RaftByteStreamSink {
                override suspend fun write(bytes: ByteArray) {
                    sink(bytes)
                }
            }
        )
    } catch (error: Throwable) {
        cancel()
        throw error
    }
}

/** Hands a body to the engine in chunks; [sink] counts and reports progress. */
private suspend fun (suspend (ByteArray) -> Unit).writeChunked(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(bytes.size, offset + RAFT_KTOR_BODY_CHUNK_BYTES)
        this(bytes.copyOfRange(offset, end))
        offset = end
    }
}

/**
 * A request body written chunk by chunk, with a known length (presigned PUTs need Content-Length)
 * and upload progress counted as bytes are handed to the engine.
 */
internal class RaftStreamingBodyContent(
    private val declaredContentType: ContentType?,
    private val length: Long?,
    private val onProgress: ((RaftWireTransferProgress) -> Unit)?,
    private val writeBody: suspend (sink: suspend (ByteArray) -> Unit) -> Unit,
) : OutgoingContent.WriteChannelContent(), RaftChunkedBody {
    override val contentType: ContentType? get() = declaredContentType
    override val contentLength: Long? get() = length

    override suspend fun writeChunks(sink: suspend (ByteArray) -> Unit) {
        var sent = 0L
        writeBody { chunk ->
            sink(chunk)
            sent += chunk.size
            onProgress?.invoke(RaftWireTransferProgress(bytesTransferred = sent, bytesTotal = length))
        }
    }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        writeChunks { chunk -> channel.writeFully(chunk, 0, chunk.size) }
        channel.flush()
    }
}

/**
 * A streamed request body as plain chunks. Progress is reported by the body itself.
 */
internal interface RaftChunkedBody {
    val contentType: ContentType?
    val contentLength: Long?

    suspend fun writeChunks(sink: suspend (ByteArray) -> Unit)
}

/**
 * multipart/form-data written by hand so file parts stream from their [MultipartFile.streamOpener]
 * (fileRef) without loading the file, and so the total length is known up front when every part's
 * size is. Progress counts every byte of the body, framing included, like NetworkKMM did.
 */
internal class RaftMultipartContent(
    private val boundary: String,
    private val fields: Map<String, String>,
    private val files: List<MultipartFile>,
    private val onProgress: ((RaftWireTransferProgress) -> Unit)?,
) : OutgoingContent.WriteChannelContent(), RaftChunkedBody {
    override val contentType: ContentType =
        ContentType.MultiPart.FormData.withParameter("boundary", boundary)

    private val fieldParts: List<ByteArray> =
        fields.map { (name, value) ->
            ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"${name.sanitizeMultipartFileName()}\"\r\n\r\n" +
                value + "\r\n").encodeToByteArray()
        }

    private val fileHeaders: List<ByteArray> =
        files.map { file ->
            ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"${file.name.sanitizeMultipartFileName()}\"; " +
                "filename=\"${file.fileName.sanitizeMultipartFileName()}\"\r\n" +
                "Content-Type: ${file.mimeType}\r\n\r\n").encodeToByteArray()
        }

    private val closing: ByteArray = "--$boundary--\r\n".encodeToByteArray()
    private val partEnd: ByteArray = "\r\n".encodeToByteArray()

    override val contentLength: Long? =
        files.map { it.bodyLengthOrNull() }.let { sizes ->
            if (sizes.any { it == null }) {
                null
            } else {
                fieldParts.sumOf { it.size.toLong() } +
                    fileHeaders.sumOf { it.size.toLong() } +
                    sizes.sumOf { it!! } +
                    files.size * partEnd.size.toLong() +
                    closing.size
            }
        }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        writeChunks { chunk -> channel.writeFully(chunk, 0, chunk.size) }
        channel.flush()
    }

    override suspend fun writeChunks(sink: suspend (ByteArray) -> Unit) {
        var sent = 0L
        val total = contentLength
        suspend fun emit(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            sink(bytes)
            sent += bytes.size
            onProgress?.invoke(RaftWireTransferProgress(bytesTransferred = sent, bytesTotal = total))
        }
        fieldParts.forEach { emit(it) }
        files.forEachIndexed { index, file ->
            emit(fileHeaders[index])
            val opener = file.streamOpener
            if (opener != null && file.fileRef != null) {
                opener.open().pipeChunksTo { chunk -> emit(chunk) }
            } else {
                val sink: suspend (ByteArray) -> Unit = { emit(it) }
                sink.writeChunked(file.bytes)
            }
            emit(partEnd)
        }
        emit(closing)
    }

    private fun MultipartFile.bodyLengthOrNull(): Long? =
        if (streamOpener != null && fileRef != null) sizeBytes else bytes.size.toLong()
}

/** Plugin set shared by every platform client: no throwing on HTTP errors, Ktor-owned timeouts. */
internal fun io.ktor.client.HttpClientConfig<*>.configureRaftKtorClient() {
    expectSuccess = false
    followRedirects = true
    install(io.ktor.client.plugins.HttpTimeout)
}

/**
 * One upload's progress route. Exactly one of [bodyWriterProgress] (count bytes as the body writer
 * hands them to the engine) and [networkHeader] (an internal marker header the platform engine strips
 * and uses to count bytes written to the connection) is set when a listener exists.
 */
internal class RaftUploadProgressRoute(
    val bodyWriterProgress: ((RaftWireTransferProgress) -> Unit)?,
    val networkHeader: Pair<String, String>?,
    val release: () -> Unit,
)

internal fun interface RaftUploadProgressTracker {
    fun route(listener: ((RaftWireTransferProgress) -> Unit)?): RaftUploadProgressRoute
}

/** Counts at the body writer: right when the engine consumes the body directly (tests, iOS). */
internal val BodyWriterUploadProgressTracker =
    RaftUploadProgressTracker { listener -> RaftUploadProgressRoute(listener, null) {} }

/**
 * Android: bytes written to the OkHttp connection (HTTP/2 flow control / socket backpressure), not to
 * Ktor's ~1 MB body channel, which made a picked photo read 100% at once (Hands e9baeedb).
 * iOS: the body writer (NSURLSession streams the body itself).
 */
internal expect fun platformRaftUploadProgressTracker(): RaftUploadProgressTracker
