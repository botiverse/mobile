package ai.slock.compose.data

internal class SqlDelightRelationalStore(
    private val database: SlockSqlDatabase
) : SlockRelationalStore {
    override suspend fun execute(sql: String, bindArgs: List<RelationalValue>) {
        database.execute(sql, bindArgs)
    }

    override suspend fun query(sql: String, bindArgs: List<RelationalValue>): RelationalRows =
        database.query(sql, bindArgs)

    override suspend fun transaction(block: suspend SlockRelationalStore.() -> Unit) {
        database.transaction {
            block()
        }
    }
}

