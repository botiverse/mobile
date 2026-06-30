package build.raft.app.data

import build.raft.app.base.BridgeModule

internal expect fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase
