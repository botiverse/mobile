package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared driver-level smoke check reused by the native (iOS + OHOS) leaf tests.
 *
 * The JVM driver already has coverage via the JVM androidUnitTest suite using
 * JdbcSqliteDriver; this exercises the real `NativeSqliteDriver` path instead:
 *   1. the schema was created/opened (the driver is already wrapped in [SlockSqlDatabase]),
 *   2. the schema is present (at least one table exists in `sqlite_master`),
 *   3. a basic write + read round-trips through [SlockSqlDatabase.execute]/[SlockSqlDatabase.query].
 *
 * Intentionally driver-level: it does not couple to generated `SlockDatabaseQueries`.
 */
internal suspend fun assertSlockSqlDatabaseSmoke(driver: SqlDriver) {
    val database = SlockSqlDatabase(driver)

    // (2) Verify the schema was created: the driver opened with SlockSqlDatabase.Schema, so
    // sqlite_master must list at least one table. Read the count as a Long directly to avoid
    // SQLite's text/integer coercion in SlockSqlDatabase.query's value mapper.
    val tableCount = driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table'",
        mapper = { cursor ->
            val count = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
            QueryResult.Value(count)
        },
        parameters = 0
    ).value
    assertTrue(tableCount > 0, "expected SlockSqlDatabase.Schema to create at least one table")

    // (3) Basic write + read against a throwaway probe table via the shared relational API.
    database.execute(
        "CREATE TABLE IF NOT EXISTS slock_native_smoke_probe(id INTEGER PRIMARY KEY, name TEXT)"
    )
    database.execute(
        "INSERT INTO slock_native_smoke_probe(id, name) VALUES (?, ?)",
        listOf(RelationalValue.LongNumber(1), RelationalValue.Text("ok"))
    )
    val rows: RelationalRows = database.query(
        "SELECT name FROM slock_native_smoke_probe WHERE id = ?",
        listOf(RelationalValue.LongNumber(1))
    )
    assertEquals(RelationalValue.Text("ok"), rows.rows.single().single())
}
