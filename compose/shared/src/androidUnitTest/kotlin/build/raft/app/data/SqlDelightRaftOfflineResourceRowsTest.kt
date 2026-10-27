package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.shared.data.local.RaftDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The offline resources' rows on the real `localSnapshotEntity` table (new queries, one scope). */
class SqlDelightRaftOfflineResourceRowsTest {
    @Test
    fun rowsRoundTripEvictInTheWriteTransactionAndWipeOnlyTheirScope() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftDatabase.Schema.create(driver).value
            val database = RaftSqlDatabase(driver)
            val rows = SqlDelightRaftOfflineResourceRows(database)
            // A neighbour scope on the same table must survive the offline-resource wipe.
            database.queries.upsertSnapshot("raft_server_computers_v1", "row", "{}", 1L)

            rows.writeAndEvict("a", "1", updatedAtMs = 10L) { emptyList() }
            rows.writeAndEvict("b", "2", updatedAtMs = 20L) { metas ->
                assertEquals(setOf("a" to 10L, "b" to 20L), metas.map { it.id to it.updatedAtMs }.toSet())
                listOf("a")
            }

            assertNull(rows.read("a"))
            assertEquals(RaftOfflineResourceRow("2", 20L), rows.read("b"))
            assertEquals(listOf("b"), rows.ids())

            rows.deleteAll()

            assertEquals(emptyList(), rows.ids())
            assertEquals("{}", database.queries.selectSnapshot("raft_server_computers_v1", "row").executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }
}
