package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule

internal fun createSlockRelationalStore(bridge: BridgeModule): SlockRelationalStore =
    SqlDelightRelationalStore(createSlockSqlDatabase(bridge))

internal expect fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase
