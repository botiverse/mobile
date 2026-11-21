package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedApiEnvironmentStoreCacheTest {
    @BeforeTest
    fun resetBefore() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        RaftAppScopeApiEnvironment.resetForTests()
    }

    @AfterTest
    fun resetAfter() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        RaftAppScopeApiEnvironment.resetForTests()
    }

    @Test
    fun currentReturnsCachedEnvironmentWithoutReReadingBackingStore() {
        val backing =
            CountingKeyValueStore(
                InMemoryRaftKeyValueStore(
                    mapOf(
                        "environment" to SharedApiEnvironmentStore.CustomKey,
                        "custom_label" to "Local Dev",
                        "custom_api_base_url" to "http://10.0.2.2:3000/api/",
                        "custom_socket_base_url" to "http://10.0.2.2:3000",
                        "custom_web_url" to "http://10.0.2.2:5173",
                    )
                )
            )
        val store = SharedApiEnvironmentStore(backing)
        val readsAfterInit = backing.readCount
        assertEquals(5, readsAfterInit)

        repeat(50) {
            val current = store.current()
            assertEquals(SharedApiEnvironmentStore.CustomKey, current.key)
            assertEquals("http://10.0.2.2:3000/api/", current.apiBaseUrl)
            assertEquals("http://10.0.2.2:3000/api/avatars/a.webp", store.rewriteApiUrl("${SharedApiEnvironmentStore.Production.apiBaseUrl}avatars/a.webp"))
            assertEquals("http://10.0.2.2:3000", store.resolveSocketUrl(SharedApiEnvironmentStore.Production.socketBaseUrl))
            assertEquals(SharedApiEnvironmentStore.CustomKey, store.developerSnapshot().environmentKey)
        }

        assertEquals(
            readsAfterInit,
            backing.readCount,
            "hot-path current()/rewriteApiUrl()/resolveSocketUrl() must not hit backing storage",
        )
    }

    @Test
    fun selectAndSaveUpdateCachedStateWithoutExtraReads() {
        val backing = CountingKeyValueStore(InMemoryRaftKeyValueStore())
        val store = SharedApiEnvironmentStore(backing)
        assertEquals(1, backing.readCount)
        assertEquals(SharedApiEnvironmentStore.Production, store.current())

        assertEquals(SharedApiEnvironmentStore.Staging, store.select(SharedApiEnvironmentStore.Staging.key))
        assertEquals(SharedApiEnvironmentStore.Staging, store.current())
        assertEquals(SharedApiEnvironmentStore.Staging, store.state.value)

        val custom =
            store.save(
                SharedSettingsApiEnvironmentRequest(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "Preview",
                    apiBaseUrl = "https://preview.example/api/",
                    socketBaseUrl = "https://preview.example",
                    webUrl = "https://web.preview.example",
                )
            )
        assertEquals(custom, store.current())
        assertEquals(custom, store.state.value)

        assertNull(
            store.save(
                SharedSettingsApiEnvironmentRequest(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "Invalid",
                    apiBaseUrl = "not-a-url",
                    socketBaseUrl = "also-not-a-url",
                    webUrl = "",
                )
            )
        )
        assertEquals(custom, store.current())
        assertEquals(1, backing.readCount)
    }

    @Test
    fun reloadRefreshesCachedStateFromBackingStorage() {
        val backing = InMemoryRaftKeyValueStore()
        val store = SharedApiEnvironmentStore(backing)
        assertEquals(SharedApiEnvironmentStore.Production, store.current())

        // Simulate an external writer (such as AndroidApiServerEnvironmentStore) mutating storage.
        backing.putString("environment", SharedApiEnvironmentStore.Staging.key)
        assertEquals(SharedApiEnvironmentStore.Production, store.current())

        assertEquals(SharedApiEnvironmentStore.Staging, store.reload())
        assertEquals(SharedApiEnvironmentStore.Staging, store.current())
        assertEquals(SharedApiEnvironmentStore.Staging, store.state.value)
    }

    @Test
    fun reloadRaftAppScopeApiEnvironmentIfLoadedRefreshesOnlyLoadedSingleton() {
        val backing = InMemoryRaftKeyValueStore()
        var creations = 0
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(SharedApiEnvironmentStore.Namespace) {
            creations += 1
            backing
        }

        // Before the process singleton is initialized, reload is a no-op and does not open storage.
        reloadRaftAppScopeApiEnvironmentIfLoaded()
        assertEquals(0, creations)

        val singleton = RaftAppScopeApiEnvironment.store()
        assertEquals(1, creations)
        assertEquals(SharedApiEnvironmentStore.Production, singleton.current())

        backing.putString("environment", SharedApiEnvironmentStore.Staging.key)
        reloadRaftAppScopeApiEnvironmentIfLoaded()

        assertEquals(SharedApiEnvironmentStore.Staging, singleton.current())
        assertEquals(SharedApiEnvironmentStore.Staging, singleton.state.value)
    }

    private class CountingKeyValueStore(
        private val delegate: RaftKeyValueStore,
    ) : RaftKeyValueStore {
        var readCount: Int = 0
            private set

        override fun getString(key: String): String? {
            readCount += 1
            return delegate.getString(key)
        }

        override fun putString(key: String, value: String?) {
            delegate.putString(key, value)
        }
    }
}
