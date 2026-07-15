package build.raft.app.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.coroutines.runBlocking
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
        assertFileBackedDurablePartitionMigrationRollbackRetry(platformName = "ios") { schema, name ->
            NativeSqliteDriver(schema = schema, name = name)
        }
    }
}
