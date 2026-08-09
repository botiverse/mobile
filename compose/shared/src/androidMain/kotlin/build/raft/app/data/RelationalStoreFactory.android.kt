package build.raft.app.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import build.raft.app.base.BridgeModule
import java.io.File
import java.io.RandomAccessFile

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    SlockSqlDatabase(
        AndroidSqliteDriver(
            schema = SlockSqlDatabase.Schema,
            context = SlockAndroidSqlDatabaseContext.require(),
            name = SlockSqlDatabase.DatabaseName
        )
    )

internal actual fun readSqliteUserVersionReadOnly(
    databaseName: String,
    basePath: String?,
): Long? {
    val databaseFile =
        basePath?.let { directory -> File(directory, databaseName) }
            ?: SlockAndroidSqlDatabaseContext.require().getDatabasePath(databaseName)
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

object SlockAndroidSqlDatabaseContext {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    internal fun require(): Context =
        context ?: error("Slock Android SQLDelight context has not been initialized")
}
