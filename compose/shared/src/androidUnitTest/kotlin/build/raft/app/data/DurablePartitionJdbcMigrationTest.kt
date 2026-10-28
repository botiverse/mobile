package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.shared.data.local.RaftDatabase
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
    fun exactCheckedInV1MigratesToFreshV10WhilePreservingEveryNonCoreDefinitionAndOutboxByte() {
        val migratedPath = copyDatabaseResource("1.db")
        val freshV10Path = Files.createTempFile("slock-fresh-v10-", ".db").also { it.deleteIfExists() }
        try {
            val before = JdbcSqliteDriver(url(migratedPath))
            val expectedOutbox = seedExactBaselineRows(before)
            val nonCoreBefore = before.masterDefinitions().filterNot { it.isCoreDefinition() }
            migrateInOneTransaction(before, RaftDatabase.Schema, oldVersion = 1L, newVersion = 10L)

            assertEquals(expectedOutbox, before.outboxRow())
            assertEquals(nonCoreBefore, before.masterDefinitions().filterNot { it.isCoreDefinition() })
            assertV10Core(before)
            assertEquals(0L, before.scalarLong("SELECT COUNT(*) FROM messageEntity"), "legacy facts must not copy")
            assertEquals(0L, before.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"), "legacy cursor must not seed")

            val freshV10 = JdbcSqliteDriver(url(freshV10Path))
            try {
                RaftDatabase.Schema.create(freshV10).value
                assertEquals(
                    freshV10.masterDefinitions().map { it.canonicalSchema() },
                    before.masterDefinitions().map { it.canonicalSchema() },
                    "migrated v10 schema must equal fresh generated v10 after comment/whitespace canonicalization",
                )
            } finally {
                freshV10.close()
            }
            before.close()
        } finally {
            migratedPath.deleteIfExists()
            freshV10Path.deleteIfExists()
        }
    }

    @Test
    fun exactCheckedInV2MigratesToFreshV10WhileDroppingEnvironmentAmbiguousRows() {
        val migratedPath = copyDatabaseResource("2.db")
        val freshV10Path = Files.createTempFile("slock-fresh-v10-", ".db").also { it.deleteIfExists() }
        try {
            val migrated = JdbcSqliteDriver(url(migratedPath))
            val expectedOutbox = seedExactV2Rows(migrated)
            val nonCoreBefore = migrated.masterDefinitions().filterNot { it.isCoreDefinition() }
            migrateInOneTransaction(migrated, RaftDatabase.Schema, oldVersion = 2L, newVersion = 10L)

            assertV10Core(migrated)
            assertEquals(0L, migrated.scalarLong("SELECT COUNT(*) FROM messageEntity"))
            assertEquals(0L, migrated.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
            assertEquals(expectedOutbox, migrated.outboxRow())
            assertEquals(nonCoreBefore, migrated.masterDefinitions().filterNot { it.isCoreDefinition() })

            val freshV10 = JdbcSqliteDriver(url(freshV10Path))
            try {
                RaftDatabase.Schema.create(freshV10).value
                assertEquals(
                    freshV10.masterDefinitions().map { it.canonicalSchema() },
                    migrated.masterDefinitions().map { it.canonicalSchema() },
                    "checked-in v2 migration must equal fresh generated v10",
                )
            } finally {
                freshV10.close()
            }
            migrated.close()
        } finally {
            migratedPath.deleteIfExists()
            freshV10Path.deleteIfExists()
        }
    }

    @Test
    fun exactCheckedInV3MigratesToFreshV10WithIndependentReadStateTable() {
        val path = copyDatabaseResource("3.db")
        try {
            val driver = JdbcSqliteDriver(url(path))
            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 3L, newVersion = 10L)
            assertV10Core(driver)
            assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM syncReadStateFactEntity"))
            driver.close()
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun v7SchemaWithoutPendingJournalMigratesToV10WithoutSynthesizingRows() {
        val path = copyDatabaseResource("1.db")
        try {
            val driver = JdbcSqliteDriver(url(path))
            // This is the upgrade shape for an installed build that predates the pending-send
            // journal: the legacy outbox exists, but no threadPendingSendEntity can exist yet.
            val expectedOutbox = seedExactBaselineRows(driver)
            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 1L, newVersion = 7L)
            assertFalse(driver.hasMasterObject("table", "threadPendingSendEntity"))

            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 7L, newVersion = 10L)

            assertV10Core(driver)
            assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM threadPendingSendEntity"))
            assertEquals(
                expectedOutbox,
                driver.outboxRow(),
                "v7->v10 must preserve the legacy outbox without materializing a Thread pending row",
            )
            driver.close()
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun v6AvatarValidatorRowMigratesToNullableV10IdentityAndForcesNoBackfill() {
        val path = copyDatabaseResource("1.db")
        try {
            val driver = JdbcSqliteDriver(url(path))
            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 1L, newVersion = 6L)
            seedLegacyAvatarMetadata(driver)

            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 6L, newVersion = 10L)

            assertV10Core(driver)
            assertEquals("legacy-etag", driver.scalarString("SELECT eTag FROM avatarCacheMetadataEntity"))
            assertEquals(
                1L,
                driver.scalarLong(
                    "SELECT COUNT(*) FROM avatarCacheMetadataEntity WHERE " +
                        "commitGeneration IS NULL AND commitNonce IS NULL AND modelRevision IS NULL"
                )
            )
            driver.close()
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun v6ToV7AvatarIdentityMigrationRollsBackAtEveryDdlBoundary() {
        for (failAt in 1..3) {
            val path = copyDatabaseResource("1.db")
            try {
                val driver = JdbcSqliteDriver(url(path))
                migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 1L, newVersion = 6L)
                seedLegacyAvatarMetadata(driver)
                val masterBefore = driver.masterDefinitions()

                assertFailsWith<InjectedMigrationFailure> {
                    migrateInOneTransaction(
                        driver,
                        FailAtMigrationStatementSchema(RaftDatabase.Schema, failAt),
                        oldVersion = 6L,
                        newVersion = 7L
                    )
                }

                assertEquals(masterBefore, driver.masterDefinitions())
                assertEquals(
                    listOf(
                        "environmentId", "accountId", "serverId",
                        "subjectKind", "subjectId", "memberKind",
                        "contentKind", "contentFingerprint", "eTag", "lastModified",
                        "fetchedAtMs", "validatedAtMs", "freshUntilMs", "staleUntilMs",
                        "sizeBytes", "contentType"
                    ),
                    driver.tableColumns("avatarCacheMetadataEntity")
                )
                assertEquals("legacy-etag", driver.scalarString("SELECT eTag FROM avatarCacheMetadataEntity"))
                driver.close()
            } finally {
                path.deleteIfExists()
            }
        }
    }

    @Test
    fun exactCheckedInV3RollsBackAndRetriesAtEveryRemainingDdlBoundary() {
        for (failAt in 1..26) {
            val path = copyDatabaseResource("3.db")
            try {
                val driver = JdbcSqliteDriver(url(path))
                val masterBefore = driver.masterDefinitions()

                val failure = assertFailsWith<InjectedMigrationFailure>("v3->v10 fail-at-$failAt must abort") {
                    migrateInOneTransaction(
                        driver,
                        FailAtMigrationStatementSchema(RaftDatabase.Schema, failAt),
                        oldVersion = 3L,
                        newVersion = 10L,
                    )
                }
                assertEquals(failAt, failure.statementNumber)
                assertEquals(masterBefore, driver.masterDefinitions(), "v3->v10 failure must roll back sqlite_master")
                assertFalse(driver.hasMasterObject("table", "syncReadStateFactEntity"))
                assertFalse(driver.hasMasterObject("table", "avatarCacheMetadataEntity"))
                assertFalse(driver.hasMasterObject("table", "avatarCacheFailureEntity"))

                migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 3L, newVersion = 10L)
                assertV10Core(driver)
                driver.close()
            } finally {
                path.deleteIfExists()
            }
        }
    }

    @Test
    fun exactCheckedInV1RollsBackAndRetriesAtEveryDdlBoundary() {
        for (failAt in 1..33) {
            val path = copyDatabaseResource("1.db")
            try {
                val driver = JdbcSqliteDriver(url(path))
                val expectedOutbox = seedExactBaselineRows(driver)
                val masterBefore = driver.masterDefinitions()

                val failure = assertFailsWith<InjectedMigrationFailure>("fail-at-$failAt must abort") {
                    migrateInOneTransaction(
                        driver,
                        FailAtMigrationStatementSchema(RaftDatabase.Schema, failAt),
                        oldVersion = 1L,
                        newVersion = 10L,
                    )
                }
                assertEquals(failAt, failure.statementNumber)
                assertEquals(masterBefore, driver.masterDefinitions(), "fail-at-$failAt must roll back sqlite_master")
                assertEquals(expectedOutbox, driver.outboxRow(), "fail-at-$failAt must preserve outbox bytes")
                assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
                assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
                assertTrue(driver.hasMasterObject("index", "messageEntity_channelSeqId"))
                assertFalse(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"))

                migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 1L, newVersion = 10L)
                assertV10Core(driver)
                assertEquals(expectedOutbox, driver.outboxRow(), "fail-at-$failAt retry must preserve outbox bytes")
                driver.close()
            } finally {
                path.deleteIfExists()
            }
        }
    }

    @Test
    fun exactCheckedInV2RollsBackAndRetriesAtEveryRemainingDdlBoundary() {
        for (failAt in 1..27) {
            val path = copyDatabaseResource("2.db")
            try {
                val driver = JdbcSqliteDriver(url(path))
                val expectedOutbox = seedExactV2Rows(driver)
                val masterBefore = driver.masterDefinitions()

                val failure = assertFailsWith<InjectedMigrationFailure>("v2->v10 fail-at-$failAt must abort") {
                    migrateInOneTransaction(
                        driver,
                        FailAtMigrationStatementSchema(RaftDatabase.Schema, failAt),
                        oldVersion = 2L,
                        newVersion = 10L,
                    )
                }
                assertEquals(failAt, failure.statementNumber)
                assertEquals(masterBefore, driver.masterDefinitions(), "v2->v10 failure must roll back sqlite_master")
                assertEquals(expectedOutbox, driver.outboxRow(), "v2->v10 failure must preserve outbox bytes")
                assertFalse(driver.hasMasterObject("table", "messageViewerOverlayEntity"))
                assertFalse(driver.hasMasterObject("table", "syncReadStateFactEntity"))

                migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 2L, newVersion = 10L)
                assertV10Core(driver)
                assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
                assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
                assertEquals(expectedOutbox, driver.outboxRow())
                driver.close()
            } finally {
                path.deleteIfExists()
            }
        }
    }

    /**
     * Task #14a: v8 -> v9 adds only `messageEntity.unknownFields`. An existing V2 fact keeps its bytes and gets
     * NULL (= every aggregate known: no partial fact could be written before v9); a failure at the single DDL
     * rolls back to the exact v8 table.
     */
    @Test
    fun v8CanonicalFactMigratesToV9AsAFullFactAndTheDdlRollsBack() {
        val path = copyDatabaseResource("1.db")
        try {
            val driver = JdbcSqliteDriver(url(path))
            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 1L, newVersion = 8L)
            driver.execute(
                null,
                "INSERT INTO messageEntity(" +
                    "environmentId,serverId,principalId,consumerId,id,channelId,scopeKind,seq,createdAt,canonicalFactJson" +
                    ") VALUES ('env-a','server-a','principal-a','sync-core.messages.v2','v2-message'," +
                    "'channel-a','channel',5,'t','{\"kept\":true}')",
                0,
            )
            val v8Master = driver.masterDefinitions()
            val v8Columns = driver.tableColumns("messageEntity")
            assertFalse("unknownFields" in v8Columns)

            val failure = assertFailsWith<InjectedMigrationFailure> {
                migrateInOneTransaction(
                    driver,
                    FailAtMigrationStatementSchema(RaftDatabase.Schema, 1),
                    oldVersion = 8L,
                    newVersion = 9L,
                )
            }
            assertEquals(1, failure.statementNumber)
            assertEquals(v8Master, driver.masterDefinitions(), "v8->v9 failure must roll back sqlite_master")
            assertEquals(v8Columns, driver.tableColumns("messageEntity"))

            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 8L, newVersion = 9L)
            assertEquals("unknownFields", driver.tableColumns("messageEntity").last())
            assertEquals(
                1L,
                driver.scalarLong(
                    "SELECT COUNT(*) FROM messageEntity WHERE id='v2-message' AND unknownFields IS NULL " +
                        "AND canonicalFactJson='{\"kept\":true}' AND seq=5",
                ),
            )
            driver.close()
        } finally {
            path.deleteIfExists()
        }
    }

    /**
     * Message storage Phase 1: v9 -> v10 turns every stored V2 window into one identical `migrated` segment
     * ([0, 0] for an empty baseline), copies `updatedAtMs` into `lastUserAccessAtMs`, gives every fact
     * localRev 0, and leaves every other cursor (no window, an inverted window, another consumer) without a
     * segment. The migrated schema equals a fresh v10 one.
     */
    @Test
    fun exactCheckedInV9WindowsBecomeIdenticalSegmentsAndMatchFreshV10() {
        val path = copyDatabaseResource("9.db")
        val freshPath = Files.createTempFile("slock-fresh-v10-", ".db").also { it.deleteIfExists() }
        try {
            val driver = JdbcSqliteDriver(url(path))
            seedV9Cursors(driver)
            migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 9L, newVersion = 10L)
            assertV10Core(driver)
            assertEquals(
                listOf(
                    "channel|window|5|20|0|1|100|migrated",
                    "dm|empty|0|0|1|1|300|migrated",
                    "thread|oldest|3|3|1|0|200|migrated",
                ),
                driver.queryRows(
                    "SELECT scopeKind,scopeId,lowSeq,highSeq,reachesOldest,headAtVerify,verifiedAtMs,source " +
                        "FROM messageSegmentEntity ORDER BY scopeKind,scopeId",
                ) { c -> (0..7).joinToString("|") { c.getString(it) ?: c.getLong(it).toString() } },
            )
            assertEquals(
                listOf("window|100", "oldest|200", "empty|300", "nowindow|400", "inverted|500", "v1-window|null"),
                driver.queryRows("SELECT scopeId,lastUserAccessAtMs FROM syncCursorEntity ORDER BY updatedAtMs") { c ->
                    "${c.getString(0)}|${c.getLong(1)}"
                },
            )
            assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity WHERE localRev != 0"))
            assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM messageLocalRevEntity"))
            assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity WHERE id='fact-a' AND seq=5"))

            val fresh = JdbcSqliteDriver(url(freshPath))
            try {
                RaftDatabase.Schema.create(fresh).value
                assertEquals(
                    fresh.masterDefinitions().map { it.canonicalSchema() },
                    driver.masterDefinitions().map { it.canonicalSchema() },
                    "migrated v10 schema must equal fresh generated v10",
                )
            } finally {
                fresh.close()
            }
            driver.close()
        } finally {
            path.deleteIfExists()
            freshPath.deleteIfExists()
        }
    }

    @Test
    fun v9ToV10RollsBackAtEveryStatementAndRetries() {
        for (failAt in 1..7) {
            val path = copyDatabaseResource("9.db")
            try {
                val driver = JdbcSqliteDriver(url(path))
                seedV9Cursors(driver)
                val masterBefore = driver.masterDefinitions()
                val cursorsBefore = driver.queryRows("SELECT * FROM syncCursorEntity ORDER BY updatedAtMs") { c ->
                    (0 until 16).map { c.getString(it) }
                }
                val failure = assertFailsWith<InjectedMigrationFailure>("v9->v10 fail-at-$failAt must abort") {
                    migrateInOneTransaction(
                        driver,
                        FailAtMigrationStatementSchema(RaftDatabase.Schema, failAt),
                        oldVersion = 9L,
                        newVersion = 10L,
                    )
                }
                assertEquals(failAt, failure.statementNumber)
                assertEquals(masterBefore, driver.masterDefinitions(), "v9->v10 fail-at-$failAt must roll back sqlite_master")
                assertFalse(driver.hasMasterObject("table", "messageSegmentEntity"))
                assertFalse(driver.hasMasterObject("table", "messageLocalRevEntity"))
                assertEquals(
                    cursorsBefore,
                    driver.queryRows("SELECT * FROM syncCursorEntity ORDER BY updatedAtMs") { c ->
                        (0 until 16).map { c.getString(it) }
                    },
                )

                migrateInOneTransaction(driver, RaftDatabase.Schema, oldVersion = 9L, newVersion = 10L)
                assertV10Core(driver)
                assertEquals(3L, driver.scalarLong("SELECT COUNT(*) FROM messageSegmentEntity"))
                driver.close()
            } finally {
                path.deleteIfExists()
            }
        }
    }

    /** V2 cursors of every window shape, one V2 fact and one V1 window (another consumer: never migrated). */
    private fun seedV9Cursors(driver: SqlDriver) {
        val v2 = "sync-core.messages.v2"
        fun cursor(consumer: String, kind: String, id: String, oldest: String, newest: String, hasOldest: Int, hasNewest: Int, at: Long) {
            val parent = if (kind == "thread") "'channel','parent-a','root-a'" else "NULL,NULL,NULL"
            driver.execute(
                null,
                "INSERT INTO syncCursorEntity(scopeId,environmentId,serverId,principalId,consumerId,scopeKind," +
                    "latestSeq,oldestLoadedSeq,newestLoadedSeq,hasLoadedOldest,hasLoadedNewest,updatedAtMs,epoch," +
                    "parentScopeKind,parentScopeId,rootMessageId) VALUES ('$id','env-a','server-a','principal-a'," +
                    "'$consumer','$kind',$newest,$oldest,$newest,$hasOldest,$hasNewest,$at,NULL,$parent)",
                0,
            )
        }
        cursor(v2, "channel", "window", "5", "20", 0, 1, 100)
        cursor(v2, "thread", "oldest", "3", "3", 1, 0, 200)
        cursor(v2, "dm", "empty", "NULL", "NULL", 1, 1, 300)
        cursor(v2, "channel", "nowindow", "NULL", "NULL", 0, 1, 400)
        cursor(v2, "channel", "inverted", "9", "4", 0, 1, 500)
        cursor("sync-core.messages.v1", "channel", "v1-window", "1", "2", 1, 1, 600)
        driver.execute(
            null,
            "INSERT INTO messageEntity(environmentId,serverId,principalId,consumerId,id,channelId,scopeKind,seq," +
                "createdAt,canonicalFactJson) VALUES ('env-a','server-a','principal-a','$v2','fact-a','window'," +
                "'channel',5,'t','{}')",
            0,
        )
    }

    private fun migrateInOneTransaction(
        driver: SqlDriver,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        oldVersion: Long,
        newVersion: Long,
    ) {
        RaftDatabase(driver).transaction {
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

    private fun seedLegacyAvatarMetadata(driver: SqlDriver) {
        driver.execute(
            null,
            """
            INSERT INTO avatarCacheMetadataEntity(
              environmentId,accountId,serverId,subjectKind,subjectId,memberKind,
              contentKind,contentFingerprint,eTag,lastModified,
              fetchedAtMs,validatedAtMs,freshUntilMs,staleUntilMs,sizeBytes,contentType
            ) VALUES (
              'https://api.example','account-a','server-a','member','member-a','agent',
              'external_url','${"a".repeat(64)}','legacy-etag','legacy-last',
              100,100,200,300,512,'image/webp'
            )
            """.trimIndent(),
            0
        )
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

    private fun assertV10Core(driver: SqlDriver) {
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
                "localRev",
            ),
            driver.tableColumns("messageEntity"),
        )
        assertEquals(
            listOf(
                "scopeId",
                "environmentId",
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
                "parentScopeKind",
                "parentScopeId",
                "rootMessageId",
                "lastUserAccessAtMs",
            ),
            driver.tableColumns("syncCursorEntity"),
        )
        assertEquals(
            listOf(
                "environmentId", "serverId", "principalId", "consumerId", "scopeKind", "scopeId",
                "lowSeq", "highSeq", "reachesOldest", "headAtVerify", "verifiedAtMs", "source",
            ),
            driver.tableColumns("messageSegmentEntity"),
        )
        assertEquals(
            listOf("environmentId", "serverId", "principalId", "consumerId", "localRev"),
            driver.tableColumns("messageLocalRevEntity"),
        )
        assertFalse(driver.hasMasterObject("index", "messageEntity_channelSeqId"))
        assertTrue(driver.hasMasterObject("index", "messageEntity_partitionChannelSeqId"))
        assertEquals(
            listOf(
                "environmentId", "accountId", "serverId",
                "subjectKind", "subjectId", "memberKind",
                "contentKind", "contentFingerprint", "eTag", "lastModified",
                "fetchedAtMs", "validatedAtMs", "freshUntilMs", "staleUntilMs",
                "sizeBytes", "contentType", "commitGeneration", "commitNonce", "modelRevision",
            ),
            driver.tableColumns("avatarCacheMetadataEntity"),
        )
        assertEquals(
            listOf(
                "environmentId", "accountId", "serverId",
                "subjectKind", "subjectId", "memberKind",
                "contentKind", "contentFingerprint", "failureKind",
                "consecutiveCount", "failedAtMs", "retryAtMs",
            ),
            driver.tableColumns("avatarCacheFailureEntity"),
        )
        assertTrue(driver.hasMasterObject("index", "syncCursorEntity_partitionUpdatedScope"))
        assertTrue(driver.hasMasterObject("table", "threadPendingSendEntity"))
        assertTrue(driver.hasMasterObject("index", "threadPendingSendEntity_identityRoute"))
        assertTrue(driver.hasMasterObject("index", "threadPendingSendEntity_randomId"))
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
        )
        assertEquals(
            listOf(
                "serverId",
                "principalId",
                "consumerId",
                "channelId",
                "readStateVersion",
                "maxReadSeq",
                "updatedAtMs",
            ),
            driver.tableColumns("syncReadStateFactEntity"),
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
        "messageSegmentEntity",
        "messageLocalRevEntity",
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
