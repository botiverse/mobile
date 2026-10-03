package build.raft.app.data

internal actual fun slockRequiresAppScopeKeyValueStore(namespace: String): Boolean =
    namespace == SLOCK_APP_LOGIN_MMKV_NAMESPACE ||
        namespace == SLOCK_AUTH_SESSION_MMKV_NAMESPACE ||
        namespace == SlockLocalPreferenceStore.KuiklyNamespace ||
        namespace == SharedApiEnvironmentStore.Namespace ||
        namespace == SLOCK_ACTIVITY_UNREAD_MMKV_NAMESPACE

internal actual fun slockCreatePlatformAppScopeKeyValueStore(namespace: String): SlockKeyValueStore? =
    if (slockRequiresAppScopeKeyValueStore(namespace)) {
        SlockOhosMMKVKeyValueStore(namespace)
    } else {
        null
    }
