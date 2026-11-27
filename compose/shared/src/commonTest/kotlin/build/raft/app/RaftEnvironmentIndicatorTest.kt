package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftAppScopeApiEnvironment
import build.raft.app.data.RaftAppScopeApiEnvironmentHolder
import build.raft.app.data.RaftSharedKeyValueStoreBinding
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.thread.key
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class RaftEnvironmentIndicatorTest {
    @BeforeTest
    fun resetAppScopeEnvironment() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        RaftAppScopeApiEnvironment.resetForTests()
    }

    @AfterTest
    fun clearAppScopeEnvironment() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        RaftAppScopeApiEnvironment.resetForTests()
    }

    @Test
    fun productionEndpointVariantsDoNotRenderAnIndicator() {
        val production = SharedApiEnvironmentStore.Production
        val variants =
            listOf(
                production,
                production.copy(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "Production alias",
                    apiBaseUrl = production.apiBaseUrl.trimEnd('/'),
                ),
            )

        variants.forEach { environment ->
            assertNull(raftEnvironmentIndicatorLabel(environment))
        }
    }

    @Test
    fun indicatorReadsTheAppScopeEnvironmentSettingWithoutCreatingTheSharedStore() {
        val keyValueStore = InMemoryRaftKeyValueStore(mapOf("environment" to SharedApiEnvironmentStore.Staging.key))
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(SharedApiEnvironmentStore.Namespace) { keyValueStore }

        val environmentStore = assertNotNull(raftEnvironmentIndicatorEnvironmentStoreOrNull())

        assertEquals("Staging", raftEnvironmentIndicatorLabel(environmentStore.state.value))
        assertNull(
            RaftSharedStoreRegistry.currentOrNull(),
            "the indicator must not build the shared store provider (it opens the database)",
        )
    }

    @Test
    fun indicatorAndProviderShareOneEnvironmentStoreSoSwitchesReachTheIndicator() {
        var bindingCreations = 0
        val holder =
            RaftAppScopeApiEnvironmentHolder {
                bindingCreations += 1
                RaftSharedKeyValueStoreBinding(store = InMemoryRaftKeyValueStore(), scope = "app")
            }
        val indicatorStore = assertNotNull(raftEnvironmentIndicatorEnvironmentStoreOrNull(holder::store))

        // The provider, created later, takes the same holder's binding and store.
        holder.binding()
        holder.store().select(SharedApiEnvironmentStore.Staging.key)

        assertSame(holder.store(), indicatorStore)
        assertEquals(SharedApiEnvironmentStore.Staging, indicatorStore.state.value)
        assertEquals(1, bindingCreations)
    }

    @Test
    fun unavailableEnvironmentSettingHidesTheIndicatorInsteadOfFailingThePage() {
        assertNull(raftEnvironmentIndicatorEnvironmentStoreOrNull { error("app-scope store missing") })
    }
}
