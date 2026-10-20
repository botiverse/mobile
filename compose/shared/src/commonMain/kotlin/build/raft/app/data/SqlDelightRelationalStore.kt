package build.raft.app.data

internal class SqlDelightRelationalStore(
    private val database: RaftSqlDatabase
) : RaftRelationalStore {
    override suspend fun execute(sql: String, bindArgs: List<RelationalValue>) {
        database.execute(sql, bindArgs)
    }

    override suspend fun query(sql: String, bindArgs: List<RelationalValue>): RelationalRows =
        database.query(sql, bindArgs)

    override suspend fun transaction(block: suspend RaftRelationalStore.() -> Unit) {
        database.transaction {
            block()
        }
    }
}

