package ai.slock.compose.data

import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.bindString
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.withConnection
import co.touchlab.sqliter.withStatement

/**
 * Minimal OHOS sqlite probe for the shared data layer.
 *
 * This intentionally does not expose production state yet. It proves the OHOS
 * source set can compile against Slock's forked native sqlite stack, which is
 * the dependency gate before moving the real local-store schema into shared.
 */
object OhosSqliteSmoke {
    fun roundTrip(value: String = "slock"): String {
        val manager =
            createDatabaseManager(
                DatabaseConfiguration(
                    name = "slock_shared_smoke",
                    version = 1,
                    inMemory = true,
                    create = { connection ->
                        connection.rawExecSql(
                            "CREATE TABLE IF NOT EXISTS shared_sqlite_smoke (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
                        )
                    }
                )
            )
        return manager.withConnection { connection ->
            connection.rawExecSql("DELETE FROM shared_sqlite_smoke")
            connection.withStatement("INSERT INTO shared_sqlite_smoke(value) VALUES (?)") {
                bindString(1, value)
                executeInsert()
            }
            connection.withStatement("SELECT value FROM shared_sqlite_smoke WHERE id = 1") {
                val cursor = query()
                check(cursor.next()) { "OHOS sqlite smoke row was not inserted" }
                cursor.getString(0)
            }
        }
    }
}
