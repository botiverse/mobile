@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.posix.O_RDONLY
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close as posixClose
import platform.posix.creat as posixCreat
import platform.posix.fsync as posixFsync
import platform.posix.open as posixOpen
import platform.posix.read as posixRead
import platform.posix.rename as posixRename
import platform.posix.unlink as posixUnlink
import platform.posix.write as posixWrite

private const val IosExternalShareAppGroup = "group.build.raft.app.external-share"
private const val IosExternalShareRoot = "ExternalShareInbox"
private const val IosExternalShareEnvelopeFile = "envelope.json"
private const val IosExternalShareConsumedFile = "consumed.txt"
private const val IosExternalSharePendingFile = "pending.txt"
private const val IosExternalShareMaximumItemBytes = 25L * 1024L * 1024L
private const val IosExternalShareMaximumTotalBytes = 100L * 1024L * 1024L
private const val IosExternalShareMaximumItems = 9
private const val IosExternalShareTtlMillis = 24L * 60L * 60L * 1000L

private val iosExternalShareJson = Json { ignoreUnknownKeys = true }

internal actual fun createDefaultExternalShareEnvelopeRepository(): ExternalShareEnvelopeRepository? =
    IosAppGroupExternalShareEnvelopeRepository.createOrNull()

private class IosAppGroupExternalShareEnvelopeRepository(
    private val rootPath: String,
    private val fileManager: NSFileManager = NSFileManager.defaultManager
) : ExternalShareEnvelopeRepository {
    override suspend fun load(token: String): DataResult<ExternalShareEnvelope> {
        val normalized = token.externalShareOpaqueTokenOrNull()
            ?: return DataResult.Error(ScreenError("external_share_identity_invalid", retryable = false))
        val directory = tokenDirectory(normalized)
        val consumedPath = "$directory/$IosExternalShareConsumedFile"
        if (fileManager.fileExistsAtPath(consumedPath)) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val envelopePath = "$directory/$IosExternalShareEnvelopeFile"
        val encoded = readText(envelopePath)
            ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val envelope =
            try {
                iosExternalShareJson.decodeFromString<ExternalShareEnvelope>(encoded)
            } catch (_: SerializationException) {
                return DataResult.Error(ScreenError("external_share_envelope_decode_failed", retryable = false))
            } catch (_: IllegalArgumentException) {
                return DataResult.Error(ScreenError("external_share_envelope_decode_failed", retryable = false))
            }
        val nowMillis = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
        val ageMillis = nowMillis - envelope.createdAtEpochMillis
        if (
            envelope.token != normalized ||
            envelope.sourcePlatform != "ios" ||
            !envelope.hasContent() ||
            ageMillis !in 0..IosExternalShareTtlMillis
        ) {
            return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
        }
        if (envelope.items.size > IosExternalShareMaximumItems) {
            return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
        }
        var totalBytes = 0L
        for (item in envelope.items) {
            val url = NSURL.URLWithString(item.localRef)
                ?: return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
            val path = url.path
                ?: return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
            if (!path.startsWith("$directory/") || ".." in path || !fileManager.fileExistsAtPath(path)) {
                return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
            }
            val size = fileSize(path)
                ?: return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
            if (size != item.sizeBytes || size <= 0L || size > IosExternalShareMaximumItemBytes) {
                return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
            }
            totalBytes += size
            if (totalBytes > IosExternalShareMaximumTotalBytes) {
                return DataResult.Error(ScreenError("external_share_envelope_invalid", retryable = false))
            }
        }
        return DataResult.Content(envelope)
    }

    override suspend fun consume(token: String, consumptionId: String): DataResult<Unit> {
        val normalized = token.externalShareOpaqueTokenOrNull()
            ?: return DataResult.Error(ScreenError("external_share_consumption_identity_invalid", retryable = false))
        val expected = "external-share-consume:$normalized"
        if (consumptionId != expected) {
            return DataResult.Error(ScreenError("external_share_consumption_identity_invalid", retryable = false))
        }
        val directory = tokenDirectory(normalized)
        if (!fileManager.fileExistsAtPath("$directory/$IosExternalShareEnvelopeFile")) {
            return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        }
        val marker = "$directory/$IosExternalShareConsumedFile"
        readText(marker)?.let { existing ->
            return if (existing == expected) {
                DataResult.Content(Unit)
            } else {
                DataResult.Error(ScreenError("external_share_consumption_conflict", retryable = false))
            }
        }
        val wrote = writeTextAtomically(marker, expected)
        if (!wrote) return DataResult.Error(ScreenError("external_share_consume_failed", retryable = true))
        val pending = "$rootPath/$IosExternalSharePendingFile"
        if (readText(pending)?.trim() == normalized) fileManager.removeItemAtPath(pending, error = null)
        return DataResult.Content(Unit)
    }

    override suspend fun delete(token: String) {
        val normalized = token.externalShareOpaqueTokenOrNull() ?: return
        val directory = tokenDirectory(normalized)
        fileManager.removeItemAtPath(directory, error = null)
        val pending = "$rootPath/$IosExternalSharePendingFile"
        if (readText(pending)?.trim() == normalized) fileManager.removeItemAtPath(pending, error = null)
    }

    private fun tokenDirectory(token: String): String = "$rootPath/$token"

    private fun readText(path: String): String? =
        readUtf8Text(path, fileSize(path))

    private fun fileSize(path: String): Long? =
        (fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue

    companion object {
        fun createOrNull(): IosAppGroupExternalShareEnvelopeRepository? {
            val group = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(
                IosExternalShareAppGroup
            ) ?: return null
            val path = group.path?.let { "$it/$IosExternalShareRoot" } ?: return null
            NSFileManager.defaultManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
            return IosAppGroupExternalShareEnvelopeRepository(path)
        }
    }
}

private fun readUtf8Text(path: String, sizeBytes: Long?): String? {
    val size = sizeBytes?.takeIf { it in 0..(2L * 1024L * 1024L) }?.toInt() ?: return null
    val descriptor = posixOpen(path, O_RDONLY)
    if (descriptor < 0) return null
    return try {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < bytes.size) {
            val count =
                bytes.usePinned { pinned ->
                    posixRead(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                }
            if (count <= 0) return null
            offset += count
        }
        bytes.decodeToString()
    } finally {
        posixClose(descriptor)
    }
}

private fun writeTextAtomically(path: String, value: String): Boolean {
    val temporary = "$path.tmp"
    posixUnlink(temporary)
    val descriptor = posixCreat(temporary, (S_IRUSR or S_IWUSR).convert())
    if (descriptor < 0) return false
    val bytes = value.encodeToByteArray()
    var offset = 0
    var success = true
    while (offset < bytes.size) {
        val count =
            bytes.usePinned { pinned ->
                posixWrite(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
            }
        if (count <= 0) {
            success = false
            break
        }
        offset += count
    }
    if (success) success = posixFsync(descriptor) == 0
    if (posixClose(descriptor) != 0) success = false
    if (success) success = posixRename(temporary, path) == 0
    if (!success) posixUnlink(temporary)
    return success
}
