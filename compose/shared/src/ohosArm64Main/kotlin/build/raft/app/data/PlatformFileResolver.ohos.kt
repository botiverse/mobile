package build.raft.app.data

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val SlockOhosPlatformFileReaderService = "slockOhosPlatformFileReader"

@ServiceConsumer(name = SlockOhosPlatformFileReaderService)
interface SlockOhosPlatformFileReader {
    fun readFile(fileRef: String): Map<String, Any?>
}

internal actual fun createDefaultPlatformFileResolver(): SlockPlatformFileResolver? =
    SlockOhosKnoiPlatformFileResolver()

private class SlockOhosKnoiPlatformFileResolver : SlockPlatformFileResolver {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult {
        val response =
            runCatching {
                KNOI.get<SlockOhosPlatformFileReader>(SlockOhosPlatformFileReaderService)
                    .readFile(fileRef)
            }.getOrElse { error ->
                return SlockPlatformFileReadResult(
                    code = 400,
                    bytes = ByteArray(0),
                    errorCode = "attachment_file_reader_unavailable",
                    message = error.message ?: "Platform file reader unavailable"
                )
            }
        val code = response.numberValue("code")?.toInt()
            ?: if (response["status"] == "ok") 200 else 400
        val encoded = response["bodyBase64"] as? String
        val bytes =
            encoded
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Base64.decode(it) }.getOrNull() }
                ?: ByteArray(0)
        return SlockPlatformFileReadResult(
            code = code,
            bytes = bytes,
            sizeBytes = response.numberValue("sizeBytes")?.toLong()?.takeIf { it > 0L },
            errorCode = (response["errorCode"] as? String).orEmpty(),
            message =
                (response["errorBodyText"] as? String)
                    ?: (response["message"] as? String)
                    ?: ""
        )
    }

    private fun Map<String, Any?>.numberValue(key: String): Number? =
        when (val value = this[key]) {
            is Number -> value
            is String -> value.toLongOrNull()
            else -> null
        }
}
