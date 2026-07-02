package build.raft.app.data

internal actual fun slockRequiresAppScopeKeyValueStore(namespace: String): Boolean =
    namespace == SLOCK_AUTH_SESSION_MMKV_NAMESPACE ||
        namespace == SlockLocalPreferenceStore.KuiklyNamespace ||
        namespace == SharedApiEnvironmentStore.Namespace

internal actual fun slockCreatePlatformAppScopeKeyValueStore(namespace: String): SlockKeyValueStore? =
    if (slockRequiresAppScopeKeyValueStore(namespace)) {
        SlockOhosMMKVKeyValueStore(namespace)
    } else {
        null
    }
