package build.raft.app.data

internal actual fun raftRequiresAppScopeKeyValueStore(namespace: String): Boolean = false

internal actual fun raftCreatePlatformAppScopeKeyValueStore(namespace: String): RaftKeyValueStore? = null
