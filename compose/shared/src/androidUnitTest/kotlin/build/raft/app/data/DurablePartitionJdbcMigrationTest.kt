package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.shared.data.local.SlockDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurablePartitionJdbcMigrationTest {
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
    fun exactCheckedInV1MigratesToFreshV3WhilePreservingEveryNonCoreDefinitionAndOutboxByte() {
        val migratedPath = copyDatabaseResource("1.db")
        val freshV3Path = Files.createTempFile("slock-fresh-v3-", ".db").also { it.deleteIfExists() }
        try {
            val before = JdbcSqliteDriver(url(migratedPath))
            val expectedOutbox = seedExactBaselineRows(before)
            val nonCoreBefore = before.masterDefinitions().filterNot { it.isCoreDefinition() }
            migrateInOneTransaction(before, SlockDatabase.Schema, oldVersion = 1L, newVersion = 3L)

            assertEquals(expectedOutbox, before.outboxRow())
            assertEquals(nonCoreBefore, before.masterDefinitions().filterNot { it.isCoreDefinition() })
            assertV3Core(before)
            assertEquals(0L, before.scalarLong("SELECT COUNT(*) FROM messageEntity"), "legacy facts must not copy")
            assertEquals(0L, before.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"), "legacy cursor must not seed")

            val freshV3 = JdbcSqliteDriver(url(freshV3Path))
            try {
                SlockDatabase.Schema.create(freshV3).value
                assertEquals(
                    freshV3.masterDefinitions().map { it.canonicalSchema() },
                    before.masterDefinitions().map { it.canonicalSchema() },
                    "migrated v3 schema must equal fresh generated v3 after comment/whitespace canonicalization",
                )
            } finally {
                freshV3.close()
            }
            before.close()
        } finally {
            migratedPath.deleteIfExists()
            freshV3Path.deleteIfExists()
        }
    }

    @Test
    fun exactCheckedInV2MigratesToFreshV3WhilePreservingPartitionRowsAndDefinitions() {
        val migratedPath = copyDatabaseResource("2.db")
        val freshV3Path = Files.createTempFile("slock-fresh-v3-", ".db").also { it.deleteIfExists() }
        try {
            val migrated = JdbcSqliteDriver(url(migratedPath))
            val expectedOutbox = seedExactV2Rows(migrated)
            val nonCoreBefore = migrated.masterDefinitions().filterNot { it.isCoreDefinition() }
            migrateInOneTransaction(migrated, SlockDatabase.Schema, oldVersion = 2L, newVersion = 3L)

            assertV3Core(migrated)
            assertEquals(1L, migrated.scalarLong("SELECT COUNT(*) FROM messageEntity"))
            assertEquals(1L, migrated.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
            assertEquals(expectedOutbox, migrated.outboxRow())
            assertEquals(nonCoreBefore, migrated.masterDefinitions().filterNot { it.isCoreDefinition() })

            val freshV3 = JdbcSqliteDriver(url(freshV3Path))
            try {
                SlockDatabase.Schema.create(freshV3).value
                assertEquals(
                    freshV3.masterDefinitions().map { it.canonicalSchema() },
                    migrated.masterDefinitions().map { it.canonicalSchema() },
                    "checked-in v2 migration must equal fresh generated v3",
                )
            } finally {
                freshV3.close()
            }
            migrated.close()
        } finally {
            migratedPath.deleteIfExists()
            freshV3Path.deleteIfExists()
        }
    }

    @Test
    fun exactCheckedInV1RollsBackAndRetriesAtEveryOneOfSevenDdlBoundaries() {
        for (failAt in 1..7) {
            val path = copyDatabaseResource("1.db")
            try {
                val driver = JdbcSqliteDriver(url(path))
                val expectedOutbox = seedExactBaselineRows(driver)
                val masterBefore = driver.masterDefinitions()

                val failure = assertFailsWith<InjectedMigrationFailure>("fail-at-$failAt must abort") {
                    migrateInOneTransaction(
                        driver,
                        FailAtMigrationStatementSchema(SlockDatabase.Schema, failAt),
                        oldVersion = 1L,
                        newVersion = 3L,
                    )
                }
                assertEquals(failAt, failure.statementNumber)
                assertEquals(masterBefore, driver.masterDefinitions(), "fail-at-$failAt must roll back sqlite_master")
                assertEquals(expectedOutbox, driver.outboxRow(), "fail-at-$failAt must preserve outbox bytes")
                assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
                assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
                assertTrue(driver.hasMasterObject("index", "messageEntity_channelSeqId"))
                assertFalse(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"))

                migrateInOneTransaction(driver, SlockDatabase.Schema, oldVersion = 1L, newVersion = 3L)
                assertV3Core(driver)
                assertEquals(expectedOutbox, driver.outboxRow(), "fail-at-$failAt retry must preserve outbox bytes")
                driver.close()
            } finally {
                path.deleteIfExists()
            }
        }
    }

    @Test
    fun exactCheckedInV2RollsBackAndRetriesAtTheViewerOverlayDdlBoundary() {
        val path = copyDatabaseResource("2.db")
        try {
            val driver = JdbcSqliteDriver(url(path))
            val expectedOutbox = seedExactV2Rows(driver)
            val masterBefore = driver.masterDefinitions()

            val failure = assertFailsWith<InjectedMigrationFailure>("v2->v3 fail-at-1 must abort") {
                migrateInOneTransaction(
                    driver,
                    FailAtMigrationStatementSchema(SlockDatabase.Schema, 1),
                    oldVersion = 2L,
                    newVersion = 3L,
                )
            }
            assertEquals(1, failure.statementNumber)
            assertEquals(masterBefore, driver.masterDefinitions(), "v2->v3 failure must roll back sqlite_master")
            assertEquals(expectedOutbox, driver.outboxRow(), "v2->v3 failure must preserve outbox bytes")
            assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
            assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
            assertFalse(driver.hasMasterObject("table", "messageViewerOverlayEntity"))

            migrateInOneTransaction(driver, SlockDatabase.Schema, oldVersion = 2L, newVersion = 3L)
            assertV3Core(driver)
            assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
            assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
            assertEquals(expectedOutbox, driver.outboxRow())
            driver.close()
        } finally {
            path.deleteIfExists()
        }
    }

    private fun migrateInOneTransaction(
        driver: SqlDriver,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        oldVersion: Long,
        newVersion: Long,
    ) {
        SlockDatabase(driver).transaction {
            schema.migrate(driver, oldVersion = oldVersion, newVersion = newVersion).value
        }
    }

    private fun seedExactBaselineRows(driver: SqlDriver): OutboxRow {
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
        return requireNotNull(driver.outboxRow())
    }

    private fun seedExactV2Rows(driver: SqlDriver): OutboxRow {
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
        return requireNotNull(driver.outboxRow())
    }

    private fun assertV3Core(driver: SqlDriver) {
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
        )
        assertEquals(
            listOf(
                "scopeId",
                "serverId",
                "principalId",
                "consumerId",
                "scopeKind",
                "latestSeq",
                "oldestLoadedSeq",
                "newestLoadedSeq",
                "hasLoadedOldest",
                "hasLoadedNewest",
                "updatedAtMs",
                "epoch",
            ),
            driver.tableColumns("syncCursorEntity"),
        )
        assertFalse(driver.hasMasterObject("index", "messageEntity_channelSeqId"))
        assertTrue(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"))
        assertEquals(
            listOf(
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
            "FROM outboxMessageEntity WHERE localId='outbox-a'",
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

    private fun MasterDefinition.isCoreDefinition(): Boolean = name in setOf(
        "messageEntity",
        "syncCursorEntity",
        "messageViewerOverlayEntity",
        "messageEntity_channelSeqId",
        "messageEntity_partitionChannelSeqId",
    )

    private fun MasterDefinition.canonicalSchema(): MasterDefinition = copy(
        sql = sql
            .lineSequence()
            .map { line -> line.substringBefore("--") }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim(),
    )

    private fun copyDatabaseResource(name: String): Path {
        val target = Files.createTempFile("slock-$name-", ".db")
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing SQLDelight database test resource $name"
        }
        stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        return target
    }

    private fun url(path: Path): String = "jdbc:sqlite:${path.toAbsolutePath()}"
}
