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

/**
 * The on-disk database was written by a newer app build (for example an older TestFlight build
 * installed over a newer one). Thrown by the single database-open entry ([openRaftSqlDatabase])
 * before any SQLDelight driver exists, so SQLDelight never sees the newer schema; the app shell's
 * launch gate turns it into the "database is newer than the app" recovery page.
 */
internal class RaftDatabaseNewerThanAppException(
    val mismatch: RaftDatabaseVersionMismatch,
) : IllegalStateException(
        "Raft database version ${mismatch.databaseVersion} is newer than the app's " +
            "schema version ${mismatch.supportedVersion}",
    )

internal sealed interface RaftDatabaseLaunchAttempt<out T> {
    data class Ready<T>(val value: T) : RaftDatabaseLaunchAttempt<T>

    data class UpgradeRequired(
        val mismatch: RaftDatabaseVersionMismatch,
    ) : RaftDatabaseLaunchAttempt<Nothing>

    data class Fatal(
        val throwable: Throwable,
    ) : RaftDatabaseLaunchAttempt<Nothing>
}

/**
 * Runs [create] (which reaches the database-open entry) and classifies the outcome. The version
 * check itself lives only in [openRaftSqlDatabase]; this just recognizes its typed error.
 */
internal fun <T> attemptRaftDatabaseLaunch(
    create: () -> T,
): RaftDatabaseLaunchAttempt<T> =
    try {
        RaftDatabaseLaunchAttempt.Ready(create())
    } catch (throwable: Throwable) {
        val newerThanApp = throwable.raftDatabaseNewerThanAppOrNull()
        if (newerThanApp != null) {
            RaftDatabaseLaunchAttempt.UpgradeRequired(newerThanApp.mismatch)
        } else {
            RaftDatabaseLaunchAttempt.Fatal(throwable)
        }
    }

private fun Throwable.raftDatabaseNewerThanAppOrNull(): RaftDatabaseNewerThanAppException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is RaftDatabaseNewerThanAppException) return current
        current = current.cause
        depth += 1
    }
    return null
}
