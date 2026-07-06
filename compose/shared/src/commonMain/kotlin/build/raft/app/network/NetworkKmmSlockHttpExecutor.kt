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
