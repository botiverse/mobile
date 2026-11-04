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
 * member directory, and the realtime frame the fact came from ([signal]).
 */
data class RaftWorkingAgent(
    val agentId: String,
    val name: String,
    val avatarWireValue: String?,
    val status: AgentStatus,
    /**
     * The realtime frame behind the fact; null when the fact is not one (a REST projection, or a
     * fact carried over a reconnect with its seq dropped), which is no signal of its own.
     */
    val signal: RaftLiveAgentSignal? = null,
)

/**
 * One realtime frame about an agent (Web: an event reaching `applyStatusActivityEvent`): a detailed
 * `agent:activity` (heartbeats included, each with a new [serverSeq]) or, with [coarse], an
 * `agent:activity_state`. A coarse state has no heartbeat after it, so the row it leaves stays until
 * a later frame (Web `heldUntilChange`); a detailed one leaves after
 * [RaftLiveAgentActivityVisibleMillis] without another.
 */
data class RaftLiveAgentSignal(
    val serverSeq: Long,
    val coarse: Boolean,
)

/**
 * One row of "Working now" (Web `LiveAgentActivityItem`, raft-design people-and-agents/working-agents.md):
 * an agent at work, in the order the agents started. [idleSince] is set while it has gone idle and
 * is still listed for [RaftLiveAgentIdleGraceMillis] in case it picks up again.
 *
 * [lastSignalAt] (Web `createdAt`) is when the agent's latest frame arrived, [lastSignalSeq] that
 * frame's seq. Unless [heldUntilChange], the row leaves [RaftLiveAgentActivityVisibleMillis] after
 * [lastSignalAt] (Web's safety net for an agent that stopped heartbeating).
 */
data class RaftLiveAgentActivityItem(
    val agentId: String,
    val name: String,
    val avatarWireValue: String?,
    val status: AgentStatus,
    val startedAt: Long,
    val idleSince: Long? = null,
    val lastSignalAt: Long = startedAt,
    val lastSignalSeq: Long? = null,
    val heldUntilChange: Boolean = true,
)

/**
 * Web `LIVE_AGENT_IDLE_GRACE_MS`: an agent that goes idle stays listed this long. Agents go idle
 * between steps and pick up again a moment later; without the grace their faces would leave and
 * come back many times a minute (DS: "faces change only when someone starts or stops").
 */
const val RaftLiveAgentIdleGraceMillis: Long = 5_000L

/**
 * Web `LIVE_AGENT_ACTIVITY_VISIBLE_MS`: how long a row from a detailed frame stays without a fresh
 * one. A safety net, not the lifecycle: it outlasts the daemon's 60 s status heartbeat (plus
 * jitter) and still clears an agent that crashed and stopped heartbeating.
 */
const val RaftLiveAgentActivityVisibleMillis: Long = 90_000L

/** Thinking and working are "at work" (Web `isLiveWorkActivity`); every other state settles the agent. */
internal fun AgentStatus?.isLiveWork(): Boolean = this == AgentStatus.Working || this == AgentStatus.Thinking

/**
 * The next "Working now" list (Web `applyStatusActivityEvent` over the coarse states):
 * - an agent still at work keeps its row and its place, with its latest name, face and state;
 * - an agent no longer at work stays for the idle grace, then leaves;
 * - an agent that starts is added at the end, so the stack changes only when someone starts or stops;
 * - an agent picking up again within the grace keeps its place.
 *
 * A new frame for an agent ([RaftWorkingAgent.signal] with a seq the row has not seen) restarts the
 * row's visible window; a coarse frame holds the row until a later one (Web `heldUntilChange`). A
 * fact that is no frame (a REST projection, a reconnect's carried-over fact) changes neither: on
 * the Web a reconnect does not touch the list. A row added from such a fact is held, as the
 * presence registry keeps it until a later fact replaces it.
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
                agent != null -> item.atWork(agent, now)
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
                    lastSignalAt = now,
                    lastSignalSeq = agent.signal?.serverSeq,
                    heldUntilChange = agent.signal?.coarse ?: true,
                )
            }
    return kept + started
}

private fun RaftLiveAgentActivityItem.atWork(agent: RaftWorkingAgent, now: Long): RaftLiveAgentActivityItem {
    val latest = copy(name = agent.name, avatarWireValue = agent.avatarWireValue, status = agent.status, idleSince = null)
    val signal = agent.signal
    return when {
        signal != null && signal.serverSeq != lastSignalSeq ->
            latest.copy(lastSignalAt = now, lastSignalSeq = signal.serverSeq, heldUntilChange = signal.coarse)
        // Back to work within the grace with no frame: as on the Web, its window starts again.
        idleSince != null -> latest.copy(lastSignalAt = now, heldUntilChange = signal?.coarse ?: true)
        else -> latest
    }
}

/** When [this] row leaves on its own (Web `liveAgentActivityExpiry`); null for a held row at work. */
private fun RaftLiveAgentActivityItem.expiry(): Long? =
    when {
        idleSince != null -> idleSince + RaftLiveAgentIdleGraceMillis
        heldUntilChange -> null
        else -> lastSignalAt + RaftLiveAgentActivityVisibleMillis
    }

/**
 * Drops the rows whose idle grace or visible window has run out (Web
 * `pruneExpiredLiveAgentActivityItems`).
 */
fun pruneLiveAgentActivity(items: List<RaftLiveAgentActivityItem>, now: Long): List<RaftLiveAgentActivityItem> =
    items.filter { item -> item.expiry()?.let { now < it } ?: true }

/** When the next row leaves on its own; null when no row will (Web `nextLiveAgentActivityExpiry`). */
fun nextLiveAgentActivityExpiry(items: List<RaftLiveAgentActivityItem>): Long? =
    items.mapNotNull { it.expiry() }.minOrNull()

/**
 * "Working now" with the agents its visible window dropped while their fact still says working.
 * Such an agent is not listed again until a new frame about it arrives: the registry keeps its
 * last fact, but on the Web a dropped row comes back only with a new event.
 */
internal class LiveAgentActivityTimeline {
    var items: List<RaftLiveAgentActivityItem> = emptyList()
        private set

    /** Agent id to the seq of the last frame seen before its window ran out. */
    private val silenced = mutableMapOf<String, Long?>()

    fun apply(working: List<RaftWorkingAgent>, now: Long): List<RaftLiveAgentActivityItem> {
        prune(now)
        val byId = working.filter { it.status.isLiveWork() }.associateBy { it.agentId }
        silenced.keys.retainAll { id ->
            val agent = byId[id]
            agent != null && (agent.signal == null || agent.signal.serverSeq == silenced[id])
        }
        items = reduceLiveAgentActivity(items, working.filter { it.agentId !in silenced }, now)
        return items
    }

    fun prune(now: Long): List<RaftLiveAgentActivityItem> {
        val next = pruneLiveAgentActivity(items, now)
        if (next.size != items.size) {
            val remaining = next.mapTo(HashSet()) { it.agentId }
            items.filter { it.agentId !in remaining && it.idleSince == null }.forEach { silenced[it.agentId] = it.lastSignalSeq }
            items = next
        }
        return items
    }
}

/**
 * "Working now" over [working] (the server's agents at work): each change goes through
 * [LiveAgentActivityTimeline.apply], and a row is pruned when its idle grace or its visible window
 * runs out.
 */
internal fun liveAgentActivityFlow(
    working: Flow<List<RaftWorkingAgent>>,
    now: () -> Long = ::defaultNowMillis,
): Flow<List<RaftLiveAgentActivityItem>> =
    channelFlow {
        val timeline = LiveAgentActivityTimeline()
        working.collectLatest { agents ->
            send(timeline.apply(agents, now()))
            while (true) {
                val expiry = nextLiveAgentActivityExpiry(timeline.items) ?: break
                // The Web prunes 50ms after the moment too.
                delay((expiry - now()).coerceAtLeast(0L) + 50L)
                send(timeline.prune(now()))
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
