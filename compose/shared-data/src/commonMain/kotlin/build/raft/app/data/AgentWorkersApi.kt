package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.MemberId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// Worker v1: an agent's Workers in Agent Detail › Activity › Workers
// (botiverse/slock `docs/contracts/worker-snapshot.md` & `docs/contracts/worker-events.md`):
//  - `GET /api/agents/:id/workers` -> `WorkersReadResponse` (`packages/shared/src/workersSnapshot.ts`)
//  - `POST /api/agents/:id/workers/:worker/stop` -> `WorkerStopResponse` (`routes/agents.ts`)
//  - `GET /api/agents/:id/workers/recent?days=7` -> `WorkersRecentResponse` (`packages/shared/src/workerEvents.ts`)
//  - `GET /api/agents/:id/workers/:launchId/timeline` -> `WorkerTimelineResponse` (`packages/shared/src/workerEvents.ts`)

/** Retention window for recent Workers and timeline events (`WORKER_EVENT_RETENTION_MS`, 7 days). */
@RaftCoreInternalApi const val AGENT_WORKER_RECENT_DAYS: Int = 7

/** A Worker launch id (`worker-<name>-<uuid>`, `workerEvents.ts` `launchIdSchema`). */
@RaftCoreInternalApi val AGENT_WORKER_LAUNCH_ID_PATTERN: Regex = Regex("^worker-[A-Za-z0-9_-]{1,160}$")

/** A snapshot received longer ago than this is stale (`WORKERS_SNAPSHOT_STALE_MS`). */
@RaftCoreInternalApi const val AGENT_WORKERS_SNAPSHOT_STALE_MS: Long = 30_000L

/** While the Workers panel is open on screen with live rows, re-read this often (`WORKERS_PANEL_REREAD_MS`). */
@RaftCoreInternalApi const val AGENT_WORKERS_PANEL_REREAD_MS: Long = 30_000L

/** How often an open Workers panel re-judges its rows' age (`FRESHNESS_TICK_MS`). */
@RaftCoreInternalApi const val AGENT_WORKERS_FRESHNESS_TICK_MS: Long = 5_000L

/** After a Stop succeeds, re-read shortly (`AFTER_STOP_READ_MS`). */
@RaftCoreInternalApi const val AGENT_WORKERS_AFTER_STOP_READ_MS: Long = 3_000L

/** Running turn silent in the model this long is stalled (`WORKER_QUIET_AFTER_MS`). */
@RaftCoreInternalApi const val AGENT_WORKER_QUIET_AFTER_MS: Long = 10 * 60_000L

/** Person's message unanswered this long becomes a warning mark (`WORKER_HUMAN_WAITING_AFTER_MS`). */
@RaftCoreInternalApi const val AGENT_WORKER_HUMAN_WAITING_AFTER_MS: Long = 2 * 60_000L

/** A Worker's name (`WORKER_NAME_PATTERN` in `workersSnapshot.ts`): `w1`, `w2`, ... */
@RaftCoreInternalApi val AGENT_WORKER_NAME_PATTERN: Regex = Regex("^w[1-9][0-9]{0,6}$")

@RaftCoreInternalApi enum class AgentWorkerObjectKind(val wireValue: String) {
    Task("task"),
    Thread("thread");

    companion object {
        fun fromWire(raw: String?): AgentWorkerObjectKind? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerState(val wireValue: String) {
    Starting("starting"),
    Running("running");

    companion object {
        fun fromWire(raw: String?): AgentWorkerState? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerDoing(val wireValue: String) {
    Starting("starting"),
    Model("model"),
    Tool("tool"),
    Idle("idle");

    companion object {
        fun fromWire(raw: String?): AgentWorkerDoing? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class WorkerConversationHoldingMode(val wireValue: String) {
    Full("full"),
    Post("post");

    companion object {
        fun fromWire(raw: String?): WorkerConversationHoldingMode? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

/**
 * One conversation a Worker holds (`workerHoldingSchema` in `packages/shared/src/workerEvents.ts`):
 * `full` (delegated, with `objectKind`) or `post` (post-only: may post there, stays parent's).
 */
@RaftCoreInternalApi data class WorkerConversationHolding(
    val mode: WorkerConversationHoldingMode,
    val objectRef: String,
    val objectKind: AgentWorkerObjectKind? = null,
)

@RaftCoreInternalApi data class AgentWorkerProgress(
    val text: String,
    val at: String,
    val atEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(at),
)

/** One live Worker row from `GET /api/agents/:id/workers` (`WorkersReadRow`). */
@RaftCoreInternalApi data class AgentWorkerRow(
    val agentId: MemberId,
    /** Worker name: `w1`, `w2`, ... */
    val worker: String,
    /** First full conversation ref held by this Worker (`#eng:#7` or `#eng:abcd1234`), or null when it holds none. */
    val objectRef: String? = null,
    val objectKind: AgentWorkerObjectKind? = null,
    val taskTitle: String? = null,
    val runtime: String? = null,
    /** Runtime-native model id launched with, e.g. `claude-opus-5-5`; null when omitted. */
    val model: String? = null,
    /** Hand-out rule (`--rule`); kept in model for wire completeness, never shown in v1 UI. */
    val rule: String? = null,
    val since: String,
    val sinceEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(since),
    val state: AgentWorkerState,
    val progress: AgentWorkerProgress? = null,
    // Observability fields (`docs/contracts/worker-events.md`):
    val launchId: String? = null,
    val turnStartedAt: String? = null,
    val lastEventAt: String? = null,
    val lastEventAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(lastEventAt),
    val doing: AgentWorkerDoing? = null,
    val tool: String? = null,
    val queued: Int? = null,
    val oldestWaitingAt: String? = null,
    val oldestWaitingAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(oldestWaitingAt),
    /** Server-computed: `doing == model` and silent >= `WORKER_QUIET_AFTER_MS`. */
    val stalled: Boolean = false,
    /** Server-computed: `doing == tool`, silent this long in milliseconds. */
    val inToolForMs: Long? = null,
    /** Server-computed: sender handle (without `@`) of the oldest unanswered person's message. */
    val waitingFrom: String? = null,
    /** Every conversation this Worker holds (`full` and `post`), in order; null on pre-holdings servers. */
    val conversations: List<WorkerConversationHolding>? = null,
)

/**
 * Web `workerRowFullObjects` (`agentWorkers.ts`): every conversation this live Worker holds in full
 * (from `conversations` when present, else legacy single `objectRef`; empty when it holds none).
 */
@RaftCoreInternalApi fun AgentWorkerRow.fullObjectRefs(): List<String> =
    conversations?.mapNotNull { holding ->
        if (holding.mode == WorkerConversationHoldingMode.Full) holding.objectRef else null
    } ?: listOfNotNull(objectRef?.trim()?.takeIf { it.isNotEmpty() })

/** Every post-only conversation this live Worker may also post in (`mode == "post"`). */
@RaftCoreInternalApi fun AgentWorkerRow.postObjectRefs(): List<String> =
    conversations?.mapNotNull { holding ->
        if (holding.mode == WorkerConversationHoldingMode.Post) holding.objectRef else null
    } ?: emptyList()

/** Parsed answer from `GET /api/agents/:id/workers` (`WorkersReadResponse`). */
@RaftCoreInternalApi sealed interface AgentWorkersReadSnapshot {
    data object Unknown : AgentWorkersReadSnapshot

    data class Known(
        val receivedAt: String,
        val ageMs: Long,
        val stale: Boolean,
        val digest: String?,
        val workers: List<AgentWorkerRow>,
    ) : AgentWorkersReadSnapshot
}

/** Parsed answer from `POST /api/agents/:id/workers/:worker/stop` (`WorkerStopOutcome`). */
@RaftCoreInternalApi enum class AgentWorkerStopOutcome(val wireValue: String) {
    Stopped("stopped"),
    NotFound("not_found");
}

private val agentWorkersJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class AgentWorkerProgressDto(
    val text: String? = null,
    val at: String? = null,
)

@Serializable
private data class WorkerConversationHoldingDto(
    val mode: String? = null,
    @SerialName("object")
    val objectRef: String? = null,
    val objectKind: String? = null,
) {
    fun toDomain(): WorkerConversationHolding? {
        val parsedMode = WorkerConversationHoldingMode.fromWire(mode?.trim()) ?: return null
        val cleanObject = objectRef?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsedKind =
            if (parsedMode == WorkerConversationHoldingMode.Post) {
                null
            } else {
                AgentWorkerObjectKind.fromWire(objectKind?.trim())
            }
        return WorkerConversationHolding(
            mode = parsedMode,
            objectRef = cleanObject,
            objectKind = parsedKind,
        )
    }
}

@Serializable
private data class AgentWorkerRowDto(
    val agentId: String? = null,
    val worker: String? = null,
    @SerialName("object")
    val objectRef: String? = null,
    val objectKind: String? = null,
    val conversations: List<WorkerConversationHoldingDto>? = null,
    val taskTitle: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val rule: String? = null,
    val since: String? = null,
    val state: String? = null,
    val progress: AgentWorkerProgressDto? = null,
    val launchId: String? = null,
    val turnStartedAt: String? = null,
    val lastEventAt: String? = null,
    val doing: String? = null,
    val tool: String? = null,
    val queued: Int? = null,
    val oldestWaitingAt: String? = null,
    val stalled: Boolean? = null,
    val inToolForMs: Long? = null,
    val waitingFrom: String? = null,
) {
    fun toDomain(): AgentWorkerRow? {
        val cleanAgentId = agentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanWorker = worker?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsedConversations = conversations?.mapNotNull { it.toDomain() }
        val firstFull = parsedConversations?.firstOrNull { it.mode == WorkerConversationHoldingMode.Full }
        val cleanObject = objectRef?.trim()?.takeIf { it.isNotEmpty() } ?: firstFull?.objectRef
        val kind = AgentWorkerObjectKind.fromWire(objectKind?.trim()) ?: firstFull?.objectKind
        val cleanSince = since?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsedState = AgentWorkerState.fromWire(state?.trim()) ?: return null
        val parsedProgress =
            progress?.let { dto ->
                val text = dto.text?.trim()?.takeIf { it.isNotEmpty() }
                val at = dto.at?.trim()?.takeIf { it.isNotEmpty() }
                if (text != null && at != null) AgentWorkerProgress(text = text, at = at) else null
            }
        val cleanLastEventAt = lastEventAt?.trim()?.takeIf { it.isNotEmpty() }
        val cleanOldestWaitingAt = oldestWaitingAt?.trim()?.takeIf { it.isNotEmpty() }
        return AgentWorkerRow(
            agentId = MemberId(cleanAgentId),
            worker = cleanWorker,
            objectRef = cleanObject,
            objectKind = kind,
            taskTitle = taskTitle?.trim()?.takeIf { it.isNotEmpty() },
            runtime = runtime?.trim()?.takeIf { it.isNotEmpty() },
            model = model?.trim()?.takeIf { it.isNotEmpty() },
            rule = rule?.trim()?.takeIf { it.isNotEmpty() },
            since = cleanSince,
            state = parsedState,
            progress = parsedProgress,
            launchId = launchId?.trim()?.takeIf { it.isNotEmpty() },
            turnStartedAt = turnStartedAt?.trim()?.takeIf { it.isNotEmpty() },
            lastEventAt = cleanLastEventAt,
            doing = AgentWorkerDoing.fromWire(doing?.trim()),
            tool = tool?.trim()?.takeIf { it.isNotEmpty() },
            queued = queued?.takeIf { it >= 0 },
            oldestWaitingAt = cleanOldestWaitingAt,
            stalled = stalled == true,
            inToolForMs = inToolForMs?.takeIf { it >= 0L },
            waitingFrom = waitingFrom?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() },
            conversations = parsedConversations,
        )
    }
}

@Serializable
private data class AgentWorkersReadResponseDto(
    val status: String? = null,
    val receivedAt: String? = null,
    val ageMs: Long? = null,
    val stale: Boolean? = null,
    val digest: String? = null,
    val workers: List<AgentWorkerRowDto>? = null,
)

@Serializable
private data class AgentWorkerStopResponseDto(
    val outcome: String? = null,
)

/** Parses a 2xx `GET /api/agents/:id/workers` body (`WorkersReadResponse`). */
@RaftCoreInternalApi fun parseSharedAgentWorkersRead(bodyText: String): AgentWorkersReadSnapshot? =
    runCatching {
        val dto = agentWorkersJson.decodeFromString(AgentWorkersReadResponseDto.serializer(), bodyText)
        when (dto.status?.trim()) {
            "unknown" -> AgentWorkersReadSnapshot.Unknown
            "known" -> {
                val receivedAt = dto.receivedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return@runCatching null
                val ageMs = (dto.ageMs ?: 0L).coerceAtLeast(0L)
                val stale = dto.stale ?: (ageMs > AGENT_WORKERS_SNAPSHOT_STALE_MS)
                val digest = dto.digest?.trim()?.takeIf { it.isNotEmpty() }
                val workers = dto.workers.orEmpty().mapNotNull { it.toDomain() }
                AgentWorkersReadSnapshot.Known(
                    receivedAt = receivedAt,
                    ageMs = ageMs,
                    stale = stale,
                    digest = digest,
                    workers = workers,
                )
            }
            else -> null
        }
    }.getOrNull()

/**
 * Parses a 2xx `POST /api/agents/:id/workers/:worker/stop` body. Like web `stopWorker`,
 * `"stopped"` is [AgentWorkerStopOutcome.Stopped] and anything else is [AgentWorkerStopOutcome.NotFound].
 */
@RaftCoreInternalApi fun parseSharedAgentWorkerStopOutcome(bodyText: String): AgentWorkerStopOutcome? =
    runCatching {
        when (agentWorkersJson.decodeFromString(AgentWorkerStopResponseDto.serializer(), bodyText).outcome) {
            AgentWorkerStopOutcome.Stopped.wireValue -> AgentWorkerStopOutcome.Stopped
            else -> AgentWorkerStopOutcome.NotFound
        }
    }.getOrNull()

// ── Worker observability: Recently ended (7 days) & Worker Timeline Detail ──
// Wire shapes verified against botiverse/slock `packages/shared/src/workerEvents.ts` and
// `packages/web/src/components/agent/AgentWorkerTimeline.tsx`.

/** Why a Worker ended (`WORKER_EVENT_END_REASONS` in `workerEvents.ts`). */
@RaftCoreInternalApi enum class AgentWorkerEndReason(val wireValue: String) {
    Exited("exited"),
    Stopped("stopped"),
    ParentRestarted("parent_restarted"),
    DaemonShutdown("daemon_shutdown"),
    StartFailed("start_failed"),
    Done("done"),
    WaitingOn("waiting_on"),
    StoppedBy("stopped_by"),
    StoppedByPerson("stopped_by_person"),
    Expired("expired"),
    TurnFailed("turn_failed");

    /** Web `endedWithError`: error chip / warning timeline dot. */
    fun isErrorReason(): Boolean =
        this == Exited || this == StartFailed || this == TurnFailed

    companion object {
        fun fromWire(raw: String?): AgentWorkerEndReason? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

/** One event kind on a Worker's timeline (`WORKER_EVENT_KINDS` in `workerEvents.ts`). */
@RaftCoreInternalApi enum class AgentWorkerEventKind(val wireValue: String) {
    HandedOut("handed_out"),
    Delegated("delegated"),
    Undelegated("undelegated"),
    Delivered("delivered"),
    TurnStarted("turn_started"),
    TurnEnded("turn_ended"),
    Reported("reported"),
    Quiet("quiet"),
    Active("active"),
    HumanWaiting("human_waiting"),
    HandedToParent("handed_to_parent"),
    HumanSettled("human_settled"),
    Ended("ended");

    companion object {
        fun fromWire(raw: String?): AgentWorkerEventKind? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerDeliverySender(val wireValue: String) {
    Human("human"),
    Agent("agent"),
    System("system");

    companion object {
        fun fromWire(raw: String?): AgentWorkerDeliverySender? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerDeliveryVia(val wireValue: String) {
    NewTurn("new_turn"),
    Steered("steered"),
    Queued("queued");

    companion object {
        fun fromWire(raw: String?): AgentWorkerDeliveryVia? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerTurnOutcome(val wireValue: String) {
    Completed("completed"),
    Failed("failed"),
    Interrupted("interrupted");

    companion object {
        fun fromWire(raw: String?): AgentWorkerTurnOutcome? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerReportKind(val wireValue: String) {
    Progress("progress"),
    NeedsDecision("needs_decision"),
    NeedsParent("needs_parent"),
    OutOfScope("out_of_scope");

    companion object {
        fun fromWire(raw: String?): AgentWorkerReportKind? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerQuietDoing(val wireValue: String) {
    Model("model"),
    Tool("tool");

    companion object {
        fun fromWire(raw: String?): AgentWorkerQuietDoing? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerHandedToParentReason(val wireValue: String) {
    Overdue("overdue"),
    Ended("ended");

    companion object {
        fun fromWire(raw: String?): AgentWorkerHandedToParentReason? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerSettledBy(val wireValue: String) {
    Worker("worker"),
    Parent("parent");

    companion object {
        fun fromWire(raw: String?): AgentWorkerSettledBy? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi enum class AgentWorkerSettledHow(val wireValue: String) {
    Replied("replied"),
    Reacted("reacted"),
    Deleted("deleted"),
    AgentLeft("agent_left"),
    Expired24h("expired_24h");

    companion object {
        fun fromWire(raw: String?): AgentWorkerSettledHow? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

@RaftCoreInternalApi data class AgentWorkerEventTokens(
    val input: Long,
    val output: Long,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
) {
    val totalInputOutput: Long
        get() = input + output
}

/** One Worker launch of the last 7 days (`GET /api/agents/:id/workers/recent?days=7`, `WorkersRecentResponse`). */
@RaftCoreInternalApi data class AgentRecentWorkerRow(
    val launchId: String,
    val worker: String,
    val objectRef: String? = null,
    val objectKind: AgentWorkerObjectKind? = null,
    val startedAt: String,
    val startedAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(startedAt),
    /** Null while the Worker is still running (no `ended` received yet). */
    val endedAt: String? = null,
    val endedAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(endedAt),
    val reason: AgentWorkerEndReason? = null,
    val handedBack: Int? = null,
    val stoppedBy: String? = null,
    val waitingOn: String? = null,
    val noteFirstLine: String? = null,
)

/** Launch metadata used to open one Worker's timeline (web `OpenWorkerTimeline`). */
@RaftCoreInternalApi data class AgentWorkerOpenTimeline(
    val launchId: String,
    val worker: String,
    /** First full conversation ref, or null for a Worker that holds no conversation. */
    val objectRef: String? = null,
)

/** Resolved conversation target for a timeline `"View message"` link (`GET /messages/context/:id`). */
@RaftCoreInternalApi data class AgentWorkerMessageTarget(
    val channelId: String,
    val messageId: String,
)

/** Typed payload of each `WorkerEventKind` on `GET /api/agents/:id/workers/:launchId/timeline`. */
@RaftCoreInternalApi sealed interface AgentWorkerTimelineEventData {
    data class HandedOut(
        val objectRef: String? = null,
        val objectKind: AgentWorkerObjectKind? = null,
        val runtime: String? = null,
        val model: String? = null,
        val rule: String? = null,
        val conversations: List<WorkerConversationHolding>? = null,
    ) : AgentWorkerTimelineEventData {
        fun fullObjectRefs(): List<String> =
            conversations?.mapNotNull { holding ->
                if (holding.mode == WorkerConversationHoldingMode.Full) holding.objectRef else null
            } ?: listOfNotNull(objectRef?.trim()?.takeIf { it.isNotEmpty() })
    }

    data class Delegated(
        val mode: WorkerConversationHoldingMode,
        val objectRef: String,
        val objectKind: AgentWorkerObjectKind? = null,
    ) : AgentWorkerTimelineEventData

    data class Undelegated(
        val objectRef: String,
        val mode: WorkerConversationHoldingMode? = null,
        val objectKind: AgentWorkerObjectKind? = null,
    ) : AgentWorkerTimelineEventData

    data class Delivered(
        val messageId: String,
        val sender: AgentWorkerDeliverySender,
        val via: AgentWorkerDeliveryVia,
    ) : AgentWorkerTimelineEventData

    data class TurnStarted(
        val turn: Int,
        val inputs: List<String> = emptyList(),
    ) : AgentWorkerTimelineEventData

    data class TurnEnded(
        val turn: Int,
        val outcome: AgentWorkerTurnOutcome,
        val durationMs: Long,
        val toolCalls: Int,
        val sent: List<String> = emptyList(),
        val tokens: AgentWorkerEventTokens? = null,
    ) : AgentWorkerTimelineEventData

    data class Reported(
        val reportKind: AgentWorkerReportKind,
        /** Retained only for [AgentWorkerReportKind.Progress]; never shown for other kinds. */
        val text: String? = null,
    ) : AgentWorkerTimelineEventData

    data class Quiet(
        val doing: AgentWorkerQuietDoing,
        val tool: String? = null,
        val quietSinceAt: String,
        val quietSinceAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(quietSinceAt),
    ) : AgentWorkerTimelineEventData

    data class Active(
        val doing: AgentWorkerQuietDoing,
        val tool: String? = null,
        val quietSinceAt: String,
        val quietSinceAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(quietSinceAt),
    ) : AgentWorkerTimelineEventData

    data class HumanWaiting(
        val messageId: String,
        val sinceAt: String,
        val sinceAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(sinceAt),
    ) : AgentWorkerTimelineEventData

    data class HandedToParent(
        val messageId: String,
        val reason: AgentWorkerHandedToParentReason,
        val waitedMs: Long,
    ) : AgentWorkerTimelineEventData

    data class HumanSettled(
        val messageId: String,
        val by: AgentWorkerSettledBy?,
        val how: AgentWorkerSettledHow,
        val waitedMs: Long,
    ) : AgentWorkerTimelineEventData

    data class Ended(
        val reason: AgentWorkerEndReason,
        val stoppedBy: String? = null,
        val waitingOn: String? = null,
        val note: String? = null,
        val handedBack: Int = 0,
        val durationMs: Long = 0L,
        val tokens: AgentWorkerEventTokens? = null,
    ) : AgentWorkerTimelineEventData
}

/** One chronological event on `GET /api/agents/:id/workers/:launchId/timeline`. */
@RaftCoreInternalApi data class AgentWorkerTimelineEvent(
    val seq: Int,
    val kind: AgentWorkerEventKind,
    val occurredAt: String,
    val occurredAtEpochMillis: Long? = parseRaftTimestampEpochMillisOrNull(occurredAt),
    val data: AgentWorkerTimelineEventData,
)

/** Parsed answer from `GET /api/agents/:id/workers/:launchId/timeline` (`WorkerTimelineResponse`). */
@RaftCoreInternalApi data class AgentWorkerTimelineSnapshot(
    val launchId: String,
    val events: List<AgentWorkerTimelineEvent>,
)

/** Timeline dot variant matching raft-ui `TimelineItemVariant` in `AgentWorkerTimeline.tsx`. */
@RaftCoreInternalApi enum class AgentWorkerTimelineVariant {
    Default,
    Information,
    Success,
    Warning,
}

/** Header state chip matching web `STATE_CHIP` / `stateOf` in `AgentWorkerTimeline.tsx`. */
@RaftCoreInternalApi enum class AgentWorkerTimelineChipState {
    Working,
    Finished,
    Stopped,
    Error,
    Ended,
}

@RaftCoreInternalApi fun agentWorkerTimelineChipState(
    isLive: Boolean,
    reason: AgentWorkerEndReason?,
): AgentWorkerTimelineChipState =
    when {
        isLive -> AgentWorkerTimelineChipState.Working
        reason == null -> AgentWorkerTimelineChipState.Ended
        reason == AgentWorkerEndReason.Done -> AgentWorkerTimelineChipState.Finished
        reason.isErrorReason() -> AgentWorkerTimelineChipState.Error
        else -> AgentWorkerTimelineChipState.Stopped
    }

/** One rendered timeline item after folding finished `turn_started` events and dropping `needs_parent`. */
@RaftCoreInternalApi data class AgentWorkerTimelineDisplayItem(
    val seq: Int,
    val kind: AgentWorkerEventKind,
    val occurredAt: String,
    val occurredAtEpochMillis: Long?,
    val variant: AgentWorkerTimelineVariant,
    val warning: Boolean,
    val data: AgentWorkerTimelineEventData,
    /** Server-clock duration for `quiet`, `active`, and `human_waiting` (`occurredAt - sinceAt`). */
    val elapsedMs: Long? = null,
    /** Start timestamp of the `turn_started` folded into a `turn_ended`, if present. */
    val foldedTurnStartedAt: String? = null,
)

/**
 * Web `RecentWorkersGroup` filter: only launches that have ended (`endedAt != null`), deduped by
 * `launchId` while preserving newest-first server order.
 */
@RaftCoreInternalApi fun recentEndedAgentWorkers(rows: List<AgentRecentWorkerRow>): List<AgentRecentWorkerRow> {
    val seen = LinkedHashMap<String, AgentRecentWorkerRow>()
    for (row in rows) {
        if (row.endedAt != null && row.launchId.isNotBlank() && row.launchId !in seen) {
            seen[row.launchId] = row
        }
    }
    return seen.values.toList()
}

/**
 * Web `useWorkersPanelTimeline` `refreshKey`: changes when the open launch's live row moves on,
 * disappears, or its ended record lands.
 */
@RaftCoreInternalApi fun agentWorkerTimelineRefreshKey(
    live: AgentWorkerRow?,
    ended: AgentRecentWorkerRow?,
): String =
    if (live != null) {
        listOf(
            live.state.wireValue,
            live.lastEventAt.orEmpty(),
            live.doing?.wireValue.orEmpty(),
            live.tool.orEmpty(),
            live.queued?.toString().orEmpty(),
            live.progress?.at.orEmpty(),
            live.oldestWaitingAt.orEmpty(),
            live.conversations?.joinToString(",") { "${it.mode.wireValue}:${it.objectRef}" }.orEmpty(),
        ).joinToString("|")
    } else {
        "ended:${ended?.endedAt ?: "pending"}"
    }

/**
 * Pure event projection matching web `AgentWorkerTimeline.tsx` `eventItems`:
 * - A finished turn's `turn_started` folds into its `turn_ended` (one item per turn).
 * - A still-running turn (no matching `turn_ended`) keeps its `turn_started`.
 * - `reported` with `needs_parent` is omitted; only `progress` retains text.
 * - `quiet` in the model is a warning; in a tool is not.
 * - `human_waiting` and `human_settled` (`expired_24h`) are warnings.
 */
@RaftCoreInternalApi fun projectAgentWorkerTimelineEvents(
    events: List<AgentWorkerTimelineEvent>,
): List<AgentWorkerTimelineDisplayItem> {
    val endedTurns = HashSet<Int>()
    val turnStartsByTurn = HashMap<Int, AgentWorkerTimelineEvent>()
    for (event in events) {
        when (val data = event.data) {
            is AgentWorkerTimelineEventData.TurnEnded -> endedTurns += data.turn
            is AgentWorkerTimelineEventData.TurnStarted -> {
                if (data.turn !in turnStartsByTurn) {
                    turnStartsByTurn[data.turn] = event
                }
            }
            else -> Unit
        }
    }
    fun between(fromMillis: Long?, toMillis: Long?): Long =
        if (fromMillis != null && toMillis != null) maxOf(0L, toMillis - fromMillis) else 0L

    val items = ArrayList<AgentWorkerTimelineDisplayItem>(events.size)
    for (event in events) {
        when (val data = event.data) {
            is AgentWorkerTimelineEventData.HandedOut ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = AgentWorkerTimelineVariant.Default,
                    warning = false,
                    data = data,
                )
            is AgentWorkerTimelineEventData.Delegated ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = AgentWorkerTimelineVariant.Default,
                    warning = false,
                    data = data,
                )
            is AgentWorkerTimelineEventData.Undelegated ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = AgentWorkerTimelineVariant.Default,
                    warning = false,
                    data = data,
                )
            is AgentWorkerTimelineEventData.Delivered ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = AgentWorkerTimelineVariant.Default,
                    warning = false,
                    data = data,
                )
            is AgentWorkerTimelineEventData.TurnStarted -> {
                if (data.turn !in endedTurns) {
                    items += AgentWorkerTimelineDisplayItem(
                        seq = event.seq,
                        kind = event.kind,
                        occurredAt = event.occurredAt,
                        occurredAtEpochMillis = event.occurredAtEpochMillis,
                        variant = AgentWorkerTimelineVariant.Information,
                        warning = false,
                        data = data,
                    )
                }
            }
            is AgentWorkerTimelineEventData.TurnEnded ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant =
                        if (data.outcome == AgentWorkerTurnOutcome.Completed) AgentWorkerTimelineVariant.Success
                        else AgentWorkerTimelineVariant.Warning,
                    warning = false,
                    data = data,
                    foldedTurnStartedAt = turnStartsByTurn[data.turn]?.occurredAt,
                )
            is AgentWorkerTimelineEventData.Reported -> {
                if (data.reportKind != AgentWorkerReportKind.NeedsParent) {
                    val safeData =
                        if (data.reportKind == AgentWorkerReportKind.Progress) data
                        else data.copy(text = null)
                    items += AgentWorkerTimelineDisplayItem(
                        seq = event.seq,
                        kind = event.kind,
                        occurredAt = event.occurredAt,
                        occurredAtEpochMillis = event.occurredAtEpochMillis,
                        variant = AgentWorkerTimelineVariant.Default,
                        warning = false,
                        data = safeData,
                    )
                }
            }
            is AgentWorkerTimelineEventData.Quiet -> {
                val isWarning = data.doing == AgentWorkerQuietDoing.Model
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = if (isWarning) AgentWorkerTimelineVariant.Warning else AgentWorkerTimelineVariant.Default,
                    warning = isWarning,
                    data = data,
                    elapsedMs = between(data.quietSinceAtEpochMillis, event.occurredAtEpochMillis),
                )
            }
            is AgentWorkerTimelineEventData.Active ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = AgentWorkerTimelineVariant.Default,
                    warning = false,
                    data = data,
                    elapsedMs = between(data.quietSinceAtEpochMillis, event.occurredAtEpochMillis),
                )
            is AgentWorkerTimelineEventData.HumanWaiting ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = AgentWorkerTimelineVariant.Warning,
                    warning = true,
                    data = data,
                    elapsedMs = between(data.sinceAtEpochMillis, event.occurredAtEpochMillis),
                )
            is AgentWorkerTimelineEventData.HandedToParent ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = AgentWorkerTimelineVariant.Default,
                    warning = false,
                    data = data,
                )
            is AgentWorkerTimelineEventData.HumanSettled -> {
                val unanswered = data.how == AgentWorkerSettledHow.Expired24h
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant = if (unanswered) AgentWorkerTimelineVariant.Warning else AgentWorkerTimelineVariant.Success,
                    warning = unanswered,
                    data = data,
                )
            }
            is AgentWorkerTimelineEventData.Ended ->
                items += AgentWorkerTimelineDisplayItem(
                    seq = event.seq,
                    kind = event.kind,
                    occurredAt = event.occurredAt,
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    variant =
                        when {
                            data.reason == AgentWorkerEndReason.Done -> AgentWorkerTimelineVariant.Success
                            data.reason.isErrorReason() -> AgentWorkerTimelineVariant.Warning
                            else -> AgentWorkerTimelineVariant.Default
                        },
                    warning = false,
                    data = data,
                )
        }
    }
    return items
}

// ── Wire DTOs & parsers for /workers/recent and /workers/:launchId/timeline ──

@Serializable
private data class AgentRecentWorkerRowDto(
    val launchId: String? = null,
    val worker: String? = null,
    @SerialName("object")
    val objectRef: String? = null,
    val objectKind: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val reason: String? = null,
    val handedBack: Int? = null,
    val stoppedBy: String? = null,
    val waitingOn: String? = null,
    val noteFirstLine: String? = null,
) {
    fun toDomain(): AgentRecentWorkerRow? {
        val cleanLaunchId = launchId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanWorker = worker?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanObject = objectRef?.trim()?.takeIf { it.isNotEmpty() }
        val kind = AgentWorkerObjectKind.fromWire(objectKind?.trim())
        val cleanStartedAt = startedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanEndedAt = endedAt?.trim()?.takeIf { it.isNotEmpty() }
        return AgentRecentWorkerRow(
            launchId = cleanLaunchId,
            worker = cleanWorker,
            objectRef = cleanObject,
            objectKind = kind,
            startedAt = cleanStartedAt,
            endedAt = cleanEndedAt,
            reason = AgentWorkerEndReason.fromWire(reason?.trim()),
            handedBack = handedBack?.takeIf { it >= 0 },
            stoppedBy = stoppedBy?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() },
            waitingOn = waitingOn?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() },
            noteFirstLine = noteFirstLine?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

@Serializable
private data class AgentRecentWorkersResponseDto(
    val workers: List<AgentRecentWorkerRowDto>? = null,
)

@Serializable
private data class AgentWorkerEventTokensDto(
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
) {
    fun toDomain(): AgentWorkerEventTokens? {
        val cleanInput = input?.takeIf { it >= 0L } ?: return null
        val cleanOutput = output?.takeIf { it >= 0L } ?: return null
        return AgentWorkerEventTokens(
            input = cleanInput,
            output = cleanOutput,
            cacheRead = cacheRead?.takeIf { it >= 0L },
            cacheWrite = cacheWrite?.takeIf { it >= 0L },
        )
    }
}

@Serializable
private data class HandedOutEventDataDto(
    @SerialName("object")
    val objectRef: String? = null,
    val objectKind: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val rule: String? = null,
    val conversations: List<WorkerConversationHoldingDto>? = null,
)

@Serializable
private data class DeliveredEventDataDto(
    val messageId: String? = null,
    val sender: String? = null,
    val via: String? = null,
)

@Serializable
private data class TurnStartedEventDataDto(
    val turn: Int? = null,
    val inputs: List<String>? = null,
)

@Serializable
private data class TurnEndedEventDataDto(
    val turn: Int? = null,
    val outcome: String? = null,
    val durationMs: Long? = null,
    val toolCalls: Int? = null,
    val sent: List<String>? = null,
    val tokens: AgentWorkerEventTokensDto? = null,
)

@Serializable
private data class ReportedEventDataDto(
    val reportKind: String? = null,
    val text: String? = null,
)

@Serializable
private data class QuietOrActiveEventDataDto(
    val doing: String? = null,
    val tool: String? = null,
    val quietSinceAt: String? = null,
)

@Serializable
private data class HumanWaitingEventDataDto(
    val messageId: String? = null,
    val sinceAt: String? = null,
)

@Serializable
private data class HandedToParentEventDataDto(
    val messageId: String? = null,
    val reason: String? = null,
    val waitedMs: Long? = null,
)

@Serializable
private data class HumanSettledEventDataDto(
    val messageId: String? = null,
    val by: String? = null,
    val how: String? = null,
    val waitedMs: Long? = null,
)

@Serializable
private data class EndedEventDataDto(
    val reason: String? = null,
    val stoppedBy: String? = null,
    val waitingOn: String? = null,
    val note: String? = null,
    val handedBack: Int? = null,
    val durationMs: Long? = null,
    val tokens: AgentWorkerEventTokensDto? = null,
)

@Serializable
private data class AgentWorkerTimelineEventDto(
    val seq: Int? = null,
    val kind: String? = null,
    val data: JsonObject? = null,
    val occurredAt: String? = null,
) {
    fun toDomain(): AgentWorkerTimelineEvent? {
        val cleanSeq = seq?.takeIf { it > 0 } ?: return null
        val parsedKind = AgentWorkerEventKind.fromWire(kind?.trim()) ?: return null
        val cleanOccurredAt = occurredAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rawData = data ?: return null
        val parsedData: AgentWorkerTimelineEventData =
            runCatching {
                when (parsedKind) {
                    AgentWorkerEventKind.HandedOut -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(HandedOutEventDataDto.serializer(), rawData)
                        val parsedConversations = dto.conversations?.mapNotNull { it.toDomain() }
                        val firstFull = parsedConversations?.firstOrNull { it.mode == WorkerConversationHoldingMode.Full }
                        val obj = dto.objectRef?.trim()?.takeIf { it.isNotEmpty() } ?: firstFull?.objectRef
                        val objKind = AgentWorkerObjectKind.fromWire(dto.objectKind?.trim()) ?: firstFull?.objectKind
                        AgentWorkerTimelineEventData.HandedOut(
                            objectRef = obj,
                            objectKind = objKind,
                            runtime = dto.runtime?.trim()?.takeIf { it.isNotEmpty() },
                            model = dto.model?.trim()?.takeIf { it.isNotEmpty() },
                            rule = dto.rule?.trim()?.takeIf { it.isNotEmpty() },
                            conversations = parsedConversations,
                        )
                    }
                    AgentWorkerEventKind.Delegated -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(WorkerConversationHoldingDto.serializer(), rawData)
                        val holding = dto.toDomain() ?: return null
                        AgentWorkerTimelineEventData.Delegated(
                            mode = holding.mode,
                            objectRef = holding.objectRef,
                            objectKind = holding.objectKind,
                        )
                    }
                    AgentWorkerEventKind.Undelegated -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(WorkerConversationHoldingDto.serializer(), rawData)
                        val cleanObject = dto.objectRef?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        val parsedMode = WorkerConversationHoldingMode.fromWire(dto.mode?.trim())
                        val parsedKind =
                            if (parsedMode == WorkerConversationHoldingMode.Post) {
                                null
                            } else {
                                AgentWorkerObjectKind.fromWire(dto.objectKind?.trim())
                            }
                        AgentWorkerTimelineEventData.Undelegated(
                            objectRef = cleanObject,
                            mode = parsedMode,
                            objectKind = parsedKind,
                        )
                    }
                    AgentWorkerEventKind.Delivered -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(DeliveredEventDataDto.serializer(), rawData)
                        val messageId = dto.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        val sender = AgentWorkerDeliverySender.fromWire(dto.sender?.trim()) ?: return null
                        val via = AgentWorkerDeliveryVia.fromWire(dto.via?.trim()) ?: return null
                        AgentWorkerTimelineEventData.Delivered(messageId = messageId, sender = sender, via = via)
                    }
                    AgentWorkerEventKind.TurnStarted -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(TurnStartedEventDataDto.serializer(), rawData)
                        val turn = dto.turn?.takeIf { it > 0 } ?: return null
                        AgentWorkerTimelineEventData.TurnStarted(
                            turn = turn,
                            inputs = dto.inputs.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
                        )
                    }
                    AgentWorkerEventKind.TurnEnded -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(TurnEndedEventDataDto.serializer(), rawData)
                        val turn = dto.turn?.takeIf { it > 0 } ?: return null
                        val outcome = AgentWorkerTurnOutcome.fromWire(dto.outcome?.trim()) ?: return null
                        val durationMs = dto.durationMs?.takeIf { it >= 0L } ?: 0L
                        val toolCalls = dto.toolCalls?.takeIf { it >= 0 } ?: 0
                        AgentWorkerTimelineEventData.TurnEnded(
                            turn = turn,
                            outcome = outcome,
                            durationMs = durationMs,
                            toolCalls = toolCalls,
                            sent = dto.sent.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
                            tokens = dto.tokens?.toDomain(),
                        )
                    }
                    AgentWorkerEventKind.Reported -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(ReportedEventDataDto.serializer(), rawData)
                        val reportKind = AgentWorkerReportKind.fromWire(dto.reportKind?.trim()) ?: return null
                        val progressText =
                            if (reportKind == AgentWorkerReportKind.Progress) {
                                dto.text?.trim()?.takeIf { it.isNotEmpty() }
                            } else {
                                null
                            }
                        AgentWorkerTimelineEventData.Reported(reportKind = reportKind, text = progressText)
                    }
                    AgentWorkerEventKind.Quiet -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(QuietOrActiveEventDataDto.serializer(), rawData)
                        val doing = AgentWorkerQuietDoing.fromWire(dto.doing?.trim()) ?: return null
                        val quietSinceAt = dto.quietSinceAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        AgentWorkerTimelineEventData.Quiet(
                            doing = doing,
                            tool = dto.tool?.trim()?.takeIf { it.isNotEmpty() },
                            quietSinceAt = quietSinceAt,
                        )
                    }
                    AgentWorkerEventKind.Active -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(QuietOrActiveEventDataDto.serializer(), rawData)
                        val doing = AgentWorkerQuietDoing.fromWire(dto.doing?.trim()) ?: return null
                        val quietSinceAt = dto.quietSinceAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        AgentWorkerTimelineEventData.Active(
                            doing = doing,
                            tool = dto.tool?.trim()?.takeIf { it.isNotEmpty() },
                            quietSinceAt = quietSinceAt,
                        )
                    }
                    AgentWorkerEventKind.HumanWaiting -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(HumanWaitingEventDataDto.serializer(), rawData)
                        val messageId = dto.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        val sinceAt = dto.sinceAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        AgentWorkerTimelineEventData.HumanWaiting(messageId = messageId, sinceAt = sinceAt)
                    }
                    AgentWorkerEventKind.HandedToParent -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(HandedToParentEventDataDto.serializer(), rawData)
                        val messageId = dto.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        val reason = AgentWorkerHandedToParentReason.fromWire(dto.reason?.trim()) ?: return null
                        val waitedMs = dto.waitedMs?.takeIf { it >= 0L } ?: 0L
                        AgentWorkerTimelineEventData.HandedToParent(
                            messageId = messageId,
                            reason = reason,
                            waitedMs = waitedMs,
                        )
                    }
                    AgentWorkerEventKind.HumanSettled -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(HumanSettledEventDataDto.serializer(), rawData)
                        val messageId = dto.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                        val how = AgentWorkerSettledHow.fromWire(dto.how?.trim()) ?: return null
                        val waitedMs = dto.waitedMs?.takeIf { it >= 0L } ?: 0L
                        AgentWorkerTimelineEventData.HumanSettled(
                            messageId = messageId,
                            by = AgentWorkerSettledBy.fromWire(dto.by?.trim()),
                            how = how,
                            waitedMs = waitedMs,
                        )
                    }
                    AgentWorkerEventKind.Ended -> {
                        val dto = agentWorkersJson.decodeFromJsonElement(EndedEventDataDto.serializer(), rawData)
                        val reason = AgentWorkerEndReason.fromWire(dto.reason?.trim()) ?: return null
                        val handedBack = dto.handedBack?.takeIf { it >= 0 } ?: 0
                        val durationMs = dto.durationMs?.takeIf { it >= 0L } ?: 0L
                        AgentWorkerTimelineEventData.Ended(
                            reason = reason,
                            stoppedBy = dto.stoppedBy?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() },
                            waitingOn = dto.waitingOn?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() },
                            note = dto.note?.trim()?.takeIf { it.isNotEmpty() },
                            handedBack = handedBack,
                            durationMs = durationMs,
                            tokens = dto.tokens?.toDomain(),
                        )
                    }
                }
            }.getOrNull() ?: return null
        return AgentWorkerTimelineEvent(
            seq = cleanSeq,
            kind = parsedKind,
            occurredAt = cleanOccurredAt,
            data = parsedData,
        )
    }
}

@Serializable
private data class AgentWorkerTimelineResponseDto(
    val launchId: String? = null,
    val events: List<AgentWorkerTimelineEventDto>? = null,
)

/** Parses a 2xx `GET /api/agents/:id/workers/recent` body (`WorkersRecentResponse`). */
@RaftCoreInternalApi fun parseSharedAgentRecentWorkers(bodyText: String): List<AgentRecentWorkerRow>? =
    runCatching {
        val dto = agentWorkersJson.decodeFromString(AgentRecentWorkersResponseDto.serializer(), bodyText)
        val rows = dto.workers ?: return@runCatching null
        rows.mapNotNull { it.toDomain() }
    }.getOrNull()

/** Parses a 2xx `GET /api/agents/:id/workers/:launchId/timeline` body (`WorkerTimelineResponse`). */
@RaftCoreInternalApi fun parseSharedAgentWorkerTimeline(bodyText: String): AgentWorkerTimelineSnapshot? =
    runCatching {
        val dto = agentWorkersJson.decodeFromString(AgentWorkerTimelineResponseDto.serializer(), bodyText)
        val launchId = dto.launchId?.trim()?.takeIf { it.isNotEmpty() } ?: return@runCatching null
        val events = dto.events ?: return@runCatching null
        AgentWorkerTimelineSnapshot(
            launchId = launchId,
            events = events.mapNotNull { it.toDomain() },
        )
    }.getOrNull()
