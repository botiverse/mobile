package build.raft.app.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class RaftDatabaseLaunchRecoveryTest {
    @Test
    fun coldLaunchWithNewerDatabaseShowsRecoveryWithoutEverOpeningSqlDelight() {
        // Production chain: RaftDatabaseLaunchGate -> attemptRaftDatabaseLaunch ->
        // RaftSharedStoreRegistry.get -> provider factory -> openRaftSqlDatabase.
        val launch = CountingDatabaseLaunch(storedVersion = 10L, supportedVersion = 9L)

        val attempt = launch.attempt()

        assertEquals(
            RaftDatabaseVersionMismatch(databaseVersion = 10, supportedVersion = 9),
            assertIs<RaftDatabaseLaunchAttempt.UpgradeRequired>(attempt).mismatch,
        )
        assertEquals(0, launch.driverOpens, "SQLDelight driver must never be opened")
        assertEquals(1, launch.providerFactoryCalls)
        assertNull(launch.registry.currentOrNull(), "a refused launch must not seal a provider")
    }

    @Test
    fun recoveryRetryOpensSqlDelightOnlyOnceTheStoredVersionIsSupported() {
        val launch = CountingDatabaseLaunch(storedVersion = 10L, supportedVersion = 9L)

        assertIs<RaftDatabaseLaunchAttempt.UpgradeRequired>(launch.attempt())
        assertIs<RaftDatabaseLaunchAttempt.UpgradeRequired>(launch.attempt())
        assertEquals(0, launch.driverOpens)

        launch.storedVersion = 9L
        val ready = assertIs<RaftDatabaseLaunchAttempt.Ready<RaftSqlDatabase>>(launch.attempt())

        assertEquals(1, launch.driverOpens)
        assertSame(ready.value, launch.registry.currentOrNull())
        assertEquals(listOf("PRAGMA user_version"), launch.drivers.single().queries)
    }

    @Test
    fun missingSameAndOlderDatabaseVersionsStayOnSqlDelightCreateOrMigrationPath() {
        listOf<Long?>(null, 9L, 8L).forEach { storedVersion ->
            val launch = CountingDatabaseLaunch(storedVersion = storedVersion, supportedVersion = 9L)

            assertIs<RaftDatabaseLaunchAttempt.Ready<RaftSqlDatabase>>(
                launch.attempt(),
                "storedVersion=$storedVersion",
            )
            assertEquals(1, launch.driverOpens, "storedVersion=$storedVersion")
        }
    }

    @Test
    fun newerDatabaseErrorIsRecognizedWhenWrappedByAProviderFactory() {
        val mismatch = RaftDatabaseVersionMismatch(databaseVersion = 10, supportedVersion = 9)

        val attempt =
            attemptRaftDatabaseLaunch<String> {
                throw IllegalStateException("provider creation failed", RaftDatabaseNewerThanAppException(mismatch))
            }

        assertEquals(mismatch, assertIs<RaftDatabaseLaunchAttempt.UpgradeRequired>(attempt).mismatch)
    }

    @Test
    fun versionPreflightFailureRemainsFatalAndNeverOpensSqlDelight() {
        val failure = IllegalStateException("Invalid SQLite database header")
        var driverOpens = 0

        val attempt =
            attemptRaftDatabaseLaunch {
                openRaftSqlDatabase(
                    readDatabaseVersion = { throw failure },
                    supportedVersion = 9L,
                    openDriver = {
                        driverOpens += 1
                        RecordingOpenSqlDriver()
                    },
                )
            }

        assertSame(failure, assertIs<RaftDatabaseLaunchAttempt.Fatal>(attempt).throwable)
        assertEquals(0, driverOpens)
    }

    @Test
    fun sqlDelightCreateOrMigrationFailureRemainsFatal() {
        val failure = IllegalStateException("migration failed: no such column")

        val attempt =
            attemptRaftDatabaseLaunch {
                openRaftSqlDatabase(
                    readDatabaseVersion = { 8L },
                    supportedVersion = 9L,
                    openDriver = { FailingOpenSqlDriver(failure) },
                )
            }

        assertSame(failure, assertIs<RaftDatabaseLaunchAttempt.Fatal>(attempt).throwable)
    }

    @Test
    fun databaseConstructionForcesLazyDriverOpenBeforeProviderCreationReturns() {
        val failure = IllegalStateException("driver open failed")

        val thrown =
            assertFailsWith<IllegalStateException> {
                RaftSqlDatabase(FailingOpenSqlDriver(failure))
            }

        assertSame(failure, thrown)
    }

    @Test
    fun databaseConstructionForcesSqlDelightInitializationWithAReadQuery() {
        val driver = RecordingOpenSqlDriver()

        RaftSqlDatabase(driver)

        assertEquals(listOf("PRAGMA user_version"), driver.queries)
        assertEquals(0, driver.executeCalls)
    }
}

/**
 * The launch chain with a fake on-disk version and a counting driver factory in place of the
 * platform SQLDelight driver, so a test can prove how many times SQLDelight was opened.
 */
private class CountingDatabaseLaunch(
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

private class FailingOpenSqlDriver(
    private val failure: Throwable,
) : SqlDriver {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = throw failure

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = error("not used")

    override fun newTransaction(): QueryResult<Transacter.Transaction> = error("not used")

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}

private class RecordingOpenSqlDriver : SqlDriver {
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
