package build.raft.app.data

interface RaftRelationalStore {
    suspend fun execute(sql: String, bindArgs: List<RelationalValue> = emptyList())
    suspend fun query(sql: String, bindArgs: List<RelationalValue> = emptyList()): RelationalRows
    suspend fun transaction(block: suspend RaftRelationalStore.() -> Unit)
}

sealed interface RelationalValue {
    data object Null : RelationalValue
    data class Text(val value: String) : RelationalValue
    data class LongNumber(val value: Long) : RelationalValue
    data class DoubleNumber(val value: Double) : RelationalValue
    data class BooleanValue(val value: Boolean) : RelationalValue
}

data class RelationalRows(
    val columns: List<String>,
    val rows: List<List<RelationalValue>>
)
