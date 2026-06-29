package ai.slock.compose.data

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
}
