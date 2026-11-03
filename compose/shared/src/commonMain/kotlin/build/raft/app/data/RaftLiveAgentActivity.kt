package build.raft.app.data

import build.raft.app.model.AgentStatus
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * An agent of the current server that its presence fact says is thinking or working right now
 * (the input of the Web's `liveAgentActivityStore`): its id, name and face from the server's
 * member directory.
 */
data class RaftWorkingAgent(
    val agentId: String,
    val name: String,
    val avatarWireValue: String?,
    val status: AgentStatus,
)

/**
 * One row of "Working now" (Web `LiveAgentActivityItem`, raft-design people-and-agents/working-agents.md):
 * an agent at work, in the order the agents started. [idleSince] is set while it has gone idle and
 * is still listed for [RaftLiveAgentIdleGraceMillis] in case it picks up again.
 */
data class RaftLiveAgentActivityItem(
    val agentId: String,
    val name: String,
    val avatarWireValue: String?,
    val status: AgentStatus,
    val startedAt: Long,
    val idleSince: Long? = null,
)

/**
 * Web `LIVE_AGENT_IDLE_GRACE_MS`: an agent that goes idle stays listed this long. Agents go idle
 * between steps and pick up again a moment later; without the grace their faces would leave and
 * come back many times a minute (DS: "faces change only when someone starts or stops").
 */
const val RaftLiveAgentIdleGraceMillis: Long = 5_000L

/** Thinking and working are "at work" (Web `isLiveWorkActivity`); every other state settles the agent. */
internal fun AgentStatus?.isLiveWork(): Boolean = this == AgentStatus.Working || this == AgentStatus.Thinking

/**
 * The next "Working now" list (Web `applyStatusActivityEvent` over the coarse states):
 * - an agent still at work keeps its row and its place, with its latest name, face and state;
 * - an agent no longer at work stays for the idle grace, then leaves;
 * - an agent that starts is added at the end, so the stack changes only when someone starts or stops;
 * - an agent picking up again within the grace keeps its place.
 *
 * The app reads the server's coarse states (and the detailed ones of the agents a screen
 * subscribes to) from the presence registry, which holds a fact until a later one replaces it, so
 * a row stays while its agent is at work (Web `heldUntilChange` for coarse states).
 */
fun reduceLiveAgentActivity(
    previous: List<RaftLiveAgentActivityItem>,
    working: List<RaftWorkingAgent>,
    now: Long,
): List<RaftLiveAgentActivityItem> {
    val workingById = working.filter { it.status.isLiveWork() }.associateBy { it.agentId }
    val kept =
        previous.mapNotNull { item ->
            val agent = workingById[item.agentId]
            when {
                agent != null ->
                    item.copy(name = agent.name, avatarWireValue = agent.avatarWireValue, status = agent.status, idleSince = null)
                item.idleSince == null -> item.copy(idleSince = now)
                now - item.idleSince < RaftLiveAgentIdleGraceMillis -> item
                else -> null
            }
        }
    val listed = kept.mapTo(HashSet()) { it.agentId }
    val started =
        workingById.values
            .filter { it.agentId !in listed }
            .map { agent ->
                RaftLiveAgentActivityItem(
                    agentId = agent.agentId,
                    name = agent.name,
                    avatarWireValue = agent.avatarWireValue,
                    status = agent.status,
                    startedAt = now,
                )
            }
    return kept + started
}

/** Drops the rows whose idle grace has run out (Web `pruneExpiredLiveAgentActivityItems`). */
fun pruneLiveAgentActivity(items: List<RaftLiveAgentActivityItem>, now: Long): List<RaftLiveAgentActivityItem> =
    items.filter { item -> item.idleSince == null || now - item.idleSince < RaftLiveAgentIdleGraceMillis }

/** When the next row leaves on its own; null when every row is at work (Web `nextLiveAgentActivityExpiry`). */
fun nextLiveAgentActivityExpiry(items: List<RaftLiveAgentActivityItem>): Long? =
    items.mapNotNull { it.idleSince?.plus(RaftLiveAgentIdleGraceMillis) }.minOrNull()

/**
 * "Working now" over [working] (the server's agents at work): each change goes through
 * [reduceLiveAgentActivity], and a row in its idle grace is pruned when the grace runs out.
 */
internal fun liveAgentActivityFlow(
    working: Flow<List<RaftWorkingAgent>>,
    now: () -> Long = ::defaultNowMillis,
): Flow<List<RaftLiveAgentActivityItem>> =
    channelFlow {
        var items = emptyList<RaftLiveAgentActivityItem>()
        working.collectLatest { agents ->
            items = reduceLiveAgentActivity(items, agents, now())
            send(items)
            while (true) {
                val expiry = nextLiveAgentActivityExpiry(items) ?: break
                // The Web prunes 50ms after the moment too.
                delay((expiry - now()).coerceAtLeast(0L) + 50L)
                items = pruneLiveAgentActivity(items, now())
                send(items)
            }
        }
    }.distinctUntilChanged()

/**
 * Who is working right now, per server (Web `liveAgentActivityStore`), for the summary at the top
 * of the window and on the Home bar. Shared by every surface that shows it, so the order the
 * agents started in survives a page being rebuilt. Reads only what the app already has: the
 * server's presence facts (`agent:activity_state`, `agent:activity`) and its member directory.
 */
class SharedLiveAgentActivityStore internal constructor(
    private val scope: CoroutineScope,
    private val workingAgents: (serverId: String) -> Flow<List<RaftWorkingAgent>>,
    private val now: () -> Long = ::defaultNowMillis,
) {
    private val lock = SynchronizedObject()
    private val flows = mutableMapOf<String, StateFlow<List<RaftLiveAgentActivityItem>>>()

    fun itemsFlow(serverId: String): StateFlow<List<RaftLiveAgentActivityItem>> {
        val key = serverId.trim()
        return synchronized(lock) {
            flows.getOrPut(key) {
                liveAgentActivityFlow(workingAgents(key), now)
                    // A page swap keeps the list (and its order); nobody watching for a while drops it.
                    .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 30_000L), emptyList())
            }
        }
    }
}
