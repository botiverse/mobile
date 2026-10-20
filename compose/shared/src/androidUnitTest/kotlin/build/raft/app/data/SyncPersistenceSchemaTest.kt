package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.shared.data.local.RaftDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #491 P1 schema gate — FRESH baseline, no migration (pre-release DB reset, `slock_cache_v4.db`). The
 * sync-core persistence ships as a canonical-native schema version 1: messageEntity holds the lossless
 * `canonicalFactJson` + minimal scope/order scalars (no presentation columns), with the full durable
 * partition + scope `(serverId, principalId, consumerId, scopeKind, channelId, seq, id)` restore index and
 * the per-partition-scope epoch cursor column.
 *
 * A fresh `Schema.create` must produce: the nullable epoch column, that tie-break index with the exact
 * column order, `seq NOT NULL` (pending never enters), and the canonical `(seq, id)` restore ordering.
 * JDBC drives this schema gate; Android/Native open-path is covered by the driver audit (task #494).
 */
class SyncPersistenceSchemaTest {

    @Test
    fun freshSchemaHasEpochCursorColumnTieBreakIndexAndFkEnforcement() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RaftDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0) // else FK assertions are false-green

        // 1) epoch column present on the fresh schema — TEXT, nullable (no value until first snapshot).
        val epoch = assertNotNull(
            driver.columnInfo("syncCursorEntity").firstOrNull { it.name == "epoch" },
            "epoch column missing from fresh schema",
        )
        assertEquals("TEXT", epoch.type)
        driver.execute(
            null,
            "INSERT INTO syncCursorEntity(scopeId, environmentId, serverId, principalId, consumerId, scopeKind, latestSeq, updatedAtMs) " +
                "VALUES ('scope1','legacy','s1','p1','sync-core.messages.v1','channel', 42, 300);",
            0,
        )
        assertNull(driver.singleText("SELECT epoch FROM syncCursorEntity WHERE scopeId='scope1';"))

        // 2) canonical tie-break index exists with the EXACT column order.
        assertEquals(
            listOf("environmentId", "serverId", "principalId", "consumerId", "scopeKind", "channelId", "seq", "id"),
            driver.indexColumns("messageEntity_partitionChannelSeqId"),
        )

        // 3) canonical-native schema contract: messageEntity carries the lossless `canonicalFactJson` +
        //    `scopeKind`, and `seq` is NOT NULL (pending/optimistic NEVER enter this table — the constraint
        //    is structural, so no "seq-null pending" state can exist to be filtered/preserved).
        val cols = driver.columnInfo("messageEntity")
        assertNotNull(cols.firstOrNull { it.name == "canonicalFactJson" }, "canonicalFactJson column missing")
        assertNotNull(cols.firstOrNull { it.name == "scopeKind" }, "scopeKind column missing")
        assertEquals(1L, cols.first { it.name == "seq" }.notNull, "seq must be NOT NULL (no pending rows)")

        // 4) owner read state is a disjoint durable fact family, not a message partition or presentation row.
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
            driver.columnInfo("syncReadStateFactEntity").map { it.name },
        )

        // 5) restore ordering via the GENERATED selectServerBackedMessagesForChannel query (proves the .sq
        //    query, not raw SQL): same seq tie-breaks by id (m1 before m2). No seq-null filter needed.
        driver.insertMessage(id = "m2", seq = 7)
        driver.insertMessage(id = "m1", seq = 7)
        val restored = RaftDatabase(driver).raftDatabaseQueries
            .selectServerBackedMessagesForChannel(
                environmentId = "legacy",
                serverId = "s1",
                principalId = "p1",
                consumerId = "sync-core.messages.v1",
                scopeKind = "channel",
                channelId = "c1",
            )
            .executeAsList()
            .map { it.id }
        assertEquals(listOf("m1", "m2"), restored)

        // (The attachment/reaction/saved child tables were removed with the canonical-native rebuild —
        // attachments/reactions live losslessly in canonicalFactJson, so there is no child FK-cascade to
        // assert here. The store-level suite proves scalars==extract(decode(payload)) instead.)

        driver.close()
    }

    private fun JdbcSqliteDriver.insertMessage(id: String, seq: Int) = execute(
        null,
        "INSERT INTO messageEntity(environmentId, serverId, principalId, consumerId, id, channelId, scopeKind, seq, createdAt, canonicalFactJson) " +
            "VALUES ('legacy','s1','p1','sync-core.messages.v1','$id','c1','channel', $seq, 't', '{\"version\":1,\"id\":\"$id\"}');",
        0,
    )

    private data class ColumnInfo(val name: String, val type: String, val notNull: Long)

    private fun JdbcSqliteDriver.columnInfo(table: String): List<ColumnInfo> =
        executeQuery(null, "PRAGMA table_info($table);", { cursor ->
            val rows = mutableListOf<ColumnInfo>()
            // PRAGMA table_info columns: cid(0), name(1), type(2), notnull(3), dflt_value(4), pk(5).
            while (cursor.next().value) rows += ColumnInfo(cursor.getString(1)!!, cursor.getString(2)!!, cursor.getLong(3)!!)
            QueryResult.Value(rows.toList())
        }, 0).value

    private fun JdbcSqliteDriver.indexColumns(index: String): List<String> =
        executeQuery(null, "PRAGMA index_xinfo($index);", { cursor ->
            val cols = mutableListOf<Pair<Long, String>>()
            while (cursor.next().value) {
                val seqno = cursor.getLong(0)!!
                val name = cursor.getString(2)
                if (name != null) cols += seqno to name // key columns have a non-null name
            }
            QueryResult.Value(cols.sortedBy { it.first }.map { it.second })
        }, 0).value

    private fun JdbcSqliteDriver.singleText(sql: String): String? =
        executeQuery(null, sql, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 0).value
}
