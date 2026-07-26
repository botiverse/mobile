package build.raft.app.core.data.auth

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

internal object QaSessionBootstrapInbox {
    private val requestIdPattern = Regex("[A-Za-z0-9._-]{1,80}")
    private const val MaxRequestBytes = 16 * 1024L
    private const val PermissionAndSpecialModeBits = 0xFFF // POSIX 07777

    internal sealed interface ConsumeResult {
        data class Consumed(val bytes: ByteArray) : ConsumeResult

        data class Rejected(val code: RejectionCode) : ConsumeResult
    }

    internal enum class RejectionCode(val wire: String) {
        RequestId("request_id"),
        Suffix("suffix"),
        Directory("directory"),
        Parent("parent"),
        Missing("missing"),
        StatDenied("stat_denied"),
        StatError("stat_error"),
        NotRegular("not_regular"),
        Size("size"),
        Mode("mode"),
        Changed("changed"),
        Remove("remove"),
        Read("read"),
        Open("open"),
    }

    fun consume(context: Context, requestId: String, suffix: String): ByteArray? =
        (consumeDetailed(context, requestId, suffix) as? ConsumeResult.Consumed)?.bytes

    internal fun consumeDetailed(context: Context, requestId: String, suffix: String): ConsumeResult {
        if (!requestIdPattern.matches(requestId)) return ConsumeResult.Rejected(RejectionCode.RequestId)
        if (suffix != "ticket" && suffix != "session" && suffix != "capture") {
            return ConsumeResult.Rejected(RejectionCode.Suffix)
        }
        val directory = File(context.filesDir, "qa-bootstrap/inbox")
        val file = File(directory, "$requestId.$suffix")
        val canonicalDirectory =
            runCatching { directory.canonicalFile }.getOrNull()
                ?: return ConsumeResult.Rejected(RejectionCode.Directory)
        if (file.parentFile?.canonicalFile != canonicalDirectory) {
            return ConsumeResult.Rejected(RejectionCode.Parent)
        }
        return consumeFileDetailed(file)
    }

    internal fun consumeFile(file: File, beforeOpenForTests: (() -> Unit)? = null): ByteArray? =
        (consumeFileDetailed(file, beforeOpenForTests) as? ConsumeResult.Consumed)?.bytes

    internal fun consumeFileDetailed(
        file: File,
        beforeOpenForTests: (() -> Unit)? = null
    ): ConsumeResult {
        val initial =
            try {
                Os.lstat(file.absolutePath)
            } catch (error: Throwable) {
                return ConsumeResult.Rejected(rejectionForLstatFailure(error))
            }
        rejectionForStat(initial.st_mode, initial.st_size)?.let {
            return ConsumeResult.Rejected(it)
        }
        beforeOpenForTests?.invoke()
        var descriptor: FileDescriptor? = null
        return try {
            descriptor = Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0
            )
            val opened = Os.fstat(descriptor)
            rejectionForStat(opened.st_mode, opened.st_size)?.let {
                return ConsumeResult.Rejected(it)
            }
            if (opened.st_dev != initial.st_dev || opened.st_ino != initial.st_ino) {
                return ConsumeResult.Rejected(RejectionCode.Changed)
            }
            try {
                Os.remove(file.absolutePath)
            } catch (_: Throwable) {
                return ConsumeResult.Rejected(RejectionCode.Remove)
            }
            val bytes = readExact(descriptor, opened.st_size.toInt())
                ?: return ConsumeResult.Rejected(RejectionCode.Read)
            ConsumeResult.Consumed(bytes)
        } catch (_: Throwable) {
            ConsumeResult.Rejected(RejectionCode.Open)
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
        }
    }

    private fun rejectionForStat(mode: Int, size: Long): RejectionCode? =
        when {
            !OsConstants.S_ISREG(mode) -> RejectionCode.NotRegular
            size !in 1..MaxRequestBytes -> RejectionCode.Size
            mode and PermissionAndSpecialModeBits !=
                (OsConstants.S_IRUSR or OsConstants.S_IWUSR) -> RejectionCode.Mode
            else -> null
        }

    internal fun rejectionForLstatFailure(error: Throwable): RejectionCode =
        when ((error as? ErrnoException)?.errno) {
            OsConstants.ENOENT -> RejectionCode.Missing
            OsConstants.EACCES,
            OsConstants.EPERM -> RejectionCode.StatDenied
            else -> RejectionCode.StatError
        }

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
