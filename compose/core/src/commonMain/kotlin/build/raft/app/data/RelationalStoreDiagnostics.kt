package build.raft.app.data

class RelationalStoreException(
    val code: Int?,
    private val safeMessage: String?,
    val category: String = classifyRelationalStoreFailure(code, safeMessage)
) : RuntimeException(safeMessage ?: "relational store failed")

data class PersistenceFailureDiagnostics(
    val errorType: String,
    val category: String,
    val code: Int?
)

fun Throwable.toPersistenceFailureDiagnostics(): PersistenceFailureDiagnostics {
    val chain = causeChain()
    chain.filterIsInstance<RelationalStoreException>().firstOrNull()?.let { relational ->
        return PersistenceFailureDiagnostics(
            errorType = "RelationalStoreException",
            category = relational.category,
            code = relational.code
        )
    }
    val message = chain.mapNotNull { it.message }.joinToString(separator = " | ")
    val code = chain.firstNotNullOfOrNull { it.sqliteCodeOrNull() }
    return PersistenceFailureDiagnostics(
        errorType = safeDiagnosticsType(),
        category = classifyRelationalStoreFailure(code, message),
        code = code
    )
}

fun Throwable.safeDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"

private fun Throwable.causeChain(): List<Throwable> =
    buildList {
        var current: Throwable? = this@causeChain
        var depth = 0
        while (current != null && depth < MaxCauseDepth) {
            add(current)
            current = current.cause
            depth += 1
        }
    }

private fun Throwable.sqliteCodeOrNull(): Int? {
    val text = listOfNotNull(message, toString()).joinToString(" ")
    return SqliteCodePatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

fun classifyRelationalStoreFailure(code: Int?, message: String?): String {
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

private val SqliteCodePatterns =
    listOf(
        Regex("""(?:sqliteCode|sqlite_code|errorCode|error_code|code)\s*[=:]\s*(-?\d+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:sqlite|SQLite)[^\d-]{0,24}(-?\d+)""")
    )

private const val MaxCauseDepth = 4
