package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.shared.data.local.SlockDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #491 P1 schema gate — FRESH baseline, no migration. Per the pre-release DB reset (filename bumped
 * to `slock_cache_v3.db`, schema/migration history reset to zero), the sync-core persistence ships as
 * a brand-new schema version 1 with the per-scope epoch cursor column + the canonical
 * (serverId, channelId, seq, id) restore index built in — there is no v1->v2 migration.
 *
 * A fresh `Schema.create` must produce: the nullable epoch column, the tie-break index with the exact
 * column order, live FK enforcement, and the canonical server-backed `(seq, id)` restore ordering.
 * JDBC drives this schema gate; Android/Native open-path is covered by the driver audit (task #494).
 */
class SyncPersistenceSchemaTest {

    @Test
    fun freshSchemaHasEpochCursorColumnTieBreakIndexAndFkEnforcement() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SlockDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0) // else FK assertions are false-green

        // 1) epoch column present on the fresh schema — TEXT, nullable (no value until first snapshot).
        val epoch = assertNotNull(
            driver.columnInfo("syncCursorEntity").firstOrNull { it.name == "epoch" },
            "epoch column missing from fresh schema",
        )
        assertEquals("TEXT", epoch.type)
        driver.execute(
            null,
            "INSERT INTO syncCursorEntity(scopeId, serverId, scopeKind, latestSeq, updatedAtMs) " +
                "VALUES ('scope1','s1','channel', 42, 300);",
            0,
        )
        assertNull(driver.singleText("SELECT epoch FROM syncCursorEntity WHERE scopeId='scope1';"))

        // 2) canonical tie-break index exists with the EXACT column order.
        assertEquals(
            listOf("serverId", "channelId", "seq", "id"),
            driver.indexColumns("messageEntity_channelSeqId"),
        )

        // 3) canonical-native schema contract: messageEntity carries the lossless `canonicalFactJson` +
        //    `scopeKind`, and `seq` is NOT NULL (pending/optimistic NEVER enter this table — the constraint
        //    is structural, so no "seq-null pending" state can exist to be filtered/preserved).
        val cols = driver.columnInfo("messageEntity")
        assertNotNull(cols.firstOrNull { it.name == "canonicalFactJson" }, "canonicalFactJson column missing")
        assertNotNull(cols.firstOrNull { it.name == "scopeKind" }, "scopeKind column missing")
        assertEquals(1L, cols.first { it.name == "seq" }.notNull, "seq must be NOT NULL (no pending rows)")

        // 4) restore ordering via the GENERATED selectServerBackedMessagesForChannel query (proves the .sq
        //    query, not raw SQL): same seq tie-breaks by id (m1 before m2). No seq-null filter needed.
        driver.insertMessage(id = "m2", seq = 7)
        driver.insertMessage(id = "m1", seq = 7)
        val restored = SlockDatabase(driver).slockDatabaseQueries
            .selectServerBackedMessagesForChannel(serverId = "s1", scopeKind = "channel", channelId = "c1")
            .executeAsList()
            .map { it.id }
        assertEquals(listOf("m1", "m2"), restored)

        // 4) FK enforcement live on the message content children that SHOULD cascade — attachment /
        //    reaction (the projector rebuilds these in the same txn on rebaseline, §4.4). NOTE:
        //    savedMessageEntity is deliberately excluded here — its ON DELETE CASCADE is a schema
        //    hazard the wrapper must work AROUND (saved is an independent receiver domain the messages
        //    snapshot never adjudicates: prune skips saved-referenced facts / snapshot preserves the
        //    saved marker). Asserting a message-delete cascading saved as "success" would codify the
        //    wrong contract, so it is proven at the wrapper layer, not here.
        driver.execute(
            null,
            "INSERT INTO attachmentEntity(id, messageId, name, mimeType, sizeLabel, sortOrder) " +
                "VALUES ('a1','m1','f.png','image/png','1kb', 0);",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO messageReactionEntity(messageId, reactorKind, reactorId, emoji) " +
                "VALUES ('m1','human','u1',':+1:');",
            0,
        )
        driver.execute(null, "DELETE FROM messageEntity WHERE id='m1';", 0)
        assertEquals(0L, driver.singleLong("SELECT COUNT(*) FROM attachmentEntity WHERE messageId='m1';"))
        assertEquals(0L, driver.singleLong("SELECT COUNT(*) FROM messageReactionEntity WHERE messageId='m1';"))

        driver.close()
    }

    private fun JdbcSqliteDriver.insertMessage(id: String, seq: Int) = execute(
        null,
        "INSERT INTO messageEntity(id, serverId, channelId, scopeKind, seq, createdAt, canonicalFactJson) " +
            "VALUES ('$id','s1','c1','channel', $seq, 't', '{\"version\":1,\"id\":\"$id\"}');",
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

    private fun JdbcSqliteDriver.singleLong(sql: String): Long? =
        executeQuery(null, sql, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        }, 0).value
}
