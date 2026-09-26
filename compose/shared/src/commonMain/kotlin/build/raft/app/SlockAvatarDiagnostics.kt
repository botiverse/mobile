package build.raft.app

import kotlin.random.Random

private val SlockAvatarDiagnosticsSessionSalt = Random.nextLong().toString(16)

internal data class SlockAvatarRowDiagnostics(
    val messageHash: String,
    val authorHash: String,
    val logger: (String) -> Unit
)

internal fun slockAvatarRowDiagnostics(
    messageId: String,
    authorId: String,
    logger: (String) -> Unit
): SlockAvatarRowDiagnostics =
    SlockAvatarRowDiagnostics(
        messageHash = messageId.slockAvatarDiagnosticsHash(),
        authorHash = authorId.slockAvatarDiagnosticsHash(),
        logger = logger
    )

internal fun String?.slockAvatarDiagnosticsHash(): String {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return "none"
    var hash = 0xcbf29ce484222325uL
    for (byte in "$SlockAvatarDiagnosticsSessionSalt:$value".encodeToByteArray()) {
        hash = hash xor byte.toUByte().toULong()
        hash *= 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0').take(12)
}

/** Diagnostic source contract: no raw URL, token, or identity ever leaves this boundary. */
internal fun String?.slockAvatarDiagnosticsSource(): String {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return "none"
    return if (value.startsWith("http://") || value.startsWith("https://")) {
        "url(${value.slockAvatarDiagnosticsHash()})"
    } else {
        "none"
    }
}

/**
 * Shape-only description of an avatar URL for diagnostics: bucketed path kind plus
 * whether the host matches the active API host. Never emits the raw URL, query, or
 * host string -- the host is only compared/bucketed, and the source hash (already
 * salted) carries identity correlation.
 */
internal fun String?.slockAvatarDiagnosticsUrlShape(apiBaseUrl: String?): String {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return "shape=none"
    if (!value.startsWith("http://") && !value.startsWith("https://")) return "shape=none"
    val authorityStart = value.indexOf("://") + 3
    val authorityEnd = value.indexOf('/', startIndex = authorityStart).let { if (it < 0) value.length else it }
    val host = value.substring(authorityStart, authorityEnd).substringBefore(':').lowercase()
    val path = if (authorityEnd < value.length) value.substring(authorityEnd).substringBefore('?').substringBefore('#') else ""
    val apiHost =
        apiBaseUrl
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { base ->
                val bStart = base.indexOf("://") + 3
                val bEnd = base.indexOf('/', startIndex = bStart).let { if (it < 0) base.length else it }
                base.substring(bStart, bEnd).substringBefore(':').lowercase()
            }
    val hostKind =
        when {
            apiHost == null -> "host=unknown_api"
            host == apiHost -> "host=api"
            else -> "host=other"
        }
    val pathKind =
        when {
            value.contains("gravatar.com/avatar", ignoreCase = true) -> "path=gravatar"
            path.startsWith("/api/avatars/") || path.startsWith("/avatars/") -> "path=avatars"
            else -> "path=other"
        }
    return "shape=$hostKind,$pathKind"
}
