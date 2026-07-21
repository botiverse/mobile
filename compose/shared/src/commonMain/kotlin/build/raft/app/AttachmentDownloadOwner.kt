package build.raft.app

import build.raft.app.data.SlockAuthSessionStore

internal suspend fun SlockAuthSessionStore.currentAttachmentDownloadOwnerFingerprint(): String? {
    val session = readSession() ?: return null
    val accountId = session.user.id.trim()
    val serverId = session.currentServerId?.trim().orEmpty()
    if (accountId.isBlank() || serverId.isBlank()) return null
    return "$accountId|$serverId"
}

internal fun ownerScopedAttachmentDownloadId(ownerFingerprint: String, downloadId: String): String {
    val ownerToken = attachmentDownloadOwnerToken(ownerFingerprint)
    return "owner-$ownerToken:$downloadId"
}

internal fun attachmentDownloadOwnerToken(ownerFingerprint: String): String {
    val hex = "0123456789abcdef"
    val bytes = ownerFingerprint.encodeToByteArray()
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

internal fun attachmentOperationCommitAllowed(
    expectedOwnerFingerprint: String,
    expectedGeneration: Long,
    currentOwnerFingerprint: String?,
    currentGeneration: Long
): Boolean =
    expectedOwnerFingerprint == currentOwnerFingerprint && expectedGeneration == currentGeneration
