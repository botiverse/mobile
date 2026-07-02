package build.raft.app.data

internal actual fun slockRequiresAppScopeKeyValueStore(namespace: String): Boolean = false

internal actual fun slockCreatePlatformAppScopeKeyValueStore(namespace: String): SlockKeyValueStore? = null
