package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi actual fun raftRequiresAppScopeKeyValueStore(namespace: String): Boolean = false

@RaftCoreInternalApi actual fun raftCreatePlatformAppScopeKeyValueStore(namespace: String): RaftKeyValueStore? = null
