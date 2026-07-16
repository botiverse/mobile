package build.raft.app.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSThread
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.posix.rename

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink = IosAttachmentDownloadAtomicSink(targetPath, attemptToken)

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) {
    NSThread.sleepForTimeInterval(durationMillis.coerceAtLeast(1L).toDouble() / 1_000.0)
}

@OptIn(ExperimentalForeignApi::class)
private class IosAttachmentDownloadAtomicSink(
    private val targetPath: String,
    attemptToken: String
) : AttachmentDownloadAtomicSink {
    private val fileManager = NSFileManager.defaultManager()
    private val stagingPath = "$targetPath.part-${attemptToken.safePathToken()}"
    private var handle: NSFileHandle? = null

    override fun begin(): Boolean =
        runCatching {
            val parent = targetPath.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotBlank()) {
                fileManager.createDirectoryAtPath(parent, true, null, null)
            }
            fileManager.removeItemAtPath(stagingPath, error = null)
            if (!fileManager.createFileAtPath(stagingPath, contents = null, attributes = null)) return false
            handle = NSFileHandle.fileHandleForWritingAtPath(stagingPath)
            handle != null
        }.getOrDefault(false)

    override fun write(bytes: ByteArray): Boolean =
        runCatching {
            val output = handle ?: return false
            if (bytes.isEmpty()) return true
            val data = bytes.usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert()) }
            output.writeData(data)
            true
        }.getOrDefault(false)

    override fun commit(): Boolean =
        runCatching {
            val output = handle ?: return false
            output.synchronizeFile()
            output.closeFile()
            handle = null
            rename(stagingPath, targetPath) == 0 && fileManager.fileExistsAtPath(targetPath)
        }.getOrDefault(false)

    override fun abort() {
        runCatching { handle?.closeFile() }
        handle = null
        runCatching { fileManager.removeItemAtPath(stagingPath, error = null) }
    }
}

private fun String.safePathToken(): String =
    filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifBlank { "attempt" }
