package build.raft.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

internal actual fun createDefaultPlatformFileResolver(): SlockPlatformFileResolver? =
    SlockIosPlatformFileResolver()

private class SlockIosPlatformFileResolver : SlockPlatformFileResolver {
    override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult {
        val trimmed = fileRef.trim()
        if (trimmed.isEmpty()) {
            return failed("missing_file_ref", "Missing file ref")
        }
        val data =
            when {
                trimmed.startsWith("/") -> NSData.dataWithContentsOfFile(trimmed)
                else -> NSURL.URLWithString(trimmed)?.let { url ->
                    val securityScoped = url.startAccessingSecurityScopedResource()
                    try {
                        NSData.dataWithContentsOfURL(url)
                    } finally {
                        if (securityScoped) {
                            url.stopAccessingSecurityScopedResource()
                        }
                    }
                }
            }
                ?: return failed("attachment_read_failed", "Unable to read file")
        val bytes = data.toByteArray()
        return SlockPlatformFileReadResult(
            code = 200,
            bytes = bytes,
            sizeBytes = bytes.size.toLong()
        )
    }

    private fun failed(errorCode: String, message: String): SlockPlatformFileReadResult =
        SlockPlatformFileReadResult(
            code = 400,
            bytes = ByteArray(0),
            errorCode = errorCode,
            message = message
        )
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return result
}
