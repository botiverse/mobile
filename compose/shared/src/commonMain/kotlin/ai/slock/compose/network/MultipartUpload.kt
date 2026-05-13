package ai.slock.compose.network

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Represents a file to be uploaded in a multipart form
 */
data class MultipartFile(
    val name: String,           // form field name (e.g., "file")
    val fileName: String,       // file name (e.g., "image.png")
    val mimeType: String,       // MIME type (e.g., "image/png")
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MultipartFile

        if (name != other.name) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * Represents multipart form data with files and additional fields
 */
data class MultipartFormData(
    val files: List<MultipartFile>,
    val fields: Map<String, String> = emptyMap()  // other form fields (e.g., "channelId")
)

/**
 * Submits multipart form data to the specified URL
 */
suspend fun HttpClient.submitMultipart(
    url: String,
    formData: MultipartFormData
): HttpResponse {
    return submitFormWithBinaryData(
        url = url,
        formData = formData {
            // Add text fields
            formData.fields.forEach { (key, value) ->
                append(key, value)
            }

            // Add file parts
            formData.files.forEach { file ->
                append(
                    key = file.name,
                    value = file.bytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, file.mimeType)
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${file.fileName}\""
                        )
                    }
                )
            }
        }
    )
}
