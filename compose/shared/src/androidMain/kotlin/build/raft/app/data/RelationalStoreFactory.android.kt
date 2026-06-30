package build.raft.app.data

import android.content.Context
import build.raft.app.base.BridgeModule
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    SlockSqlDatabase(
        AndroidSqliteDriver(
            schema = SlockSqlDatabase.Schema,
            context = SlockAndroidSqlDatabaseContext.require(),
            name = SlockSqlDatabase.DatabaseName
        )
    )

object SlockAndroidSqlDatabaseContext {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    internal fun require(): Context =
        context ?: error("Slock Android SQLDelight context has not been initialized")
}
