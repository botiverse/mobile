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
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkByteStreamSink
import io.ktor.utils.io.ByteWriteChannel
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
 * Same contract as [NetworkKmmRaftHttpExecutor] (see [RaftHttpExecutor]): transport failures map to
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
        val traceHeaders = networkKmmTraceHeaders(headers)
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
        onUploadProgress: ((RaftTransferProgress) -> Unit)?
    ): RaftHttpExecutorResponse {
        val traceHeaders = networkKmmTraceHeaders(headers)
        val (requestHeaders, _) = withRaftApiClientHeaders(traceHeaders).splitContentHeaders()
        val streaming = files.hasStreamingFileRefParts()
        val boundary = "RaftBoundary" + Random.nextBytes(12).joinToString("") { byte ->
            byte.toUByte().toString(radix = 16).padStart(2, '0')
        }
        return perform(
            KtorCallSpec(
                logPrefix = "slock_http_multipart",
                path = url.substringBefore('?'),
                url = url,
                method = "POST",
                priority = priority,
                kind = RaftKtorCallKind.NonIdempotent,
                headers = requestHeaders,
                content = {
                    RaftMultipartContent(
                        boundary = boundary,
                        fields = fields,
                        files = files,
                        onProgress = onUploadProgress,
                    )
                },
                requestBytes = files.sumOf { file -> file.bytes.size.toLong().takeIf { it > 0L } ?: file.sizeBytes ?: 0L },
                totalTimeoutMillis = if (streaming) RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS else RAFT_KTOR_REQUEST_TIMEOUT_MILLIS,
                connectTimeoutMillis = RAFT_KTOR_CONNECT_TIMEOUT_MILLIS,
                socketTimeoutMillis = RAFT_KTOR_UPLOAD_SOCKET_TIMEOUT_MILLIS,
                methodClass = RaftNetworkMethodClass.ProcessMutation,
                redactSensitive = false,
                extraLogFields = "fields=${fields.size} files=${files.size} streaming=$streaming",
            )
        )
    }

    override suspend fun executeBytes(
        priority: RaftRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        onUploadProgress: ((RaftTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse {
        val traceHeaders = networkKmmTraceHeaders(headers)
        val (requestHeaders, contentType) = traceHeaders.splitContentHeaders()
        return perform(
            presignedSpec(
                bodyKind = "bytes",
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
                        onProgress = onUploadProgress,
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
        onUploadProgress: ((RaftTransferProgress) -> Unit)?,
    ): RaftHttpExecutorResponse {
        val traceHeaders = networkKmmTraceHeaders(headers)
        val (requestHeaders, contentType) = traceHeaders.splitContentHeaders()
        return perform(
            presignedSpec(
                bodyKind = "file",
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
                        onProgress = onUploadProgress,
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
        )

    private suspend fun perform(spec: KtorCallSpec): RaftHttpExecutorResponse {
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

    /** One transport attempt. Never throws except for cancellation of its own job. */
    private suspend fun runCall(
        spec: KtorCallSpec,
        clientRequestId: String,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
    ): RaftHttpExecutorResponse =
        try {
            val content = spec.content()
            val response: HttpResponse =
                clients.client(spec.priority, spec.kind).request(spec.url) {
                    method = HttpMethod.parse(spec.method)
                    headers { spec.headers.forEach { (name, value) -> append(name, value.toSafeHeaderValue()) } }
                    if (content != null) setBody(content)
                    timeout {
                        requestTimeoutMillis = spec.totalTimeoutMillis
                        connectTimeoutMillis = spec.connectTimeoutMillis
                        spec.socketTimeoutMillis?.let { socketTimeoutMillis = it }
                    }
                }
            val status = response.status.value
            val text = if (spec.redactSensitive) null else response.bodyAsText()
            val responseHeaders =
                if (spec.redactSensitive) emptyMap()
                else response.headers.entries().associate { (name, values) -> name to values }
            val diagnostics =
                ktorDiagnostics(
                    status = status,
                    kind = null,
                    detail = null,
                    protocol = response.version.toString(),
                    bodyBytes = text?.encodeToByteArray()?.size ?: 0,
                    totalMs = startedAt.elapsedNow().inWholeMilliseconds,
                    facts = transportFacts(clientRequestId),
                )
            when {
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
        } catch (error: Throwable) {
            val kind = classifyRaftKtorFailure(error)
            // A Ktor request timeout is a CancellationException subtype on some paths; only a real
            // cancellation of this attempt's job (caller gone or lifecycle retirement) is rethrown.
            if (kind == RaftTransportFailureKind.CANCELLED && error is CancellationException) throw error
            RaftHttpExecutorResponse(
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
    PresignedUpload;

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
}

internal object RaftKtorProcessClients {
    val provider: RaftKtorClientProvider by lazy { createPlatformRaftKtorClientProvider() }
}

/** Calls outlive their caller's frame only until the lifetime coordinator cancels them. */
private val RaftKtorCallScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** The transport error kinds diagnostics and the reachability gate already understand (`kind=`). */
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

/** Streams a NetworkKMM file stream into [sink]; the stream is cancelled if writing fails. */
private suspend fun NetworkByteStream.pipeChunksTo(sink: suspend (ByteArray) -> Unit) {
    try {
        readChunks(
            object : NetworkByteStreamSink {
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
    private val onProgress: ((RaftTransferProgress) -> Unit)?,
    private val writeBody: suspend (sink: suspend (ByteArray) -> Unit) -> Unit,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType? get() = declaredContentType
    override val contentLength: Long? get() = length

    override suspend fun writeTo(channel: ByteWriteChannel) {
        var sent = 0L
        writeBody { chunk ->
            channel.writeFully(chunk, 0, chunk.size)
            sent += chunk.size
            onProgress?.invoke(RaftTransferProgress(bytesTransferred = sent, bytesTotal = length))
        }
        channel.flush()
    }
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
    private val onProgress: ((RaftTransferProgress) -> Unit)?,
) : OutgoingContent.WriteChannelContent() {
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
        var sent = 0L
        val total = contentLength
        suspend fun emit(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            channel.writeFully(bytes, 0, bytes.size)
            sent += bytes.size
            onProgress?.invoke(RaftTransferProgress(bytesTransferred = sent, bytesTotal = total))
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
        channel.flush()
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
