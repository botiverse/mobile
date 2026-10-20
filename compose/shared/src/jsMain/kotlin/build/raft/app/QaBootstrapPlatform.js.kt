package build.raft.app

import build.raft.app.data.SharedAuthDataSource

internal actual suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource) = Unit
