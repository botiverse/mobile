package build.raft.app.network

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String,
): AttachmentDownloadAtomicSink = AttachmentDownloadAtomicFileSink(targetPath, attemptToken, DesktopAttachmentDownloadFileOps)

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) {
    Thread.sleep(durationMillis.coerceAtLeast(1L))
}

private object DesktopAttachmentDownloadFileOps : AttachmentDownloadFileOps {
    override fun open(path: String): Any? =
        runCatching {
            FileChannel.open(
                Paths.get(path),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }.getOrNull()

    override fun write(handle: Any, bytes: ByteArray, offset: Int, length: Int): Int =
        (handle as FileChannel).write(ByteBuffer.wrap(bytes, offset, length))

    override fun fsync(handle: Any): Boolean = runCatching { (handle as FileChannel).force(true) }.isSuccess

    override fun close(handle: Any): Boolean = runCatching { (handle as FileChannel).close() }.isSuccess

    override fun rename(sourcePath: String, targetPath: String): Boolean =
        runCatching {
            Files.move(Paths.get(sourcePath), Paths.get(targetPath), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess

    override fun unlink(path: String) {
        runCatching { File(path).delete() }
    }
}
