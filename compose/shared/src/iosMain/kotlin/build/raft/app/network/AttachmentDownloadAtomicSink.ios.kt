package build.raft.app.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSThread
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close as posixClose
import platform.posix.creat as posixCreat
import platform.posix.fsync as posixFsync
import platform.posix.rename as posixRename
import platform.posix.unlink as posixUnlink
import platform.posix.write as posixWrite

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink =
    AttachmentDownloadAtomicFileSink(targetPath, attemptToken, IosAttachmentDownloadFileOps)

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) {
    NSThread.sleepForTimeInterval(durationMillis.coerceAtLeast(1L).toDouble() / 1_000.0)
}

@OptIn(ExperimentalForeignApi::class)
private object IosAttachmentDownloadFileOps : AttachmentDownloadFileOps {
    override fun open(path: String): Any? =
        posixCreat(path, (S_IRUSR or S_IWUSR).convert())
            .takeIf { it >= 0 }
            ?.let(::IosAttachmentDownloadFileHandle)

    override fun write(handle: Any, bytes: ByteArray, offset: Int, length: Int): Int =
        bytes.usePinned { pinned ->
            posixWrite(
                (handle as IosAttachmentDownloadFileHandle).descriptor,
                pinned.addressOf(offset),
                length.convert()
            ).toInt()
        }

    override fun fsync(handle: Any): Boolean =
        posixFsync((handle as IosAttachmentDownloadFileHandle).descriptor) == 0

    override fun close(handle: Any): Boolean =
        posixClose((handle as IosAttachmentDownloadFileHandle).descriptor) == 0

    override fun rename(sourcePath: String, targetPath: String): Boolean = posixRename(sourcePath, targetPath) == 0

    override fun unlink(path: String) {
        posixUnlink(path)
    }
}

private class IosAttachmentDownloadFileHandle(val descriptor: Int)
