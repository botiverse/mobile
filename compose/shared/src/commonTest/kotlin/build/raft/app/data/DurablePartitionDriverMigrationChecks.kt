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

/** Minimal file-backed v2 fixture for the isolated one-DDL viewer-overlay migration. */
internal object DurablePartitionV2Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 2L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            null,
            """
            CREATE TABLE messageEntity (
              serverId TEXT NOT NULL,
              principalId TEXT NOT NULL,
              consumerId TEXT NOT NULL,
              id TEXT NOT NULL,
              channelId TEXT NOT NULL,
              scopeKind TEXT NOT NULL,
              seq INTEGER NOT NULL,
              createdAt TEXT NOT NULL,
              canonicalFactJson TEXT NOT NULL,
              PRIMARY KEY (serverId, principalId, consumerId, id)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "CREATE INDEX messageEntity_partitionChannelSeqId " +
                "ON messageEntity(serverId,principalId,consumerId,scopeKind,channelId,seq,id)",
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE syncCursorEntity (
              scopeId TEXT NOT NULL,
              serverId TEXT NOT NULL,
              principalId TEXT NOT NULL,
              consumerId TEXT NOT NULL,
              scopeKind TEXT NOT NULL,
              latestSeq INTEGER,
              oldestLoadedSeq INTEGER,
              newestLoadedSeq INTEGER,
              hasLoadedOldest INTEGER NOT NULL DEFAULT 0,
              hasLoadedNewest INTEGER NOT NULL DEFAULT 1,
              updatedAtMs INTEGER NOT NULL,
              epoch TEXT,
              PRIMARY KEY (serverId, principalId, consumerId, scopeKind, scopeId)
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

private data class DriverMasterDefinition(
    val type: String,
    val name: String,
    val tableName: String,
    val sql: String,
)

private data class DriverOutboxRow(
    val localId: String,
    val serverId: String,
    val channelId: String,
    val body: String,
    val attachmentIdsJson: String,
    val asTask: Long,
    val status: String,
    val createdAtMs: Long,
    val lastErrorCode: String?,
)

/**
 * Runs the exact v1->v9 N=1..26 and v2->v9 N=1..20 rollback/retry matrices against a real file-backed
 * driver's automatic open path.
 * The caller supplies AndroidSqliteDriver or NativeSqliteDriver construction.
 */
internal fun assertFileBackedDurablePartitionMigrationRollbackRetry(
    platformName: String,
    openDriver: (schema: SqlSchema<QueryResult.Value<Unit>>, databaseName: String) -> SqlDriver,
    cleanupDatabase: (databaseName: String) -> Unit,
) {
    for (failAt in 1..26) {
        val name = "slock-durable-$platformName-$failAt-${Random.nextLong().toString().replace('-', 'n')}.db"
        cleanupDatabase(name)
        try {
            var expectedNonCoreMaster = emptyList<DriverMasterDefinition>()
            var expectedOutbox: DriverOutboxRow? = null
            openDriver(DurablePartitionLegacyV1Schema, name).useDriver { driver ->
                seedLegacyMigrationRows(driver)
                assertEquals(1L, driver.scalarLong("PRAGMA user_version"), "fresh v1 fixture version")
                assertLegacyFixtureIntact(driver, "fresh v1 fixture")
                expectedNonCoreMaster = driver.nonCoreMasterDefinitions()
                expectedOutbox = driver.outboxRow()
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
                assertEquals(1L, driver.scalarLong("PRAGMA user_version"), "$platformName fail-at-$failAt version")
                assertLegacyFixtureIntact(driver, "$platformName fail-at-$failAt rollback")
                assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                assertEquals(expectedOutbox, driver.outboxRow())
            }

            openDriver(SlockDatabase.Schema, name).useDriver { driver ->
                assertEquals(9L, driver.scalarLong("PRAGMA user_version"), "$platformName retry version")
                assertMigratedFixtureIntact(driver, "$platformName fail-at-$failAt retry")
                assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                assertEquals(expectedOutbox, driver.outboxRow())
                seedNewPartitionRows(driver)
            }

            openDriver(SlockDatabase.Schema, name).useDriver { driver ->
                assertEquals(9L, driver.scalarLong("PRAGMA user_version"), "$platformName second reopen version")
                assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity WHERE id='partition-message'"))
                assertEquals(
                    11L,
                    driver.scalarLong(
                        "SELECT latestSeq FROM syncCursorEntity " +
                            "WHERE serverId='server-a' AND principalId='principal-a' " +
                            "AND consumerId='sync-core.messages.v1' AND scopeKind='channel' AND scopeId='channel-a'",
                    ),
                )
                assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                assertEquals(expectedOutbox, driver.outboxRow())
            }
        } finally {
            cleanupDatabase(name)
        }
    }

    assertFileBackedV2ToV9RollbackRetry(platformName, openDriver, cleanupDatabase)
}

private fun assertFileBackedV2ToV9RollbackRetry(
    platformName: String,
    openDriver: (schema: SqlSchema<QueryResult.Value<Unit>>, databaseName: String) -> SqlDriver,
    cleanupDatabase: (databaseName: String) -> Unit,
) {
    val name = "slock-overlay-$platformName-${Random.nextLong().toString().replace('-', 'n')}.db"
    cleanupDatabase(name)
    try {
        var expectedNonCoreMaster = emptyList<DriverMasterDefinition>()
        var expectedOutbox: DriverOutboxRow? = null
        openDriver(DurablePartitionV2Schema, name).useDriver { driver ->
            seedV2MigrationRows(driver)
            assertEquals(2L, driver.scalarLong("PRAGMA user_version"), "$platformName fresh v2 version")
            assertV2FixtureIntact(driver, "$platformName fresh v2")
            expectedNonCoreMaster = driver.nonCoreMasterDefinitions()
            expectedOutbox = driver.outboxRow()
        }

        for (failAt in 1..20) {
            val failure = assertFails("$platformName v2->v9 fail-at-$failAt must abort driver open") {
                openDriver(FailAtMigrationStatementSchema(SlockDatabase.Schema, failAt), name).useDriver { driver ->
                    driver.scalarLong("SELECT 1")
                }
            }
            assertTrue(
                failure.hasCause<InjectedMigrationFailure>(),
                "$platformName v2->v9 must expose fail-at-$failAt, got $failure",
            )

            openDriver(DurablePartitionV2Schema, name).useDriver { driver ->
                assertEquals(2L, driver.scalarLong("PRAGMA user_version"), "$platformName v2 rollback version")
                assertV2FixtureIntact(driver, "$platformName v2 rollback")
                assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                assertEquals(expectedOutbox, driver.outboxRow())
            }

            if (failAt < 20) {
                cleanupDatabase(name)
                openDriver(DurablePartitionV2Schema, name).useDriver { driver ->
                    seedV2MigrationRows(driver)
                }
            }
        }

        openDriver(SlockDatabase.Schema, name).useDriver { driver ->
            assertEquals(9L, driver.scalarLong("PRAGMA user_version"), "$platformName v2 retry version")
            assertMigratedFixtureIntact(driver, "$platformName v2 retry")
            assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
            assertEquals(expectedOutbox, driver.outboxRow())
            seedViewerOverlayRow(driver)
        }

        openDriver(SlockDatabase.Schema, name).useDriver { driver ->
            assertEquals(9L, driver.scalarLong("PRAGMA user_version"), "$platformName v2 second reopen version")
            assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageViewerOverlayEntity"))
            assertEquals(
                Long.MAX_VALUE,
                driver.scalarLong("SELECT overlayVersion FROM messageViewerOverlayEntity"),
            )
            assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
            assertEquals(expectedOutbox, driver.outboxRow())
        }
    } finally {
        cleanupDatabase(name)
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
        "INSERT INTO outboxMessageEntity(" +
            "localId,serverId,channelId,body,attachmentIdsJson,asTask,status,createdAtMs,lastErrorCode" +
            ") VALUES ('outbox-a','server-a','channel-a','keep-me','[\"attachment-a\"]',1,'retry',123,'E_TEST')",
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
    assertEquals(expectedOutboxRow(), driver.outboxRow(), "$label: outbox columns changed")
    assertEquals(
        "keep-me",
        driver.scalarString("SELECT value FROM durablePartitionMigrationSentinel WHERE id='sentinel-a'"),
    )
}

private fun assertMigratedFixtureIntact(
    driver: SqlDriver,
    label: String,
    expectLegacyRowsCleared: Boolean = true,
) {
    assertEquals(
        listOf(
            "environmentId",
            "serverId",
            "principalId",
            "consumerId",
            "id",
            "channelId",
            "scopeKind",
            "seq",
            "createdAt",
            "canonicalFactJson",
            "unknownFields",
        ),
        driver.tableColumns("messageEntity"),
        "$label: partitioned message schema missing",
    )
    assertFalse(driver.hasMasterObject("index", "messageEntity_channelSeqId"), "$label: old index survived")
    assertTrue(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"), "$label: new index missing")
    val expectedCoreRows = if (expectLegacyRowsCleared) 0L else 1L
    assertEquals(expectedCoreRows, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"), "$label: message rows")
    assertEquals(expectedCoreRows, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"), "$label: cursor rows")
    assertEquals(
        listOf(
            "environmentId",
            "serverId",
            "principalId",
            "consumerId",
            "scopeKind",
            "scopeId",
            "subjectKind",
            "subjectId",
            "overlayKind",
            "overlayVersion",
            "completeness",
            "payloadJson",
        ),
        driver.tableColumns("messageViewerOverlayEntity"),
        "$label: viewer-overlay schema missing",
    )
    assertTrue(driver.hasMasterObject("table", "syncReadStateFactEntity"), "$label: read-state table missing")
    assertTrue(driver.hasMasterObject("table", "avatarCacheMetadataEntity"), "$label: avatar metadata table missing")
    assertTrue(driver.hasMasterObject("table", "avatarCacheFailureEntity"), "$label: avatar failure table missing")
    assertTrue(driver.hasMasterObject("table", "threadPendingSendEntity"), "$label: pending-send table missing")
    assertTrue(
        driver.hasMasterObject("index", "threadPendingSendEntity_identityRoute"),
        "$label: pending-send identity index missing",
    )
    assertTrue(
        driver.hasMasterObject("index", "threadPendingSendEntity_randomId"),
        "$label: pending-send randomId index missing",
    )
    assertEquals(
        listOf(
            "environmentId", "accountId", "serverId",
            "subjectKind", "subjectId", "memberKind",
            "contentKind", "contentFingerprint", "eTag", "lastModified",
            "fetchedAtMs", "validatedAtMs", "freshUntilMs", "staleUntilMs",
            "sizeBytes", "contentType", "commitGeneration", "commitNonce", "modelRevision"
        ),
        driver.tableColumns("avatarCacheMetadataEntity"),
        "$label: durable avatar commit columns missing"
    )
    assertEquals(expectedOutboxRow(), driver.outboxRow(), "$label: outbox columns changed")
    assertEquals(
        "keep-me",
        driver.scalarString("SELECT value FROM durablePartitionMigrationSentinel WHERE id='sentinel-a'"),
    )
    assertTrue(
        driver.hasMasterObject("index", "durablePartitionMigrationSentinel_value"),
        "$label: non-core index definition must survive",
    )
}

private fun seedV2MigrationRows(driver: SqlDriver) {
    driver.execute(
        null,
        "INSERT INTO messageEntity(" +
            "serverId,principalId,consumerId,id,channelId,scopeKind,seq,createdAt,canonicalFactJson" +
            ") VALUES ('server-a','principal-a','sync-core.messages.v1','partition-message'," +
            "'channel-a','channel',11,'t','{}')",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO syncCursorEntity(" +
            "scopeId,serverId,principalId,consumerId,scopeKind,latestSeq,updatedAtMs" +
            ") VALUES ('channel-a','server-a','principal-a','sync-core.messages.v1','channel',11,123)",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO outboxMessageEntity(" +
            "localId,serverId,channelId,body,attachmentIdsJson,asTask,status,createdAtMs,lastErrorCode" +
            ") VALUES ('outbox-a','server-a','channel-a','keep-me','[\"attachment-a\"]',1,'retry',123,'E_TEST')",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO durablePartitionMigrationSentinel(id,value) VALUES ('sentinel-a','keep-me')",
        0,
    )
}

private fun assertV2FixtureIntact(driver: SqlDriver, label: String) {
    assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"), "$label: message row lost")
    assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"), "$label: cursor row lost")
    assertTrue(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"), "$label: index missing")
    assertFalse(driver.hasMasterObject("table", "messageViewerOverlayEntity"), "$label: overlay table leaked")
    assertEquals(expectedOutboxRow(), driver.outboxRow(), "$label: outbox changed")
    assertEquals(
        "keep-me",
        driver.scalarString("SELECT value FROM durablePartitionMigrationSentinel WHERE id='sentinel-a'"),
    )
}

private fun seedViewerOverlayRow(driver: SqlDriver) {
    driver.execute(
        null,
        "INSERT INTO messageViewerOverlayEntity(" +
            "environmentId,serverId,principalId,consumerId,scopeKind,scopeId,subjectKind,subjectId,overlayKind," +
            "overlayVersion,completeness,payloadJson" +
            ") VALUES ('https://api.example','server-a','principal-a','sync-core.messages.v2','channel','channel-a'," +
            "'message','partition-message','reactions',9223372036854775807,'complete','{}')",
        0,
    )
}

private fun seedNewPartitionRows(driver: SqlDriver) {
    driver.execute(
        null,
        "INSERT INTO messageEntity(" +
            "environmentId,serverId,principalId,consumerId,id,channelId,scopeKind,seq,createdAt,canonicalFactJson" +
            ") VALUES ('https://api.example','server-a','principal-a','sync-core.messages.v1','partition-message'," +
            "'channel-a','channel',11,'t','{}')",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO syncCursorEntity(" +
            "scopeId,environmentId,serverId,principalId,consumerId,scopeKind,latestSeq,updatedAtMs" +
            ") VALUES ('channel-a','https://api.example','server-a','principal-a','sync-core.messages.v1'," +
            "'channel',11,123)",
        0,
    )
}

private fun expectedOutboxRow(): DriverOutboxRow = DriverOutboxRow(
    localId = "outbox-a",
    serverId = "server-a",
    channelId = "channel-a",
    body = "keep-me",
    attachmentIdsJson = "[\"attachment-a\"]",
    asTask = 1L,
    status = "retry",
    createdAtMs = 123L,
    lastErrorCode = "E_TEST",
)

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

private fun SqlDriver.nonCoreMasterDefinitions(): List<DriverMasterDefinition> =
    masterDefinitions().filterNot { definition ->
        definition.name in setOf(
            "messageEntity",
            "syncCursorEntity",
            "messageViewerOverlayEntity",
            "syncReadStateFactEntity",
            "avatarCacheMetadataEntity",
            "avatarCacheFailureEntity",
            "messageEntity_channelSeqId",
            "messageEntity_partitionChannelSeqId",
            "syncCursorEntity_partitionUpdatedScope",
        )
    }

private fun SqlDriver.masterDefinitions(): List<DriverMasterDefinition> = queryRows(
    "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name",
) { cursor ->
    DriverMasterDefinition(
        type = requireNotNull(cursor.getString(0)),
        name = requireNotNull(cursor.getString(1)),
        tableName = requireNotNull(cursor.getString(2)),
        sql = requireNotNull(cursor.getString(3)),
    )
}

private fun SqlDriver.outboxRow(): DriverOutboxRow? = executeQuery(
    identifier = null,
    sql = "SELECT localId,serverId,channelId,body,attachmentIdsJson,asTask,status,createdAtMs,lastErrorCode " +
        "FROM outboxMessageEntity WHERE localId='outbox-a'",
    mapper = { cursor ->
        QueryResult.Value(
            if (cursor.next().value) {
                DriverOutboxRow(
                    localId = requireNotNull(cursor.getString(0)),
                    serverId = requireNotNull(cursor.getString(1)),
                    channelId = requireNotNull(cursor.getString(2)),
                    body = requireNotNull(cursor.getString(3)),
                    attachmentIdsJson = requireNotNull(cursor.getString(4)),
                    asTask = requireNotNull(cursor.getLong(5)),
                    status = requireNotNull(cursor.getString(6)),
                    createdAtMs = requireNotNull(cursor.getLong(7)),
                    lastErrorCode = cursor.getString(8),
                )
            } else {
                null
            },
        )
    },
    parameters = 0,
).value

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
