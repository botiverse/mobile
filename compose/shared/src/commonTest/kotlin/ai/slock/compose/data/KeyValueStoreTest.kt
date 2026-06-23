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
}
