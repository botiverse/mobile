package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import app.cash.sqldelight.driver.native.NativeSqliteDriver

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    SlockSqlDatabase(
        NativeSqliteDriver(
            schema = SlockSqlDatabase.Schema,
            name = SlockSqlDatabase.DatabaseName
        )
    )
