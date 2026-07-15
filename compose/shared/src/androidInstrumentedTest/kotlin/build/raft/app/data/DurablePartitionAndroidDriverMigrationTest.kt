package build.raft.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import build.raft.app.shared.data.local.SlockDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurablePartitionAndroidDriverMigrationTest {
    @Test
    fun androidFileBackedMigrationRollsBackAndRetriesForEveryDdlBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (failAt in 1..6) {
            val name = "slock-durable-android-$failAt-${UUID.randomUUID()}.db"
            context.deleteDatabase(name)
            try {
                AndroidSqliteDriver(LegacyV1Schema, context, name).useDriver { driver ->
                    seedLegacyRows(driver)
                    assertLegacyState(driver)
                }

                val failure = try {
                    AndroidSqliteDriver(FailAtSchema(SlockDatabase.Schema, failAt), context, name).useDriver { driver ->
                        driver.scalarLong("SELECT 1")
                    }
                    fail("fail-at-$failAt must abort AndroidSqliteDriver migration")
                    error("unreachable")
                } catch (throwable: Throwable) {
                    throwable
                }
                assertTrue(
                    "fail-at-$failAt must expose the injected DDL failure: $failure",
                    failure.hasCause<InjectedAndroidMigrationFailure>(),
                )

                AndroidSqliteDriver(LegacyV1Schema, context, name).useDriver(::assertLegacyState)
                AndroidSqliteDriver(SlockDatabase.Schema, context, name).useDriver(::assertMigratedState)
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    private class InjectedAndroidMigrationFailure(val statementNumber: Int) :
        RuntimeException("injected Android migration failure at DDL $statementNumber")

    private class FailAtSchema(
        private val delegate: SqlSchema<QueryResult.Value<Unit>>,
        private val failAt: Int,
    ) : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long
            get() = delegate.version

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = delegate.create(driver)

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = delegate.migrate(
            FailAtDriver(driver, failAt),
            oldVersion,
            newVersion,
            *callbacks,
        )
    }

    private class FailAtDriver(
        private val delegate: SqlDriver,
        private val failAt: Int,
    ) : SqlDriver by delegate {
        private var executes: Int = 0

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            executes += 1
            if (executes == failAt) throw InjectedAndroidMigrationFailure(failAt)
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private object LegacyV1Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1L

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE messageEntity(" +
                    "id TEXT NOT NULL PRIMARY KEY,serverId TEXT NOT NULL,channelId TEXT NOT NULL," +
                    "scopeKind TEXT NOT NULL,seq INTEGER NOT NULL,createdAt TEXT NOT NULL," +
                    "canonicalFactJson TEXT NOT NULL)",
                0,
            )
            driver.execute(
                null,
                "CREATE INDEX messageEntity_channelSeqId " +
                    "ON messageEntity(serverId,scopeKind,channelId,seq,id)",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE syncCursorEntity(" +
                    "scopeId TEXT NOT NULL,serverId TEXT NOT NULL,scopeKind TEXT NOT NULL,latestSeq INTEGER," +
                    "oldestLoadedSeq INTEGER,newestLoadedSeq INTEGER,hasLoadedOldest INTEGER NOT NULL DEFAULT 0," +
                    "hasLoadedNewest INTEGER NOT NULL DEFAULT 1,updatedAtMs INTEGER NOT NULL,epoch TEXT," +
                    "PRIMARY KEY(serverId,scopeKind,scopeId))",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE outboxMessageEntity(" +
                    "localId TEXT NOT NULL PRIMARY KEY,serverId TEXT NOT NULL,channelId TEXT NOT NULL," +
                    "body TEXT NOT NULL,attachmentIdsJson TEXT NOT NULL DEFAULT '[]'," +
                    "asTask INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,createdAtMs INTEGER NOT NULL," +
                    "lastErrorCode TEXT)",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE androidMigrationSentinel(id TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)",
                0,
            )
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    private fun seedLegacyRows(driver: SqlDriver) {
        driver.execute(
            null,
            "INSERT INTO messageEntity VALUES ('m1','s1','c1','channel',1,'t','{}')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO syncCursorEntity(scopeId,serverId,scopeKind,latestSeq,updatedAtMs) " +
                "VALUES ('c1','s1','channel',1,100)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO outboxMessageEntity(localId,serverId,channelId,body,status,createdAtMs) " +
                "VALUES ('o1','s1','c1','keep-me','pending',100)",
            0,
        )
        driver.execute(null, "INSERT INTO androidMigrationSentinel VALUES ('sentinel','keep-me')", 0)
    }

    private fun assertLegacyState(driver: SqlDriver) {
        assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
        assertEquals(1L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
        assertTrue(driver.hasObject("index", "messageEntity_channelSeqId"))
        assertFalse(driver.hasObject("index", "messageEntity_partitionChannelSeqId"))
        assertEquals("keep-me", driver.scalarString("SELECT body FROM outboxMessageEntity WHERE localId='o1'"))
        assertEquals("keep-me", driver.scalarString("SELECT value FROM androidMigrationSentinel WHERE id='sentinel'"))
    }

    private fun assertMigratedState(driver: SqlDriver) {
        assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM messageEntity"))
        assertEquals(0L, driver.scalarLong("SELECT COUNT(*) FROM syncCursorEntity"))
        assertFalse(driver.hasObject("index", "messageEntity_channelSeqId"))
        assertTrue(driver.hasObject("index", "messageEntity_partitionChannelSeqId"))
        assertEquals("keep-me", driver.scalarString("SELECT body FROM outboxMessageEntity WHERE localId='o1'"))
        assertEquals("keep-me", driver.scalarString("SELECT value FROM androidMigrationSentinel WHERE id='sentinel'"))
    }

    private fun SqlDriver.hasObject(type: String, name: String): Boolean =
        scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type='$type' AND name='$name'") == 1L

    private fun SqlDriver.scalarLong(sql: String): Long = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
    ).value

    private fun SqlDriver.scalarString(sql: String): String? = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 0,
    ).value

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private inline fun SqlDriver.useDriver(block: (SqlDriver) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
