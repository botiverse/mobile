package build.raft.app.data

internal data class SlockDatabaseVersionMismatch(
    val databaseVersion: Long,
    val supportedVersion: Long,
) {
    init {
        require(databaseVersion > supportedVersion) {
            "databaseVersion must be newer than supportedVersion"
        }
    }
}

internal sealed interface SlockDatabaseLaunchAttempt<out T> {
    data class Ready<T>(val value: T) : SlockDatabaseLaunchAttempt<T>

    data class UpgradeRequired(
        val mismatch: SlockDatabaseVersionMismatch,
    ) : SlockDatabaseLaunchAttempt<Nothing>

    data class Fatal(
        val throwable: Throwable,
    ) : SlockDatabaseLaunchAttempt<Nothing>
}

internal fun <T> attemptSlockDatabaseLaunch(
    create: () -> T,
): SlockDatabaseLaunchAttempt<T> =
    attemptSlockDatabaseLaunch(
        readDatabaseVersion = {
            readSqliteUserVersionReadOnly(SlockSqlDatabase.DatabaseName)
        },
        supportedVersion = SlockSqlDatabase.Schema.version,
        create = create,
    )

internal fun <T> attemptSlockDatabaseLaunch(
    readDatabaseVersion: () -> Long?,
    supportedVersion: Long,
    create: () -> T,
): SlockDatabaseLaunchAttempt<T> =
    try {
        val databaseVersion = readDatabaseVersion()
        if (databaseVersion != null && databaseVersion > supportedVersion) {
            SlockDatabaseLaunchAttempt.UpgradeRequired(
                SlockDatabaseVersionMismatch(
                    databaseVersion = databaseVersion,
                    supportedVersion = supportedVersion,
                )
            )
        } else {
            // Missing databases, same-version databases, and older databases all stay on the
            // canonical SQLDelight path. SQLDelight remains the only owner of create/migrate.
            SlockDatabaseLaunchAttempt.Ready(create())
        }
    } catch (throwable: Throwable) {
        SlockDatabaseLaunchAttempt.Fatal(throwable)
    }
