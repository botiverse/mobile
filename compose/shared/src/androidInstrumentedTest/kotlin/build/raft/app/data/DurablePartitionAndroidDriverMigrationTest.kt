package build.raft.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import build.raft.app.shared.data.local.RaftDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurablePartitionAndroidDriverMigrationTest {
    private data class MasterDefinition(
        val type: String,
        val name: String,
        val tableName: String,
        val sql: String,
    )

    private data class OutboxRow(
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

    @Test
    fun androidFileBackedV1ToV9MigrationRollsBackAndRetriesForEveryDdlBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (failAt in 1..26) {
            val name = "slock-durable-android-$failAt-${UUID.randomUUID()}.db"
            context.deleteDatabase(name)
            try {
                var expectedNonCoreMaster = emptyList<MasterDefinition>()
                var expectedOutbox: OutboxRow? = null
                AndroidSqliteDriver(LegacyV1Schema, context, name).useDriver { driver ->
                    seedLegacyRows(driver)
                    assertEquals(1L, driver.scalarLong("PRAGMA user_version"))
                    assertLegacyState(driver)
                    expectedNonCoreMaster = driver.nonCoreMasterDefinitions()
                    expectedOutbox = driver.outboxRow()
                }

                val failure = try {
                    AndroidSqliteDriver(FailAtSchema(RaftDatabase.Schema, failAt), context, name).useDriver { driver ->
                        driver.scalarLong("SELECT 1")
                    }
                    fail("fail-at-$failAt must abort AndroidSqliteDriver migration")
                    error("unreachable")
                } catch (throwable: Throwable) {
                    throwable
                }
                assertTrue(
                    "fail-at-$failAt must expose the injected DDL failure: $failure",
                    failure.hasCause<InjectedAndroidMigrationFailure>(),
                )

                AndroidSqliteDriver(LegacyV1Schema, context, name).useDriver { driver ->
                    assertEquals(1L, driver.scalarLong("PRAGMA user_version"))
                    assertLegacyState(driver)
                    assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                    assertEquals(expectedOutbox, driver.outboxRow())
                }
                AndroidSqliteDriver(RaftDatabase.Schema, context, name).useDriver { driver ->
                    assertEquals(9L, driver.scalarLong("PRAGMA user_version"))
                    assertMigratedState(driver)
                    assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                    assertEquals(expectedOutbox, driver.outboxRow())
                    seedNewPartitionRows(driver)
                }
                AndroidSqliteDriver(RaftDatabase.Schema, context, name).useDriver { driver ->
                    assertEquals(9L, driver.scalarLong("PRAGMA user_version"))
                    assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity WHERE id='partition-message'"))
                    assertEquals(
                        11L,
                        driver.scalarLong(
                            "SELECT latestSeq FROM syncCursorEntity " +
                                "WHERE environmentId='https://api.raft.build' " +
                                "AND serverId='server-a' AND principalId='principal-a' " +
                                "AND consumerId='sync-core.messages.v1' AND scopeKind='channel' AND scopeId='channel-a'",
                        ),
                    )
                    assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                    assertEquals(expectedOutbox, driver.outboxRow())
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun androidFileBackedV2ToV9MigrationRollsBackAndRetriesAtEveryRemainingDdlBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (failAt in 1..20) {
            val name = "slock-overlay-android-$failAt-${UUID.randomUUID()}.db"
            context.deleteDatabase(name)
            try {
                var expectedNonCoreMaster = emptyList<MasterDefinition>()
                var expectedOutbox: OutboxRow? = null
                AndroidSqliteDriver(LegacyV2Schema, context, name).useDriver { driver ->
                    seedV2Rows(driver)
                    assertEquals(2L, driver.scalarLong("PRAGMA user_version"))
                    assertV2State(driver)
                    expectedNonCoreMaster = driver.nonCoreMasterDefinitions()
                    expectedOutbox = driver.outboxRow()
                }
                val failure = try {
                    AndroidSqliteDriver(FailAtSchema(RaftDatabase.Schema, failAt), context, name).useDriver { driver ->
                        driver.scalarLong("SELECT 1")
                    }
                    fail("v2->v9 fail-at-$failAt must abort AndroidSqliteDriver migration")
                    error("unreachable")
                } catch (throwable: Throwable) {
                    throwable
                }
                assertTrue(
                    "v2->v9 must expose the injected DDL failure: $failure",
                    failure.hasCause<InjectedAndroidMigrationFailure>(),
                )

                AndroidSqliteDriver(LegacyV2Schema, context, name).useDriver { driver ->
                    assertEquals(2L, driver.scalarLong("PRAGMA user_version"))
                    assertV2State(driver)
                    assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                    assertEquals(expectedOutbox, driver.outboxRow())
                }
                AndroidSqliteDriver(RaftDatabase.Schema, context, name).useDriver { driver ->
                    assertEquals(9L, driver.scalarLong("PRAGMA user_version"))
                    assertMigratedState(driver)
                    assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                    assertEquals(expectedOutbox, driver.outboxRow())
                    seedOverlayRow(driver)
                }
                AndroidSqliteDriver(RaftDatabase.Schema, context, name).useDriver { driver ->
                    assertEquals(9L, driver.scalarLong("PRAGMA user_version"))
                    assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageViewerOverlayEntity"))
                    assertEquals(Long.MAX_VALUE, driver.scalarLong("SELECT overlayVersion FROM messageViewerOverlayEntity"))
                    assertEquals(expectedNonCoreMaster, driver.nonCoreMasterDefinitions())
                    assertEquals(expectedOutbox, driver.outboxRow())
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    private class InjectedAndroidMigrationFailure(val statementNumber: Int) :
        RuntimeException("injected Android migration failure at DDL $statementNumber")

    private class FailAtSchema(
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
            FailAtDriver(driver, failAt),
            oldVersion,
            newVersion,
            *callbacks,
        )
    }

    private class FailAtDriver(
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
            if (executes == failAt) throw InjectedAndroidMigrationFailure(failAt)
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private object LegacyV1Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1L

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE messageEntity(" +
                    "id TEXT NOT NULL PRIMARY KEY,serverId TEXT NOT NULL,channelId TEXT NOT NULL," +
                    "scopeKind TEXT NOT NULL,seq INTEGER NOT NULL,createdAt TEXT NOT NULL," +
                    "canonicalFactJson TEXT NOT NULL)",
                0,
            )
            driver.execute(
                null,
                "CREATE INDEX messageEntity_channelSeqId " +
                    "ON messageEntity(serverId,scopeKind,channelId,seq,id)",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE syncCursorEntity(" +
                    "scopeId TEXT NOT NULL,serverId TEXT NOT NULL,scopeKind TEXT NOT NULL,latestSeq INTEGER," +
                    "oldestLoadedSeq INTEGER,newestLoadedSeq INTEGER,hasLoadedOldest INTEGER NOT NULL DEFAULT 0," +
                    "hasLoadedNewest INTEGER NOT NULL DEFAULT 1,updatedAtMs INTEGER NOT NULL,epoch TEXT," +
                    "PRIMARY KEY(serverId,scopeKind,scopeId))",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE outboxMessageEntity(" +
                    "localId TEXT NOT NULL PRIMARY KEY,serverId TEXT NOT NULL,channelId TEXT NOT NULL," +
                    "body TEXT NOT NULL,attachmentIdsJson TEXT NOT NULL DEFAULT '[]'," +
                    "asTask INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,createdAtMs INTEGER NOT NULL," +
                    "lastErrorCode TEXT)",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE androidMigrationSentinel(id TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)",
                0,
            )
            driver.execute(
                null,
                "CREATE INDEX androidMigrationSentinel_value ON androidMigrationSentinel(value)",
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

    private object LegacyV2Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 2L

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE messageEntity(" +
                    "serverId TEXT NOT NULL,principalId TEXT NOT NULL,consumerId TEXT NOT NULL," +
                    "id TEXT NOT NULL,channelId TEXT NOT NULL,scopeKind TEXT NOT NULL,seq INTEGER NOT NULL," +
                    "createdAt TEXT NOT NULL,canonicalFactJson TEXT NOT NULL," +
                    "PRIMARY KEY(serverId,principalId,consumerId,id))",
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
                "CREATE TABLE syncCursorEntity(" +
                    "scopeId TEXT NOT NULL,serverId TEXT NOT NULL,principalId TEXT NOT NULL," +
                    "consumerId TEXT NOT NULL,scopeKind TEXT NOT NULL,latestSeq INTEGER," +
                    "oldestLoadedSeq INTEGER,newestLoadedSeq INTEGER," +
                    "hasLoadedOldest INTEGER NOT NULL DEFAULT 0,hasLoadedNewest INTEGER NOT NULL DEFAULT 1," +
                    "updatedAtMs INTEGER NOT NULL,epoch TEXT," +
                    "PRIMARY KEY(serverId,principalId,consumerId,scopeKind,scopeId))",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE outboxMessageEntity(" +
                    "localId TEXT NOT NULL PRIMARY KEY,serverId TEXT NOT NULL,channelId TEXT NOT NULL," +
                    "body TEXT NOT NULL,attachmentIdsJson TEXT NOT NULL DEFAULT '[]'," +
                    "asTask INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,createdAtMs INTEGER NOT NULL," +
                    "lastErrorCode TEXT)",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE androidMigrationSentinel(id TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)",
                0,
            )
            driver.execute(
                null,
                "CREATE INDEX androidMigrationSentinel_value ON androidMigrationSentinel(value)",
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

    private fun seedLegacyRows(driver: SqlDriver) {
        driver.execute(
            null,
            "INSERT INTO messageEntity VALUES ('m1','s1','c1','channel',1,'t','{}')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO syncCursorEntity(scopeId,serverId,scopeKind,latestSeq,updatedAtMs) " +
                "VALUES ('c1','s1','channel',1,100)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO outboxMessageEntity(" +
                "localId,serverId,channelId,body,attachmentIdsJson,asTask,status,createdAtMs,lastErrorCode" +
                ") VALUES ('o1','s1','c1','keep-me','[\"attachment-a\"]',1,'retry',123,'E_TEST')",
            0,
        )
        driver.execute(null, "INSERT INTO androidMigrationSentinel VALUES ('sentinel','keep-me')", 0)
    }

    private fun assertLegacyState(driver: SqlDriver) {
        assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
        assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
        assertTrue(driver.hasObject("index", "messageEntity_channelSeqId"))
        assertFalse(driver.hasObject("index", "messageEntity_partitionChannelSeqId"))
        assertEquals(expectedOutboxRow(), driver.outboxRow())
        assertEquals("keep-me", driver.scalarString("SELECT value FROM androidMigrationSentinel WHERE id='sentinel'"))
        assertTrue(driver.hasObject("index", "androidMigrationSentinel_value"))
    }

    private fun assertMigratedState(driver: SqlDriver, expectedCoreRows: Long = 0L) {
        assertEquals(expectedCoreRows, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
        assertEquals(expectedCoreRows, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
        assertFalse(driver.hasObject("index", "messageEntity_channelSeqId"))
        assertTrue(driver.hasObject("index", "messageEntity_partitionChannelSeqId"))
        assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM pragma_table_info('messageEntity') WHERE name='unknownFields'"))
        assertTrue(driver.hasObject("table", "messageViewerOverlayEntity"))
        assertTrue(driver.hasObject("table", "syncReadStateFactEntity"))
        assertTrue(driver.hasObject("table", "avatarCacheMetadataEntity"))
        assertTrue(driver.hasObject("table", "avatarCacheFailureEntity"))
        assertTrue(driver.hasObject("table", "threadPendingSendEntity"))
        assertTrue(driver.hasObject("index", "threadPendingSendEntity_identityRoute"))
        assertTrue(driver.hasObject("index", "threadPendingSendEntity_randomId"))
        assertEquals(expectedOutboxRow(), driver.outboxRow())
        assertEquals("keep-me", driver.scalarString("SELECT value FROM androidMigrationSentinel WHERE id='sentinel'"))
        assertTrue(driver.hasObject("index", "androidMigrationSentinel_value"))
    }

    private fun seedV2Rows(driver: SqlDriver) {
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
                ") VALUES ('o1','s1','c1','keep-me','[\"attachment-a\"]',1,'retry',123,'E_TEST')",
            0,
        )
        driver.execute(null, "INSERT INTO androidMigrationSentinel VALUES ('sentinel','keep-me')", 0)
    }

    private fun assertV2State(driver: SqlDriver) {
        assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
        assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
        assertTrue(driver.hasObject("index", "messageEntity_partitionChannelSeqId"))
        assertFalse(driver.hasObject("table", "messageViewerOverlayEntity"))
        assertFalse(driver.hasObject("table", "syncReadStateFactEntity"))
        assertEquals(expectedOutboxRow(), driver.outboxRow())
        assertEquals("keep-me", driver.scalarString("SELECT value FROM androidMigrationSentinel WHERE id='sentinel'"))
    }

    private fun seedOverlayRow(driver: SqlDriver) {
        driver.execute(
            null,
            "INSERT INTO messageViewerOverlayEntity(" +
                "environmentId,serverId,principalId,consumerId,scopeKind,scopeId,subjectKind,subjectId,overlayKind," +
                "overlayVersion,completeness,payloadJson" +
                ") VALUES ('https://api.raft.build','server-a','principal-a','sync-core.messages.v2','channel','channel-a'," +
                "'message','partition-message','reactions',9223372036854775807,'complete','{}')",
            0,
        )
    }

    private fun seedNewPartitionRows(driver: SqlDriver) {
        driver.execute(
            null,
            "INSERT INTO messageEntity(" +
                "environmentId,serverId,principalId,consumerId,id,channelId,scopeKind,seq,createdAt,canonicalFactJson" +
                ") VALUES ('https://api.raft.build','server-a','principal-a','sync-core.messages.v1','partition-message'," +
                "'channel-a','channel',11,'t','{}')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO syncCursorEntity(" +
                "scopeId,environmentId,serverId,principalId,consumerId,scopeKind,latestSeq,updatedAtMs" +
                ") VALUES ('channel-a','https://api.raft.build','server-a','principal-a'," +
                "'sync-core.messages.v1','channel',11,123)",
            0,
        )
    }

    private fun expectedOutboxRow(): OutboxRow = OutboxRow(
        localId = "o1",
        serverId = "s1",
        channelId = "c1",
        body = "keep-me",
        attachmentIdsJson = "[\"attachment-a\"]",
        asTask = 1L,
        status = "retry",
        createdAtMs = 123L,
        lastErrorCode = "E_TEST",
    )

    private fun SqlDriver.hasObject(type: String, name: String): Boolean =
        scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type='$type' AND name='$name'") == 1L

    private fun SqlDriver.nonCoreMasterDefinitions(): List<MasterDefinition> =
        masterDefinitions().filterNot { definition ->
            definition.name in setOf(
                "messageEntity",
                "syncCursorEntity",
                "messageViewerOverlayEntity",
                "syncReadStateFactEntity",
                "avatarCacheMetadataEntity",
                "avatarCacheFailureEntity",
                "threadPendingSendEntity",
                "threadPendingSendEntity_identityRoute",
                "threadPendingSendEntity_randomId",
                "messageEntity_channelSeqId",
                "messageEntity_partitionChannelSeqId",
                "syncCursorEntity_partitionUpdatedScope",
            )
        }

    private fun SqlDriver.masterDefinitions(): List<MasterDefinition> = queryRows(
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name",
    ) { cursor ->
        MasterDefinition(
            type = requireNotNull(cursor.getString(0)),
            name = requireNotNull(cursor.getString(1)),
            tableName = requireNotNull(cursor.getString(2)),
            sql = requireNotNull(cursor.getString(3)),
        )
    }

    private fun SqlDriver.outboxRow(): OutboxRow? = executeQuery(
        identifier = null,
        sql = "SELECT localId,serverId,channelId,body,attachmentIdsJson,asTask,status,createdAtMs,lastErrorCode " +
            "FROM outboxMessageEntity WHERE localId='o1'",
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) {
                    OutboxRow(
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

    private fun SqlDriver.scalarLong(sql: String): Long = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
    ).value

    private fun SqlDriver.scalarString(sql: String): String? = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 0,
    ).value

    private fun <T> SqlDriver.queryRows(sql: String, row: (SqlCursor) -> T): List<T> = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<T>()
            while (cursor.next().value) rows += row(cursor)
            QueryResult.Value(rows.toList())
        },
        parameters = 0,
    ).value

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
}
