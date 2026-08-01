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
