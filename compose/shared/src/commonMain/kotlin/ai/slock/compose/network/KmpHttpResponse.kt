package ai.slock.compose.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

data class KmpHttpResponse(
    val code: Int,
    val bodyText: String?,
    val errorBodyText: String?,
    val headers: Map<String, List<String>> = emptyMap()
) {
    val isSuccessful: Boolean
        get() = code in 200..299

    val payloadText: String?
        get() = bodyText ?: errorBodyText
}

suspend fun HttpResponse.toKmpHttpResponse(): KmpHttpResponse {
    val rawBody = bodyAsText()
    val responseHeaders = headers.names().associateWith { name ->
        headers.getAll(name).orEmpty()
    }
    return if (status.value in 200..299) {
        KmpHttpResponse(
            code = status.value,
            bodyText = rawBody,
            errorBodyText = null,
            headers = responseHeaders
        )
    } else {
        KmpHttpResponse(
            code = status.value,
            bodyText = null,
            errorBodyText = rawBody,
            headers = responseHeaders
        )
    }
}
