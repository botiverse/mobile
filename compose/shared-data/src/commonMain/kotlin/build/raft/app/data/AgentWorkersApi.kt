package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.MemberId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Worker v1: an agent's live Workers in Agent Detail › Activity › Workers
// (botiverse/slock `docs/contracts/worker-snapshot.md` & `docs/contracts/worker-events.md`):
//  - `GET /api/agents/:id/workers` -> `WorkersReadResponse` (`packages/shared/src/workersSnapshot.ts`)
//  - `POST /api/agents/:id/workers/:worker/stop` -> `WorkerStopResponse` (`routes/agents.ts`)

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
    /** Conversation ref held by this Worker: `#eng:#7` or `#eng:abcd1234`. */
    val objectRef: String,
    val objectKind: AgentWorkerObjectKind,
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
)

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
private data class AgentWorkerRowDto(
    val agentId: String? = null,
    val worker: String? = null,
    @SerialName("object")
    val objectRef: String? = null,
    val objectKind: String? = null,
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
        val cleanObject = objectRef?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val kind = AgentWorkerObjectKind.fromWire(objectKind?.trim()) ?: return null
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
