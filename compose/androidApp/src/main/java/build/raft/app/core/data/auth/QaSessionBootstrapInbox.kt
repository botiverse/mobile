package build.raft.app.core.data.auth

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

internal object QaSessionBootstrapInbox {
    private val requestIdPattern = Regex("[A-Za-z0-9._-]{1,80}")
    private const val MaxRequestBytes = 16 * 1024L
    private const val PermissionAndSpecialModeBits = 0xFFF // POSIX 07777

    fun consume(context: Context, requestId: String, suffix: String): ByteArray? {
        if (!requestIdPattern.matches(requestId)) return null
        if (suffix != "ticket" && suffix != "capture") return null
        val directory = File(context.filesDir, "qa-bootstrap/inbox")
        val file = File(directory, "$requestId.$suffix")
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (file.parentFile?.canonicalFile != canonicalDirectory) return null
        return consumeFile(file)
    }

    internal fun consumeFile(file: File, beforeOpenForTests: (() -> Unit)? = null): ByteArray? {
        val initial = runCatching { Os.lstat(file.absolutePath) }.getOrNull() ?: return null
        if (!validRequestStat(initial.st_mode, initial.st_size)) return null
        beforeOpenForTests?.invoke()
        var descriptor: FileDescriptor? = null
        return try {
            descriptor = Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0
            )
            val opened = Os.fstat(descriptor)
            if (
                !validRequestStat(opened.st_mode, opened.st_size) ||
                opened.st_dev != initial.st_dev ||
                opened.st_ino != initial.st_ino
            ) return null
            Os.remove(file.absolutePath)
            readExact(descriptor, opened.st_size.toInt())
        } catch (_: Throwable) {
            null
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
        }
    }

    private fun validRequestStat(mode: Int, size: Long): Boolean =
        OsConstants.S_ISREG(mode) &&
            size in 1..MaxRequestBytes &&
            mode and PermissionAndSpecialModeBits ==
                (OsConstants.S_IRUSR or OsConstants.S_IWUSR)

    private fun readExact(descriptor: FileDescriptor, size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < bytes.size) {
            val read = Os.read(descriptor, bytes, offset, bytes.size - offset)
            if (read <= 0) return null
            offset += read
        }
        val trailing = ByteArray(1)
        return if (Os.read(descriptor, trailing, 0, 1) == 0) bytes else null
    }
}
