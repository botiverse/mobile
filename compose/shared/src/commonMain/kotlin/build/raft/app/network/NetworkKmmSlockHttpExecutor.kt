package build.raft.app.network

import build.raft.app.slockPlatformNeedsExplicitGzipAcceptEncoding
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkMultipartPart
import com.tencent.kmm.network.export.NetworkPriority
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.export.IVBPBLog
import com.tencent.kmm.network.export.VBTransportInitConfig
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.VBTransportInitHelper
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

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

private fun ensureNetworkKmmInitialized(warningLogger: (String) -> Unit) {
    if (networkKmmInitialized) return
    try {
        VBTransportInitHelper.init(
            VBTransportInitConfig(
                logImpl =
                    object : IVBPBLog {
                        override fun d(tag: String?, content: String?) = Unit
                        override fun i(tag: String?, content: String?) = Unit
                        override fun e(tag: String?, content: String?, throwable: Throwable?) {
                            warningLogger("slock_networkkmm tag=${tag.orEmpty()} ${content.orEmpty().take(160)}")
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
    ): SlockHttpExecutorResponse =
        try {
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
            request.applyGzipAcceptEncoding(headers)
            client.execute(request).toExecutorResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_networkkmm_failed path=${url.substringBefore('?')} " +
                    "method=$method ${error.safeDiagnosticsSummary()}"
            )
            SlockHttpExecutorResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }

    override suspend fun executeMultipart(
        priority: SlockRequestPriority,
        url: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>
    ): SlockHttpExecutorResponse =
        try {
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
            request.applyGzipAcceptEncoding(headers)
            client.execute(request).toExecutorResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_networkkmm_failed path=${url.substringBefore('?')} " +
                    "method=multipart ${error.safeDiagnosticsSummary()}"
            )
            SlockHttpExecutorResponse(code = 0, bodyText = null, errorBodyText = error.message)
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
    if (status == null) {
        return SlockHttpExecutorResponse(
            code = 0,
            bodyText = null,
            errorBodyText = error?.message ?: "network_kmm_no_status"
        )
    }
    val text = body.text()
    return if (status in 200..299) {
        SlockHttpExecutorResponse(code = status, bodyText = text, errorBodyText = null)
    } else {
        SlockHttpExecutorResponse(code = status, bodyText = null, errorBodyText = text ?: error?.message)
    }
}

internal fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")

// OHOS only: NetworkKMM's libcurl wrapper decompresses gzip only when the
// request opts in with this header; Android/iOS negotiate gzip transparently
// and must NOT receive it (see slockPlatformNeedsExplicitGzipAcceptEncoding).
private fun NetworkRequest.applyGzipAcceptEncoding(callerHeaders: Map<String, String>) {
    resolveGzipAcceptEncoding(slockPlatformNeedsExplicitGzipAcceptEncoding(), callerHeaders)
        ?.let { setHeader("Accept-Encoding", it) }
}

/**
 * The Accept-Encoding value to inject, or null to leave the request untouched.
 * Only injects on platforms that need the explicit opt-in, and never overrides
 * an Accept-Encoding the caller already set (case-insensitive).
 */
internal fun resolveGzipAcceptEncoding(
    platformNeedsExplicit: Boolean,
    callerHeaders: Map<String, String>
): String? =
    if (platformNeedsExplicit &&
        callerHeaders.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }
    ) {
        "gzip"
    } else {
        null
    }
