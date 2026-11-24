package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi actual suspend fun raftSetIosRemoteNotificationsEnabled(enabled: Boolean) = Unit
