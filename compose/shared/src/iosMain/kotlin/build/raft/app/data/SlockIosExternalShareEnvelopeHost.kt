@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.posix.EEXIST
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.read
import platform.posix.unlink
import platform.posix.write

private const val IosExternalShareAppGroup = "group.build.raft.app.external-share"
private const val IosExternalShareRoot = "ExternalShareInbox"
private const val IosExternalShareEnvelopeFile = "envelope.json"
private const val IosExternalShareConsumedFile = "consumed.txt"
private const val IosExternalSharePendingFile = "pending.txt"
private const val IosExternalShareMaximumEnvelopeBytes = 2L * 1024L * 1024L
private const val IosExternalShareMaximumItemBytes = 25L * 1024L * 1024L
private const val IosExternalShareMaximumTotalBytes = 100L * 1024L * 1024L
private const val IosExternalShareMaximumItems = 9
private const val IosExternalShareMaximumTextCharacters = 256 * 1024
private const val IosExternalShareTtlMillis = 24L * 60L * 60L * 1000L

private val iosExternalShareJson = Json { ignoreUnknownKeys = true }

/** Installs the iOS App Group inbox as the shared external-share bytes owner. */
fun installSlockIosExternalShareEnvelopeHost() {
    SlockExternalShareEnvelopeHostProvider.installAppScopeHost {
        IosAppGroupExternalShareEnvelopeHost.createOrUnavailable()
    }
}

private class IosAppGroupExternalShareEnvelopeHost(
    private val rootPath: String,
    private val fileManager: NSFileManager = NSFileManager.defaultManager
) : SlockExternalShareEnvelopeHost {
    override suspend fun load(token: String): SlockExternalShareEnvelopeHostReadResult {
        val normalized = token.externalShareOpaqueTokenOrNull()
            ?: return failure("external_share_identity_invalid")
        val directory = tokenDirectory(normalized)
        val consumedPath = "$directory/$IosExternalShareConsumedFile"
        if (fileManager.fileExistsAtPath(consumedPath)) return SlockExternalShareEnvelopeHostReadResult.Missing
        val envelopePath = "$directory/$IosExternalShareEnvelopeFile"
        val encoded = readText(envelopePath) ?: return SlockExternalShareEnvelopeHostReadResult.Missing
        val envelope =
            try {
                iosExternalShareJson.decodeFromString<IosExternalShareEnvelope>(encoded)
            } catch (_: SerializationException) {
                return failure("external_share_envelope_decode_failed")
            } catch (_: IllegalArgumentException) {
                return failure("external_share_envelope_decode_failed")
            }
        val nowMillis = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
        if (
            envelope.token != normalized ||
            envelope.sourcePlatform != "ios" ||
            nowMillis - envelope.createdAtEpochMillis !in 0..IosExternalShareTtlMillis ||
            envelope.items.size > IosExternalShareMaximumItems ||
            envelope.text.length > IosExternalShareMaximumTextCharacters ||
            envelope.subject.length > IosExternalShareMaximumTextCharacters ||
            (envelope.text.isEmpty() && envelope.subject.isEmpty() && envelope.items.isEmpty())
        ) return failure("external_share_envelope_invalid")

        var totalBytes = 0L
        val items = mutableListOf<SlockExternalShareEnvelopeHostItem>()
        for (item in envelope.items) {
            val path = NSURL.URLWithString(item.localRef)?.path
                ?: return failure("external_share_envelope_invalid")
            if (!path.startsWith("$directory/") || ".." in path || !fileManager.fileExistsAtPath(path)) {
                return failure("external_share_envelope_invalid")
            }
            val size = fileSize(path) ?: return failure("external_share_envelope_invalid")
            if (size != item.sizeBytes || size <= 0L || size > IosExternalShareMaximumItemBytes) {
                return failure("external_share_envelope_invalid")
            }
            totalBytes += size
            if (totalBytes > IosExternalShareMaximumTotalBytes) return failure("external_share_envelope_invalid")
            items +=
                SlockExternalShareEnvelopeHostItem(
                    localRef = item.localRef,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    sizeBytes = size
                )
        }
        return SlockExternalShareEnvelopeHostReadResult.Content(
            SlockExternalShareEnvelopeHostRecord(
                token = normalized,
                createdAtEpochMillis = envelope.createdAtEpochMillis,
                sourcePlatform = envelope.sourcePlatform,
                text = envelope.text,
                subject = envelope.subject,
                items = items
            )
        )
    }

    override suspend fun consume(token: String, consumptionId: String): SlockExternalShareEnvelopeHostMutationResult {
        val normalized = token.externalShareOpaqueTokenOrNull()
            ?: return mutationFailure("external_share_consumption_identity_invalid")
        val expected = "external-share-consume:$normalized"
        if (consumptionId != expected) return mutationFailure("external_share_consumption_identity_invalid")
        val directory = tokenDirectory(normalized)
        if (!fileManager.fileExistsAtPath("$directory/$IosExternalShareEnvelopeFile")) {
            return SlockExternalShareEnvelopeHostMutationResult.Missing
        }
        val marker = "$directory/$IosExternalShareConsumedFile"
        when (createExclusiveMarker(marker, expected)) {
            MarkerResult.Created -> Unit
            MarkerResult.Exists -> {
                return if (readText(marker) == expected) {
                    SlockExternalShareEnvelopeHostMutationResult.Success
                } else {
                    mutationFailure("external_share_consumption_conflict")
                }
            }
            MarkerResult.Failed -> return mutationFailure("external_share_consume_failed", retryable = true)
        }
        val pending = "$rootPath/$IosExternalSharePendingFile"
        if (readText(pending)?.trim() == normalized) fileManager.removeItemAtPath(pending, error = null)
        return SlockExternalShareEnvelopeHostMutationResult.Success
    }

    override suspend fun delete(token: String) {
        val normalized = token.externalShareOpaqueTokenOrNull() ?: return
        fileManager.removeItemAtPath(tokenDirectory(normalized), error = null)
        val pending = "$rootPath/$IosExternalSharePendingFile"
        if (readText(pending)?.trim() == normalized) fileManager.removeItemAtPath(pending, error = null)
    }

    private fun tokenDirectory(token: String): String = "$rootPath/$token"

    private fun fileSize(path: String): Long? =
        (fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue

    private fun readText(path: String): String? = readUtf8Text(path, fileSize(path))

    private fun failure(code: String, retryable: Boolean = false) =
        SlockExternalShareEnvelopeHostReadResult.Failure(code, retryable)

    private fun mutationFailure(code: String, retryable: Boolean = false) =
        SlockExternalShareEnvelopeHostMutationResult.Failure(code, retryable)

    companion object {
        fun createOrUnavailable(): SlockExternalShareEnvelopeHost {
            val group = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(
                IosExternalShareAppGroup
            ) ?: return UnavailableIosExternalShareEnvelopeHost
            val path = group.path?.let { "$it/$IosExternalShareRoot" }
                ?: return UnavailableIosExternalShareEnvelopeHost
            NSFileManager.defaultManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
            return IosAppGroupExternalShareEnvelopeHost(path)
        }
    }
}

private object UnavailableIosExternalShareEnvelopeHost : SlockExternalShareEnvelopeHost {
    override suspend fun load(token: String) =
        SlockExternalShareEnvelopeHostReadResult.Failure("external_share_app_group_unavailable", true)

    override suspend fun consume(token: String, consumptionId: String) =
        SlockExternalShareEnvelopeHostMutationResult.Failure("external_share_app_group_unavailable", true)

    override suspend fun delete(token: String) = Unit
}

@Serializable
private data class IosExternalShareEnvelopeItem(
    val localRef: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

@Serializable
private data class IosExternalShareEnvelope(
    val token: String,
    val createdAtEpochMillis: Long,
    val sourcePlatform: String,
    val text: String = "",
    val subject: String = "",
    val items: List<IosExternalShareEnvelopeItem> = emptyList()
)

private fun readUtf8Text(path: String, sizeBytes: Long?): String? {
    val size = sizeBytes?.takeIf { it in 0..IosExternalShareMaximumEnvelopeBytes }?.toInt() ?: return null
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) return null
    return try {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { pinned ->
                read(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
            }
            if (count <= 0) return null
            offset += count
        }
        bytes.decodeToString()
    } finally {
        close(descriptor)
    }
}

private enum class MarkerResult { Created, Exists, Failed }

private fun createExclusiveMarker(path: String, value: String): MarkerResult {
    val descriptor = open(path, O_CREAT or O_EXCL or O_WRONLY, S_IRUSR or S_IWUSR)
    if (descriptor < 0) return if (errno == EEXIST) MarkerResult.Exists else MarkerResult.Failed
    val bytes = value.encodeToByteArray()
    var offset = 0
    var success = true
    while (offset < bytes.size) {
        val count = bytes.usePinned { pinned ->
            write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
        }
        if (count <= 0) {
            success = false
            break
        }
        offset += count
    }
    if (success) success = fsync(descriptor) == 0
    if (close(descriptor) != 0) success = false
    if (!success) unlink(path)
    return if (success) MarkerResult.Created else MarkerResult.Failed
}
