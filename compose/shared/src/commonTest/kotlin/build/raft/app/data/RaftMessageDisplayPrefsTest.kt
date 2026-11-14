package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Collapse long messages" on the account (web `message-display-settings`) and the one-time move of the device value. */
class RaftMessageDisplayPrefsTest {
    @Test
    fun patchBodyCarriesOnlyTheSwitch() {
        assertEquals("""{"collapseLongMessages":true}""", buildRaftMessageDisplayPrefsBody(true))
        assertEquals("""{"collapseLongMessages":false}""", buildRaftMessageDisplayPrefsBody(false))
    }

    @Test
    fun responseParsesLikeTheWeb() {
        assertEquals(RaftMessageDisplayPrefs(true, 3L), parseRaftMessageDisplayPrefs("""{"collapseLongMessages":true,"prefsVersion":3}"""))
        // Anything but true is off; a bad version is dropped (web normalizeMessageDisplayPrefs).
        assertEquals(RaftMessageDisplayPrefs(false, null), parseRaftMessageDisplayPrefs("""{"collapseLongMessages":"yes","prefsVersion":-1}"""))
        assertEquals(RaftMessageDisplayPrefs(false, null), parseRaftMessageDisplayPrefs("{}"))
        assertNull(parseRaftMessageDisplayPrefs("not json"))
    }

    @Test
    fun threadsAndOlderServersFallBackToTheDevice() {
        assertEquals(RaftMessageDisplayPrefsResult.Unsupported, raftMessageDisplayPrefsResult(400, """{"error":"x"}"""))
        assertEquals(RaftMessageDisplayPrefsResult.Unsupported, raftMessageDisplayPrefsResult(404, null))
        assertEquals(RaftMessageDisplayPrefsResult.Failed, raftMessageDisplayPrefsResult(500, null))
        assertEquals(RaftMessageDisplayPrefsResult.Failed, raftMessageDisplayPrefsResult(200, "nope"))
        assertEquals(
            RaftMessageDisplayPrefsResult.Loaded(RaftMessageDisplayPrefs(true, 1L)),
            raftMessageDisplayPrefsResult(200, """{"collapseLongMessages":true,"prefsVersion":1}""")
        )
    }

    @Test
    fun deviceValueMovesOnlyWhenTheAccountNeverSetOne() {
        val unset = RaftMessageDisplayPrefs(collapseLongMessages = false, prefsVersion = 0L)
        assertTrue(unset.unset)
        assertEquals(true, raftLongMessageCollapseMigrationWrite(localEffective = true, account = unset))
        assertNull(raftLongMessageCollapseMigrationWrite(localEffective = false, account = unset))
        // Someone chose on the web or another device: the account wins.
        assertNull(raftLongMessageCollapseMigrationWrite(localEffective = true, account = RaftMessageDisplayPrefs(false, 4L)))
        assertNull(raftLongMessageCollapseMigrationWrite(localEffective = false, account = RaftMessageDisplayPrefs(true, 2L)))
    }

    @Test
    fun realtimeUpdateFromAnotherDeviceAppliesUnlessOlder() {
        RaftMessageDisplayPrefsStore.resetForTests()
        try {
            RaftMessageDisplayPrefsStore.applyRealtime("s1", "c1", RaftMessageDisplayPrefs(collapseLongMessages = true, prefsVersion = 3))
            assertEquals(RaftMessageDisplayPrefs(true, 3), RaftMessageDisplayPrefsStore.state.value["s1:c1"])
            // A late, older push does not undo the newer value.
            RaftMessageDisplayPrefsStore.applyRealtime("s1", "c1", RaftMessageDisplayPrefs(collapseLongMessages = false, prefsVersion = 2))
            assertEquals(RaftMessageDisplayPrefs(true, 3), RaftMessageDisplayPrefsStore.state.value["s1:c1"])
            RaftMessageDisplayPrefsStore.applyRealtime("s1", "c1", RaftMessageDisplayPrefs(collapseLongMessages = false, prefsVersion = 4))
            assertEquals(RaftMessageDisplayPrefs(false, 4), RaftMessageDisplayPrefsStore.state.value["s1:c1"])
        } finally {
            RaftMessageDisplayPrefsStore.resetForTests()
        }
    }

    @Test
    fun keysNeedBothIds() {
        assertEquals("s1:c1", raftMessageDisplayPrefsKey(" s1 ", "c1"))
        assertNull(raftMessageDisplayPrefsKey(null, "c1"))
        assertNull(raftMessageDisplayPrefsKey("s1", " "))
        assertEquals("chatLayout:long_message_collapse_migrated:s1:c1", raftLongMessageCollapseMigratedKey("s1", "c1"))
    }
}
