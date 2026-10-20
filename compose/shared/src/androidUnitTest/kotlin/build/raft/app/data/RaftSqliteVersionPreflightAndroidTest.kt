package build.raft.app.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RaftSqliteVersionPreflightAndroidTest {
    private lateinit var databaseDirectory: File

    @BeforeTest
    fun setUp() {
        databaseDirectory = Files.createTempDirectory("slock-version-preflight-android").toFile()
    }

    @AfterTest
    fun tearDown() {
        databaseDirectory.deleteRecursively()
    }

    @Test
    fun readsCommittedWalVersionWithoutChangingAnyDatabaseFileBytes() {
        val databaseName = "future.db"
        val databaseFile = File(databaseDirectory, databaseName)
        val futureVersion = RaftSqlDatabase.Schema.version + 3L
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.execSQL("PRAGMA user_version = ${RaftSqlDatabase.Schema.version}")
        }
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.rawQuery("PRAGMA journal_mode = WAL", null).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0).lowercase())
            }
            database.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.execSQL("PRAGMA user_version = $futureVersion")
            val filesBefore = databaseDirectory.fileBytes()
            check(filesBefore.keys.any { it.endsWith("-wal") })
            check(filesBefore.keys.any { it.endsWith("-shm") })
            assertEquals(RaftSqlDatabase.Schema.version, databaseFile.sqliteHeaderUserVersion())

            val storedVersion =
                readSqliteUserVersionReadOnly(
                    databaseName = databaseName,
                    basePath = databaseDirectory.absolutePath,
                )

            assertEquals(futureVersion, storedVersion)
            assertFileBytesEqual(filesBefore, databaseDirectory.fileBytes())
        }
    }

    @Test
    fun missingDatabaseReturnsNullWithoutCreatingAFile() {
        val databaseName = "missing.db"

        assertEquals(
            null,
            readSqliteUserVersionReadOnly(
                databaseName = databaseName,
                basePath = databaseDirectory.absolutePath,
            ),
        )

        assertFalse(File(databaseDirectory, databaseName).exists())
        assertEquals(emptyList(), databaseDirectory.fileNames())
    }

    @Test
    fun corruptExistingDatabaseFailsClosedInsteadOfBeingTreatedAsMissing() {
        val databaseName = "corrupt.db"
        File(databaseDirectory, databaseName).writeText("not sqlite")

        assertFails {
            readSqliteUserVersionReadOnly(
                databaseName = databaseName,
                basePath = databaseDirectory.absolutePath,
            )
        }
    }

    private fun File.fileNames(): List<String> =
        list()?.sorted().orEmpty()

    private fun File.fileBytes(): Map<String, ByteArray> =
        listFiles()
            ?.filter(File::isFile)
            ?.associate { file -> file.name to file.readBytes() }
            .orEmpty()

    private fun File.sqliteHeaderUserVersion(): Long {
        val header = ByteArray(64)
        RandomAccessFile(this, "r").use { file -> file.readFully(header) }
        return (
            ((header[60].toInt() and 0xff) shl 24) or
                ((header[61].toInt() and 0xff) shl 16) or
                ((header[62].toInt() and 0xff) shl 8) or
                (header[63].toInt() and 0xff)
        ).toLong()
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
}
