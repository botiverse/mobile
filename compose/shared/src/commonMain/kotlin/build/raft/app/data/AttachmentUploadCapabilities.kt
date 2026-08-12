package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val attachmentUploadCapabilitiesJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class AttachmentUploadCapabilitiesDto(
    val maxBytes: Long
)

internal fun parseSharedAttachmentUploadCapabilities(bodyText: String): AttachmentUploadCapabilities {
    val dto = attachmentUploadCapabilitiesJson.decodeFromString<AttachmentUploadCapabilitiesDto>(bodyText)
    require(dto.maxBytes > 0L) { "attachment upload maxBytes must be positive" }
    return AttachmentUploadCapabilities(maxBytes = dto.maxBytes)
}
