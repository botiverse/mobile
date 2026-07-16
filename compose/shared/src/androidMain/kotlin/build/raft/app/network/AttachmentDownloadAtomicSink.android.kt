package build.raft.app.network

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink =
    AttachmentDownloadAtomicFileSink(targetPath, attemptToken, AndroidAttachmentDownloadFileOps)

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) {
    Thread.sleep(durationMillis.coerceAtLeast(1L))
}

private object AndroidAttachmentDownloadFileOps : AttachmentDownloadFileOps {
    override fun open(path: String): Any =
        Os.open(path, OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_WRONLY, 0x180)

    override fun write(handle: Any, bytes: ByteArray, offset: Int, length: Int): Int =
        Os.write(handle as FileDescriptor, bytes, offset, length)

    override fun fsync(handle: Any): Boolean = runCatching { Os.fsync(handle as FileDescriptor) }.isSuccess

    override fun close(handle: Any): Boolean = runCatching { Os.close(handle as FileDescriptor) }.isSuccess

    override fun rename(sourcePath: String, targetPath: String): Boolean =
        runCatching { Os.rename(sourcePath, targetPath) }.isSuccess

    override fun unlink(path: String) {
        runCatching { File(path).delete() }
    }
}
