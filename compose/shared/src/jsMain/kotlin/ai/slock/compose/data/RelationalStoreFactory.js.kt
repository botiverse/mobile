package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    error("Slock SQLDelight relational store is not supported on JS yet")
