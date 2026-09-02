package build.raft.app.data

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val SlockOhosPlatformFileReaderService = "slockOhosPlatformFileReader"

@ServiceConsumer(name = SlockOhosPlatformFileReaderService)
interface SlockOhosPlatformFileReader {
    fun readFile(fileRef: String): Map<String, Any?>
    fun statFile(fileRef: String): Map<String, Any?>
    fun openFileStream(fileRef: String, expectedSizeBytes: Long): Map<String, Any?>
    fun readFileChunk(streamId: String, maxBytes: Int): Map<String, Any?>
    fun closeFileStream(streamId: String): Map<String, Any?>
}

internal actual fun createDefaultPlatformFileResolver(): SlockPlatformFileResolver? =
    SlockOhosKnoiPlatformFileResolver()

private class SlockOhosKnoiPlatformFileResolver : SlockPlatformFileResolver {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult {
        val response = callReader { it.readFile(fileRef) }
            ?: return SlockPlatformFileReadResult(
                code = 400,
                bytes = ByteArray(0),
                errorCode = "attachment_file_reader_unavailable",
                message = "Platform file reader unavailable"
            )
        val encoded = response["bodyBase64"] as? String
        val bytes =
            encoded
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Base64.decode(it) }.getOrNull() }
                ?: ByteArray(0)
        return SlockPlatformFileReadResult(
            code = response.responseCode(),
            bytes = bytes,
            sizeBytes = response.numberValue("sizeBytes")?.toLong()?.takeIf { it > 0L },
            errorCode = (response["errorCode"] as? String).orEmpty(),
            message = response.errorMessage()
        )
    }

    override suspend fun statFile(fileRef: String): SlockPlatformFileStatResult {
        val response = callReader { it.statFile(fileRef) }
            ?: return unavailable()
        return response.toStatResult()
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun openFileStream(
        fileRef: String,
        expectedSizeBytes: Long
    ): SlockPlatformFileChunkSource {
        val reader = KNOI.get<SlockOhosPlatformFileReader>(SlockOhosPlatformFileReaderService)
        val opened = reader.openFileStream(fileRef, expectedSizeBytes)
        opened.requireSuccess()
        val streamId = (opened["streamId"] as? String)?.takeIf { it.isNotBlank() }
            ?: error("Platform file stream did not return an id")
        val actualSizeBytes = opened.numberValue("sizeBytes")?.toLong()
        if (actualSizeBytes != expectedSizeBytes) {
            runCatching { reader.closeFileStream(streamId) }
            error("Attachment size changed: expected=$expectedSizeBytes actual=${actualSizeBytes ?: "unknown"}")
        }
        return object : SlockPlatformFileChunkSource {
            override suspend fun read(maxBytes: Int): ByteArray {
                val chunk = reader.readFileChunk(streamId, maxBytes)
                chunk.requireSuccess()
                return chunk.decodeChunk()
            }

            override fun close() {
                runCatching { reader.closeFileStream(streamId) }
            }
        }
    }

    private fun callReader(
        block: (SlockOhosPlatformFileReader) -> Map<String, Any?>
    ): Map<String, Any?>? =
        try {
            block(KNOI.get<SlockOhosPlatformFileReader>(SlockOhosPlatformFileReaderService))
        } catch (cancellation: CancellationException) {
            // A canceled stat/read belongs to the caller's coroutine lifecycle.  Do not turn it
            // into an "unavailable" result, otherwise the direct-upload coordinator cannot
            // propagate cancellation into its NonCancellable compensation path.
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private fun unavailable(): SlockPlatformFileStatResult =
        SlockPlatformFileStatResult(
            code = 400,
            errorCode = "attachment_file_reader_unavailable",
            message = "Platform file reader unavailable"
        )

    private fun Map<String, Any?>.toStatResult(): SlockPlatformFileStatResult =
        SlockPlatformFileStatResult(
            code = responseCode(),
            sizeBytes = numberValue("sizeBytes")?.toLong()?.takeIf { it > 0L },
            errorCode = (this["errorCode"] as? String).orEmpty(),
            message = errorMessage()
        )

    @OptIn(ExperimentalEncodingApi::class)
    private fun Map<String, Any?>.decodeChunk(): ByteArray {
        val encoded = (this["bodyBase64"] as? String).orEmpty()
        if (encoded.isBlank()) return ByteArray(0)
        val bytes = Base64.decode(encoded)
        val declared = numberValue("readBytes")?.toInt()
        if (declared != null && declared != bytes.size) {
            error("Platform file chunk length mismatch")
        }
        return bytes
    }

    private fun Map<String, Any?>.requireSuccess() {
        if (responseCode() !in 200..299 || this["status"] == "error") {
            error(errorMessage().ifBlank { "Platform file stream failed" })
        }
    }

    private fun Map<String, Any?>.responseCode(): Int =
        numberValue("code")?.toInt() ?: if (this["status"] == "ok") 200 else 400

    private fun Map<String, Any?>.errorMessage(): String =
        (this["errorBodyText"] as? String)
            ?: (this["message"] as? String)
            ?: ""

    private fun Map<String, Any?>.numberValue(key: String): Number? =
        when (val value = this[key]) {
            is Number -> value
            is String -> value.toLongOrNull()
            else -> null
        }
}
