@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.posix.O_RDONLY
import platform.posix.close as posixClose
import platform.posix.open as posixOpen
import platform.posix.read as posixRead

internal actual fun createDefaultPlatformFileResolver(): RaftPlatformFileResolver? =
    RaftIosPlatformFileResolver()

private class RaftIosPlatformFileResolver : RaftPlatformFileResolver {
    override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult {
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
            RaftPlatformFileReadResult(code = 200, bytes = bytes, sizeBytes = bytes.size.toLong())
        } finally {
            scoped.close()
        }
    }

    override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult {
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
            RaftPlatformFileStatResult(code = 200, sizeBytes = sizeBytes)
        } finally {
            scoped.close()
        }
    }

    override suspend fun openFileStream(
        fileRef: String,
        expectedSizeBytes: Long
    ): RaftPlatformFileChunkSource {
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
        return object : RaftPlatformFileChunkSource {
            override suspend fun read(maxBytes: Int): ByteArray = readChunk(descriptor, maxBytes)

            override fun close() {
                runCatching { posixClose(descriptor) }
                scoped.close()
            }
        }
    }

    private fun failed(errorCode: String, message: String): RaftPlatformFileStatResult =
        RaftPlatformFileStatResult(
            code = 400,
            errorCode = errorCode,
            message = message
        )

    private fun failedRead(errorCode: String, message: String): RaftPlatformFileReadResult =
        RaftPlatformFileReadResult(
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
