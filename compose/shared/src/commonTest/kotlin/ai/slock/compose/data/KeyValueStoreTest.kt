package ai.slock.compose.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
}
