package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedAgentPanelTabOrderStoreTest {
    @Test
    fun bindServerCarriesInitialOrderAndResetsOnServerChange() {
        val store = SharedAgentPanelTabOrderStore()

        store.bindServer("server-1", listOf("activity", "profile"))

        assertEquals("server-1", store.state.value.serverId)
        assertEquals(listOf("activity", "profile"), store.state.value.order)
        assertTrue(store.state.value.loaded)

        store.bindServer("server-2")

        assertEquals("server-2", store.state.value.serverId)
        assertEquals(emptyList(), store.state.value.order)
        assertFalse(store.state.value.loaded)
    }

    @Test
    fun loadedAndOptimisticUpdatesStayScopedToServer() {
        val store = SharedAgentPanelTabOrderStore()

        store.bindServer("server-1")
        store.applyLoaded("server-1", listOf("profile", "activity"))
        store.applyOptimistic("server-1", listOf("activity", "profile"))

        assertEquals("server-1", store.state.value.serverId)
        assertEquals(listOf("activity", "profile"), store.state.value.order)
        assertTrue(store.state.value.loaded)

        store.applyOptimistic("server-2", listOf("chat"))

        assertEquals("server-2", store.state.value.serverId)
        assertEquals(listOf("chat"), store.state.value.order)
        assertTrue(store.state.value.loaded)
    }

    @Test
    fun tracedWritesReportSourceAndBeforeAfterOrder() {
        val store = SharedAgentPanelTabOrderStore()
        val logs = mutableListOf<String>()

        store.bindServer(
            serverId = "server-1",
            source = "test_bind",
            logger = logs::add
        )
        store.applyLoaded(
            serverId = "server-1",
            order = listOf("activity", "profile"),
            source = "test_load",
            logger = logs::add
        )

        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("operation=bindServer"))
        assertTrue(logs[0].contains("source=test_bind"))
        assertTrue(logs[0].contains("changed=true"))
        assertTrue(logs[0].contains("beforeOrder=[]"))
        assertTrue(logs[0].contains("afterOrder=[]"))
        assertTrue(logs[1].contains("operation=applyLoaded"))
        assertTrue(logs[1].contains("source=test_load"))
        assertTrue(logs[1].contains("beforeOrder=[]"))
        assertTrue(logs[1].contains("inputOrder=[activity,profile]"))
        assertTrue(logs[1].contains("afterOrder=[activity,profile]"))
    }

    @Test
    fun coldStartKnowsTheLastOrderBeforeAnyBindOrLoad() {
        // Task #22: the order saved by a previous process run is readable during the profile's
        // first composition, before any effect binds the store or the server answers.
        val preferences = InMemoryRaftKeyValueStore()
        val previousRun = SharedAgentPanelTabOrderStore(KeyValueAgentPanelTabOrderPreferenceRepository(preferences))
        previousRun.bindServer("server-1")
        previousRun.applyLoaded("server-1", listOf("activity", "profile", "chat"))

        val store = SharedAgentPanelTabOrderStore(KeyValueAgentPanelTabOrderPreferenceRepository(preferences))

        assertEquals(listOf("activity", "profile", "chat"), store.knownOrderFor(" server-1 "))
        assertNull(store.knownOrderFor("server-2"))

        store.bindServer("server-1")

        assertEquals(listOf("activity", "profile", "chat"), store.state.value.order)
        assertTrue(store.state.value.cached)
        // Still refreshed from the server: the cache is the last known value, not the answer.
        assertFalse(store.state.value.loaded)
        assertTrue(store.state.value.orderKnown)
    }

    @Test
    fun aKnownDefaultOrderIsDistinctFromNeverSeen() {
        val preferences = InMemoryRaftKeyValueStore()
        SharedAgentPanelTabOrderStore(KeyValueAgentPanelTabOrderPreferenceRepository(preferences))
            .applyLoaded("server-1", emptyList())

        val store = SharedAgentPanelTabOrderStore(KeyValueAgentPanelTabOrderPreferenceRepository(preferences))

        assertEquals(emptyList(), store.knownOrderFor("server-1"))
    }

    @Test
    fun serverOrderReplacesCacheAndFailureKeepsTheLastKnownOrder() {
        val preferences = InMemoryRaftKeyValueStore()
        val cache = KeyValueAgentPanelTabOrderPreferenceRepository(preferences)
        cache.writeOrder("server-1", listOf("activity", "profile"))
        val store = SharedAgentPanelTabOrderStore(cache)
        store.bindServer("server-1")

        store.applyLoadFailed("server-1")

        assertEquals(listOf("activity", "profile"), store.state.value.order)
        assertTrue(store.state.value.loaded)
        assertEquals(listOf("activity", "profile"), cache.readOrder("server-1"))

        store.applyOptimistic("server-1", listOf("chat", "activity"))

        assertEquals(listOf("chat", "activity"), cache.readOrder("server-1"))
        assertEquals(listOf("chat", "activity"), store.knownOrderFor("server-1"))
    }

    @Test
    fun bindingWithoutAServerKeepsTheCurrentOrder() {
        // A page that has not resolved its server yet used to reset the shared order, so the next
        // profile open drew the default order again.
        val store = SharedAgentPanelTabOrderStore()
        store.applyLoaded("server-1", listOf("activity", "profile"))

        store.bindServer(null)
        store.bindServer("  ")

        assertEquals("server-1", store.state.value.serverId)
        assertEquals(listOf("activity", "profile"), store.state.value.order)
        assertTrue(store.state.value.loaded)
    }
}
