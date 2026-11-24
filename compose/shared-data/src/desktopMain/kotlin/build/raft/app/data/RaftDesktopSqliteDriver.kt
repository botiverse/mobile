package build.raft.app.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.ConnectionManager.Transaction
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/**
 * SQLDelight's JDBC SQLite driver with its connections kept open between statements.
 *
 * SQLDelight's own `JdbcSqliteDriver` (file URL) opens a new SQLite connection for every statement
 * outside a transaction and closes it right after: each query pays `sqlite3_open` plus a re-parse of
 * the schema. Pages read the database on the UI thread, so that open showed up on the UI thread in
 * the freeze's sample (`NativeDB._open_utf8`). Here a statement borrows an idle connection (at most
 * [maxIdle] are kept; more are opened while that many run at once, and closed when returned), and
 * a transaction keeps its connection until it ends, on the thread that began it, as before.
 * Transactions, listeners and the schema create / migrate step behave as `JdbcSqliteDriver`'s.
 */
class RaftDesktopSqliteDriver(
    private val url: String,
    private val properties: Properties = Properties(),
    private val maxIdle: Int = 4,
) : JdbcDriver() {
    private val idle = ArrayDeque<Connection>()
    private val transactions = ThreadLocal<Transaction?>()
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    @Volatile private var closed = false

    private val opened = AtomicInteger(0)

    /** Connections opened so far (tests: reuse instead of one open per statement). */
    val openedConnections: Int get() = opened.get()

    val idleConnections: Int get() = synchronized(idle) { idle.size }

    override fun getConnection(): Connection {
        check(!closed) { "driver closed" }
        synchronized(idle) { idle.removeLastOrNull() }?.let { return it }
        opened.incrementAndGet()
        return DriverManager.getConnection(url, properties)
    }

    override fun closeConnection(connection: Connection) {
        // Still the running transaction's connection: kept until the transaction ends.
        if (transactions.get()?.connection === connection) return
        val reusable = !closed && runCatching { !connection.isClosed && connection.autoCommit }.getOrDefault(false)
        val kept = reusable && synchronized(idle) { if (idle.size < maxIdle) idle.addLast(connection) else null } != null
        if (!kept) runCatching { connection.close() }
    }

    override var transaction: Transaction?
        get() = transactions.get()
        set(value) {
            val previous = transactions.get()
            transactions.set(value)
            if (value == null && previous != null) closeConnection(previous.connection)
        }

    // As JdbcSqliteDriver (its JdbcSqliteDriverConnectionManager): SQLite's own statements, with
    // the connection left in auto-commit mode.
    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN TRANSACTION").use { it.execute() }
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use { it.execute() }
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use { it.execute() }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it) { linkedSetOf() }.add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val notify = linkedSetOf<Query.Listener>()
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.let(notify::addAll) } }
        notify.forEach(Query.Listener::queryResultsChanged)
    }

    override fun close() {
        closed = true
        val connections = synchronized(idle) { idle.toList().also { idle.clear() } }
        connections.forEach { runCatching { it.close() } }
    }

    companion object {
        /** `JdbcSqliteDriver(url, properties, schema)`: creates or migrates [schema] (user_version) first. */
        fun open(
            url: String,
            properties: Properties,
            schema: SqlSchema<QueryResult.Value<Unit>>,
            vararg callbacks: AfterVersion,
        ): RaftDesktopSqliteDriver {
            val driver = RaftDesktopSqliteDriver(url, properties)
            object : TransacterImpl(driver) {}.transaction {
                val version =
                    driver.executeQuery(
                        null,
                        "PRAGMA user_version",
                        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                        0,
                    ).value ?: 0L
                if (version == 0L) {
                    schema.create(driver).value
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                } else if (version < schema.version) {
                    schema.migrate(driver, version, schema.version, *callbacks).value
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                }
            }
            return driver
        }
    }
}
