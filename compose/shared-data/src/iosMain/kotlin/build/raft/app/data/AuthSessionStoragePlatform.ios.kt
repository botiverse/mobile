package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi actual fun raftRequiresAppScopeKeyValueStore(namespace: String): Boolean =
    namespace == RAFT_AUTH_SESSION_MMKV_NAMESPACE ||
        namespace == RaftLocalPreferenceStore.KuiklyNamespace ||
        namespace == SharedApiEnvironmentStore.Namespace ||
        namespace == RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE ||
        namespace == RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE ||
        namespace == RAFT_IOS_MOBILE_OAUTH_MMKV_NAMESPACE ||
        namespace == RAFT_APP_LOGIN_MMKV_NAMESPACE

@RaftCoreInternalApi actual fun raftCreatePlatformAppScopeKeyValueStore(namespace: String): RaftKeyValueStore? = null
