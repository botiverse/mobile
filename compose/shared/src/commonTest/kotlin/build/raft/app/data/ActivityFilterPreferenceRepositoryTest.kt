package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun anUnknownStoredValueReadsAsAll() {
        val keyValues =
            InMemoryRaftKeyValueStore(
                mapOf(
                    "raft.activity.filter.server-1" to "bookmarked",
                    "raft.activity.filter.server-2" to "Unread",
                    "raft.activity.filter.server-3" to "",
                )
            )
        val preferences = ActivityFilterPreferenceRepository(keyValues)

        assertEquals(InboxFilter.All, preferences.read("server-1"), "not one of the menu's choices")
        assertEquals(InboxFilter.All, preferences.read("server-2"), "values are the web's lower-case strings")
        assertEquals(InboxFilter.All, preferences.read("server-3"))
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
        val keyValues = InMemoryRaftKeyValueStore()
        val preferences = ActivityFilterPreferenceRepository(keyValues)
        InboxFilter.entries.forEach { filter ->
            preferences.write("server-1", filter)
            assertEquals(filter, preferences.read("server-1"))
        }
        preferences.write("server-1", InboxFilter.Tasks)
        assertEquals("tasks", keyValues.getString("raft.activity.filter.server-1"))
        assertEquals(InboxFilter.Tasks, parseActivityFilterChoice("tasks"))
        assertTrue(
            InboxFilter.entries.map { it.preferenceValue() }.toSet() ==
                setOf("all", "unread", "mentions", "pinned", "saved", "tasks", "artifacts")
        )
    }

    @Test
    fun toAnswerChoicePersistsPerServerAndFallsBackToAllWhenFlagOff() {
        val keyValues = InMemoryRaftKeyValueStore()
        val preferences = ActivityFilterPreferenceRepository(keyValues)

        preferences.writeChoice("server-1", ActivityFilterChoice.ToAnswer)

        assertEquals(TO_ANSWER_FILTER_PREFERENCE_VALUE, keyValues.getString("raft.activity.filter.server-1"))
        assertTrue(preferences.readIsToAnswerChosen("server-1"))
        assertFalse(preferences.readIsToAnswerChosen("server-2"))
        // Underlying inbox store always keeps All while To answer is persisted.
        assertEquals(InboxFilter.All, preferences.read("server-1"))
        // Flag ON restores To answer; flag OFF falls back to All.
        assertEquals(ActivityFilterChoice.ToAnswer, preferences.readChoice("server-1", toAnswerEnabled = true))
        assertEquals(ActivityFilterChoice.Inbox(InboxFilter.All), preferences.readChoice("server-1", toAnswerEnabled = false))
        assertEquals(ActivityFilterChoice.Inbox(InboxFilter.All), preferences.readChoice("server-2", toAnswerEnabled = true))

        // Clearing back to All removes the stored preference key.
        preferences.writeChoice("server-1", ActivityFilterChoice.Inbox(InboxFilter.All))
        assertNull(keyValues.getString("raft.activity.filter.server-1"))
        assertFalse(preferences.readIsToAnswerChosen("server-1"))
    }

    @Test
    fun snakeCaseToAnswerAliasAndShownActivityFilterChoiceWorkAcrossFlagStates() {
        val keyValues = InMemoryRaftKeyValueStore(mapOf("raft.activity.filter.server-1" to "to_answer"))
        val preferences = ActivityFilterPreferenceRepository(keyValues)

        assertTrue(preferences.readIsToAnswerChosen("server-1"))
        assertEquals(ActivityFilterChoice.ToAnswer, preferences.readChoice("server-1", toAnswerEnabled = true))
        assertEquals(ActivityFilterChoice.Inbox(InboxFilter.All), preferences.readChoice("server-1", toAnswerEnabled = false))

        assertEquals(
            ActivityFilterChoice.Inbox(InboxFilter.All),
            shownActivityFilterChoice(InboxFilter.All, toAnswerChosen = true, toAnswerEnabled = false),
        )
        assertEquals(
            ActivityFilterChoice.ToAnswer,
            shownActivityFilterChoice(InboxFilter.All, toAnswerChosen = true, toAnswerEnabled = true),
        )
        assertEquals(
            ActivityFilterChoice.Inbox(InboxFilter.Mentions),
            shownActivityFilterChoice(InboxFilter.Mentions, toAnswerChosen = false, toAnswerEnabled = true),
        )
    }
}
