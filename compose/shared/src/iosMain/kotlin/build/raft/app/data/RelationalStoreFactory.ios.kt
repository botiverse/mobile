@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package build.raft.app.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.F_OK
import platform.posix.O_RDONLY
import platform.posix.access
import platform.posix.close as posixClose
import platform.posix.fstat
import platform.posix.open as posixOpen
import platform.posix.pread
import platform.posix.stat

internal actual fun openPlatformRaftSqlDriver(): SqlDriver =
    NativeSqliteDriver(
        schema = RaftSqlDatabase.Schema,
        name = RaftSqlDatabase.DatabaseName,
        onConfiguration = { configuration ->
            configuration.copy(
                extendedConfig =
                    configuration.extendedConfig.copy(
                        basePath = requireIosDatabaseBasePath()
                    )
            )
        }
    )

internal actual fun readSqliteUserVersionReadOnly(
    databaseName: String,
    basePath: String?,
): Long? {
    val directory = basePath ?: existingIosDatabaseBasePathOrNull() ?: return null
    val databasePath = "$directory/$databaseName"
    if (!NSFileManager.defaultManager().fileExistsAtPath(databasePath)) return null
    return readPosixSqliteUserVersionReadOnly(databasePath)
}

private fun requireIosDatabaseBasePath(): String {
    existingIosDatabaseBasePathOrNull()?.let { return it }
    val databaseDirectory = iosDatabaseBasePath()
    val fileManager = NSFileManager.defaultManager()
    val created = fileManager.createDirectoryAtPath(databaseDirectory, true, null, null)
    if (!created && !fileManager.fileExistsAtPath(databaseDirectory)) {
        error("Unable to create iOS Slock SQLDelight database directory")
    }
    return databaseDirectory
}

private fun existingIosDatabaseBasePathOrNull(): String? {
    val databaseDirectory = iosDatabaseBasePath()
    return databaseDirectory.takeIf(NSFileManager.defaultManager()::fileExistsAtPath)
}

private fun iosDatabaseBasePath(): String {
    val applicationSupportDirectory =
        NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
            ?: error("iOS Application Support directory is unavailable for Slock SQLDelight")
    return "$applicationSupportDirectory/Slock/databases"
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
