package ai.slock.compose.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

data class SharedDownloadedBytes(
    val bytes: ByteArray,
    val contentType: String?
)

object SharedKtorBinaryDownloader {
    private val client: HttpClient =
        createSlockKtorHttpClient(
            priority = SlockRequestPriority.Background,
            config =
                HttpClientConfig(
                    timeout =
                        TimeoutConfig(
                            requestTimeoutMillis = 60_000,
                            connectTimeoutMillis = 30_000,
                            socketTimeoutMillis = 60_000
                        )
                )
        )

    suspend fun fetchBytes(url: String): SharedDownloadedBytes {
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Download failed: HTTP ${response.status.value}")
        }
        return SharedDownloadedBytes(
            bytes = response.body(),
            contentType = response.contentTypeHeader()
        )
    }

    suspend fun download(
        url: String,
        onStart: (totalBytes: Long?) -> Unit,
        beforeRead: () -> Unit = {},
        onChunk: (bytes: ByteArray, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): String? {
        val response =
            client.get(url) {
                header(HttpHeaders.Accept, "*/*")
            }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Attachment download failed: HTTP ${response.status.value}")
        }
        val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0L }
        onStart(totalBytes)
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloadedBytes = 0L
        while (true) {
            beforeRead()
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            downloadedBytes += read.toLong()
            onChunk(buffer.copyOf(read), downloadedBytes, totalBytes)
        }
        return response.contentTypeHeader()
    }

    private fun io.ktor.client.statement.HttpResponse.contentTypeHeader(): String? =
        headers[HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
