@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import platform.posix.remove
import kotlin.test.Test

class SlockSqlDatabaseOhosSmokeTest {
    @Test
    fun nativeDriverOpensSchemaAndRoundTrips() = runBlocking {
        val driver = NativeSqliteDriver(
            schema = SlockSqlDatabase.Schema,
            name = "slock_native_smoke.db",
            onConfiguration = { configuration -> configuration.copy(inMemory = true) }
        )
        try {
            assertSlockSqlDatabaseSmoke(driver)
        } finally {
            driver.close()
        }
    }

    @Test
    fun nativeFileBackedMigrationRollsBackAndRetriesForEveryDdlBoundary() {
        val basePath = getenv("TMPDIR")?.toKString()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "/tmp"
        assertFileBackedDurablePartitionMigrationRollbackRetry(
            platformName = "ohos",
            openDriver = { schema, name ->
                NativeSqliteDriver(
                    schema = schema,
                    name = name,
                    onConfiguration = { configuration ->
                        configuration.copy(
                            extendedConfig = configuration.extendedConfig.copy(basePath = basePath),
                        )
                    },
                )
            },
            cleanupDatabase = { name ->
                listOf(name, "$name-wal", "$name-shm").forEach { candidate ->
                    remove("$basePath/$candidate")
                }
            },
        )
    }
}
