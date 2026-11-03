package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftDisplayPreferenceRuntimeTest {
    private class MemoryStore : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    @AfterTest
    fun reset() {
        RaftDisplayPreferenceRuntime.resetForTest(null)
    }

    @Test
    fun defaultsFollowTheWebForModelAndTheAppForEmptySections() {
        RaftDisplayPreferenceRuntime.resetForTest(MemoryStore())
        assertTrue(RaftDisplayPreferenceRuntime.showAgentModelName)
        assertEquals(RaftSidebarHideEmptySectionsDefault, RaftDisplayPreferenceRuntime.hideEmptySidebarSections)
    }

    @Test
    fun choicesAreSavedOnTheDeviceUnderTheWebKeys() {
        val store = MemoryStore()
        RaftDisplayPreferenceRuntime.resetForTest(store)
        RaftDisplayPreferenceRuntime.setShowAgentModelName(false)
        RaftDisplayPreferenceRuntime.setHideEmptySidebarSections(false)
        assertEquals("false", store.values["slock_show_agent_model_name"])
        assertEquals("false", store.values["slock:sidebarHideEmptySections"])

        // A new process reads them back.
        RaftDisplayPreferenceRuntime.resetForTest(store)
        assertFalse(RaftDisplayPreferenceRuntime.showAgentModelName)
        assertFalse(RaftDisplayPreferenceRuntime.hideEmptySidebarSections)
    }

    @Test
    fun theHomeMenuChoiceIsTheSameValue() {
        val store = MemoryStore()
        KeyValueRaftSidebarHideEmptySectionsStore(store).write(false)
        RaftDisplayPreferenceRuntime.resetForTest(store)
        assertFalse(RaftDisplayPreferenceRuntime.hideEmptySidebarSections)
    }
}
