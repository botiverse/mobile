package build.raft.app.data

import build.raft.app.RaftMoreAttention
import build.raft.app.appShellPhoneBottomTabs
import build.raft.app.raftMoreAttention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** raft-design #620 "Attention dots": the Computers dot marks an unseen change, not a state. */
class RaftComputerAttentionSeenTest {
    private class MapPreferenceStore : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    /** The shell's loop: observe the notices, then read the dot. */
    private class Session(var seen: Set<String> = emptySet()) {
        private var previous: Set<String>? = null
        var current: Set<String> = emptySet()
            private set

        fun notices(vararg ids: String): Boolean {
            current = raftComputerAttentionPairs(ids.toList())
            seen = raftComputerAttentionObserve(seen, previous, current)
            previous = current
            return raftComputerAttentionUnseen(current, seen)
        }

        fun openComputers() {
            seen = raftComputerAttentionMarkSeen(seen, current)
        }

        fun dot(): Boolean = raftComputerAttentionUnseen(current, seen)
    }

    @Test
    fun pairsComeFromMachineNoticesOnly() {
        assertEquals(
            setOf(
                raftComputerAttentionPair("m1", RaftComputerAttentionState.Offline),
                raftComputerAttentionPair("m2", RaftComputerAttentionState.Offline),
                raftComputerAttentionPair("m3", RaftComputerAttentionState.Outdated),
            ),
            raftComputerAttentionPairs(
                listOf("machine-offline:m1,m2", "machine-outdated:1.2.0:m3", APP_UPDATE_HOME_NOTIFICATION_ID, "announcement:1")
            ),
        )
        assertEquals(emptySet(), raftComputerAttentionPairs(emptyList()))
    }

    @Test
    fun enteringAStateLightsAndOpeningComputersSeesIt() {
        val s = Session()
        assertFalse(s.notices())
        assertTrue(s.notices("machine-offline:m1"))
        s.openComputers()
        assertFalse(s.dot())
    }

    @Test
    fun sameComputerSameStateDoesNotLightAgain() {
        val s = Session()
        s.notices("machine-offline:m1")
        s.openComputers()
        assertFalse(s.notices("machine-offline:m1"))
        assertFalse(s.notices("machine-offline:m1"))
    }

    @Test
    fun backOnlineThenOfflineLightsAgain() {
        val s = Session()
        s.notices("machine-offline:m1")
        s.openComputers()
        assertFalse(s.notices())
        assertEquals(emptySet(), s.seen, "leaving the state forgets it was seen")
        assertTrue(s.notices("machine-offline:m1"))
    }

    @Test
    fun aNewStateOrAnotherComputerLightsAgain() {
        val s = Session()
        s.notices("machine-offline:m1")
        s.openComputers()
        assertTrue(s.notices("machine-offline:m1", "machine-outdated:1.2.0:m1"))
        s.openComputers()
        assertTrue(s.notices("machine-offline:m1,m2", "machine-outdated:1.2.0:m1"))
    }

    @Test
    fun coldStartBeforeNoticesLoadKeepsWhatWasSeen() {
        val seen = setOf(raftComputerAttentionPair("m1", RaftComputerAttentionState.Offline))
        val s = Session(seen)
        assertFalse(s.notices(), "an empty pre-load list forgets nothing")
        assertFalse(s.notices("machine-offline:m1"))
    }

    @Test
    fun guestHasNoComputersDot() {
        // Guests cannot manage machines, so the shell never carries machine notices for them.
        val rows = raftMoreAttention(listOf(APP_UPDATE_HOME_NOTIFICATION_ID), emptySet())
        assertEquals(setOf(RaftMoreAttention.AppUpdate), rows)
    }

    @Test
    fun moreTabAndComputersRowReadTheSameSeenState() {
        val ids = listOf("machine-offline:m1")
        val seen = raftComputerAttentionMarkSeen(emptySet(), raftComputerAttentionPairs(ids))
        val tabDot = { rows: Set<RaftMoreAttention> ->
            appShellPhoneBottomTabs("home", "h", "a", "s", "t", "m", moreAttention = rows)
                .single { it.key == "more" }
                .attentionDot
        }
        val unseenRows = raftMoreAttention(ids)
        assertEquals(setOf(RaftMoreAttention.Computers), unseenRows)
        assertTrue(tabDot(unseenRows))
        val seenRows = raftMoreAttention(ids, seen)
        assertEquals(emptySet(), seenRows)
        assertFalse(tabDot(seenRows))
    }

    @Test
    fun storePersistsPerUserAndServer() {
        val prefs = MapPreferenceStore()
        val store = KeyValueRaftComputerAttentionSeenStore(prefs)
        val pairs = raftComputerAttentionPairs(listOf("machine-offline:m1", "machine-outdated:1.2.0:m2"))
        store.write("u1", "s1", pairs)
        assertEquals(pairs, store.read("u1", "s1"))
        assertEquals(emptySet(), store.read("u1", "s2"))
        assertEquals(emptySet(), store.read("u2", "s1"))
        store.write("u1", "s1", emptySet())
        assertEquals(emptySet(), store.read("u1", "s1"))
        assertEquals(emptySet(), store.read("", "s1"))
    }
}
