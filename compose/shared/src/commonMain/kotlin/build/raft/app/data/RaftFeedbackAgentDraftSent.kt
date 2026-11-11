package build.raft.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The Web's `raft_feedback_agent_draft_sent:<userId>` key (feedback/agentFeedbackSentFlag.ts), kept as the stored key. */
internal const val RaftFeedbackAgentDraftSentKeyPrefix = "raft_feedback_agent_draft_sent:"

/**
 * "This person has sent feedback an agent drafted" (botiverse/slock #14076, web
 * `feedback/agentFeedbackSentFlag.ts`): set when they send a `feedback:submit` card (the native
 * feedback form on the card's draft, or a card sheet's execute that comes back executed), read by
 * Settings › Feedback's agent tip, which then stops showing: they already know an agent can draft it.
 *
 * Kept on this device, one key per user, as the web keeps it per browser: another device shows the
 * tip until a card is sent there (the server does not say which tickets an agent drafted). A store
 * that refuses the write keeps the flag for this process only.
 */
class RaftFeedbackAgentDraftSentRepository(private val store: () -> SharedPreferenceStore?) {
    /** Users marked in this process: the flag holds even if the store refused it, and readers re-read. */
    private val marked = MutableStateFlow<Set<String>>(emptySet())

    fun isSent(userId: String?): Boolean {
        val id = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (id in marked.value) return true
        return runCatching { store()?.getString(RaftFeedbackAgentDraftSentKeyPrefix + id) }.getOrNull() == "1"
    }

    /** [isSent] for [userId], again after every [markSent]. */
    fun sent(userId: String?): Flow<Boolean> = marked.map { isSent(userId) }.distinctUntilChanged()

    fun markSent(userId: String?) {
        val id = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching { store()?.putString(RaftFeedbackAgentDraftSentKeyPrefix + id, "1") }
        marked.update { it + id }
    }
}

/** The app's one [RaftFeedbackAgentDraftSentRepository], over the shared preference store. */
object RaftFeedbackAgentDraftSent {
    /** Tests swap it for one over a memory store. */
    var repository: RaftFeedbackAgentDraftSentRepository =
        RaftFeedbackAgentDraftSentRepository { runCatching { raftSharedPreferenceStore() }.getOrNull() }
        internal set
}
