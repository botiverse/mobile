@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import build.raft.app.base.BridgeModule
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.NULL
import platform.posix.F_OK
import platform.posix.O_RDONLY
import platform.posix.PATH_MAX
import platform.posix.S_IFDIR
import platform.posix.W_OK
import platform.posix.access
import platform.posix.close as posixClose
import platform.posix.fstat
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.open as posixOpen
import platform.posix.pread
import platform.posix.stat

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    SlockSqlDatabase(
        NativeSqliteDriver(
            schema = SlockSqlDatabase.Schema,
            name = SlockSqlDatabase.DatabaseName,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig =
                        configuration.extendedConfig.copy(
                            basePath = ohosDatabaseBasePath()
                        )
                )
            }
        )
    )

internal actual fun readSqliteUserVersionReadOnly(
    databaseName: String,
    basePath: String?,
): Long? {
    val databasePath = "${basePath ?: ohosDatabaseBasePath()}/$databaseName"
    if (access(databasePath, F_OK) != 0) return null
    return readPosixSqliteUserVersionReadOnly(databasePath)
}

private fun ohosDatabaseBasePath(): String {
    val candidates =
        buildList {
            add("/data/storage/el2/base/haps/entry/files")
            add("/data/storage/el2/base/hap/entry/files")
            getenv("TMPDIR")?.toKString()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let(::add)
            getenv("HOME")?.toKString()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let(::add)
            memScoped {
                val buffer = ByteArray(PATH_MAX)
                if (getcwd(buffer.refTo(0), buffer.size.toULong()) != NULL) {
                    add(buffer.toKString().trimEnd('\u0000').trimEnd('/'))
                }
            }
        }
    return candidates.distinct().firstOrNull(::isWritableDirectory)
        ?: "/data/storage/el2/base/haps/entry/files"
}

private fun isWritableDirectory(path: String): Boolean =
    path.isNotBlank() &&
        memScoped {
            val status = alloc<stat>()
            if (stat(path, status.ptr) != 0) return@memScoped false
            val isDirectory = (status.st_mode.toInt() and S_IFDIR) == S_IFDIR
            isDirectory && access(path, W_OK) == 0
        }

private fun readPosixSqliteUserVersionReadOnly(databasePath: String): Long {
    val database = PosixSqliteReadOnlyFile.open(databasePath)
    return try {
        val walPath = "$databasePath-wal"
        val wal =
            if (access(walPath, F_OK) == 0) {
                PosixSqliteReadOnlyFile.open(walPath)
            } else {
                null
            }
        try {
            readSqliteUserVersionFromFiles(database, wal)
        } finally {
            wal?.close()
        }
    } finally {
        database.close()
    }
}

private class PosixSqliteReadOnlyFile private constructor(
    private val descriptor: Int,
    override val size: Long,
) : SqliteReadOnlyFile {
    override fun read(
        offset: Long,
        byteCount: Int,
    ): ByteArray {
        val bytes = ByteArray(byteCount)
        var totalRead = 0
        while (totalRead < byteCount) {
            val count =
                bytes.usePinned { pinned ->
                    pread(
                        descriptor,
                        pinned.addressOf(totalRead),
                        (byteCount - totalRead).convert(),
                        (offset + totalRead).convert(),
                    ).toLong()
                }
            check(count > 0L) { "Unable to read SQLite file" }
            totalRead += count.toInt()
        }
        return bytes
    }

    fun close() {
        posixClose(descriptor)
    }

    companion object {
        fun open(path: String): PosixSqliteReadOnlyFile {
            val descriptor = posixOpen(path, O_RDONLY)
            check(descriptor >= 0) { "Unable to open SQLite file read-only" }
            return try {
                val size =
                    memScoped {
                        val status = alloc<stat>()
                        check(fstat(descriptor, status.ptr) == 0) {
                            "Unable to stat SQLite file"
                        }
                        status.st_size
                    }
                PosixSqliteReadOnlyFile(descriptor, size)
            } catch (failure: Throwable) {
                posixClose(descriptor)
                throw failure
            }
        }
    }
}
