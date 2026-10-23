package build.raft.app.data

import build.raft.app.model.AgentTypingPhase
import build.raft.app.model.AgentTypingUpdated
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Agent typing line (raft-design 0042, `design/people-and-agents/working-agents.md` "Who is about to
 * post"): who is about to post where. Port of the web's `packages/web/src/store/agentTypingStore.ts`.
 *
 * Wire contract, botiverse/slock staging `packages/shared/src/agentTyping.ts`: the server emits
 * `agent:typing` to `channel:<channelId>` with `{ agentId, serverId, channelId, parentMessageId,
 * taskId?, callId, state: "start" | "end", expiresAt }`. A thread reply names the parent channel and
 * the thread's root message; a task v2 reply names the task conversation with a null
 * `parentMessageId`. `start` repeats every [AGENT_TYPING_SEND_INTENT_REFRESH_MS] while the message
 * is being written; a line lives [AGENT_TYPING_TTL_MS] without a refresh. Only agents send it.
 */
const val AGENT_TYPING_TTL_MS: Long = 20_000L

/** slock `AGENT_SEND_INTENT_REFRESH_MS`: how often a live call re-sends `start`. */
const val AGENT_TYPING_SEND_INTENT_REFRESH_MS: Long = 8_000L

/** web `AGENT_TYPING_CLEARED_CALLS_MAX`: calls kept as "posted, ignore its starts". */
internal const val AGENT_TYPING_CLEARED_CALLS_MAX = 128

/** web `AGENT_TYPING_CLEARED_CALL_TTL_MS`: how long a posted call's starts are ignored after the last one. */
internal const val AGENT_TYPING_CLEARED_CALL_TTL_MS: Long = AGENT_TYPING_TTL_MS

/** `agent:typing` as it comes off the wire; [toAgentTypingUpdated] keeps the frames we can place. */
@Serializable
internal data class AgentTypingWire(
    val agentId: String? = null,
    val serverId: String? = null,
    val channelId: String? = null,
    val parentMessageId: String? = null,
    val taskId: String? = null,
    val callId: String? = null,
    val state: String? = null,
    val expiresAt: Double? = null,
)

/** web `readAgentTypingEvent`: null for a frame we cannot place; a missing `expiresAt` reads as 0. */
internal fun AgentTypingWire.toAgentTypingUpdated(): AgentTypingUpdated? {
    val agent = agentId?.takeIf { it.isNotBlank() } ?: return null
    val server = serverId?.takeIf { it.isNotBlank() } ?: return null
    val channel = channelId?.takeIf { it.isNotBlank() } ?: return null
    val call = callId?.takeIf { it.isNotBlank() } ?: return null
    val phase =
        when (state) {
            "start" -> AgentTypingPhase.Start
            "end" -> AgentTypingPhase.End
            else -> return null
        }
    return AgentTypingUpdated(
        agentId = MemberId(agent),
        serverId = ServerId(server),
        channelId = ChannelId(channel),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        callId = call,
        phase = phase,
        expiresAtEpochMillis = expiresAt?.takeIf { it.isFinite() }?.toLong() ?: 0L,
        taskId = taskId?.takeIf { it.isNotBlank() },
    )
}

/** One live call: [agentId] + [callId] is its identity. */
data class AgentTypingEntry(
    val agentId: String,
    val callId: String,
    val channelId: String,
    /** The thread's root message for a thread reply; null for the channel itself. */
    val parentMessageId: String?,
    /** Local ms of the call's first `start`: the display order. */
    val startedAt: Long,
    /** Local ms after which the entry is dropped without a refresh. */
    val deadline: Long,
)

/** Live calls per channel of [serverId], each list in the order the calls started. */
data class AgentTypingState(
    val serverId: String? = null,
    val byChannel: Map<String, List<AgentTypingEntry>> = emptyMap(),
)

/**
 * web `localTypingDeadline`: `expiresAt` is the server's clock. A client clock ahead or behind would
 * drop the line at once or keep it far too long, so a remaining time outside (0, TTL] falls back to
 * the TTL from now.
 */
fun agentTypingLocalDeadline(expiresAt: Long, now: Long): Long {
    val remaining = expiresAt - now
    return now + if (remaining in 1..AGENT_TYPING_TTL_MS) remaining else AGENT_TYPING_TTL_MS
}

/**
 * web `selectTypingAgentIds`: the agents typing into one conversation (the channel itself for a null
 * [parentMessageId], or one thread under it), each once, in the order they started.
 */
fun AgentTypingState.typingAgentIds(serverId: String?, channelId: String?, parentMessageId: String?): List<String> {
    if (channelId.isNullOrBlank() || serverId.isNullOrBlank() || serverId != this.serverId) return emptyList()
    val entries = byChannel[channelId] ?: return emptyList()
    val ids = mutableListOf<String>()
    for (entry in entries) {
        if (entry.parentMessageId != parentMessageId) continue
        if (entry.agentId !in ids) ids += entry.agentId
    }
    return ids
}

/**
 * The agent typing slice of one shell, fed by the shell's realtime owner (every accepted event of
 * the selected server, whichever page is open). An entry leaves on its `end`, when its deadline
 * passes (one timer for the earliest deadline, none while empty), when the agent's message lands in
 * that conversation, and when the server changes. Delivery may repeat (several sinks, a replay):
 * every operation is idempotent.
 */
class SharedAgentTypingStore internal constructor(
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val mutableState = MutableStateFlow(AgentTypingState())
    val state: StateFlow<AgentTypingState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()
    private var expiryJob: Job? = null

    /** `agentId` + `callId` -> local ms until which its `start`s are ignored; insertion order = age. */
    private val clearedCalls = LinkedHashMap<String, Long>()

    /** Routes one accepted realtime event of [serverId]. */
    fun consume(serverId: String, event: RealtimeEvent) {
        when (event) {
            is AgentTypingUpdated -> if (event.serverId.value == serverId) apply(event)
            is MessageNew -> {
                val message = event.message
                if (message.author.kind != MemberKind.Agent) return
                val context = message.conversationContext
                if (context?.channelKind == ConversationKind.Thread) {
                    // A thread reply clears by its root, wherever its channel is.
                    context.parentMessageId?.let { clearForThreadReply(serverId, message.author.id.value, it.value) }
                    return
                }
                message.channelId?.let { clearForMessage(serverId, message.author.id.value, it.value, null) }
            }
            // The channel view may not hold the thread's room: its reply lands here.
            is ThreadUpdated -> {
                val reply = event.latestReply ?: return
                if (reply.senderType == "agent") clearForThreadReply(serverId, reply.senderId, event.parentMessageId.value)
            }
            else -> Unit
        }
    }

    fun apply(event: AgentTypingUpdated): Boolean = mutate { state ->
        val current = state.forServer(event.serverId.value)
        val channelId = event.channelId.value
        val entries = current.byChannel[channelId].orEmpty()
        val index = entries.indexOfFirst { it.agentId == event.agentId.value && it.callId == event.callId }
        if (event.phase == AgentTypingPhase.End) {
            if (index < 0) return@mutate current
            return@mutate current.withChannel(channelId, entries.filterIndexed { i, _ -> i != index })
        }
        val at = now()
        if (index < 0 && isClearedCall(event.agentId.value, event.callId, at)) return@mutate current
        val deadline = agentTypingLocalDeadline(event.expiresAtEpochMillis, at)
        val parentMessageId = event.parentMessageId?.value
        if (index >= 0) {
            // A refresh: same place in line, new deadline.
            val next = entries.toMutableList()
            next[index] = entries[index].copy(parentMessageId = parentMessageId, deadline = deadline)
            current.withChannel(channelId, next)
        } else {
            current.withChannel(
                channelId,
                entries +
                    AgentTypingEntry(
                        agentId = event.agentId.value,
                        callId = event.callId,
                        channelId = channelId,
                        parentMessageId = parentMessageId,
                        startedAt = at,
                        deadline = deadline,
                    ),
            )
        }
    }

    /** The agent posted into [channelId] ([parentMessageId] = the thread, or null). */
    fun clearForMessage(serverId: String, agentId: String, channelId: String, parentMessageId: String?): Boolean = mutate { state ->
        if (state.serverId != serverId) return@mutate state
        val entries = state.byChannel[channelId] ?: return@mutate state
        val cleared = entries.filter { it.agentId == agentId && it.parentMessageId == parentMessageId }
        if (cleared.isEmpty()) return@mutate state
        rememberCleared(cleared, now())
        state.withChannel(channelId, entries - cleared.toSet())
    }

    /** The agent replied in the thread under [parentMessageId], wherever its channel is. */
    fun clearForThreadReply(serverId: String, agentId: String, parentMessageId: String): Boolean = mutate { state ->
        if (state.serverId != serverId) return@mutate state
        val cleared = mutableListOf<AgentTypingEntry>()
        var next = state
        for ((channelId, entries) in state.byChannel) {
            val drop = entries.filter { it.agentId == agentId && it.parentMessageId == parentMessageId }
            if (drop.isEmpty()) continue
            cleared += drop
            next = next.withChannel(channelId, entries - drop.toSet())
        }
        if (cleared.isNotEmpty()) rememberCleared(cleared, now())
        next
    }

    /** Drops every entry whose deadline has passed. */
    fun pruneExpired() {
        val changed =
            mutate { state ->
                val at = now()
                var next = state
                for ((channelId, entries) in state.byChannel) {
                    val kept = entries.filter { it.deadline > at }
                    if (kept.size != entries.size) next = next.withChannel(channelId, kept)
                }
                next
            }
        // Nothing due yet (a timer that fired early): keep waiting for the earliest.
        if (!changed) synchronized(lock) { scheduleExpiry(mutableState.value) }
    }

    /** The viewer's server: a different one (a switch) starts over, as the web does on a server change. */
    fun bindServer(serverId: String?): Boolean = mutate { state ->
        val server = serverId?.takeIf { it.isNotBlank() }
        if (server == null || server == state.serverId) state else AgentTypingState(serverId = server).also { clearedCalls.clear() }
    }

    internal fun close() {
        synchronized(lock) {
            expiryJob?.cancel()
            expiryJob = null
        }
    }

    private fun AgentTypingState.forServer(serverId: String): AgentTypingState =
        if (this.serverId == serverId) this else AgentTypingState(serverId = serverId).also { clearedCalls.clear() }

    /** Applies [block] under [lock]; true when the state changed (and the timer was re-armed). */
    private inline fun mutate(block: (AgentTypingState) -> AgentTypingState): Boolean =
        synchronized(lock) {
            val before = mutableState.value
            val after = block(before)
            if (after == before) return@synchronized false
            mutableState.value = after
            scheduleExpiry(after)
            true
        }

    /** One timer for the earliest deadline; none while nothing is live. Caller holds [lock]. */
    private fun scheduleExpiry(state: AgentTypingState) {
        expiryJob?.cancel()
        expiryJob = null
        val next = state.byChannel.values.flatten().minOfOrNull { it.deadline } ?: return
        expiryJob =
            scope.launch {
                delay((next - now()).coerceAtLeast(0L))
                pruneExpired()
            }
    }

    private fun rememberCleared(entries: List<AgentTypingEntry>, at: Long) {
        for (entry in entries) {
            val key = callKey(entry.agentId, entry.callId)
            clearedCalls.remove(key)
            clearedCalls[key] = at + AGENT_TYPING_CLEARED_CALL_TTL_MS
        }
        val iterator = clearedCalls.entries.iterator()
        while (iterator.hasNext()) {
            val (_, until) = iterator.next()
            if (clearedCalls.size <= AGENT_TYPING_CLEARED_CALLS_MAX && until > at) break
            iterator.remove()
        }
    }

    /** A start for a call whose message already landed: ignored, and it keeps the call remembered while it repeats. */
    private fun isClearedCall(agentId: String, callId: String, at: Long): Boolean {
        if (clearedCalls.isEmpty()) return false
        val key = callKey(agentId, callId)
        val until = clearedCalls.remove(key) ?: return false
        if (until <= at) return false
        clearedCalls[key] = at + AGENT_TYPING_CLEARED_CALL_TTL_MS
        return true
    }

    private fun callKey(agentId: String, callId: String) = "$agentId\u0000$callId"
}

private fun AgentTypingState.withChannel(channelId: String, entries: List<AgentTypingEntry>): AgentTypingState =
    copy(byChannel = if (entries.isEmpty()) byChannel - channelId else byChannel + (channelId to entries))

/** The four sentence shapes of one locale, with `{first}`, `{second}`, `{third}`, `{others}`, `{count}`. */
data class AgentTypingSentenceTemplates(
    val one: String,
    val two: String,
    val three: String,
    val many: String,
)

/**
 * web `formatAgentTypingSentence`: "Niko is typing…", "Niko and Kirby are typing…", "Niko, Kirby and
 * Tenny are typing…", "Niko, Kirby and 3 other agents are typing…". Empty for nobody. `{others}` is
 * everyone past the first two, `{count}` everyone.
 */
fun formatAgentTypingSentence(templates: AgentTypingSentenceTemplates, names: List<String>): String {
    if (names.isEmpty()) return ""
    val template =
        when (names.size) {
            1 -> templates.one
            2 -> templates.two
            3 -> templates.three
            else -> templates.many
        }
    // One pass, so a name that itself reads like a placeholder stays as written.
    return AgentTypingPlaceholder.replace(template) { match ->
        when (match.groupValues[1]) {
            "first" -> names[0]
            "second" -> names.getOrElse(1) { "" }
            "third" -> names.getOrElse(2) { "" }
            "others" -> (names.size - 2).toString()
            else -> names.size.toString()
        }
    }
}

private val AgentTypingPlaceholder = Regex("""\{(first|second|third|others|count)\}""")
