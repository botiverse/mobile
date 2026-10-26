package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

internal actual fun createRaftSqlDatabase(): RaftSqlDatabase =
    RaftSqlDatabase(
        JdbcSqliteDriver(
            url = "jdbc:sqlite:" + File(RaftDesktopPaths.databaseDir, RaftSqlDatabase.DatabaseName).absolutePath,
            properties = Properties(),
            schema = RaftSqlDatabase.Schema,
        )
    )

internal actual fun readSqliteUserVersionReadOnly(
    databaseName: String,
    basePath: String?,
): Long? {
    val databaseFile = File(basePath ?: RaftDesktopPaths.databaseDir.absolutePath, databaseName)
    if (!databaseFile.exists()) return null
    check(databaseFile.isFile) { "SQLite database path is not a regular file" }
    return RandomAccessFile(databaseFile, "r").use { database ->
        val walFile = File("${databaseFile.absolutePath}-wal")
        if (!walFile.exists()) {
            readSqliteUserVersionFromFiles(DesktopSqliteReadOnlyFile(database), wal = null)
        } else {
            RandomAccessFile(walFile, "r").use { wal ->
                readSqliteUserVersionFromFiles(DesktopSqliteReadOnlyFile(database), DesktopSqliteReadOnlyFile(wal))
            }
        }
    }
}

private class DesktopSqliteReadOnlyFile(private val file: RandomAccessFile) : SqliteReadOnlyFile {
    override val size: Long = file.length()

    override fun read(offset: Long, byteCount: Int): ByteArray =
        ByteArray(byteCount).also { bytes ->
            file.seek(offset)
            file.readFully(bytes)
        }
}

/** In-process only, as on Android: no platform listener consumes app-scope events. */
internal actual fun createRaftPlatformEventBus(): RaftEventBus = RaftEnhancedNotifyCenter

// TODO(desktop): attachment uploads (file picker -> fileRef) are not wired on desktop yet.
internal actual fun createDefaultPlatformFileResolver(): RaftPlatformFileResolver? = null
