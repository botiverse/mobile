@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSURL
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close as posixClose
import platform.posix.fsync as posixFsync
import platform.posix.link as posixLink
import platform.posix.open as posixOpen
import platform.posix.read as posixRead
import platform.posix.unlink as posixUnlink
import platform.posix.write as posixWrite

internal actual fun createDefaultPlatformFileResolver(): SlockPlatformFileResolver? =
    SlockIosPlatformFileResolver()

private class SlockIosPlatformFileResolver : SlockPlatformFileResolver {
    override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult {
        val trimmed = fileRef.trim()
        if (trimmed.isEmpty()) {
            return failedRead("missing_file_ref", "Missing file ref")
        }
        val scoped = trimmed.openScopedFileUrl()
            ?: return failedRead("attachment_read_failed", "Unable to read file")
        return try {
            val path = scoped.url.path
                ?: return failedRead("attachment_read_failed", "Unable to resolve file path")
            val bytes = runCatching { readWholeFile(path) }.getOrElse { failure ->
                return failedRead(
                    "attachment_read_failed",
                    failure.message ?: "Unable to read file"
                )
            }
            SlockPlatformFileReadResult(code = 200, bytes = bytes, sizeBytes = bytes.size.toLong())
        } finally {
            scoped.close()
        }
    }

    override suspend fun statFile(fileRef: String): SlockPlatformFileStatResult {
        val scoped = fileRef.openScopedFileUrl()
            ?: return failed("missing_file_ref", "Missing file ref")
        return try {
            val path = scoped.url.path
                ?: return failed("attachment_read_failed", "Unable to resolve file path")
            val descriptor = posixOpen(path, O_RDONLY)
            if (descriptor < 0) return failed("attachment_read_failed", "Unable to open file")
            posixClose(descriptor)
            val sizeBytes = fileSize(path)
                ?: return failed("attachment_size_unavailable", "Attachment size unavailable")
            SlockPlatformFileStatResult(code = 200, sizeBytes = sizeBytes)
        } finally {
            scoped.close()
        }
    }

    override suspend fun openFileStream(
        fileRef: String,
        expectedSizeBytes: Long
    ): SlockPlatformFileChunkSource {
        val scoped = fileRef.openScopedFileUrl()
            ?: error("Missing file ref")
        val path = scoped.url.path
        if (path == null) {
            scoped.close()
            error("Unable to resolve file path")
        }
        val descriptor = posixOpen(path, O_RDONLY)
        if (descriptor < 0) {
            scoped.close()
            error("Unable to open file")
        }
        val actualSizeBytes = fileSize(path)
        if (actualSizeBytes == null || actualSizeBytes != expectedSizeBytes) {
            posixClose(descriptor)
            scoped.close()
            error("Attachment size changed: expected=$expectedSizeBytes actual=${actualSizeBytes ?: "unknown"}")
        }
        return object : SlockPlatformFileChunkSource {
            override suspend fun read(maxBytes: Int): ByteArray = readChunk(descriptor, maxBytes)

            override fun close() {
                runCatching { posixClose(descriptor) }
                scoped.close()
            }
        }
    }

    override suspend fun persistImmutableFile(
        ownerKey: String,
        scopeKey: String,
        index: Int,
        bytes: ByteArray
    ): SlockPlatformFilePersistResult =
        runCatching {
            require(ownerKey.isImmutableFeedbackKey())
            require(scopeKey.isImmutableFeedbackKey())
            require(index in 0..2)
            require(bytes.isNotEmpty())
            val directory = "${immutableFeedbackRoot()}/$ownerKey/$scopeKey"
            val fileManager = NSFileManager.defaultManager
            if (!fileManager.fileExistsAtPath(directory)) {
                check(fileManager.createDirectoryAtPath(directory, true, null, null))
            }
            val target = "$directory/$index.bin"
            if (fileManager.fileExistsAtPath(target)) {
                check(readWholeFile(target).contentEquals(bytes)) { "Immutable feedback snapshot conflict" }
            } else {
                writeWholeFileAtomically(target, bytes)
            }
            SlockPlatformFilePersistResult(200, NSURL.fileURLWithPath(target).absoluteString)
        }.getOrElse { throwable ->
            SlockPlatformFilePersistResult(
                code = 400,
                errorCode = "immutable_file_persist_failed",
                message = throwable.message ?: "Unable to persist feedback snapshot"
            )
        }

    override suspend fun retireImmutableScope(ownerKey: String, scopeKey: String): Boolean {
        if (!ownerKey.isImmutableFeedbackKey() || !scopeKey.isImmutableFeedbackKey()) return false
        return removeImmutableFeedbackPath("${immutableFeedbackRoot()}/$ownerKey/$scopeKey")
    }

    override suspend fun retireImmutableOwner(ownerKey: String): Boolean {
        if (!ownerKey.isImmutableFeedbackKey()) return false
        return removeImmutableFeedbackPath("${immutableFeedbackRoot()}/$ownerKey")
    }

    override suspend fun retireAllImmutableOwners(): Boolean =
        removeImmutableFeedbackPath(immutableFeedbackRoot())

    private fun failed(errorCode: String, message: String): SlockPlatformFileStatResult =
        SlockPlatformFileStatResult(
            code = 400,
            errorCode = errorCode,
            message = message
        )

    private fun failedRead(errorCode: String, message: String): SlockPlatformFileReadResult =
        SlockPlatformFileReadResult(
            code = 400,
            bytes = ByteArray(0),
            errorCode = errorCode,
            message = message
        )

    private fun fileSize(path: String): Long? =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue
            ?.takeIf { it > 0L }
}

private fun immutableFeedbackRoot(): String {
    val applicationSupport =
        NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
            ?: error("Application Support is unavailable")
    return "$applicationSupport/Raft/product-feedback-retry"
}

private fun removeImmutableFeedbackPath(path: String): Boolean {
    val fileManager = NSFileManager.defaultManager
    return !fileManager.fileExistsAtPath(path) || fileManager.removeItemAtPath(path, error = null)
}

private fun String.isImmutableFeedbackKey(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private data class ScopedFileUrl(
    val url: NSURL,
    val securityScoped: Boolean
) {
    fun close() {
        if (securityScoped) url.stopAccessingSecurityScopedResource()
    }
}

private fun String.openScopedFileUrl(): ScopedFileUrl? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    val url =
        if (trimmed.startsWith("/")) {
            NSURL.fileURLWithPath(trimmed)
        } else {
            NSURL.URLWithString(trimmed)
        } ?: return null
    return ScopedFileUrl(
        url = url,
        securityScoped = url.startAccessingSecurityScopedResource()
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun readChunk(descriptor: Int, maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val buffer = ByteArray(maxBytes)
    val count =
        buffer.usePinned { pinned ->
            posixRead(descriptor, pinned.addressOf(0), maxBytes.convert()).toInt()
        }
    if (count < 0) error("Unable to read file")
    if (count == 0) return ByteArray(0)
    return if (count == buffer.size) buffer else buffer.copyOf(count)
}

private fun readWholeFile(path: String): ByteArray {
    val sizeBytes =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue
            ?.takeIf { it > 0L }
            ?: error("Attachment size unavailable")
    if (sizeBytes > Int.MAX_VALUE.toLong()) error("Attachment is too large to buffer")
    val descriptor = posixOpen(path, O_RDONLY)
    if (descriptor < 0) error("Unable to open file")
    return try {
        val result = ByteArray(sizeBytes.toInt())
        var offset = 0
        while (offset < result.size) {
            val count =
                result.usePinned { pinned ->
                    posixRead(
                        descriptor,
                        pinned.addressOf(offset),
                        (result.size - offset).convert()
                    ).toInt()
                }
            if (count <= 0) error("Attachment ended before its declared size")
            offset += count
        }
        result
    } finally {
        posixClose(descriptor)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeWholeFileAtomically(target: String, bytes: ByteArray) {
    val temporary = "$target.${NSUUID.UUID().UUIDString}.tmp"
    val descriptor = posixOpen(temporary, O_WRONLY or O_CREAT or O_EXCL, 0x180)
    if (descriptor < 0) error("Unable to create immutable snapshot")
    try {
        var offset = 0
        while (offset < bytes.size) {
            val count =
                bytes.usePinned { pinned ->
                    posixWrite(
                        descriptor,
                        pinned.addressOf(offset),
                        (bytes.size - offset).convert()
                    ).toInt()
                }
            if (count <= 0) error("Unable to write immutable snapshot")
            offset += count
        }
        if (posixFsync(descriptor) != 0) error("Unable to sync immutable snapshot")
    } finally {
        posixClose(descriptor)
    }
    try {
        if (posixLink(temporary, target) != 0) {
            check(readWholeFile(target).contentEquals(bytes)) { "Immutable feedback snapshot conflict" }
        }
    } finally {
        posixUnlink(temporary)
    }
}
