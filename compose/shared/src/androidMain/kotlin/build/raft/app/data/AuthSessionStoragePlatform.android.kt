package build.raft.app.data

internal actual fun raftRequiresAppScopeKeyValueStore(namespace: String): Boolean =
    namespace == RAFT_AUTH_SESSION_MMKV_NAMESPACE ||
        namespace == RaftLocalPreferenceStore.KuiklyNamespace ||
        namespace == SharedApiEnvironmentStore.Namespace ||
        namespace == RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE ||
        namespace == RAFT_APP_LOGIN_MMKV_NAMESPACE ||
        namespace == RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE

internal actual fun raftCreatePlatformAppScopeKeyValueStore(namespace: String): RaftKeyValueStore? = null
