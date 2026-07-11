package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import build.raft.app.shared.data.local.SlockDatabase
import build.raft.app.shared.data.local.SlockDatabaseQueries

internal class SlockSqlDatabase(
    private val driver: SqlDriver
) {
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
        execute("BEGIN IMMEDIATE")
        try {
            block()
            execute("COMMIT")
        } catch (throwable: Throwable) {
            runCatching { execute("ROLLBACK") }
            throw throwable
        }
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
        // #491 P1: fresh DB file for the sync-core persistence baseline. Bumping the filename (v2->v3)
        // starts a brand-new DB with schema/migration history reset to zero — the pre-release reset
        // means no live DB to migrate, so the epoch cursor column + (seq,id) restore index ship as the
        // Fresh schema v1: pre-release caches are replaceable server mirrors, so v3 is abandoned.
        const val DatabaseName: String = "slock_cache_v4.db"

        val Schema = SlockDatabase.Schema
    }
}
