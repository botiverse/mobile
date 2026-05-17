package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

interface SlockRelationalStore {
    suspend fun execute(sql: String, bindArgs: List<RelationalValue> = emptyList())
    suspend fun query(sql: String, bindArgs: List<RelationalValue> = emptyList()): RelationalRows
    suspend fun transaction(block: suspend SlockRelationalStore.() -> Unit)
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

internal class KuiklyRelationalStore(
    private val bridge: BridgeModule,
    private val databaseName: String = "slock_shared.db",
    private val version: Int = 1
) : SlockRelationalStore {
    override suspend fun execute(sql: String, bindArgs: List<RelationalValue>) {
        val response = bridge.relationalStore(command("execute", sql, bindArgs))
        response.requireOk("execute")
    }

    override suspend fun query(sql: String, bindArgs: List<RelationalValue>): RelationalRows {
        val response = bridge.relationalStore(command("query", sql, bindArgs))
        response.requireOk("query")
        val columnsJson = response?.optJSONArray("columns")
        val rowsJson = response?.optJSONArray("rows")
        return RelationalRows(
            columns = columnsJson.toStringList(),
            rows =
                buildList {
                    if (rowsJson == null) return@buildList
                    for (rowIndex in 0 until rowsJson.length()) {
                        add(rowsJson.optJSONArray(rowIndex).toRelationalValueList())
                    }
                }
        )
    }

    override suspend fun transaction(block: suspend SlockRelationalStore.() -> Unit) {
        execute("BEGIN IMMEDIATE")
        try {
            block()
            execute("COMMIT")
        } catch (throwable: Throwable) {
            runCatching { execute("ROLLBACK") }
            throw throwable
        }
    }

    private fun command(
        action: String,
        sql: String,
        bindArgs: List<RelationalValue>
    ): JSONObject =
        JSONObject()
            .put("databaseName", databaseName)
            .put("version", version)
            .put("action", action)
            .put("sql", sql)
            .put("bindArgs", bindArgs.toJsonArray())

    private fun JSONObject?.requireOk(action: String) {
        if (this?.optString("status") == "ok") return
        val message = this?.optString("message").orEmpty().ifBlank { "unknown relationalStore error" }
        throw IllegalStateException("relationalStore $action failed: $message")
    }
}

class InMemoryRelationalStore : SlockRelationalStore {
    val executedStatements = mutableListOf<Pair<String, List<RelationalValue>>>()

    override suspend fun execute(sql: String, bindArgs: List<RelationalValue>) {
        executedStatements += sql to bindArgs
    }

    override suspend fun query(sql: String, bindArgs: List<RelationalValue>): RelationalRows {
        executedStatements += sql to bindArgs
        return RelationalRows(emptyList(), emptyList())
    }

    override suspend fun transaction(block: suspend SlockRelationalStore.() -> Unit) {
        execute("BEGIN IMMEDIATE")
        try {
            block()
            execute("COMMIT")
        } catch (throwable: Throwable) {
            execute("ROLLBACK")
            throw throwable
        }
    }
}

private fun List<RelationalValue>.toJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }

private fun RelationalValue.toJsonValue(): Any? =
    when (this) {
        RelationalValue.Null -> null
        is RelationalValue.Text -> value
        is RelationalValue.LongNumber -> value
        is RelationalValue.DoubleNumber -> value
        is RelationalValue.BooleanValue -> value
    }

private fun JSONArray?.toStringList(): List<String> =
    buildList {
        if (this@toStringList == null) return@buildList
        for (index in 0 until this@toStringList.length()) {
            add(this@toStringList.optString(index).orEmpty())
        }
    }

private fun JSONArray?.toRelationalValueList(): List<RelationalValue> =
    buildList {
        if (this@toRelationalValueList == null) return@buildList
        for (index in 0 until this@toRelationalValueList.length()) {
            add(this@toRelationalValueList.opt(index).toRelationalValue())
        }
    }

private fun Any?.toRelationalValue(): RelationalValue =
    when (this) {
        null -> RelationalValue.Null
        is String -> RelationalValue.Text(this)
        is Int -> RelationalValue.LongNumber(toLong())
        is Long -> RelationalValue.LongNumber(this)
        is Float -> RelationalValue.DoubleNumber(toDouble())
        is Double -> RelationalValue.DoubleNumber(this)
        is Boolean -> RelationalValue.BooleanValue(this)
        else -> RelationalValue.Text(toString())
    }
