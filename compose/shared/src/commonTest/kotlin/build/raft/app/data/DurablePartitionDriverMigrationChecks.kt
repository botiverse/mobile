package build.raft.app.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import build.raft.app.shared.data.local.SlockDatabase
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

internal class InjectedMigrationFailure(val statementNumber: Int) :
    RuntimeException("injected migration failure at DDL $statementNumber")

/** Delegates a generated migration while failing immediately before its Nth DDL reaches the real driver. */
internal class FailAtMigrationStatementSchema(
    private val delegate: SqlSchema<QueryResult.Value<Unit>>,
    private val failAt: Int,
) : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
        get() = delegate.version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = delegate.create(driver)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = delegate.migrate(
        FailAtNthExecuteDriver(driver, failAt),
        oldVersion,
        newVersion,
        *callbacks,
    )
}

private class FailAtNthExecuteDriver(
    private val delegate: SqlDriver,
    private val failAt: Int,
) : SqlDriver by delegate {
    private var executes: Int = 0

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        executes += 1
        if (executes == failAt) throw InjectedMigrationFailure(failAt)
        return delegate.execute(identifier, sql, parameters, binders)
    }
}

/** Minimal file-backed v1 fixture for real Android/Native rollback semantics. */
internal object DurablePartitionLegacyV1Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            null,
            """
            CREATE TABLE messageEntity (
              id TEXT NOT NULL PRIMARY KEY,
              serverId TEXT NOT NULL,
              channelId TEXT NOT NULL,
              scopeKind TEXT NOT NULL,
              seq INTEGER NOT NULL,
              createdAt TEXT NOT NULL,
              canonicalFactJson TEXT NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX messageEntity_channelSeqId ON messageEntity(serverId, scopeKind, channelId, seq, id)",
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE syncCursorEntity (
              scopeId TEXT NOT NULL,
              serverId TEXT NOT NULL,
              scopeKind TEXT NOT NULL,
              latestSeq INTEGER,
              oldestLoadedSeq INTEGER,
              newestLoadedSeq INTEGER,
              hasLoadedOldest INTEGER NOT NULL DEFAULT 0,
              hasLoadedNewest INTEGER NOT NULL DEFAULT 1,
              updatedAtMs INTEGER NOT NULL,
              epoch TEXT,
              PRIMARY KEY (serverId, scopeKind, scopeId)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE outboxMessageEntity (
              localId TEXT NOT NULL PRIMARY KEY,
              serverId TEXT NOT NULL,
              channelId TEXT NOT NULL,
              body TEXT NOT NULL,
              attachmentIdsJson TEXT NOT NULL DEFAULT '[]',
              asTask INTEGER NOT NULL DEFAULT 0,
              status TEXT NOT NULL,
              createdAtMs INTEGER NOT NULL,
              lastErrorCode TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE durablePartitionMigrationSentinel(id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX durablePartitionMigrationSentinel_value ON durablePartitionMigrationSentinel(value)",
            0,
        )
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/**
 * Runs the exact N=1..6 rollback/retry matrix against a real file-backed driver's automatic open path.
 * The caller supplies AndroidSqliteDriver or NativeSqliteDriver construction.
 */
internal fun assertFileBackedDurablePartitionMigrationRollbackRetry(
    platformName: String,
    openDriver: (schema: SqlSchema<QueryResult.Value<Unit>>, databaseName: String) -> SqlDriver,
) {
    for (failAt in 1..6) {
        val name = "slock-durable-$platformName-$failAt-${Random.nextLong().toString().replace('-', 'n')}.db"
        openDriver(DurablePartitionLegacyV1Schema, name).useDriver { driver ->
            seedLegacyMigrationRows(driver)
            assertLegacyFixtureIntact(driver, "fresh v1 fixture")
        }

        val failure = assertFails("$platformName fail-at-$failAt must abort driver open") {
            openDriver(FailAtMigrationStatementSchema(SlockDatabase.Schema, failAt), name).useDriver { driver ->
                driver.scalarLong("SELECT 1")
            }
        }
        assertTrue(
            failure.hasCause<InjectedMigrationFailure>(),
            "$platformName fail-at-$failAt must expose the injected DDL failure, got $failure",
        )

        openDriver(DurablePartitionLegacyV1Schema, name).useDriver { driver ->
            assertLegacyFixtureIntact(driver, "$platformName fail-at-$failAt rollback")
        }

        openDriver(SlockDatabase.Schema, name).useDriver { driver ->
            assertMigratedFixtureIntact(driver, "$platformName fail-at-$failAt retry")
        }
    }
}

private fun seedLegacyMigrationRows(driver: SqlDriver) {
    driver.execute(
        null,
        "INSERT INTO messageEntity(id,serverId,channelId,scopeKind,seq,createdAt,canonicalFactJson) " +
            "VALUES ('legacy-message','server-a','channel-a','channel',7,'t','{}')",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO syncCursorEntity(scopeId,serverId,scopeKind,latestSeq,updatedAtMs) " +
            "VALUES ('channel-a','server-a','channel',7,100)",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO outboxMessageEntity(localId,serverId,channelId,body,status,createdAtMs) " +
            "VALUES ('outbox-a','server-a','channel-a','keep-me','pending',100)",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO durablePartitionMigrationSentinel(id,value) VALUES ('sentinel-a','keep-me')",
        0,
    )
}

private fun assertLegacyFixtureIntact(driver: SqlDriver, label: String) {
    assertEquals(
        listOf("id", "serverId", "channelId", "scopeKind", "seq", "createdAt", "canonicalFactJson"),
        driver.tableColumns("messageEntity"),
        "$label: old message columns must be byte-for-byte rollback-visible",
    )
    assertTrue(driver.hasMasterObject("index", "messageEntity_channelSeqId"), "$label: old index missing")
    assertFalse(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"), "$label: new index leaked")
    assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"), "$label: old message row lost")
    assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"), "$label: old cursor row lost")
    assertEquals("keep-me", driver.scalarString("SELECT body FROM outboxMessageEntity WHERE localId='outbox-a'"))
    assertEquals(
        "keep-me",
        driver.scalarString("SELECT value FROM durablePartitionMigrationSentinel WHERE id='sentinel-a'"),
    )
}

private fun assertMigratedFixtureIntact(driver: SqlDriver, label: String) {
    assertEquals(
        listOf(
            "serverId",
            "principalId",
            "consumerId",
            "id",
            "channelId",
            "scopeKind",
            "seq",
            "createdAt",
            "canonicalFactJson",
        ),
        driver.tableColumns("messageEntity"),
        "$label: partitioned message schema missing",
    )
    assertFalse(driver.hasMasterObject("index", "messageEntity_channelSeqId"), "$label: old index survived")
    assertTrue(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"), "$label: new index missing")
    assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"), "$label: old cache rows must not copy")
    assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"), "$label: old cursors must not copy")
    assertEquals("keep-me", driver.scalarString("SELECT body FROM outboxMessageEntity WHERE localId='outbox-a'"))
    assertEquals(
        "keep-me",
        driver.scalarString("SELECT value FROM durablePartitionMigrationSentinel WHERE id='sentinel-a'"),
    )
    assertTrue(
        driver.hasMasterObject("index", "durablePartitionMigrationSentinel_value"),
        "$label: non-core index definition must survive",
    )
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}

private inline fun SqlDriver.useDriver(block: (SqlDriver) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}

internal fun SqlDriver.tableColumns(table: String): List<String> = queryRows("PRAGMA table_info($table)") { cursor ->
    cursor.getString(1).orEmpty()
}

internal fun SqlDriver.hasMasterObject(type: String, name: String): Boolean =
    scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type='$type' AND name='$name'") == 1L

internal fun SqlDriver.scalarLong(sql: String): Long = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
    parameters = 0,
).value

internal fun SqlDriver.scalarString(sql: String): String? = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
    parameters = 0,
).value

internal fun <T> SqlDriver.queryRows(sql: String, row: (SqlCursor) -> T): List<T> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<T>()
        while (cursor.next().value) rows += row(cursor)
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value
