package build.raft.app.data

internal data class RaftDatabaseVersionMismatch(
    val databaseVersion: Long,
    val supportedVersion: Long,
) {
    init {
        require(databaseVersion > supportedVersion) {
            "databaseVersion must be newer than supportedVersion"
        }
    }
}

internal sealed interface RaftDatabaseLaunchAttempt<out T> {
    data class Ready<T>(val value: T) : RaftDatabaseLaunchAttempt<T>

    data class UpgradeRequired(
        val mismatch: RaftDatabaseVersionMismatch,
    ) : RaftDatabaseLaunchAttempt<Nothing>

    data class Fatal(
        val throwable: Throwable,
    ) : RaftDatabaseLaunchAttempt<Nothing>
}

internal fun <T> attemptRaftDatabaseLaunch(
    create: () -> T,
): RaftDatabaseLaunchAttempt<T> =
    attemptRaftDatabaseLaunch(
        readDatabaseVersion = {
            readSqliteUserVersionReadOnly(RaftSqlDatabase.DatabaseName)
        },
        supportedVersion = RaftSqlDatabase.Schema.version,
        create = create,
    )

internal fun <T> attemptRaftDatabaseLaunch(
    readDatabaseVersion: () -> Long?,
    supportedVersion: Long,
    create: () -> T,
): RaftDatabaseLaunchAttempt<T> =
    try {
        val databaseVersion = readDatabaseVersion()
        if (databaseVersion != null && databaseVersion > supportedVersion) {
            RaftDatabaseLaunchAttempt.UpgradeRequired(
                RaftDatabaseVersionMismatch(
                    databaseVersion = databaseVersion,
                    supportedVersion = supportedVersion,
                )
            )
        } else {
            // Missing databases, same-version databases, and older databases all stay on the
            // canonical SQLDelight path. SQLDelight remains the only owner of create/migrate.
            RaftDatabaseLaunchAttempt.Ready(create())
        }
    } catch (throwable: Throwable) {
        RaftDatabaseLaunchAttempt.Fatal(throwable)
    }
