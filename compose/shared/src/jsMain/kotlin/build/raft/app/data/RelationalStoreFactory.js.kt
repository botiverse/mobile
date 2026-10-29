package build.raft.app.data

import app.cash.sqldelight.db.SqlDriver

internal actual fun openPlatformRaftSqlDriver(): SqlDriver =
    error("Slock SQLDelight relational store is not supported on JS yet")
