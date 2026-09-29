package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import build.raft.app.shared.data.local.SlockDatabase
import build.raft.app.shared.data.local.SlockDatabaseQueries
import kotlinx.coroutines.runBlocking

internal class SlockSqlDatabase(
    private val driver: SqlDriver
) {
    init {
        // Version compatibility is checked by the platform read-only preflight before this class
        // is constructed. NativeSqliteDriver still creates its pools lazily, so force one query
        // while the AppShell provider is being created. SQLDelight then owns new-database creation
        // and forward migration synchronously instead of surfacing failures from a Store coroutine.
        driver.executeQuery<Unit>(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { QueryResult.Value(Unit) },
            parameters = 0,
            binders = null,
        )
    }

    private val database: SlockDatabase = SlockDatabase(driver)
    val queries: SlockDatabaseQueries = database.slockDatabaseQueries

    suspend fun execute(sql: String, bindArgs: List<RelationalValue> = emptyList()) {
        driver.execute(
            identifier = null,
            sql = sql,
            parameters = bindArgs.size,
            binders = { bind(bindArgs) }
        )
    }

    suspend fun query(sql: String, bindArgs: List<RelationalValue> = emptyList()): RelationalRows {
        val rows = mutableListOf<List<RelationalValue>>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows += listOf(cursor.valueAt(index = 0))
                }
                QueryResult.Value(Unit)
            },
            parameters = bindArgs.size,
            binders = { bind(bindArgs) }
        )
        return RelationalRows(columns = emptyList(), rows = rows)
    }

    suspend fun transaction(block: suspend SlockSqlDatabase.() -> Unit) {
        // Use SQLDelight's driver transaction API so every statement is pinned to the same physical
        // connection. Raw BEGIN/COMMIT execute calls are not sufficient for pooled file-backed JDBC: each
        // call may acquire a different connection, making COMMIT observe no active transaction. The store
        // block is synchronous SQL today; runBlocking keeps its suspend-shaped contract inside the driver's
        // thread-confined transaction callback on Android/JDBC/Native.
        database.transaction {
            runBlocking {
                block(this@SlockSqlDatabase)
            }
        }
    }

    /**
     * Synchronous transaction seam for admission paths that deliberately run before the
     * network request is launched.  The journal API is synchronous so a caller cannot
     * accidentally suspend after the UI has been admitted but before the durable write
     * commits; this helper still uses SQLDelight's real transaction boundary.
     */
    fun transactionBlocking(block: SlockSqlDatabase.() -> Unit) {
        database.transaction { block(this@SlockSqlDatabase) }
    }

    private fun SqlPreparedStatement.bind(values: List<RelationalValue>) {
        values.forEachIndexed { index, value ->
            when (value) {
                RelationalValue.Null -> bindString(index, null)
                is RelationalValue.Text -> bindString(index, value.value)
                is RelationalValue.LongNumber -> bindLong(index, value.value)
                is RelationalValue.DoubleNumber -> bindDouble(index, value.value)
                is RelationalValue.BooleanValue -> bindBoolean(index, value.value)
            }
        }
    }

    private fun SqlCursor.valueAt(index: Int): RelationalValue =
        getString(index)?.let(RelationalValue::Text)
            ?: getLong(index)?.let(RelationalValue::LongNumber)
            ?: getDouble(index)?.let(RelationalValue::DoubleNumber)
            ?: getBoolean(index)?.let(RelationalValue::BooleanValue)
            ?: RelationalValue.Null

    companion object {
        // The v4 filename is stable. SQLDelight schema v1 is the checked-in compatibility baseline, v2
        // migrates only the canonical message/cursor core into the durable S/P/C partition, v3 adds the
        // independent receiver-private viewer-overlay table, and v4 adds the independent owner read-state
        // fact family. v5 adds environment ownership to durable facts; v6 adds only scoped avatar
        // metadata/resource-failure policy while native caches keep byte ownership. Every migration
        // preserves outbox and every unrelated definition. v7 adds the native avatar commit
        // identity; v8 adds the identity-bound Thread pending-send journal while leaving the
        // legacy outbox table/query family intact.
        const val DatabaseName: String = "slock_cache_v4.db"

        val Schema = SlockDatabase.Schema
    }
}
