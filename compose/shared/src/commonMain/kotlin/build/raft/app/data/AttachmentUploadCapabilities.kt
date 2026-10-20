package build.raft.app.data

import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val attachmentUploadCapabilitiesJson = Json { ignoreUnknownKeys = true }

private const val ATTACHMENT_UPLOAD_MAX_BYTES = 200L * 1024L * 1024L
private const val ATTACHMENT_UPLOAD_URL_MAX_CHARS = 8_192
private const val ATTACHMENT_UPLOAD_HEADER_NAME_MAX_CHARS = 64
private const val ATTACHMENT_UPLOAD_HEADER_VALUE_MAX_CHARS = 1_024
private const val ATTACHMENT_UPLOAD_CONTENT_TYPE_MAX_CHARS = 255
private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val ISO_INSTANT_PATTERN = Regex(
    "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$"
)

/**
 * A create response can be malformed after the server has already reserved a session.  Keep the
 * recovery id on a typed parse failure so the caller can compensate it without putting the id in
 * user-facing text or ordinary diagnostics.
 */
internal class AttachmentUploadSessionCreateParseException(
    val recoveryUploadId: String?,
    cause: Throwable,
) : IllegalArgumentException("direct upload create response is invalid", cause)

@Serializable
private data class AttachmentUploadCapabilitiesDto(
    val directUploadEnabled: Boolean = false,
    val directUploadThresholdBytes: Long? = null,
    val maxBytes: Long = 0L,
    val sessionExpiresInSeconds: Int? = null,
    /** Advertised legacy multipart cap; absent until the server ships the field. */
    val legacyMultipartMaxBytes: Long? = null,
)

@Serializable
private data class AttachmentUploadSessionPutDto(
    val method: String = "",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
private data class AttachmentUploadSessionCreateDto(
    val uploadId: String = "",
    val attachmentId: String = "",
    val state: String = "",
    val expiresAt: String = "",
    val upload: AttachmentUploadSessionPutDto = AttachmentUploadSessionPutDto(),
)

@Serializable
private data class AttachmentUploadSessionAttachmentDto(
    val id: String = "",
    val filename: String = "",
    val mimeType: String? = null,
    val sizeBytes: Long = -1L,
    val thumbnailUrl: String? = null,
)

@Serializable
private data class AttachmentUploadSessionViewDto(
    val uploadId: String = "",
    val state: String = "",
    val expiresAt: String = "",
    val attachment: AttachmentUploadSessionAttachmentDto? = null,
    val terminalReason: String? = null,
    val reservationState: String? = null,
)

@Serializable
private data class AttachmentUploadSessionCompleteDto(
    val uploadId: String = "",
    val state: String = "",
    val attachment: AttachmentUploadSessionAttachmentDto = AttachmentUploadSessionAttachmentDto(),
)

internal fun parseSharedAttachmentUploadCapabilities(bodyText: String): AttachmentUploadCapabilities {
    val dto = attachmentUploadCapabilitiesJson.decodeFromString<AttachmentUploadCapabilitiesDto>(bodyText)
    require(dto.maxBytes in 1L..ATTACHMENT_UPLOAD_MAX_BYTES) {
        "attachment upload maxBytes must be positive and within the contract limit"
    }
    // The server contract caps threshold at ATTACHMENT_UPLOAD_MAX_BYTES, not at
    // this response's maxBytes. Production Free-plan capabilities advertise
    // threshold=90MiB with maxBytes=50MiB; requiring threshold <= maxBytes
    // fail-closes the whole document and blocks every image upload.
    require(dto.directUploadThresholdBytes == null ||
        dto.directUploadThresholdBytes in 1L..ATTACHMENT_UPLOAD_MAX_BYTES) {
        "attachment upload directUploadThresholdBytes is invalid"
    }
    require(dto.sessionExpiresInSeconds == null || dto.sessionExpiresInSeconds in 1..86_400) {
        "attachment upload sessionExpiresInSeconds is invalid"
    }
    // A legacy-multipart cap above the contract limit is a server bug; fail closed on the
    // document rather than silently trusting a number the client cannot honour.
    require(dto.legacyMultipartMaxBytes == null ||
        dto.legacyMultipartMaxBytes in 1L..ATTACHMENT_UPLOAD_MAX_BYTES) {
        "attachment upload legacyMultipartMaxBytes is invalid"
    }
    if (!dto.directUploadEnabled) {
        require(dto.directUploadThresholdBytes == null && dto.sessionExpiresInSeconds == null) {
            "disabled direct upload cannot advertise session parameters"
        }
    }
    return AttachmentUploadCapabilities(
        maxBytes = dto.maxBytes,
        directUploadEnabled = dto.directUploadEnabled,
        directUploadThresholdBytes = dto.directUploadThresholdBytes,
        sessionExpiresInSeconds = dto.sessionExpiresInSeconds,
        legacyMultipartMaxBytes = dto.legacyMultipartMaxBytes,
    )
}

internal fun encodeSharedAttachmentUploadSessionCreateRequest(
    request: AttachmentUploadSessionCreateRequest,
): String =
    attachmentUploadCapabilitiesJson.encodeToString(
        AttachmentUploadSessionCreateRequestDto.serializer(),
        AttachmentUploadSessionCreateRequestDto(
            channelId = request.channelId.value,
            filename = request.filename,
            mimeType = request.mimeType,
            sizeBytes = request.sizeBytes,
            clientRequestId = request.clientRequestId,
        )
    )

@Serializable
private data class AttachmentUploadSessionCreateRequestDto(
    val channelId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val clientRequestId: String,
)

internal fun parseSharedAttachmentUploadSessionCreate(bodyText: String): AttachmentUploadSessionCreateResult {
    val recoveryUploadId = extractLegalUploadId(bodyText)
    return try {
        val dto = attachmentUploadCapabilitiesJson.decodeFromString<AttachmentUploadSessionCreateDto>(bodyText)
        requireValidUuid(dto.uploadId, "uploadId")
        requireValidUuid(dto.attachmentId, "attachmentId")
        require(dto.state == "pending") { "direct upload create state must be pending" }
        requireValidIsoInstant(dto.expiresAt, "expiresAt")
        require(dto.upload.method == "PUT") { "direct upload method must be PUT" }
        requireValidAttachmentUploadPresignedUrl(dto.upload.url)
        requireValidAttachmentUploadPutHeaders(dto.upload.headers)
        AttachmentUploadSessionCreateResult(
            uploadId = dto.uploadId,
            attachmentId = AttachmentId(dto.attachmentId),
            state = dto.state,
            expiresAt = dto.expiresAt,
            upload = AttachmentUploadSessionPut(
                method = dto.upload.method,
                // Keep this value in memory only; callers must not include it in diagnostics or
                // durable state.
                url = dto.upload.url,
                headers = dto.upload.headers,
            ),
        )
    } catch (error: Exception) {
        throw AttachmentUploadSessionCreateParseException(recoveryUploadId, error)
    }
}

/** Extract only a UUID-shaped upload id from a possibly malformed JSON body. */
private fun extractLegalUploadId(bodyText: String): String? {
    val fromJson =
        runCatching {
            (attachmentUploadCapabilitiesJson.parseToJsonElement(bodyText) as? JsonObject)
                ?.get("uploadId")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
    val candidate =
        fromJson
            ?: Regex("[\\\"']uploadId[\\\"']\\s*:\\s*[\\\"']([0-9a-fA-F-]{36})[\\\"']")
                .find(bodyText)
                ?.groupValues
                ?.getOrNull(1)
    return candidate?.takeIf { UUID_PATTERN.matches(it) }
}

internal fun parseSharedAttachmentUploadSessionComplete(
    bodyText: String,
): AttachmentUploadSessionCompleteResult {
    val dto = attachmentUploadCapabilitiesJson.decodeFromString<AttachmentUploadSessionCompleteDto>(bodyText)
    requireValidUuid(dto.uploadId, "uploadId")
    require(dto.state == "completed") { "direct upload complete state must be completed" }
    return AttachmentUploadSessionCompleteResult(
        uploadId = dto.uploadId,
        state = dto.state,
        attachment = dto.attachment.toAttachment(),
    )
}

internal fun parseSharedAttachmentUploadSessionView(bodyText: String): AttachmentUploadSessionView {
    val dto = attachmentUploadCapabilitiesJson.decodeFromString<AttachmentUploadSessionViewDto>(bodyText)
    requireValidUuid(dto.uploadId, "uploadId")
    require(dto.state in setOf("pending", "verifying", "completed", "canceled", "expired", "failed")) {
        "direct upload session state is invalid"
    }
    if (dto.reservationState != null) {
        require(dto.reservationState in setOf("pending", "consumed", "canceled", "expired")) {
            "direct upload reservation state is invalid"
        }
    }
    requireValidIsoInstant(dto.expiresAt, "expiresAt")
    val attachment = dto.attachment?.toAttachment()
    if (dto.state == "completed") require(attachment != null) { "completed session has no attachment" }
    return AttachmentUploadSessionView(
        uploadId = dto.uploadId,
        state = dto.state,
        expiresAt = dto.expiresAt,
        attachment = attachment,
        terminalReason = dto.terminalReason,
        reservationState = dto.reservationState,
    )
}

internal fun parseSharedAttachmentUploadErrorCode(bodyText: String?): String? {
    val raw = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val element = runCatching { attachmentUploadCapabilitiesJson.parseToJsonElement(raw) }.getOrNull()
    val obj = element as? JsonObject ?: return null
    return obj["code"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        ?: obj["errorCode"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

private fun AttachmentUploadSessionAttachmentDto.toAttachment(): Attachment {
    requireValidUuid(id, "attachment.id")
    require(filename.isNotBlank() && filename.length <= 255) { "attachment filename is invalid" }
    // The wire field is nullable, but an explicitly supplied value must carry a real
    // media-type token.  Mapping an empty string to the model's nullable-compatible `""`
    // would otherwise let a malformed complete/status response reach the composer and
    // silently change the upload contract.
    require(mimeType == null || (mimeType.isNotBlank() && mimeType.length <= 255)) {
        "attachment mimeType is invalid"
    }
    require(sizeBytes in 0L..ATTACHMENT_UPLOAD_MAX_BYTES) { "attachment sizeBytes is invalid" }
    return Attachment(
        id = AttachmentId(id),
        name = filename,
        mimeType = mimeType.orEmpty(),
        sizeLabel = "",
        sizeBytes = sizeBytes,
        thumbnailUrl = thumbnailUrl?.also(::requireValidAttachmentUploadPresignedUrl),
    )
}

private fun requireValidUuid(value: String, label: String) {
    require(isLegalAttachmentUploadSessionId(value)) { "$label is not a UUID" }
}

/**
 * Upload-session ids are used as a path component by the cancel/status/complete endpoints.
 * Keep the admission predicate in shared code so parsers, repositories and compensating callers
 * cannot disagree about which values are safe to put on the wire.
 */
internal fun isLegalAttachmentUploadSessionId(value: String): Boolean =
    UUID_PATTERN.matches(value.trim())

/**
 * Validate a server-issued object URL without using a platform URL parser.  The shared module is
 * compiled for JVM, Native and OHOS, so this deliberately accepts only the small, unambiguous
 * absolute HTTP(S) grammar needed by the direct-upload contract.
 *
 * In particular, do not relax this to "startsWith(https://)": userinfo, encoded authority
 * delimiters, malformed ports and control characters can make the transport resolve a different
 * host than the one a reviewer saw.
 */
internal fun requireValidAttachmentUploadPresignedUrl(value: String) {
    require(value.isNotEmpty() && value.length <= ATTACHMENT_UPLOAD_URL_MAX_CHARS) {
        "direct upload URL length is invalid"
    }
    require(value == value.trim()) { "direct upload URL has surrounding whitespace" }
    require(value.none { char -> char == '\\' || char == '#' || char.code < 0x20 || char.code == 0x7f }) {
        "direct upload URL contains an unsafe character"
    }

    val schemeEnd = value.indexOf("://")
    require(schemeEnd == 4 || schemeEnd == 5) { "direct upload URL must be absolute HTTP(S)" }
    val scheme = value.substring(0, schemeEnd)
    require(scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) {
        "direct upload URL must be absolute HTTP(S)"
    }
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
        .let { if (it < 0) value.length else it }
    val authority = value.substring(authorityStart, authorityEnd)
    require(authority.isNotEmpty()) { "direct upload URL authority is empty" }
    require('@' !in authority) { "direct upload URL userinfo is not allowed" }
    // A percent sign in authority is never needed for a DNS name or an IPv6 literal. Rejecting it
    // also closes encoded '/', '?', '#', '\\' and '@' authority-confusion forms.
    require('%' !in authority) { "direct upload URL authority encoding is not allowed" }
    require(authority.none { char -> char.code > 0x7f || char.isWhitespace() }) {
        "direct upload URL authority contains an invalid character"
    }

    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        require(close > 1) { "direct upload URL IPv6 authority is malformed" }
        require(authority.substring(close + 1).let { suffix ->
            suffix.isEmpty() || (suffix.startsWith(':') && suffix.length > 1 && suffix.drop(1).all(Char::isDigit))
        }) { "direct upload URL port is malformed" }
        require('[' !in authority.substring(1) && ']' !in authority.substring(close + 1)) {
            "direct upload URL IPv6 authority is malformed"
        }
        val ipv6 = authority.substring(1, close)
        require(ipv6.count { it == ':' } >= 2 && ipv6.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            "direct upload URL IPv6 host is invalid"
        }
        validateAttachmentUploadPort(authority.substring(close + 1).removePrefix(":"))
    } else {
        require('[' !in authority && ']' !in authority) { "direct upload URL authority is malformed" }
        require(authority.count { it == ':' } <= 1) { "direct upload URL host is ambiguous" }
        val colon = authority.indexOf(':')
        val host = if (colon < 0) authority else authority.substring(0, colon)
        val port = if (colon < 0) "" else authority.substring(colon + 1)
        requireValidAttachmentUploadHost(host)
        validateAttachmentUploadPort(port)
    }
}

internal fun requireValidAttachmentUploadPutHeaders(headers: Map<String, String>) {
    require(headers.size <= 2) { "direct upload returned too many PUT headers" }
    require(headers.keys.all { key ->
        key.isNotEmpty() && key.length <= ATTACHMENT_UPLOAD_HEADER_NAME_MAX_CHARS &&
            key.all(::isAttachmentUploadHeaderToken)
    }) { "direct upload returned an invalid PUT header name" }
    require(headers.values.all { value ->
        value.length <= ATTACHMENT_UPLOAD_HEADER_VALUE_MAX_CHARS &&
            value == value.trim() &&
            value.none { char -> char.code < 0x20 || char.code == 0x7f }
    }) { "direct upload returned an invalid PUT header value" }
    require(headers.keys.all { key -> key == "Content-Type" || key == "If-None-Match" }) {
        "direct upload returned an unsupported PUT header"
    }
    val contentType = headers["Content-Type"]
    require(contentType != null && contentType.isNotBlank() && contentType.length <= ATTACHMENT_UPLOAD_CONTENT_TYPE_MAX_CHARS) {
        "direct upload Content-Type header is missing"
    }
    require(headers["If-None-Match"] == "*") {
        "direct upload If-None-Match header must be *"
    }
}

private fun isAttachmentUploadHeaderToken(char: Char): Boolean =
    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
        char in "!#$%&'*+-.^_`|~"

private fun validateAttachmentUploadPort(port: String) {
    if (port.isEmpty()) return
    require(port.length <= 5 && port.all(Char::isDigit)) { "direct upload URL port is invalid" }
    val number = port.toLongOrNull()
    require(number != null && number in 1L..65_535L) { "direct upload URL port is out of range" }
}

private fun requireValidAttachmentUploadHost(host: String) {
    require(host.isNotEmpty() && host.length <= 253) { "direct upload URL host is invalid" }
    val labels = host.split('.')
    require(labels.all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first() != '-' && label.last() != '-' &&
            label.all { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' }
    }) { "direct upload URL host is invalid" }
    // Four numeric labels are interpreted as an IPv4 address by most transports. Reject values
    // outside octet range instead of allowing parser disagreement across platforms.
    if (labels.size == 4 && labels.all { it.all(Char::isDigit) }) {
        require(labels.all { it.toLongOrNull()?.let { octet -> octet in 0L..255L } == true }) {
            "direct upload URL IPv4 host is invalid"
        }
    }
}

private fun requireValidIsoInstant(value: String, label: String) {
    require(ISO_INSTANT_PATTERN.matches(value)) { "$label is not an ISO timestamp" }
}
