package build.raft.app.network

fun interface MultipartFileStreamOpener {
    suspend fun open(): RaftByteStream
}

/**
 * Represents a file to be uploaded in a multipart form
 */
data class MultipartFile(
    val name: String,           // form field name (e.g., "file")
    val fileName: String,       // file name (e.g., "image.png")
    val mimeType: String,       // MIME type (e.g., "image/png")
    val bytes: ByteArray = ByteArray(0),
    val fileRef: String? = null,
    val sizeBytes: Long? = null,
    val streamOpener: MultipartFileStreamOpener? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MultipartFile

        if (name != other.name) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (fileRef != other.fileRef) return false
        if (sizeBytes != other.sizeBytes) return false
        if ((streamOpener == null) != (other.streamOpener == null)) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (fileRef?.hashCode() ?: 0)
        result = 31 * result + (sizeBytes?.hashCode() ?: 0)
        result = 31 * result + (streamOpener != null).hashCode()
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
