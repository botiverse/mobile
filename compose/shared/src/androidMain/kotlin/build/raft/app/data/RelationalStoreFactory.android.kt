package build.raft.app.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import java.io.RandomAccessFile

internal actual fun createRaftSqlDatabase(): RaftSqlDatabase =
    RaftSqlDatabase(
        AndroidSqliteDriver(
            schema = RaftSqlDatabase.Schema,
            context = RaftAndroidSqlDatabaseContext.require(),
            name = RaftSqlDatabase.DatabaseName
        )
    )

internal actual fun readSqliteUserVersionReadOnly(
    databaseName: String,
    basePath: String?,
): Long? {
    val databaseFile =
        basePath?.let { directory -> File(directory, databaseName) }
            ?: RaftAndroidSqlDatabaseContext.require().getDatabasePath(databaseName)
    if (!databaseFile.exists()) return null
    check(databaseFile.isFile) { "SQLite database path is not a regular file" }

    return RandomAccessFile(databaseFile, "r").use { database ->
        val databaseReader = JavaSqliteReadOnlyFile(database)
        val walFile = File("${databaseFile.absolutePath}-wal")
        if (!walFile.exists()) {
            readSqliteUserVersionFromFiles(databaseReader, wal = null)
        } else {
            check(walFile.isFile) { "SQLite WAL path is not a regular file" }
            RandomAccessFile(walFile, "r").use { wal ->
                readSqliteUserVersionFromFiles(databaseReader, JavaSqliteReadOnlyFile(wal))
            }
        }
    }
}

private class JavaSqliteReadOnlyFile(
    private val file: RandomAccessFile,
) : SqliteReadOnlyFile {
    override val size: Long = file.length()

    override fun read(
        offset: Long,
        byteCount: Int,
    ): ByteArray =
        ByteArray(byteCount).also { bytes ->
            file.seek(offset)
            file.readFully(bytes)
        }
}

object RaftAndroidSqlDatabaseContext {
    /**
     * File name of the shared SQLDelight database under the app's databases dir.
     * The Android host's Storage page sizes this file; it reads the name from here
     * so it can never drift from the driver again (it counted a non-existent
     * `slock_shared.db` and always showed 0 B).
     */
    const val DatabaseFileName: String = RaftSqlDatabase.DatabaseName

    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    internal fun require(): Context =
        context ?: error("Slock Android SQLDelight context has not been initialized")
}
