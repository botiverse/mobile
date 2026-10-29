package build.raft.app.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * The launch chain with a fake on-disk version and a counting driver factory in place of the
 * platform SQLDelight driver, so a test can prove how many times SQLDelight was opened.
 */
internal class CountingDatabaseLaunch(
    var storedVersion: Long?,
    private val supportedVersion: Long,
) {
    var providerFactoryCalls = 0
        private set
    val drivers = mutableListOf<RecordingOpenSqlDriver>()
    val driverOpens: Int
        get() = drivers.size

    val registry =
        RaftSharedStoreRegistryState<RaftSqlDatabase, Unit>(
            defaultFactory = {
                providerFactoryCalls += 1
                openRaftSqlDatabase(
                    readDatabaseVersion = { storedVersion },
                    supportedVersion = supportedVersion,
                    openDriver = { RecordingOpenSqlDriver().also(drivers::add) },
                )
            },
            updateExisting = { _, _ -> },
        )

    fun attempt(): RaftDatabaseLaunchAttempt<RaftSqlDatabase> = attemptRaftDatabaseLaunch { registry.get(Unit) }
}

internal class RecordingOpenSqlDriver : SqlDriver {
    val queries = mutableListOf<String>()
    var executeCalls: Int = 0

    @Suppress("UNCHECKED_CAST")
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        queries += sql
        return QueryResult.Value(Unit as R)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        executeCalls += 1
        return QueryResult.Value(0L)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = error("not used")

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}
