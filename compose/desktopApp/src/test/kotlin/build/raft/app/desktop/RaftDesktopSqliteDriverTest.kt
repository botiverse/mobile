package build.raft.app.desktop

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import build.raft.app.data.RaftDesktopSqliteDriver
import build.raft.app.shared.data.local.RaftDatabase
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The desktop database driver reuses its SQLite connections (SQLDelight's JdbcSqliteDriver opened
 * one per statement, on the UI thread for page reads) with JdbcSqliteDriver's schema, transaction
 * and listener behaviour.
 */
class RaftDesktopSqliteDriverTest {
    private val dir: File = Files.createTempDirectory("raft-sqlite").toFile()
    private val url = "jdbc:sqlite:" + File(dir, "test.db").absolutePath

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private class Schema(override val version: Long) : SqlSchema<QueryResult.Value<Unit>> {
        val migrations = mutableListOf<Pair<Long, Long>>()

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE item(id INTEGER PRIMARY KEY, name TEXT NOT NULL)", 0)
            return QueryResult.Unit
        }

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            migrations += oldVersion to newVersion
            driver.execute(null, "ALTER TABLE item ADD COLUMN extra TEXT", 0)
            return QueryResult.Unit
        }
    }

    private fun SqlDriver.count(): Long =
        executeQuery(null, "SELECT count(*) FROM item", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value ?: -1L

    private fun SqlDriver.userVersion(): Long =
        executeQuery(null, "PRAGMA user_version", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value ?: -1L

    @Test fun `statements reuse one connection instead of opening one each`() {
        val driver = RaftDesktopSqliteDriver.open(url, Properties(), Schema(1))
        try {
            repeat(50) { index -> driver.execute(null, "INSERT INTO item(name) VALUES ('n$index')", 0) }
            repeat(50) { assertEquals(50L, driver.count()) }
            assertEquals(1, driver.openedConnections)
            assertEquals(1, driver.idleConnections)
        } finally {
            driver.close()
        }
    }

    @Test fun `create sets user_version and a newer schema migrates once`() {
        RaftDesktopSqliteDriver.open(url, Properties(), Schema(1)).use { driver ->
            assertEquals(1L, driver.userVersion())
            driver.execute(null, "INSERT INTO item(name) VALUES ('kept')", 0)
        }
        val v2 = Schema(2)
        RaftDesktopSqliteDriver.open(url, Properties(), v2).use { driver ->
            assertEquals(listOf(1L to 2L), v2.migrations)
            assertEquals(2L, driver.userVersion())
            assertEquals(1L, driver.count())
        }
        val again = Schema(2)
        RaftDesktopSqliteDriver.open(url, Properties(), again).use { assertEquals(emptyList(), again.migrations) }
    }

    @Test fun `a transaction keeps its connection, commits or rolls back, then returns it`() {
        val driver = RaftDesktopSqliteDriver.open(url, Properties(), Schema(1))
        val transacter = object : TransacterImpl(driver) {}
        try {
            transacter.transaction {
                driver.execute(null, "INSERT INTO item(name) VALUES ('a')", 0)
                transacter.transaction { driver.execute(null, "INSERT INTO item(name) VALUES ('b')", 0) }
                assertEquals(2L, driver.count())
            }
            assertEquals(2L, driver.count())
            assertFailsWith<IllegalStateException> {
                transacter.transaction {
                    driver.execute(null, "INSERT INTO item(name) VALUES ('c')", 0)
                    error("roll back")
                }
            }
            assertEquals(2L, driver.count())
            assertEquals(1, driver.idleConnections)
        } finally {
            driver.close()
        }
    }

    @Test fun `concurrent readers get their own connections and only maxIdle stay open`() {
        val driver = RaftDesktopSqliteDriver.open(url, Properties(), Schema(1))
        try {
            driver.execute(null, "INSERT INTO item(name) VALUES ('x')", 0)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads =
                (1..8).map {
                    thread {
                        runCatching { repeat(200) { check(driver.count() == 1L) } }.onFailure(failures::add)
                    }
                }
            threads.forEach { it.join() }
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertTrue(driver.idleConnections <= 4, "idle=${driver.idleConnections}")
        } finally {
            driver.close()
        }
    }

    @Test fun `the app's schema creates as with SQLDelight's driver and reopens without migrating`() {
        val reference = File(dir, "reference.db")
        app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:" + reference.absolutePath, Properties(), RaftDatabase.Schema).use { }
        fun tables(path: String): List<String> =
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:$path", Properties()).use { driver ->
                driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type IN ('table','index') ORDER BY name", { c ->
                    QueryResult.Value(buildList { while (c.next().value) add(c.getString(0).orEmpty()) })
                }, 0).value
            }
        RaftDesktopSqliteDriver.open(url, Properties(), RaftDatabase.Schema).use { assertEquals(RaftDatabase.Schema.version, it.userVersion()) }
        RaftDesktopSqliteDriver.open(url, Properties(), RaftDatabase.Schema).use { assertEquals(RaftDatabase.Schema.version, it.userVersion()) }
        assertEquals(tables(reference.absolutePath), tables(File(dir, "test.db").absolutePath))
    }

    @Test fun `listeners are notified for their query keys`() {
        val driver = RaftDesktopSqliteDriver.open(url, Properties(), Schema(1))
        try {
            var calls = 0
            val listener = Query.Listener { calls += 1 }
            driver.addListener("item", listener = listener)
            driver.notifyListeners("other")
            driver.notifyListeners("item", "other")
            driver.removeListener("item", listener = listener)
            driver.notifyListeners("item")
            assertEquals(1, calls)
        } finally {
            driver.close()
        }
    }
}
