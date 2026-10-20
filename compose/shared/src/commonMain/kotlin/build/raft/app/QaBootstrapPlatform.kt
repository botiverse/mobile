package build.raft.app

import build.raft.app.data.SharedAuthDataSource

internal expect suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource)
