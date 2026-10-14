package build.raft.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The only diagnostics archive type accepted by the Hands feedback presign route. */
const val FEEDBACK_DIAGNOSTICS_CONTENT_TYPE: String = "application/zip"

/** Matches the direct-to-R2 limit enforced by Hands' feedback route. */
const val FEEDBACK_DIAGNOSTICS_MAX_BYTES: Long = 200L * 1024L * 1024L

private val feedbackDiagnosticsPresignJson = Json {
    // The route may add response metadata over time, but required fields and their names are
    // still decoded by these DTOs.  We intentionally do not try snake/camel aliases or envelope
    // probing here: this is the one canonical Hands feedback presign contract.
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** One item in the request body accepted by POST `/public/v2/apps/:slug/feedback/presign`. */
@Serializable
data class FeedbackDiagnosticsPresignFileRequestDto(
    val filename: String,
    @SerialName("content_type")
    val contentType: String,
    val size: Long,
)

/** Canonical request envelope for the Hands feedback presign endpoint. */
@Serializable
data class FeedbackDiagnosticsPresignRequestDto(
    val files: List<FeedbackDiagnosticsPresignFileRequestDto>,
)

/** One presigned upload returned by the Hands feedback route. */
@Serializable
data class FeedbackDiagnosticsPresignedUploadDto(
    @SerialName("attachment_id")
    val attachmentId: String,
    @SerialName("r2_key")
    val r2Key: String,
    @SerialName("upload_url")
    val uploadUrl: String,
    @SerialName("expires_at")
    val expiresAt: Long,
)

/** Canonical response envelope for the Hands feedback presign endpoint. */
@Serializable
data class FeedbackDiagnosticsPresignResponseDto(
    val uploads: List<FeedbackDiagnosticsPresignedUploadDto>,
)

/** Native-only upload capability extracted from the typed presign response. */
data class FeedbackDiagnosticsPresignedUpload(
    val attachmentId: String,
    val r2Key: String,
    val uploadUrl: String,
    val expiresAt: Long,
)

/** Signed archive uploads must remain HTTPS and may not contain header-injection characters. */
fun isValidFeedbackDiagnosticsUploadUrl(value: String): Boolean =
    value.startsWith("https://") &&
        value.length > "https://".length &&
        value.removePrefix("https://")
            .substringBefore('/').substringBefore('?').substringBefore('#').isNotBlank() &&
        value.none { it.code < 0x20 || it.code == 0x7f || it.isWhitespace() }

/**
 * Build the exact Hands presign request body.  Keep this in shared code so every platform uses
 * the same wire fields and validation before handing the body to its native HTTP transport.
 */
fun encodeSharedFeedbackDiagnosticsPresignRequest(
    filename: String,
    contentType: String,
    size: Long,
): String {
    require(filename.isNotBlank() && filename.length <= 256) {
        "diagnostics archive filename is invalid"
    }
    require(contentType == FEEDBACK_DIAGNOSTICS_CONTENT_TYPE) {
        "diagnostics archive content type is invalid"
    }
    require(size in 1L..FEEDBACK_DIAGNOSTICS_MAX_BYTES) {
        "diagnostics archive size is outside the permitted bound"
    }
    return feedbackDiagnosticsPresignJson.encodeToString(
        FeedbackDiagnosticsPresignRequestDto.serializer(),
        FeedbackDiagnosticsPresignRequestDto(
            files = listOf(
                FeedbackDiagnosticsPresignFileRequestDto(
                    filename = filename,
                    contentType = contentType,
                    size = size,
                ),
            ),
        ),
    )
}

/**
 * Decode and validate the one-file presign response.  A response containing zero or multiple
 * uploads is rejected instead of selecting an arbitrary entry, which keeps the native ticket
 * reference bound to the archive that was actually streamed.
 */
fun parseSharedFeedbackDiagnosticsPresignResponse(
    bodyText: String,
): FeedbackDiagnosticsPresignedUpload {
    require(bodyText.isNotBlank()) { "feedback diagnostics presign response is empty" }
    val response = feedbackDiagnosticsPresignJson.decodeFromString(
        FeedbackDiagnosticsPresignResponseDto.serializer(),
        bodyText,
    )
    require(response.uploads.size == 1) {
        "feedback diagnostics presign response must contain exactly one upload"
    }
    val upload = response.uploads.single()
    require(upload.attachmentId.isNotBlank()) {
        "feedback diagnostics presign response attachment id is missing"
    }
    require(upload.r2Key.isNotBlank()) {
        "feedback diagnostics presign response R2 key is missing"
    }
    require(upload.uploadUrl.isNotBlank()) {
        "feedback diagnostics presign response upload URL is missing"
    }
    require(isValidFeedbackDiagnosticsUploadUrl(upload.uploadUrl)) {
        "feedback diagnostics presign response upload URL is invalid"
    }
    require(upload.expiresAt > 0L) {
        "feedback diagnostics presign response expiry is invalid"
    }
    return FeedbackDiagnosticsPresignedUpload(
        attachmentId = upload.attachmentId,
        r2Key = upload.r2Key,
        uploadUrl = upload.uploadUrl,
        expiresAt = upload.expiresAt,
    )
}
