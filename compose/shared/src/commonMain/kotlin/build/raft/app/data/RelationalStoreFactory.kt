package build.raft.app.data


internal expect fun createRaftSqlDatabase(): RaftSqlDatabase

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
