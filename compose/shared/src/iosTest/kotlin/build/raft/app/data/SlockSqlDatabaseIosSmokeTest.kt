package build.raft.app.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test

class SlockSqlDatabaseIosSmokeTest {
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
        val basePath = "${NSTemporaryDirectory().trimEnd('/')}/slock-durable-partition-tests"
        val files = NSFileManager.defaultManager()
        if (!files.fileExistsAtPath(basePath)) {
            check(files.createDirectoryAtPath(basePath, true, null, null))
        }
        assertFileBackedDurablePartitionMigrationRollbackRetry(
            platformName = "ios",
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
                    val path = "$basePath/$candidate"
                    if (files.fileExistsAtPath(path)) files.removeItemAtPath(path, null)
                }
            },
        )
    }
}
