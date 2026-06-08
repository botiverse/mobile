package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal class BridgeRelationalStore(
    private val bridge: BridgeModule,
    private val databaseName: String = SlockSqlDatabase.DatabaseName,
    private val version: Int = 1
) : SlockRelationalStore {
    override suspend fun execute(sql: String, bindArgs: List<RelationalValue>) {
        bridge.relationalStore(command(action = "execute", sql = sql, bindArgs = bindArgs))
            .ensureOk(sql)
    }

    override suspend fun query(sql: String, bindArgs: List<RelationalValue>): RelationalRows {
        val response =
            bridge.relationalStore(command(action = "query", sql = sql, bindArgs = bindArgs))
                .ensureOk(sql)
        return response.toRelationalRows()
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
            .put("action", action)
            .put("databaseName", databaseName)
            .put("version", version)
            .put("sql", sql)
            .put("bindArgs", bindArgs.toJsonArray())

    private fun List<RelationalValue>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { value ->
                when (value) {
                    RelationalValue.Null -> array.put(null)
                    is RelationalValue.Text -> array.put(value.value)
                    is RelationalValue.LongNumber -> array.put(value.value)
                    is RelationalValue.DoubleNumber -> array.put(value.value)
                    is RelationalValue.BooleanValue -> array.put(value.value)
                }
            }
        }
}

internal class RelationalStoreException(
    val code: Int?,
    private val safeMessage: String?,
    val category: String = classifyRelationalStoreFailure(code, safeMessage)
) : RuntimeException(safeMessage ?: "relational store failed")

private fun JSONObject?.ensureOk(sql: String): JSONObject {
    val response =
        this ?: throw RelationalStoreException(
            code = null,
            safeMessage = "relational store did not return a response"
        )
    if (response.optString("status") == "ok") return response
    val code = response.optString("code").toIntOrNull()
    val message = response.optString("message").takeIf { it.isNotBlank() }
    throw RelationalStoreException(
        code = code,
        safeMessage = message ?: "relational store failed for ${sql.safeSqlVerb()}"
    )
}

private fun JSONObject.toRelationalRows(): RelationalRows {
    val columnsJson = optJSONArray("columns")
    val rowsJson = optJSONArray("rows")
    val columns =
        buildList {
            if (columnsJson != null) {
                for (index in 0 until columnsJson.length()) {
                    columnsJson.optString(index)?.let(::add)
                }
            }
        }
    val rows =
        buildList {
            if (rowsJson != null) {
                for (rowIndex in 0 until rowsJson.length()) {
                    val rowJson = rowsJson.optJSONArray(rowIndex) ?: continue
                    add(
                        buildList {
                            for (columnIndex in 0 until rowJson.length()) {
                                add(rowJson.optString(columnIndex)?.let(RelationalValue::Text) ?: RelationalValue.Null)
                            }
                        }
                    )
                }
            }
        }
    return RelationalRows(columns = columns, rows = rows)
}

internal fun classifyRelationalStoreFailure(code: Int?, message: String?): String {
    val lower = message.orEmpty().lowercase()
    return when {
        "no such table" in lower ||
            "no such column" in lower ||
            "schema" in lower ||
            "migration" in lower -> "schema"
        "locked" in lower ||
            "busy" in lower -> "lock"
        "corrupt" in lower ||
            "malformed" in lower ||
            "not a database" in lower -> "corrupt"
        "permission" in lower ||
            "readonly" in lower ||
            "read-only" in lower ||
            "access denied" in lower -> "permission"
        "unable to open" in lower ||
            "no such file" in lower ||
            "path" in lower -> "path"
        code != null -> "unknown_code"
        else -> "unknown"
    }
}

private fun String.safeSqlVerb(): String =
    trimStart().substringBefore(' ', missingDelimiterValue = "").ifBlank { "sql" }.uppercase()
