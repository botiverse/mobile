package build.raft.app

import build.raft.app.data.SharedAuthDataSource

/** Emits QA_BOOTSTRAP_READY once the QA-bootstrapped shell is on its route; true when READY was logged. */
internal expect suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource): Boolean
