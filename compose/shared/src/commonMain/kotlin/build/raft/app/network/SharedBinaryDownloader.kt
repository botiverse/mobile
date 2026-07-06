package build.raft.app.network

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig

data class SharedDownloadedBytes(
    val bytes: ByteArray,
    val contentType: String?
)

/**
 * Binary downloads over NetworkKMM (task #12; formerly Ktor).
 *
 * NOTE: NetworkKMM does not stream response bodies yet (fork issue #8), so
 * [download] receives the full payload and re-emits it in chunks to preserve
 * the caller's progress contract. True incremental streaming lands with #8.
 */
object SharedBinaryDownloader {
    private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
    private const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L

    private val client: NetworkClient by lazy {
        NetworkClient(
            NetworkClientConfig(
                defaultPolicy = NetworkRequestPolicy(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS)
            )
        )
    }

    suspend fun fetchBytes(url: String): SharedDownloadedBytes {
        val response = fetch(url)
        return SharedDownloadedBytes(
            bytes = response.bytes,
            contentType = response.contentType
        )
    }

    suspend fun download(
        url: String,
        onStart: (totalBytes: Long?) -> Unit,
        beforeRead: () -> Unit = {},
        onChunk: (bytes: ByteArray, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): String? {
        val response = fetch(url, failurePrefix = "Attachment download failed")
        val bytes = response.bytes
        val totalBytes = bytes.size.toLong().takeIf { it > 0L }
        onStart(totalBytes)
        var offset = 0
        var downloadedBytes = 0L
        while (offset < bytes.size) {
            beforeRead()
            val end = minOf(offset + DOWNLOAD_BUFFER_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            downloadedBytes += chunk.size.toLong()
            onChunk(chunk, downloadedBytes, totalBytes)
            offset = end
        }
        return response.contentType
    }

    private class FetchedBytes(val bytes: ByteArray, val contentType: String?)

    private suspend fun fetch(url: String, failurePrefix: String = "Download failed"): FetchedBytes {
        val response =
            client.execute(
                NetworkRequest(
                    method = VBTransportMethod.GET,
                    url = url,
                    body = NetworkBody.Empty,
                    policy = NetworkRequestPolicy(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS)
                )
            )
        val status = response.statusCode
        if (status == null || status !in 200..299) {
            throw IllegalStateException(
                "$failurePrefix: HTTP ${status ?: 0}${response.error?.message?.let { " ($it)" } ?: ""}"
            )
        }
        return FetchedBytes(
            bytes = response.body.bytes ?: ByteArray(0),
            contentType = response.contentTypeHeader()
        )
    }

    private fun com.tencent.kmm.network.export.NetworkResponse.contentTypeHeader(): String? =
        headers.entries
            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
