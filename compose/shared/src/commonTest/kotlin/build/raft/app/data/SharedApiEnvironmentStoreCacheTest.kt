package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
