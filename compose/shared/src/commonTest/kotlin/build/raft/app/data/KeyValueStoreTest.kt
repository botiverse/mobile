package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyValueStoreTest {
    @Test
    fun booleanHelpersRoundTripThroughStringStorage() {
        val store = InMemoryRaftKeyValueStore()

        assertFalse(store.getBoolean("notifications"))

        store.putBoolean("notifications", true)
        assertTrue(store.getBoolean("notifications"))

        store.putBoolean("notifications", false)
        assertFalse(store.getBoolean("notifications", true))
    }

    @Test
    fun authSessionKeyValueProviderUsesInstalledAppScopeFactory() {
        val appScopeStore = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RAFT_AUTH_SESSION_MMKV_NAMESPACE) { appScopeStore }
        try {
            val binding = RaftSharedAuthSessionKeyValueStoreProvider.create()

            assertEquals("app", binding.scope)
            assertSame(appScopeStore, binding.store)
        } finally {
            RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        }
    }

    @Test
    fun authSessionKeyValueProviderFailsFastWithoutAppScopeStore() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

        if (raftCreatePlatformAppScopeKeyValueStore(RAFT_AUTH_SESSION_MMKV_NAMESPACE) == null) {
            assertFailsWith<IllegalStateException> {
                RaftSharedAuthSessionKeyValueStoreProvider.create()
            }
        }
    }

    @Test
    fun globalKeyValueProviderDoesNotFallbackToPagerScopeStore() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

        listOf(
            RAFT_AUTH_SESSION_MMKV_NAMESPACE to "auth session",
            RaftLocalPreferenceStore.KuiklyNamespace to "shared preference",
            SharedApiEnvironmentStore.Namespace to "API environment",
            RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE to "Activity unread summary"
        ).forEach { (namespace, storageName) ->
            if (raftCreatePlatformAppScopeKeyValueStore(namespace) == null) {
                assertFailsWith<IllegalStateException> {
                    RaftSharedKeyValueStoreProvider.create(
                        namespace = namespace,
                        storageName = storageName
                    )
                }
            }
        }
    }
}
