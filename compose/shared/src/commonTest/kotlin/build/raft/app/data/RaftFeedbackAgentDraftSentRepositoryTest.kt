package build.raft.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings › Feedback's "an agent can draft it" tip retires per person once they send an agent's
 * draft (botiverse/slock 0d4ec9fd4f58, #14076 `feedback/agentFeedbackSentFlag.ts`): one key per
 * user, the web's key, "1"; a store that refuses the write keeps the flag for this process.
 */
class RaftFeedbackAgentDraftSentRepositoryTest {
    @Test
    fun aSentDraftIsKeptPerUserUnderTheWebsKey() {
        val preferences = SyncTestPreferences()
        val repository = RaftFeedbackAgentDraftSentRepository { preferences }

        assertFalse(repository.isSent("user-1"))
        repository.markSent("user-1")

        assertEquals("1", preferences.values["raft_feedback_agent_draft_sent:user-1"])
        assertTrue(repository.isSent("user-1"))
        assertFalse(repository.isSent("user-2"))
        // Read again from the device store (another launch).
        assertTrue(RaftFeedbackAgentDraftSentRepository { preferences }.isSent("user-1"))
        // No user: nothing is kept, nothing reads as sent.
        repository.markSent(null)
        assertFalse(repository.isSent(null))
        assertEquals(setOf("raft_feedback_agent_draft_sent:user-1"), preferences.values.keys)
    }

    @Test
    fun aStoreThatRefusesTheWriteKeepsTheFlagForThisProcess() {
        val refusing =
            object : SharedPreferenceStore {
                override fun getString(key: String): String? = null

                override fun putString(key: String, value: String?) = error("storage full")
            }
        val repository = RaftFeedbackAgentDraftSentRepository { refusing }
        repository.markSent("user-1")
        assertTrue(repository.isSent("user-1"))
    }

    @Test
    fun readersFollowAMarkMadeElsewhere() = runTest {
        val repository = RaftFeedbackAgentDraftSentRepository { SyncTestPreferences() }
        assertFalse(repository.sent("user-1").first())
        val seen = mutableListOf<Boolean>()
        val reader = launch(UnconfinedTestDispatcher(testScheduler)) { repository.sent("user-1").take(2).toList(seen) }
        repository.markSent("user-2")
        repository.markSent("user-1")
        reader.join()
        assertEquals(listOf(false, true), seen)
    }
}
