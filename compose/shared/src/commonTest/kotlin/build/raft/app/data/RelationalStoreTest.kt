package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelationalStoreTest {
    @Test
    fun transactionCommitsSuccessfulWork() = runBlocking {
        val store = InMemoryRelationalStore()

        store.transaction {
            execute("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)")
            execute(
                "INSERT OR REPLACE INTO kv(key, value) VALUES(?, ?)",
                listOf(RelationalValue.Text("theme"), RelationalValue.Text("dark"))
            )
        }

        assertEquals(
            listOf(
                "BEGIN IMMEDIATE",
                "CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)",
                "INSERT OR REPLACE INTO kv(key, value) VALUES(?, ?)",
                "COMMIT"
            ),
            store.executedStatements.map { it.first }
        )
    }

    @Test
    fun transactionRollsBackFailedWork() = runBlocking {
        val store = InMemoryRelationalStore()

        assertFailsWith<IllegalStateException> {
            store.transaction {
                execute("INSERT INTO kv(key, value) VALUES(?, ?)")
                error("write failed")
            }
        }

        assertEquals(
            listOf(
                "BEGIN IMMEDIATE",
                "INSERT INTO kv(key, value) VALUES(?, ?)",
                "ROLLBACK"
            ),
            store.executedStatements.map { it.first }
        )
    }
}
