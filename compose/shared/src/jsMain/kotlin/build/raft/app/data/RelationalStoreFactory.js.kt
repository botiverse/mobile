package build.raft.app.data


internal actual fun createRaftSqlDatabase(): RaftSqlDatabase =
    error("Slock SQLDelight relational store is not supported on JS yet")
