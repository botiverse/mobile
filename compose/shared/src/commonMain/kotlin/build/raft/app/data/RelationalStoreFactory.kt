package build.raft.app.data

import app.cash.sqldelight.db.SqlDriver

/**
 * The single production database-open entry: every path that needs the shared SQLDelight database
 * (the shared store provider, and through it every page) goes through here.
 */
internal fun createRaftSqlDatabase(): RaftSqlDatabase =
    openRaftSqlDatabase(
        readDatabaseVersion = { readSqliteUserVersionReadOnly(RaftSqlDatabase.DatabaseName) },
        supportedVersion = RaftSqlDatabase.Schema.version,
        openDriver = ::openPlatformRaftSqlDriver,
    )

/**
 * Checks the stored schema version with read-only file I/O, then opens SQLDelight.
 *
 * A database newer than [supportedVersion] throws [RaftDatabaseNewerThanAppException] without
 * calling [openDriver]: SQLDelight would otherwise fail with "Database version N newer than config
 * version M". Missing, same-version and older databases stay on SQLDelight's create/migrate path.
 */
internal fun openRaftSqlDatabase(
    readDatabaseVersion: () -> Long?,
    supportedVersion: Long,
    openDriver: () -> SqlDriver,
): RaftSqlDatabase {
    val databaseVersion = readDatabaseVersion()
    if (databaseVersion != null && databaseVersion > supportedVersion) {
        throw RaftDatabaseNewerThanAppException(
            RaftDatabaseVersionMismatch(
                databaseVersion = databaseVersion,
                supportedVersion = supportedVersion,
            ),
        )
    }
    return RaftSqlDatabase(openDriver())
}

/** Platform SQLDelight driver for [RaftSqlDatabase.DatabaseName]. Only [createRaftSqlDatabase] calls it. */
internal expect fun openPlatformRaftSqlDriver(): SqlDriver

/**
 * Reads SQLite's stored schema version without creating the database, running migrations, or
 * opening the file through SQLite.
 *
 * A missing database returns null so [createRaftSqlDatabase] remains the sole owner of database
 * creation. Platform implementations must use read-only file I/O for the database and WAL; even a
 * read-only SQLite connection may update the WAL shared-memory file.
 */
internal expect fun readSqliteUserVersionReadOnly(
    databaseName: String,
    basePath: String? = null,
): Long?
