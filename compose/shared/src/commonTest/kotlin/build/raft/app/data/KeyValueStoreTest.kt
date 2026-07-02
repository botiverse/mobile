package build.raft.app.data

import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.pager.Pager
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
    fun mmkvStoreReadsNamespacedValue() {
        val backend =
            FakeMMKVBackend(
                initialValues = mapOf(("slockLocal" to "slockLocal:locale") to "zh-Hans")
            )
        val store =
            KuiklyMMKVKeyValueStoreDelegate(
                backend = backend,
                namespace = "slockLocal"
            )

        assertEquals("zh-Hans", store.getString("locale"))
    }

    @Test
    fun mmkvStoreWritesAndDeletesNamespacedValue() {
        val backend =
            FakeMMKVBackend(
                initialValues = mapOf(("slockLocal" to "slockLocal:locale") to "zh-Hans")
            )
        val store =
            KuiklyMMKVKeyValueStoreDelegate(
                backend = backend,
                namespace = "slockLocal"
            )

        store.putString("locale", "en")
        assertEquals("en", backend.values["slockLocal" to "slockLocal:locale"])

        store.putString("locale", null)

        assertNull(store.getString("locale"))
        assertFalse(("slockLocal" to "slockLocal:locale") in backend.values)
    }

    @Test
    fun authSessionKeyValueProviderUsesInstalledAppScopeFactory() {
        val appScopeStore = InMemorySlockKeyValueStore()
        SlockSharedKeyValueStoreProvider.installAppScopeFactory(SLOCK_AUTH_SESSION_MMKV_NAMESPACE) { appScopeStore }
        try {
            val binding = SlockSharedAuthSessionKeyValueStoreProvider.create(FakePager())

            assertEquals("app", binding.scope)
            assertSame(appScopeStore, binding.store)
        } finally {
            SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        }
    }

    @Test
    fun authSessionKeyValueProviderFallsBackToPagerScopeStore() {
        SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

        if (slockRequiresAppScopeKeyValueStore(SLOCK_AUTH_SESSION_MMKV_NAMESPACE)) {
            assertFailsWith<IllegalStateException> {
                SlockSharedAuthSessionKeyValueStoreProvider.create(FakePager())
            }
        } else {
            val binding = SlockSharedAuthSessionKeyValueStoreProvider.create(FakePager())

            assertEquals("pager", binding.scope)
            assertTrue(binding.store is RebindableSlockKeyValueStore)
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
                    pager = FakePager(),
                    namespace = SlockLocalPreferenceStore.KuiklyNamespace,
                    storageName = "shared preference"
                )
            val apiBinding =
                SlockSharedKeyValueStoreProvider.create(
                    pager = FakePager(),
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
    fun globalKeyValueProviderRequiresAppScopeForAndroidGlobalNamespaces() {
        SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

        listOf(
            SLOCK_AUTH_SESSION_MMKV_NAMESPACE to "auth session",
            SlockLocalPreferenceStore.KuiklyNamespace to "shared preference",
            SharedApiEnvironmentStore.Namespace to "API environment"
        ).forEach { (namespace, storageName) ->
            if (slockRequiresAppScopeKeyValueStore(namespace)) {
                assertFailsWith<IllegalStateException> {
                    SlockSharedKeyValueStoreProvider.create(
                        pager = FakePager(),
                        namespace = namespace,
                        storageName = storageName
                    )
                }
            } else {
                val binding =
                    SlockSharedKeyValueStoreProvider.create(
                        pager = FakePager(),
                        namespace = namespace,
                        storageName = storageName
                    )

                assertEquals("pager", binding.scope)
                assertTrue(binding.store is RebindableSlockKeyValueStore)
            }
        }
    }

    private class FakeMMKVBackend(
        initialValues: Map<Pair<String, String>, String> = emptyMap()
    ) : SlockMMKVKeyValueBackend {
        val values = initialValues.toMutableMap()

        override fun containsKey(key: String, mmapID: String): Boolean =
            (mmapID to key) in values

        override fun getString(key: String, mmapID: String): String? =
            values[mmapID to key]

        override fun putString(key: String, value: String, mmapID: String) {
            values[mmapID to key] = value
        }

        override fun removeString(key: String, mmapID: String) {
            values.remove(mmapID to key)
        }
    }

    private class FakePager : Pager() {
        override fun body(): ViewBuilder = {}
    }

}
