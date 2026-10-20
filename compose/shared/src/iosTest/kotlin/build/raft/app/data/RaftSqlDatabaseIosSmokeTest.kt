@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RaftSqlDatabaseIosSmokeTest {
    @Test
    fun nativeDriverOpensSchemaAndRoundTrips() = runBlocking {
        val driver = NativeSqliteDriver(
            schema = RaftSqlDatabase.Schema,
            name = "slock_native_smoke.db",
            onConfiguration = { configuration -> configuration.copy(inMemory = true) }
        )
        try {
            assertRaftSqlDatabaseSmoke(driver)
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

    @Test
    fun nativeVersionPreflightReadsCommittedWalWithoutChangingAnyFileBytes() {
        val basePath = "${NSTemporaryDirectory().trimEnd('/')}/slock-version-preflight-ios"
        val files = NSFileManager.defaultManager()
        if (!files.fileExistsAtPath(basePath)) {
            check(files.createDirectoryAtPath(basePath, true, null, null))
        }
        val name = "future-${NSUUID.UUID().UUIDString}.db"
        val futureVersion = RaftSqlDatabase.Schema.version + 3L
        val driver =
            NativeSqliteDriver(
                schema = RaftSqlDatabase.Schema,
                name = name,
                onConfiguration = { configuration ->
                    configuration.copy(
                        extendedConfig = configuration.extendedConfig.copy(basePath = basePath),
                    )
                },
            )
        try {
            driver.execute(null, "PRAGMA user_version = $futureVersion", 0)
            val filesBefore = databaseFileBytes(basePath, name)
            check(filesBefore.keys.any { it.endsWith("-wal") })
            check(filesBefore.keys.any { it.endsWith("-shm") })

            val storedVersion =
                readSqliteUserVersionReadOnly(
                    databaseName = name,
                    basePath = basePath,
                )

            assertEquals(futureVersion, storedVersion)
            assertFileBytesEqual(filesBefore, databaseFileBytes(basePath, name))
        } finally {
            driver.close()
            deleteDatabaseFiles(basePath, name)
        }
    }

    @Test
    fun nativeVersionPreflightDoesNotCreateMissingDatabase() {
        val basePath = "${NSTemporaryDirectory().trimEnd('/')}/slock-version-preflight-ios"
        val files = NSFileManager.defaultManager()
        if (!files.fileExistsAtPath(basePath)) {
            check(files.createDirectoryAtPath(basePath, true, null, null))
        }
        val name = "missing-${NSUUID.UUID().UUIDString}.db"

        assertEquals(
            null,
            readSqliteUserVersionReadOnly(
                databaseName = name,
                basePath = basePath,
            ),
        )
        assertFalse(files.fileExistsAtPath("$basePath/$name"))
    }

    private fun databaseFileBytes(
        basePath: String,
        name: String,
    ): Map<String, ByteArray> {
        val files = NSFileManager.defaultManager()
        return listOf(name, "$name-wal", "$name-shm", "$name-journal")
            .mapNotNull { candidate ->
                val path = "$basePath/$candidate"
                val data = files.contentsAtPath(path) ?: return@mapNotNull null
                val length = data.length.toInt()
                val bytes =
                    if (length == 0) {
                        ByteArray(0)
                    } else {
                        checkNotNull(data.bytes).readBytes(length)
                    }
                candidate to bytes
            }
            .toMap()
    }

    private fun assertFileBytesEqual(
        expected: Map<String, ByteArray>,
        actual: Map<String, ByteArray>,
    ) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) ->
            assertContentEquals(bytes, actual.getValue(name), name)
        }
    }

    private fun deleteDatabaseFiles(
        basePath: String,
        name: String,
    ) {
        val files = NSFileManager.defaultManager()
        listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach { candidate ->
            val path = "$basePath/$candidate"
            if (files.fileExistsAtPath(path)) files.removeItemAtPath(path, null)
        }
    }
}
