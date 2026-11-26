package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationWorkerStateKind
import build.raft.app.model.ConversationWorkerStateUpdated
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.reportUnknownTypeDropped
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Worker v1 conversation status line (`worker_v1`, slock `packages/shared/src/workerEvents.ts` and
 * `packages/web/src/components/message/conversationWorkerState.ts`): tracks which agent's Worker is
 * handling (or handed back) an open thread or task conversation.
 *
 * Hydrated via `GET /api/conversations/worker-state` when a conversation opens and on socket
 * reconnect, then kept live via `conversation:worker:state` pushed to `channel:<channelId>`.
 * When `worker_v1` is OFF for the server, performs zero network requests and mutates no state.
 */

/** System-message `systemSubtype` written by the server when a Worker ends in a conversation. */
const val WORKER_ENDED_RECORD_SUBTYPE: String = "worker.ended"

/** Running turn with no runtime event this long is quiet / long tool call (slock `WORKER_QUIET_AFTER_MS`). */
const val WORKER_QUIET_AFTER_MS: Long = 10 * 60_000L

/** Human message unanswered this long promotes waiting line to warning tone (`WORKER_HUMAN_WAITING_AFTER_MS`). */
const val WORKER_HUMAN_WAITING_AFTER_MS: Long = 2 * 60_000L

/** Refresh interval for elapsed durations shown on the conversation Worker line (`LINE_TICK_MS`). */
const val CONVERSATION_WORKER_LINE_TICK_MS: Long = 15_000L

/** Target conversation for the Worker line: either a reply thread or a task conversation. */
data class WorkerConversationTarget(
    val channelId: String,
    val parentMessageId: String? = null,
    val taskId: String? = null,
)

fun workerConversationTargetForThread(
    parentChannelId: String?,
    parentMessageId: String?,
): WorkerConversationTarget? {
    val channel = parentChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parent = parentMessageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return WorkerConversationTarget(channelId = channel, parentMessageId = parent, taskId = null)
}

fun workerConversationTargetForTask(
    taskChannelId: String?,
    taskId: String?,
): WorkerConversationTarget? {
    val channel = taskChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val task = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return WorkerConversationTarget(channelId = channel, parentMessageId = null, taskId = task)
}

/** Matches web `workerConversationKey`: `${channelId}:${parentMessageId ?? ""}:${taskId ?? ""}`. */
fun WorkerConversationTarget.conversationKey(): String =
    "$channelId:${if (taskId != null) "" else parentMessageId.orEmpty()}:${taskId.orEmpty()}"

/** Matches web `keyOfState`: taskId takes precedence over parentMessageId when present. */
fun ConversationWorkerStateUpdated.conversationKey(): String =
    "${channelId.value}:${if (taskId != null) "" else parentMessageId?.value.orEmpty()}:${taskId.orEmpty()}"

/** One agent's Worker state as held locally (web `HeldConversationWorkerState`). */
data class HeldConversationWorkerState(
    val state: ConversationWorkerStateUpdated,
    /** Local epoch ms when this client first observed the agent in this `state.state` kind. */
    val stateSeenAtMs: Long,
    /** Local epoch ms when this client first observed the agent's current `(doing, tool)` pair. */
    val doingSeenAtMs: Long,
    /** Local epoch ms when this client received this entry (read or push). */
    val receivedAtMs: Long,
)

/** Live Worker states for the bound server, keyed by [WorkerConversationTarget.conversationKey] then agentId. */
data class ConversationWorkerStoreState(
    val serverId: String? = null,
    val byConversation: Map<String, Map<String, HeldConversationWorkerState>> = emptyMap(),
)

/** Sorted states for [target] on [serverId], ordered lexicographically by `agentId`. */
fun ConversationWorkerStoreState.heldStatesFor(
    serverId: String?,
    target: WorkerConversationTarget?,
): List<HeldConversationWorkerState> {
    if (serverId.isNullOrBlank() || target == null || this.serverId != serverId) return emptyList()
    val entries = byConversation[target.conversationKey()] ?: return emptyList()
    return entries.values.sortedBy { it.state.agentId.value }
}

private val conversationWorkerStateJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/**
 * Strict wire parser matching web `conversationWorkerStateOf`:
 * - Required string fields: `channelId`, `agentId`, `sinceAt`, `state` (closed union).
 * - Optional fields (`parentMessageId`, `taskId`, `worker`, `waitingSinceAt`, `waitingFrom`,
 *   `doing`, `tool`, `quietSinceAt`): must be absent or a JSON string (explicit `null` or non-string
 *   values reject the payload).
 */
@RaftCoreInternalApi fun parseConversationWorkerStateJson(element: JsonElement?): ConversationWorkerStateUpdated? {
    val obj = element as? JsonObject ?: return null
    val channelId = obj.requiredNonBlankString("channelId") ?: return null
    val agentId = obj.requiredNonBlankString("agentId") ?: return null
    val sinceAt = obj.requiredNonBlankString("sinceAt") ?: return null
    val stateRaw = obj.requiredNonBlankString("state") ?: return null
    val state =
        when (stateRaw) {
            "handling" -> ConversationWorkerStateKind.Handling
            "stalled" -> ConversationWorkerStateKind.Stalled
            "waiting" -> ConversationWorkerStateKind.Waiting
            "handed_back" -> ConversationWorkerStateKind.HandedBack
            "none" -> ConversationWorkerStateKind.None
            else -> {
                reportUnknownTypeDropped("realtime_conversation_worker_state", "state", stateRaw)
                return null
            }
        }
    val (parentOk, parentMessageId) = obj.optionalStrictString("parentMessageId")
    val (taskOk, taskId) = obj.optionalStrictString("taskId")
    val (workerOk, worker) = obj.optionalStrictString("worker")
    val (waitingSinceOk, waitingSinceAt) = obj.optionalStrictString("waitingSinceAt")
    val (waitingFromOk, waitingFrom) = obj.optionalStrictString("waitingFrom")
    val (doingOk, doing) = obj.optionalStrictString("doing")
    val (toolOk, tool) = obj.optionalStrictString("tool")
    val (quietSinceOk, quietSinceAt) = obj.optionalStrictString("quietSinceAt")
    if (!parentOk || !taskOk || !workerOk || !waitingSinceOk || !waitingFromOk || !doingOk || !toolOk || !quietSinceOk) {
        return null
    }
    return ConversationWorkerStateUpdated(
        channelId = ChannelId(channelId),
        parentMessageId = parentMessageId?.let(::MessageId),
        taskId = taskId,
        agentId = MemberId(agentId),
        worker = worker,
        state = state,
        sinceAt = sinceAt,
        waitingSinceAt = waitingSinceAt,
        waitingFrom = waitingFrom,
        doing = doing,
        tool = tool,
        quietSinceAt = quietSinceAt,
    )
}

/** Parses `GET /api/conversations/worker-state` (`{ "states": [...] }`), keeping valid entries only. */
@RaftCoreInternalApi fun parseConversationWorkerStateResponse(bodyText: String?): List<ConversationWorkerStateUpdated> {
    if (bodyText.isNullOrBlank()) return emptyList()
    val root =
        try {
            conversationWorkerStateJson.parseToJsonElement(bodyText) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()
    val states = root["states"] as? JsonArray ?: return emptyList()
    return states.mapNotNull(::parseConversationWorkerStateJson)
}

private fun JsonObject.requiredNonBlankString(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.isNotBlank() }
}

private fun JsonObject.optionalStrictString(key: String): Pair<Boolean, String?> {
    if (!containsKey(key)) return true to null
    val primitive = this[key] as? JsonPrimitive ?: return false to null
    if (!primitive.isString) return false to null
    return true to primitive.content.takeIf { it.isNotBlank() }
}

/** `GET /api/conversations/worker-state?channelId=...&parentMessageId=...` (or `taskId=...`). */
@RaftCoreInternalApi suspend fun fetchConversationWorkerState(
    networkClient: RaftNetworkClient,
    serverId: ServerId,
    target: WorkerConversationTarget,
): List<ConversationWorkerStateUpdated> {
    val query = buildList {
        add("channelId" to target.channelId)
        val task = target.taskId?.takeIf { it.isNotBlank() }
        val parent = target.parentMessageId?.takeIf { it.isNotBlank() }
        if (task != null) add("taskId" to task)
        else if (parent != null) add("parentMessageId" to parent)
    }
    val response =
        try {
            networkClient.request(
                priority = DataRequestPriority.Visible,
                path = "conversations/worker-state",
                query = query,
                serverIdOverride = serverId,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return emptyList()
        }
    if (!response.isSuccessful) return emptyList()
    return parseConversationWorkerStateResponse(response.bodyText)
}

private fun holdWorkerState(
    previous: HeldConversationWorkerState?,
    incoming: ConversationWorkerStateUpdated,
    nowMs: Long,
): HeldConversationWorkerState {
    val sameKind = previous?.state?.state == incoming.state
    val sameDoing = previous != null && previous.state.doing == incoming.doing && previous.state.tool == incoming.tool
    return HeldConversationWorkerState(
        state = incoming,
        stateSeenAtMs = if (sameKind) previous.stateSeenAtMs else nowMs,
        doingSeenAtMs = if (sameDoing) previous.doingSeenAtMs else nowMs,
        receivedAtMs = nowMs,
    )
}

/**
 * Shell-scoped conversation Worker state store (port of web `useConversationWorkerStore`).
 *
 * Ref-counts mounted lines per conversation so closing the last line forgets its state (preventing
 * stale flash on reopen). Live pushes arriving while an initial or reconnect REST read is in flight
 * win over the older read response via per-(conversation, agent) `pushedAtMs` timestamps.
 */
class SharedConversationWorkerStateStore @RaftCoreInternalApi constructor(
    private val scope: CoroutineScope,
    private val fetchStates: suspend (ServerId, WorkerConversationTarget) -> List<ConversationWorkerStateUpdated>,
    private val isEnabledForServer: (String) -> Boolean = { true },
    private val now: () -> Long = ::defaultNowMillis,
) {
    private val mutableState = MutableStateFlow(ConversationWorkerStoreState())
    val state: StateFlow<ConversationWorkerStoreState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()
    private val pushedAtMs = mutableMapOf<String, MutableMap<String, Long>>()
    private val mountedCounts = mutableMapOf<String, Int>()
    private val inFlightReads = mutableMapOf<String, Job>()

    /** Routes one accepted realtime event for [serverId]. Ignored when `worker_v1` is OFF or unmounted. */
    fun consume(serverId: String, event: RealtimeEvent) {
        if (event !is ConversationWorkerStateUpdated) return
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        if (!isEnabledForServer(cleanServer)) return
        val key = event.conversationKey()
        val mounted =
            synchronized(lock) {
                mutableState.value.serverId == cleanServer && (mountedCounts[key] ?: 0) > 0
            }
        if (!mounted) return
        apply(cleanServer, event, now())
    }

    /** Switches the bound server; clears cached states, push timestamps, and in-flight reads on change. */
    fun bindServer(serverId: String?): Boolean =
        synchronized(lock) {
            val clean = serverId?.trim()?.takeIf { it.isNotEmpty() }
            val current = mutableState.value
            if (clean == null || clean == current.serverId) return@synchronized false
            cancelAllReadsLocked()
            pushedAtMs.clear()
            mountedCounts.clear()
            mutableState.value = ConversationWorkerStoreState(serverId = clean)
            true
        }

    /** Registers a mounted conversation line and starts an initial REST read if `worker_v1` is ON. */
    fun acquire(serverId: String, target: WorkerConversationTarget) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        if (!isEnabledForServer(cleanServer)) return
        val key = target.conversationKey()
        synchronized(lock) {
            ensureServerLocked(cleanServer)
            mountedCounts[key] = (mountedCounts[key] ?: 0) + 1
        }
        refresh(cleanServer, target)
    }

    /** Unregisters a mounted conversation line; forgets its state when the last mount leaves. */
    fun release(serverId: String, target: WorkerConversationTarget) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val key = target.conversationKey()
        synchronized(lock) {
            if (mutableState.value.serverId != cleanServer) return@synchronized
            val remaining = (mountedCounts[key] ?: 0) - 1
            if (remaining > 0) {
                mountedCounts[key] = remaining
            } else {
                mountedCounts.remove(key)
                inFlightReads.remove(key)?.cancel()
                forgetLocked(key)
            }
        }
    }

    /** Triggers a fresh REST read (initial open or socket reconnect); no-op when `worker_v1` is OFF. */
    fun refresh(serverId: String, target: WorkerConversationTarget) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        if (!isEnabledForServer(cleanServer)) return
        val key = target.conversationKey()
        val requestStartMs = now()
        val job =
            scope.launch {
                val fetched =
                    try {
                        fetchStates(ServerId(cleanServer), target)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        emptyList()
                    }
                synchronized(lock) {
                    if (mutableState.value.serverId != cleanServer || (mountedCounts[key] ?: 0) <= 0) {
                        return@synchronized
                    }
                    replaceLocked(key, fetched, now(), requestStartMs)
                }
            }
        synchronized(lock) {
            if (mutableState.value.serverId != cleanServer || (mountedCounts[key] ?: 0) <= 0) {
                job.cancel()
                return@synchronized
            }
            inFlightReads.remove(key)?.cancel()
            inFlightReads[key] = job
        }
    }

    /** Applies one live `conversation:worker:state` push (including `None` removals) and records its timestamp. */
    fun apply(
        serverId: String,
        event: ConversationWorkerStateUpdated,
        nowMs: Long = now(),
    ): Boolean =
        synchronized(lock) {
            val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return@synchronized false
            ensureServerLocked(cleanServer)
            val key = event.conversationKey()
            val agentId = event.agentId.value
            val current = mutableState.value
            val previous = current.byConversation[key].orEmpty()
            val nextEntries = previous.toMutableMap()
            if (event.state == ConversationWorkerStateKind.None) {
                nextEntries.remove(agentId)
            } else {
                nextEntries[agentId] = holdWorkerState(previous[agentId], event, nowMs)
            }
            pushedAtMs.getOrPut(key) { mutableMapOf() }[agentId] = nowMs
            val nextByConversation =
                if (nextEntries.isEmpty()) current.byConversation - key else current.byConversation + (key to nextEntries)
            mutableState.value = current.copy(byConversation = nextByConversation)
            true
        }

    /**
     * Applies a REST read snapshot for [key], preserving any agent whose live push arrived at or
     * after [requestStartMs] (including a `None` push that cleared an agent while the read was in flight).
     */
    fun replace(
        serverId: String,
        key: String,
        states: List<ConversationWorkerStateUpdated>,
        nowMs: Long = now(),
        requestStartMs: Long,
    ): Boolean =
        synchronized(lock) {
            val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return@synchronized false
            ensureServerLocked(cleanServer)
            replaceLocked(key, states, nowMs, requestStartMs)
            true
        }

    /** Drops every cached state and push timestamp for [key]. */
    fun forget(serverId: String, key: String) {
        synchronized(lock) {
            if (mutableState.value.serverId != serverId) return@synchronized
            inFlightReads.remove(key)?.cancel()
            forgetLocked(key)
        }
    }

    @RaftCoreInternalApi fun close() {
        synchronized(lock) {
            cancelAllReadsLocked()
            pushedAtMs.clear()
            mountedCounts.clear()
            mutableState.value = ConversationWorkerStoreState()
        }
    }

    private fun ensureServerLocked(cleanServer: String) {
        if (mutableState.value.serverId == cleanServer) return
        cancelAllReadsLocked()
        pushedAtMs.clear()
        mountedCounts.clear()
        mutableState.value = ConversationWorkerStoreState(serverId = cleanServer)
    }

    private fun replaceLocked(
        key: String,
        states: List<ConversationWorkerStateUpdated>,
        nowMs: Long,
        requestStartMs: Long,
    ) {
        val current = mutableState.value
        val previous = current.byConversation[key].orEmpty()
        val pushed = pushedAtMs[key].orEmpty()
        fun isNewerPush(agentId: String): Boolean = (pushed[agentId] ?: Long.MIN_VALUE) >= requestStartMs
        val nextEntries = LinkedHashMap<String, HeldConversationWorkerState>()
        for (incoming in states) {
            val agentId = incoming.agentId.value
            if (incoming.state == ConversationWorkerStateKind.None || isNewerPush(agentId)) continue
            nextEntries[agentId] = holdWorkerState(previous[agentId], incoming, nowMs)
        }
        for ((agentId, held) in previous) {
            if (isNewerPush(agentId)) nextEntries[agentId] = held
        }
        val nextByConversation =
            if (nextEntries.isEmpty()) current.byConversation - key else current.byConversation + (key to nextEntries)
        mutableState.value = current.copy(byConversation = nextByConversation)
    }

    private fun forgetLocked(key: String) {
        pushedAtMs.remove(key)
        val current = mutableState.value
        if (key in current.byConversation) {
            mutableState.value = current.copy(byConversation = current.byConversation - key)
        }
    }

    private fun cancelAllReadsLocked() {
        inFlightReads.values.forEach { it.cancel() }
        inFlightReads.clear()
    }
}
