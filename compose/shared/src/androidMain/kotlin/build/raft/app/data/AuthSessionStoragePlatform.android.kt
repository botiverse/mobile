package build.raft.app.data

internal actual fun slockRequiresAppScopeKeyValueStore(namespace: String): Boolean =
    namespace == SLOCK_AUTH_SESSION_MMKV_NAMESPACE ||
        namespace == SlockLocalPreferenceStore.KuiklyNamespace ||
        namespace == SharedApiEnvironmentStore.Namespace
