package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportProjectionRowDropped
import build.raft.app.reportUnknownTypeDropped
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * What a person did to an agent (start, stop, restart, reset, Stop Current Turn, migration, a
 * runtime / model change), and the restarts Raft did by itself, as the Activity overview shows them
 * between turns (botiverse/slock #13937, #proj-runtime:#51).
 *
 * Wire contract, verified against botiverse/slock 18d389311f299d559e237c3f8875d65491a227f3:
 * - `GET /agents/:id/operator-events?since=<ms>&until=<ms>&limit=` (packages/server/src/routes/agents.ts,
 *   service packages/server/src/services/agentOperatorEvents.ts) answers `AgentOperatorEventsPage`
 *   (packages/shared/src/agentOperatorEvents.ts): `{ events: AgentOperatorEventView[], truncated }`,
 *   newest first, at most 200, behind `canInspectAgentPrivateSurfaces` (same gate as the overview).
 * - `actor` is `{ kind: "user", name: string | null }` (null: someone the server no longer names) or
 *   `{ kind: "system" }`. `details` carries Computer names for a migration and before/after values
 *   for `config_changed`.
 * - There is no realtime event: the web asks again whenever the overview reloads its turns.
 */

private val operatorEventsJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
@RaftCoreInternalApi data class AgentOperatorEventsPageWire(
    val events: List<JsonElement>,
    val truncated: Boolean = false,
)

@Serializable
@RaftCoreInternalApi data class AgentOperatorEventActorWire(
    val kind: String,
    val name: String? = null,
)

@Serializable
@RaftCoreInternalApi data class AgentOperatorEventDetailsWire(
    val fromComputer: String? = null,
    val toComputer: String? = null,
    val beforeModel: String? = null,
    val afterModel: String? = null,
    val beforeRuntime: String? = null,
    val afterRuntime: String? = null,
)

@Serializable
@RaftCoreInternalApi data class AgentOperatorEventWire(
    val id: String,
    val at: Long,
    val kind: String,
    val actor: AgentOperatorEventActorWire,
    val details: AgentOperatorEventDetailsWire? = null,
)

/** slock `AgentOperatorEventKind`: `AGENT_OPERATOR_EVENT_STORED_KINDS` plus `config_changed`. */
enum class AgentOperatorEventKind(val wireValue: String) {
    Start("start"),
    Stop("stop"),
    Restart("restart"),
    ResetSession("reset_session"),
    ResetFull("reset_full"),
    TurnStopped("turn_stopped"),
    MigrationStarted("migration_started"),
    MigrationCancelled("migration_cancelled"),
    SystemRestart("system_restart"),
    ConfigChanged("config_changed");

    companion object {
        fun fromWire(value: String): AgentOperatorEventKind? = entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface AgentOperatorEventActor {
    /** A person; [name] is null when the server no longer names them here ("Someone"). */
    data class User(val name: String?) : AgentOperatorEventActor

    /** Raft itself (or an agent's own change): fainter, and names no one. */
    data object System : AgentOperatorEventActor
}

data class AgentOperatorEvent(
    val id: String,
    val atEpochMillis: Long,
    val kind: AgentOperatorEventKind,
    val actor: AgentOperatorEventActor,
    val fromComputer: String? = null,
    val toComputer: String? = null,
    val beforeModel: String? = null,
    val afterModel: String? = null,
    val beforeRuntime: String? = null,
    val afterRuntime: String? = null,
)

data class AgentOperatorEventsPage(
    /** Newest first, as the server sends them. */
    val events: List<AgentOperatorEvent>,
    val truncated: Boolean,
)

/** slock `AGENT_OPERATOR_EVENTS_PAGE_LIMIT`. */
const val AGENT_OPERATOR_EVENTS_PAGE_LIMIT: Int = 200

private const val OperatorEventsSource = "agent_operator_events"

/**
 * Parses `GET /agents/:id/operator-events`. Null when the body is not the `{ events: [...] }`
 * envelope. A row that does not decode is skipped and logged; a row of a kind or actor this build
 * does not know is dropped and logged (there is no generic line to show it as).
 */
fun parseSharedAgentOperatorEventsPage(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): AgentOperatorEventsPage? {
    val root = runCatching { operatorEventsJson.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject ?: return null
    if (root["events"] !is JsonArray) return null
    val page = runCatching {
        operatorEventsJson.decodeFromJsonElement(AgentOperatorEventsPageWire.serializer(), root)
    }.getOrNull() ?: return null
    val events = page.events.mapIndexedNotNull { index, element ->
        val wire = runCatching {
            operatorEventsJson.decodeFromJsonElement(AgentOperatorEventWire.serializer(), element)
        }.getOrNull()
        if (wire == null) {
            reportProjectionRowDropped(OperatorEventsSource, "undecodable", index, diagnostics)
            return@mapIndexedNotNull null
        }
        wire.toDomain(diagnostics)
    }
    return AgentOperatorEventsPage(events = events, truncated = page.truncated)
}

private fun AgentOperatorEventWire.toDomain(diagnostics: (String) -> Unit): AgentOperatorEvent? {
    val kind = AgentOperatorEventKind.fromWire(kind) ?: run {
        reportUnknownTypeDropped(OperatorEventsSource, "kind", kind, diagnostics = diagnostics)
        return null
    }
    val actor = when (actor.kind) {
        "user" -> AgentOperatorEventActor.User(actor.name?.takeIf { it.isNotBlank() })
        "system" -> AgentOperatorEventActor.System
        else -> {
            reportUnknownTypeDropped(OperatorEventsSource, "actor.kind", actor.kind, diagnostics = diagnostics)
            return null
        }
    }
    return AgentOperatorEvent(
        id = id,
        atEpochMillis = at,
        kind = kind,
        actor = actor,
        fromComputer = details?.fromComputer?.takeIf { it.isNotBlank() },
        toComputer = details?.toComputer?.takeIf { it.isNotBlank() },
        beforeModel = details?.beforeModel,
        afterModel = details?.afterModel,
        beforeRuntime = details?.beforeRuntime,
        afterRuntime = details?.afterRuntime,
    )
}

/** Web `OPERATOR_EVENTS_FOLD_AT`: runs of this many lines or more fold into one disclosure. */
const val AGENT_OPERATOR_EVENTS_FOLD_AT: Int = 3

sealed interface AgentActivityOverviewSegment {
    data class Turns(val turns: List<AgentActivityTurn>) : AgentActivityOverviewSegment

    /** One run of consecutive events, oldest first. */
    data class Events(val events: List<AgentOperatorEvent>) : AgentActivityOverviewSegment
}

data class AgentActivityOverviewTimeline(
    val segments: List<AgentActivityOverviewSegment>,
    /** Events after the last finished turn that started before the running one (or all, with none running). */
    val eventsBeforeRunning: List<AgentOperatorEvent>,
    /** Events at or after the running turn's start; empty with no running turn. */
    val eventsDuringRunning: List<AgentOperatorEvent>,
)

/**
 * Web `interleaveOperatorEvents` + the overview's running split: finished turns (oldest first) and
 * events (any order) in time order, each run of events before the first turn that started after it.
 */
fun agentActivityOverviewTimeline(
    chronologicalTurns: List<AgentActivityTurn>,
    events: List<AgentOperatorEvent>,
    runningStartEpochMillis: Long?,
): AgentActivityOverviewTimeline {
    val pending = events.sortedBy { it.atEpochMillis }
    val segments = mutableListOf<AgentActivityOverviewSegment>()
    val runTurns = mutableListOf<AgentActivityTurn>()
    var index = 0
    for (turn in chronologicalTurns) {
        val before = mutableListOf<AgentOperatorEvent>()
        while (index < pending.size && pending[index].atEpochMillis < turn.startedAtEpochMillis) before += pending[index++]
        if (before.isNotEmpty()) {
            if (runTurns.isNotEmpty()) segments += AgentActivityOverviewSegment.Turns(runTurns.toList())
            runTurns.clear()
            segments += AgentActivityOverviewSegment.Events(before)
        }
        runTurns += turn
    }
    if (runTurns.isNotEmpty()) segments += AgentActivityOverviewSegment.Turns(runTurns.toList())
    val after = pending.subList(index, pending.size)
    return if (runningStartEpochMillis == null) {
        AgentActivityOverviewTimeline(segments, after.toList(), emptyList())
    } else {
        AgentActivityOverviewTimeline(
            segments,
            after.filter { it.atEpochMillis < runningStartEpochMillis },
            after.filter { it.atEpochMillis >= runningStartEpochMillis },
        )
    }
}
