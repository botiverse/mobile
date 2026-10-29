package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftAppScopeApiEnvironment
import build.raft.app.data.RaftAppScopeApiEnvironmentHolder
import build.raft.app.data.RaftSharedKeyValueStoreBinding
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.compat.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun nonProductionEnvironmentsRenderTheirConfiguredNames() {
        val cases =
            listOf(
                SharedApiEnvironmentStore.Staging to "Staging",
                SharedSettingsApiEnvironmentOption(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "  Local Dev  ",
                    apiBaseUrl = "http://10.0.2.2:3000/api/",
                    socketBaseUrl = "http://10.0.2.2:3000",
                    webUrl = "http://10.0.2.2:3000",
                ) to "Local Dev",
            )

        cases.forEach { (environment, expectedLabel) ->
            assertEquals(expectedLabel, raftEnvironmentIndicatorLabel(environment))
        }
    }

    @Test
    fun environmentStoreStateTracksSelectionsForTheMountedGlobalHost() {
        val store = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())

        assertEquals(SharedApiEnvironmentStore.Production, store.state.value)

        store.select(SharedApiEnvironmentStore.Staging.key)

        assertEquals(SharedApiEnvironmentStore.Staging, store.state.value)

        val custom =
            store.save(
                SharedSettingsApiEnvironmentRequest(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "Local Dev",
                    apiBaseUrl = "http://10.0.2.2:3000/api/",
                    socketBaseUrl = "http://10.0.2.2:3000",
                    webUrl = "http://10.0.2.2:3000",
                ),
            )

        assertEquals(custom, store.state.value)
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

    @Test
    fun embeddedChildMarkerUsesACopyAndSuppressesDuplicateIndicator() {
        val original =
            JSONObject().apply {
                put("threadId", "thread-1")
            }

        val embedded = raftEmbeddedChildPageData(original)

        assertFalse(original.optBoolean(RAFT_EMBEDDED_CHILD_PAGE_PARAM, false))
        assertTrue(embedded.optBoolean(RAFT_EMBEDDED_CHILD_PAGE_PARAM, false))
        assertEquals("thread-1", embedded.optString("threadId"))
        assertTrue(raftEnvironmentIndicatorEnabledForPage(original))
        assertFalse(raftEnvironmentIndicatorEnabledForPage(embedded))
    }

    @Test
    fun draggableIndicatorDefaultsToSafeTopRightAndClampsToEveryVisibleEdge() {
        val bounds =
            RaftEnvironmentIndicatorDragBounds(
                hostWidthPx = 720f,
                hostHeightPx = 1280f,
                badgeWidthPx = 96f,
                badgeHeightPx = 40f,
                topSafeAreaPx = 48f,
                bottomSafeAreaPx = 72f,
                edgeMarginPx = 12f,
            )

        assertEquals(
            RaftEnvironmentIndicatorPosition(xPx = 612f, yPx = 60f),
            raftDefaultEnvironmentIndicatorPosition(bounds),
        )
        assertEquals(
            RaftEnvironmentIndicatorPosition(xPx = 12f, yPx = 60f),
            raftClampEnvironmentIndicatorPosition(
                RaftEnvironmentIndicatorPosition(xPx = -1_000f, yPx = -1_000f),
                bounds,
            ),
        )
        assertEquals(
            RaftEnvironmentIndicatorPosition(xPx = 612f, yPx = 1_156f),
            raftClampEnvironmentIndicatorPosition(
                RaftEnvironmentIndicatorPosition(xPx = 10_000f, yPx = 10_000f),
                bounds,
            ),
        )
    }

    @Test
    fun draggableIndicatorRemainsVisibleWhenInsetsExceedTheTinyHost() {
        val bounds =
            RaftEnvironmentIndicatorDragBounds(
                hostWidthPx = 80f,
                hostHeightPx = 60f,
                badgeWidthPx = 100f,
                badgeHeightPx = 40f,
                topSafeAreaPx = 30f,
                bottomSafeAreaPx = 30f,
                edgeMarginPx = 12f,
            )

        assertEquals(
            RaftEnvironmentIndicatorPosition(xPx = 0f, yPx = 10f),
            raftClampEnvironmentIndicatorPosition(
                RaftEnvironmentIndicatorPosition(xPx = 500f, yPx = 500f),
                bounds,
            ),
        )
    }
}
