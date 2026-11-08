package build.raft.app.data

import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One realtime `agent:updated { agentId }` on [serverId]. */
internal data class RaftAgentUpdatedSignal(val serverId: String, val agentId: String)

/**
 * Process-wide `agent:updated` ticks for an open agent Profile. The app shell re-reads the roster
 * (`GET /agents`) on that event, but the Profile shows `GET /agents/:id`, so it listens here and
 * re-reads its agent (botiverse/slock #13492: the server sends `agent:updated` when an agent's
 * `modelLimits` signal may have changed). Never suspends the publisher: a burst keeps the newest.
 */
internal class RaftAgentUpdatedSignals {
    private val _events =
        MutableSharedFlow<RaftAgentUpdatedSignal>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RaftAgentUpdatedSignal> = _events.asSharedFlow()

    fun publish(serverId: String, agentId: String) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val agent = agentId.trim().takeIf { it.isNotEmpty() } ?: return
        _events.tryEmit(RaftAgentUpdatedSignal(server, agent))
    }
}

/** Whether [signal] is about the agent the Profile shows ([agentId] on [serverId]). */
internal fun RaftAgentUpdatedSignal.isFor(serverId: String?, agentId: String?): Boolean =
    serverId?.trim() == this.serverId && agentId?.trim() == this.agentId

/** The agent of a realtime `agent:updated { agentId }`, else null. */
internal fun RealtimeEvent.updatedAgentIdOrNull(): String? =
    (this as? RealtimeRefreshRequired)
        ?.takeIf { it.isAgentProfileUpdate() }
        ?.let { event -> event.subjectId?.trim()?.takeIf(String::isNotEmpty) ?: event.memberId?.value?.trim()?.takeIf(String::isNotEmpty) }
