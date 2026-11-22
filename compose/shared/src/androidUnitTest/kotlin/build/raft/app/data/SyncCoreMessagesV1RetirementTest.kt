package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.purgeSyncCoreMessagesV1Rows
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Message storage Phase 0a on real SQLite: the V1 (`sync-core.messages.v1`) shadow rows are purged at
 * provider start, and no realtime frame writes them again, while display still runs.
 */
class SyncCoreMessagesV1RetirementTest {
    private val tables = listOf("messageEntity", "syncCursorEntity", "messageViewerOverlayEntity")

    @Test
    fun purgeDeletesEveryV1RowAndKeepsEveryOtherConsumerAndIsIdempotent() = runBlocking {
        val (driver, database) = freshDatabase()
        seedPartition(driver, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, principal = "principal-a")
        seedPartition(driver, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, principal = "principal-b")
        seedPartition(driver, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, principal = "principal-a")
        seedPartition(driver, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, principal = "principal-b")
        tables.forEach { table ->
            assertEquals(2L, count(driver, table, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID), "seeded v1 $table")
        }

        purgeSyncCoreMessagesV1Rows(database)

        tables.forEach { table ->
            assertEquals(0L, count(driver, table, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID), "v1 $table purged")
            assertEquals(2L, count(driver, table, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID), "v2 $table kept")
        }

        purgeSyncCoreMessagesV1Rows(database)

        tables.forEach { table ->
            assertEquals(0L, count(driver, table, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID))
            assertEquals(2L, count(driver, table, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID))
        }
    }

    private fun freshDatabase(): Pair<JdbcSqliteDriver, RaftSqlDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        build.raft.app.shared.data.local.RaftDatabase.Schema.create(driver).value
        return driver to RaftSqlDatabase(driver)
    }

    private fun seedPartition(driver: JdbcSqliteDriver, consumerId: String, principal: String) {
        driver.execute(
            null,
            "INSERT INTO messageEntity (environmentId, serverId, principalId, consumerId, id, channelId, " +
                "scopeKind, seq, createdAt, canonicalFactJson, unknownFields) " +
                "VALUES ('env-test', 'server-a', '$principal', '$consumerId', 'm-$principal', 'channel-a', " +
                "'channel', 1, '2026-10-27T00:00:00Z', '{}', NULL)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO syncCursorEntity (scopeId, environmentId, serverId, principalId, consumerId, scopeKind, " +
                "latestSeq, updatedAtMs) " +
                "VALUES ('channel-a', 'env-test', 'server-a', '$principal', '$consumerId', 'channel', 1, 100)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO messageViewerOverlayEntity (environmentId, serverId, principalId, consumerId, scopeKind, " +
                "scopeId, subjectKind, subjectId, overlayKind, overlayVersion, completeness, payloadJson) " +
                "VALUES ('env-test', 'server-a', '$principal', '$consumerId', 'channel', 'channel-a', 'message', " +
                "'m-$principal', 'action-card', 1, 'complete', '{}')",
            0,
        )
    }

    private fun count(driver: JdbcSqliteDriver, table: String, consumerId: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM $table WHERE consumerId = ?",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 1,
            binders = { bindString(0, consumerId) },
        ).value
}
