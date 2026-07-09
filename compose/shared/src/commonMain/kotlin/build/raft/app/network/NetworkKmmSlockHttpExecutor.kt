package build.raft.app.network

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkMultipartPart
import com.tencent.kmm.network.export.NetworkPriority
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.VBTransportResultCode
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.IVBPBLog
import com.tencent.kmm.network.export.VBTransportInitConfig
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.VBTransportInitHelper
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * All platforms route HTTP through the KuiklyBase NetworkKMM client
 * (task #12): OHOS because Ktor CIO has no native TLS there (task #6),
 * Android/iOS so the app has one explicit network stack with pooling
 * owned by the infra library (raft.4).
 */
internal fun createPlatformSlockHttpExecutor(
    warningLogger: (String) -> Unit
): SlockHttpExecutor = NetworkKmmSlockHttpExecutor(warningLogger)

private const val SLOCK_REQUEST_TIMEOUT_MILLIS = 30_000L

@Volatile
private var networkKmmInitialized = false

internal fun ensureNetworkKmmInitialized(warningLogger: (String) -> Unit) {
    if (networkKmmInitialized) return
    try {
        VBTransportInitHelper.init(
            VBTransportInitConfig(
                logImpl =
                    object : IVBPBLog {
                        override fun d(tag: String?, content: String?) = Unit
                        override fun i(tag: String?, content: String?) {
                            warningLogger(
                                "slock_networkkmm_info tag=${tag.orEmpty()} " +
                                    content.orEmpty().toSafeNetworkKmmLogMessage()
                            )
                        }

                        override fun e(tag: String?, content: String?, throwable: Throwable?) {
                            warningLogger(
                                "slock_networkkmm_error tag=${tag.orEmpty()} " +
                                    content.orEmpty().toSafeNetworkKmmLogMessage() +
                                    throwable?.let { " ${it.safeDiagnosticsSummary()}" }.orEmpty()
                            )
                        }
                    }
            )
        )
        networkKmmInitialized = true
    } catch (error: Exception) {
        warningLogger("slock_networkkmm_init_failed ${error.safeDiagnosticsSummary()}")
    }
}

internal class NetworkKmmSlockHttpExecutor(
    private val warningLogger: (String) -> Unit = {}
) : SlockHttpExecutor {

    private val client: NetworkClient by lazy {
        ensureNetworkKmmInitialized(warningLogger)
        NetworkClient(
            NetworkClientConfig(
                defaultPolicy = NetworkRequestPolicy(timeoutMillis = SLOCK_REQUEST_TIMEOUT_MILLIS)
            )
        )
    }

    override suspend fun execute(
        priority: SlockRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): SlockHttpExecutorResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val path = url.substringBefore('?')
        val requestBytes = bodyText?.encodeToByteArray()?.size ?: 0
        warningLogger(
            "slock_networkkmm_execute_start path=$path method=${method.uppercase()} " +
                "priority=$priority jsonBody=$jsonBody requestBytes=$requestBytes " +
                "headers=${headers.toSafeHeaderDiagnostics()}"
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
                        NetworkRequestPolicy(
                            timeoutMillis = SLOCK_REQUEST_TIMEOUT_MILLIS,
                            priority = priority.toNetworkKmmPriority()
                        )
                )
            headers.forEach { (name, value) -> request.setHeader(name, value) }
            val response = client.execute(request).toExecutorResponse()
            warningLogger(
                "slock_networkkmm_execute_finish path=$path method=${method.uppercase()} " +
                    "priority=$priority jsonBody=$jsonBody requestBytes=$requestBytes " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
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
            SlockHttpExecutorResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }
    }

    override suspend fun executeMultipart(
        priority: SlockRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>
    ): SlockHttpExecutorResponse {
        val startedAt = TimeSource.Monotonic.markNow()
        val path = url.substringBefore('?')
        val requestBytes = files.sumOf { it.bytes.size }
        warningLogger(
            "slock_networkkmm_multipart_start path=$path method=POST priority=$priority " +
                "fields=${fields.size} files=${files.size} requestBytes=$requestBytes " +
                "headers=${headers.toSafeHeaderDiagnostics()}"
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
                                            body = NetworkBody.Bytes(file.bytes),
                                            headers = mapOf("Content-Type" to file.mimeType)
                                        )
                                    }
                        ),
                    policy =
                        NetworkRequestPolicy(
                            timeoutMillis = SLOCK_REQUEST_TIMEOUT_MILLIS,
                            priority = priority.toNetworkKmmPriority()
                        )
                )
            headers.forEach { (name, value) -> request.setHeader(name, value) }
            val response = client.execute(request).toExecutorResponse()
            warningLogger(
                "slock_networkkmm_multipart_finish path=$path method=POST priority=$priority " +
                    "fields=${fields.size} files=${files.size} requestBytes=$requestBytes " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
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
            SlockHttpExecutorResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }
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

private fun SlockRequestPriority.toNetworkKmmPriority(): NetworkPriority =
    when (this) {
        SlockRequestPriority.Foreground -> NetworkPriority.HIGH
        SlockRequestPriority.Visible -> NetworkPriority.NORMAL
        SlockRequestPriority.Background -> NetworkPriority.LOW
    }

private fun NetworkResponse.toExecutorResponse(): SlockHttpExecutorResponse {
    val status = statusCode
    val diagnostics = networkKmmDiagnostics()
    if (status == null) {
        return SlockHttpExecutorResponse(
            code = 0,
            bodyText = null,
            errorBodyText = error?.message ?: "network_kmm_no_status",
            transportDiagnostics = diagnostics
        )
    }
    val text = body.text()
    return if (status in 200..299) {
        SlockHttpExecutorResponse(code = status, bodyText = text, errorBodyText = null, transportDiagnostics = diagnostics)
    } else {
        SlockHttpExecutorResponse(
            code = status,
            bodyText = null,
            errorBodyText = text ?: error?.message,
            transportDiagnostics = diagnostics
        )
    }
}

private fun NetworkResponse.networkKmmDiagnostics(): String {
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
        listOfNotNull(error?.message, rawResponse?.errorMessage)
            .firstOrNull { it.isNotBlank() }
            ?.toSafeDiagnosticsToken()
            ?: "none"
    return "engine=networkkmm status=${statusCode ?: "none"} " +
        "kind=${error?.kind ?: "none"} rawCode=$rawCodeLabel detail=$detail " +
        "bodyBytes=${body.bytes?.size ?: 0} errorStatus=${error?.statusCode ?: "none"} " +
        "totalMs=${timing.totalTimeMs}"
}

internal fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")
