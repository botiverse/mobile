package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The font picker is gone; a choice stored by an older build is cleared, never applied. */
class LegacyFontPreferenceTest {
    @Test
    fun aStoredChoiceIsCleared() {
        val store = InMemoryRaftKeyValueStore()
        store.putString("appearance:font_preference", "hanken_grotesk")
        store.putString("appearance:other", "kept")

        assertTrue(raftClearLegacyFontPreference(store))
        assertNull(store.getString("appearance:font_preference"))
        assertEquals("kept", store.getString("appearance:other"))
    }

    @Test
    fun nothingStoredIsANoOp() {
        assertFalse(raftClearLegacyFontPreference(InMemoryRaftKeyValueStore()))
    }

    @Test
    fun theAppScopeStoreIsClearedAtStartup() {
        val store = InMemoryRaftKeyValueStore()
        store.putString("appearance:font_preference", "geist_inter")
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
        try {
            assertTrue(raftClearLegacyFontPreference())
            assertNull(store.getString("appearance:font_preference"))
        } finally {
            RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
        }
    }
}
