package ai.slock.compose.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import ai.slock.shared.data.local.SlockDatabase
import ai.slock.shared.data.local.SlockDatabaseQueries

internal class SlockSqlDatabase(
    private val driver: SqlDriver
) {
    init {
        ensureCompatibilitySchema()
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

    private fun ensureCompatibilitySchema() {
        ensureMessageReactionSchema()
        driver.execute(
            identifier = null,
            sql =
                """
                CREATE TABLE IF NOT EXISTS conversationAliasEntity (
                  serverId TEXT NOT NULL,
                  aliasId TEXT NOT NULL,
                  aliasKind TEXT NOT NULL,
                  canonicalChannelId TEXT NOT NULL,
                  lastSyncedAtMs INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (serverId, aliasId, aliasKind),
                  FOREIGN KEY (serverId, canonicalChannelId)
                    REFERENCES channelEntity(serverId, channelId)
                    ON DELETE CASCADE
                )
                """.trimIndent(),
            parameters = 0
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX IF NOT EXISTS conversationAliasEntity_alias ON conversationAliasEntity(serverId, aliasId)",
            parameters = 0
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX IF NOT EXISTS conversationAliasEntity_canonical ON conversationAliasEntity(serverId, canonicalChannelId)",
            parameters = 0
        )
    }

    private fun ensureMessageReactionSchema() {
        driver.execute(
            identifier = null,
            sql =
                """
                CREATE TABLE IF NOT EXISTS messageReactionEntity (
                  messageId TEXT NOT NULL REFERENCES messageEntity(id) ON DELETE CASCADE,
                  reactorKind TEXT NOT NULL,
                  reactorId TEXT NOT NULL,
                  emoji TEXT NOT NULL,
                  sortOrder INTEGER NOT NULL DEFAULT 0,
                  createdAtMs INTEGER,
                  lastSyncedAtMs INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (messageId, reactorKind, reactorId, emoji)
                )
                """.trimIndent(),
            parameters = 0
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX IF NOT EXISTS messageReactionEntity_messageId ON messageReactionEntity(messageId)",
            parameters = 0
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX IF NOT EXISTS messageReactionEntity_reactor ON messageReactionEntity(reactorKind, reactorId)",
            parameters = 0
        )
        if (tableHasColumn("messageReactionEntity", "sortOrder")) return
        driver.execute(
            identifier = null,
            sql = "ALTER TABLE messageReactionEntity ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0",
            parameters = 0
        )
    }

    private fun tableHasColumn(tableName: String, columnName: String): Boolean {
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($tableName)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    if (cursor.getString(1) == columnName) {
                        found = true
                    }
                }
                QueryResult.Value(Unit)
            },
            parameters = 0
        )
        return found
    }

    companion object {
        const val DatabaseName: String = "slock_shared.db"

        val Schema = SlockDatabase.Schema
    }
}
