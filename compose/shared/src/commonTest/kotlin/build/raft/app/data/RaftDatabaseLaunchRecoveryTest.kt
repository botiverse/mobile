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
import kotlin.test.assertSame

class RaftDatabaseLaunchRecoveryTest {
    @Test
    fun newerStoredDatabaseVersionRequiresUpgradeWithoutCreatingProvider() {
        var createCalls = 0
        val attempt =
            attemptRaftDatabaseLaunch(
                readDatabaseVersion = { 7L },
                supportedVersion = 6L,
                create = {
                    createCalls += 1
                    "ready"
                },
            )

        assertEquals(
            RaftDatabaseVersionMismatch(databaseVersion = 7, supportedVersion = 6),
            assertIs<RaftDatabaseLaunchAttempt.UpgradeRequired>(attempt).mismatch,
        )
        assertEquals(0, createCalls)
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

    @Test
    fun missingSameAndOlderDatabaseVersionsStayOnSqlDelightCreateOrMigrationPath() {
        listOf<Long?>(null, 6L, 5L).forEach { storedVersion ->
            val provider = Any()
            val attempt =
                attemptRaftDatabaseLaunch(
                    readDatabaseVersion = { storedVersion },
                    supportedVersion = 6L,
                    create = { provider },
                )

            assertSame(
                provider,
                assertIs<RaftDatabaseLaunchAttempt.Ready<Any>>(attempt).value,
                "storedVersion=$storedVersion",
            )
        }
    }

    @Test
    fun versionPreflightFailureRemainsFatalAndDoesNotCreateProvider() {
        val failure = IllegalStateException("read-only SQLite open failed")
        var createCalls = 0

        val attempt =
            attemptRaftDatabaseLaunch(
                readDatabaseVersion = { throw failure },
                supportedVersion = 6L,
                create = {
                    createCalls += 1
                    "ready"
                },
            )

        assertSame(failure, assertIs<RaftDatabaseLaunchAttempt.Fatal>(attempt).throwable)
        assertEquals(0, createCalls)
    }

    @Test
    fun sqlDelightCreateOrMigrationFailureRemainsFatal() {
        val failure = IllegalStateException("migration failed: no such column")

        val attempt =
            attemptRaftDatabaseLaunch<String>(
                readDatabaseVersion = { 5L },
                supportedVersion = 6L,
                create = { throw failure },
            )

        assertSame(failure, assertIs<RaftDatabaseLaunchAttempt.Fatal>(attempt).throwable)
    }

    @Test
    fun failedRegistryCreationDoesNotSealRetryAfterUpgradeBlock() {
        var versionReads = 0
        var providerCreations = 0
        val registry =
            RaftSharedStoreRegistryState<String, Unit>(
                defaultFactory = {
                    providerCreations += 1
                    "ready"
                },
                updateExisting = { _, _ -> },
            )
        val readDatabaseVersion = {
            versionReads += 1
            if (versionReads == 1) 7L else 6L
        }

        val first =
            attemptRaftDatabaseLaunch(
                readDatabaseVersion = readDatabaseVersion,
                supportedVersion = 6L,
                create = { registry.get(Unit) },
            )
        val second =
            attemptRaftDatabaseLaunch(
                readDatabaseVersion = readDatabaseVersion,
                supportedVersion = 6L,
                create = { registry.get(Unit) },
            )

        assertIs<RaftDatabaseLaunchAttempt.UpgradeRequired>(first)
        assertEquals("ready", assertIs<RaftDatabaseLaunchAttempt.Ready<String>>(second).value)
        assertEquals(2, versionReads)
        assertEquals(1, providerCreations)
        assertEquals("ready", registry.currentOrNull())
    }
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
