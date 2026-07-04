package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * Ktor-backed [SlockHttpExecutor]: one client per priority lane so engine-level
 * dispatcher/connection caps keep foreground traffic ahead of background work.
 * Used by the Android (OkHttp) and iOS (Darwin) actuals.
 */
internal class KtorSlockHttpExecutor(
    private val warningLogger: (String) -> Unit = {},
    private val foregroundHttpClient: HttpClient = createSlockKtorHttpClient(SlockRequestPriority.Foreground),
    private val visibleHttpClient: HttpClient = createSlockKtorHttpClient(SlockRequestPriority.Visible),
    private val backgroundHttpClient: HttpClient = createSlockKtorHttpClient(SlockRequestPriority.Background)
) : SlockHttpExecutor {

    override suspend fun execute(
        priority: SlockRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): SlockHttpExecutorResponse =
        try {
            priority.httpClient().request {
                url(url)
                this.method = HttpMethod(method)
                headers.forEach { (name, value) -> header(name, value) }
                if (bodyText != null) {
                    if (jsonBody) contentType(ContentType.Application.Json)
                    setBody(bodyText)
                }
            }.toExecutorResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_shared_ktor_failed path=${url.substringBefore('?')} " +
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
            priority.httpClient().submitFormWithBinaryData(
                url = url,
                formData =
                    formData {
                        fields.forEach { (key, value) -> append(key, value) }
                        files.forEach { file ->
                            append(
                                key = file.name,
                                value = file.bytes,
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentType, file.mimeType)
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "filename=\"${file.fileName.sanitizeMultipartFileName()}\""
                                        )
                                    }
                            )
                        }
                    }
            ) {
                headers.forEach { (name, value) -> header(name, value) }
            }.toExecutorResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_shared_ktor_failed path=${url.substringBefore('?')} " +
                    "method=multipart ${error.safeDiagnosticsSummary()}"
            )
            SlockHttpExecutorResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }

    private fun SlockRequestPriority.httpClient(): HttpClient =
        when (this) {
            SlockRequestPriority.Foreground -> foregroundHttpClient
            SlockRequestPriority.Visible -> visibleHttpClient
            SlockRequestPriority.Background -> backgroundHttpClient
        }
}

private suspend fun HttpResponse.toExecutorResponse(): SlockHttpExecutorResponse {
    val body = bodyAsText()
    return if (status.value in 200..299) {
        SlockHttpExecutorResponse(code = status.value, bodyText = body, errorBodyText = null)
    } else {
        SlockHttpExecutorResponse(code = status.value, bodyText = null, errorBodyText = body)
    }
}

internal fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")
