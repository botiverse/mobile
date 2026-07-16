package build.raft.app.network

import android.system.Os
import java.io.File
import java.io.FileOutputStream

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink = AndroidAttachmentDownloadAtomicSink(File(targetPath), attemptToken)

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) {
    Thread.sleep(durationMillis.coerceAtLeast(1L))
}

private class AndroidAttachmentDownloadAtomicSink(
    private val target: File,
    attemptToken: String
) : AttachmentDownloadAtomicSink {
    private val staging = File(target.parentFile, "${target.name}.part-${attemptToken.safePathToken()}")
    private var output: FileOutputStream? = null

    override fun begin(): Boolean =
        runCatching {
            target.parentFile?.mkdirs()
            staging.delete()
            output = FileOutputStream(staging, false)
            true
        }.getOrDefault(false)

    override fun write(bytes: ByteArray): Boolean =
        runCatching {
            output?.write(bytes) ?: return false
            true
        }.getOrDefault(false)

    override fun commit(): Boolean =
        runCatching {
            val stream = output ?: return false
            stream.fd.sync()
            stream.close()
            output = null
            if (!staging.isFile) return false
            Os.rename(staging.absolutePath, target.absolutePath)
            target.isFile
        }.getOrDefault(false)

    override fun abort() {
        runCatching { output?.close() }
        output = null
        runCatching { staging.delete() }
    }
}

private fun String.safePathToken(): String =
    filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifBlank { "attempt" }
