package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule

internal expect fun createSlockRelationalStore(bridge: BridgeModule): SlockRelationalStore

internal expect fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase
