package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyValueStoreTest {
    @Test
    fun inMemoryStoreReadsAndOverwritesStrings() {
        val store = InMemorySlockKeyValueStore()

        assertNull(store.getString("locale"))

        store.putString("locale", "zh-Hans")
        assertEquals("zh-Hans", store.getString("locale"))

        store.putString("locale", "en")
        assertEquals("en", store.getString("locale"))
    }

    @Test
    fun nullStringRemovesValue() {
        val store = InMemorySlockKeyValueStore(mapOf("token" to "abc"))

        store.putString("token", null)

        assertNull(store.getString("token"))
    }

    @Test
    fun booleanHelpersRoundTripThroughStringStorage() {
        val store = InMemorySlockKeyValueStore()

        assertFalse(store.getBoolean("notifications"))

        store.putBoolean("notifications", true)
        assertTrue(store.getBoolean("notifications"))

        store.putBoolean("notifications", false)
        assertFalse(store.getBoolean("notifications", true))
    }

    @Test
    fun authSessionKeyValueProviderUsesInstalledAppScopeFactory() {
        val appScopeStore = InMemorySlockKeyValueStore()
        SlockSharedKeyValueStoreProvider.installAppScopeFactory(SLOCK_AUTH_SESSION_MMKV_NAMESPACE) { appScopeStore }
        try {
            val binding = SlockSharedAuthSessionKeyValueStoreProvider.create()

            assertEquals("app", binding.scope)
            assertSame(appScopeStore, binding.store)
        } finally {
            SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        }
    }

    @Test
    fun authSessionKeyValueProviderFailsFastWithoutAppScopeStore() {
        SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

        if (slockCreatePlatformAppScopeKeyValueStore(SLOCK_AUTH_SESSION_MMKV_NAMESPACE) == null) {
            assertFailsWith<IllegalStateException> {
                SlockSharedAuthSessionKeyValueStoreProvider.create()
            }
        }
    }

    @Test
    fun globalKeyValueProviderUsesInstalledAppScopeFactoryForNamespace() {
        val sharedPreferenceStore = InMemorySlockKeyValueStore()
        val apiEnvironmentStore = InMemorySlockKeyValueStore()
        SlockSharedKeyValueStoreProvider.installAppScopeFactory(SlockLocalPreferenceStore.KuiklyNamespace) {
            sharedPreferenceStore
        }
        SlockSharedKeyValueStoreProvider.installAppScopeFactory(SharedApiEnvironmentStore.Namespace) {
            apiEnvironmentStore
        }
        try {
            val sharedBinding =
                SlockSharedKeyValueStoreProvider.create(
                    namespace = SlockLocalPreferenceStore.KuiklyNamespace,
                    storageName = "shared preference"
                )
            val apiBinding =
                SlockSharedKeyValueStoreProvider.create(
                    namespace = SharedApiEnvironmentStore.Namespace,
                    storageName = "API environment"
                )

            assertEquals("app", sharedBinding.scope)
            assertSame(sharedPreferenceStore, sharedBinding.store)
            assertEquals("app", apiBinding.scope)
            assertSame(apiEnvironmentStore, apiBinding.store)
        } finally {
            SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        }
    }

    @Test
    fun globalKeyValueProviderDoesNotFallbackToPagerScopeStore() {
        SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

        listOf(
            SLOCK_AUTH_SESSION_MMKV_NAMESPACE to "auth session",
            SlockLocalPreferenceStore.KuiklyNamespace to "shared preference",
            SharedApiEnvironmentStore.Namespace to "API environment",
            SLOCK_ACTIVITY_UNREAD_MMKV_NAMESPACE to "Activity unread summary"
        ).forEach { (namespace, storageName) ->
            if (slockCreatePlatformAppScopeKeyValueStore(namespace) == null) {
                assertFailsWith<IllegalStateException> {
                    SlockSharedKeyValueStoreProvider.create(
                        namespace = namespace,
                        storageName = storageName
                    )
                }
            }
        }
    }
}
