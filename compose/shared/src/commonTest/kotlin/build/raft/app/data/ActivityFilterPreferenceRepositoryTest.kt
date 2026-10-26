package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** web `store/activityFilterPreference.ts` (slock #11383): the Filter menu's last choice, per server. */
class ActivityFilterPreferenceRepositoryTest {
    @Test
    fun eachServerKeepsItsOwnChoice() {
        val keyValues = InMemoryRaftKeyValueStore()
        val preferences = ActivityFilterPreferenceRepository(keyValues)

        preferences.write("server-1", InboxFilter.Unread)
        preferences.write("server-2", InboxFilter.Pinned)

        assertEquals(InboxFilter.Unread, preferences.read("server-1"))
        assertEquals(InboxFilter.Pinned, preferences.read("server-2"))
        assertEquals(InboxFilter.All, preferences.read("server-3"), "a server never filtered starts on All")
        // The web's keys and values.
        assertEquals("unread", keyValues.getString("raft.activity.filter.server-1"))
        assertEquals("pinned", keyValues.getString("raft.activity.filter.server-2"))
    }

    @Test
    fun aNewRepositoryOverTheSameStoreReadsTheChoiceBack() {
        val keyValues = InMemoryRaftKeyValueStore()
        ActivityFilterPreferenceRepository(keyValues).write("server-1", InboxFilter.Mentions)

        assertEquals(InboxFilter.Mentions, ActivityFilterPreferenceRepository(keyValues).read("server-1"))
    }

    @Test
    fun choosingAllRemovesTheKey() {
        val keyValues = InMemoryRaftKeyValueStore()
        val preferences = ActivityFilterPreferenceRepository(keyValues)
        preferences.write("server-1", InboxFilter.Mentions)

        preferences.write("server-1", InboxFilter.All)

        assertNull(keyValues.getString("raft.activity.filter.server-1"))
        assertEquals(InboxFilter.All, preferences.read("server-1"))
    }

    @Test
    fun anUnknownStoredValueReadsAsAll() {
        val keyValues =
            InMemoryRaftKeyValueStore(
                mapOf(
                    "raft.activity.filter.server-1" to "saved",
                    "raft.activity.filter.server-2" to "Unread",
                    "raft.activity.filter.server-3" to "",
                )
            )
        val preferences = ActivityFilterPreferenceRepository(keyValues)

        assertEquals(InboxFilter.All, preferences.read("server-1"), "Saved is not one of the menu's choices")
        assertEquals(InboxFilter.All, preferences.read("server-2"), "values are the web's lower-case strings")
        assertEquals(InboxFilter.All, preferences.read("server-3"))
    }

    @Test
    fun aBlankServerKeepsNothing() {
        val keyValues = InMemoryRaftKeyValueStore()
        val preferences = ActivityFilterPreferenceRepository(keyValues)

        preferences.write(null, InboxFilter.Unread)
        preferences.write("  ", InboxFilter.Unread)

        assertEquals(InboxFilter.All, preferences.read(null))
        assertEquals(InboxFilter.All, preferences.read(""))
        assertNull(keyValues.getString("raft.activity.filter."))
    }

    @Test
    fun aFailingStoreFallsBackToAllAndSwallowsTheWrite() {
        val broken =
            object : SharedPreferenceStore {
                override fun getString(key: String): String? = error("storage unavailable")
                override fun putString(key: String, value: String?) = error("storage full")
            }
        val preferences = ActivityFilterPreferenceRepository(broken)

        preferences.write("server-1", InboxFilter.Unread)

        assertEquals(InboxFilter.All, preferences.read("server-1"))
    }

    @Test
    fun everyMenuChoiceRoundTrips() {
        val preferences = ActivityFilterPreferenceRepository(InMemoryRaftKeyValueStore())
        InboxFilter.entries.forEach { filter ->
            preferences.write("server-1", filter)
            assertEquals(filter, preferences.read("server-1"))
        }
        assertTrue(InboxFilter.entries.map { it.preferenceValue() }.toSet() == setOf("all", "unread", "mentions", "pinned"))
    }
}
