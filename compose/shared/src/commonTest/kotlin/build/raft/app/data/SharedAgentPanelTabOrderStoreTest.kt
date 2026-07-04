package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
