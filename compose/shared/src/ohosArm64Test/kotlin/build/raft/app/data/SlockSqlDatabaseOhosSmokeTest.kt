@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.O_RDONLY
import platform.posix.close as posixClose
import platform.posix.getenv
import platform.posix.open as posixOpen
import platform.posix.read as posixRead
import platform.posix.remove
import platform.posix.stat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    @Test
    fun nativeVersionPreflightReadsCommittedWalWithoutChangingAnyFileBytes() {
        val basePath = getenv("TMPDIR")?.toKString()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "/tmp"
        val name = "slock-version-preflight-ohos.db"
        val futureVersion = SlockSqlDatabase.Schema.version + 3L
        deleteDatabaseFiles(basePath, name)
        val driver =
            NativeSqliteDriver(
                schema = SlockSqlDatabase.Schema,
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
        val basePath = getenv("TMPDIR")?.toKString()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "/tmp"
        val name = "slock-version-preflight-ohos-missing.db"
        deleteDatabaseFiles(basePath, name)

        assertEquals(
            null,
            readSqliteUserVersionReadOnly(
                databaseName = name,
                basePath = basePath,
            ),
        )
        assertFalse(databaseFileExists(basePath, name))
    }

    private fun databaseFileBytes(
        basePath: String,
        name: String,
    ): Map<String, ByteArray> =
        listOf(name, "$name-wal", "$name-shm", "$name-journal")
            .mapNotNull { candidate ->
                readFileBytesOrNull("$basePath/$candidate")?.let { bytes -> candidate to bytes }
            }
            .toMap()

    private fun assertFileBytesEqual(
        expected: Map<String, ByteArray>,
        actual: Map<String, ByteArray>,
    ) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) ->
            assertContentEquals(bytes, actual.getValue(name), name)
        }
    }

    private fun databaseFileExists(
        basePath: String,
        name: String,
    ): Boolean = fileSizeOrNull("$basePath/$name") != null

    private fun fileSizeOrNull(path: String): Long? =
        memScoped {
            val fileStatus = alloc<stat>()
            if (stat(path, fileStatus.ptr) == 0) fileStatus.st_size else null
        }

    private fun readFileBytesOrNull(path: String): ByteArray? {
        val size = fileSizeOrNull(path) ?: return null
        check(size <= Int.MAX_VALUE.toLong()) { "Test database file is too large to snapshot" }
        val descriptor = posixOpen(path, O_RDONLY)
        check(descriptor >= 0) { "Unable to open test database file" }
        return try {
            val bytes = ByteArray(size.toInt())
            var totalRead = 0
            while (totalRead < bytes.size) {
                val count =
                    bytes.usePinned { pinned ->
                        posixRead(
                            descriptor,
                            pinned.addressOf(totalRead),
                            (bytes.size - totalRead).convert(),
                        ).toInt()
                    }
                check(count > 0) { "Unable to read complete test database file" }
                totalRead += count
            }
            bytes
        } finally {
            posixClose(descriptor)
        }
    }

    private fun deleteDatabaseFiles(
        basePath: String,
        name: String,
    ) {
        listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach { candidate ->
            remove("$basePath/$candidate")
        }
    }
}
