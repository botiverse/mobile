package build.raft.app.data

import build.raft.app.base.BridgeModule

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    error("Slock SQLDelight relational store is not supported on JS yet")
