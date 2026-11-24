package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.reportProjectionRowDropped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/*
 * The server's agent migration log (web `/migrations`), botiverse/slock staging 383e8553641a:
 *  - packages/shared/src/agentMigrationLog.ts (the wire shape).
 *  - packages/server/src/routes/agentMigrationLog.ts (`GET agent-migrations/log`; 403 for anyone
 *    but the server's owners and admins).
 *  - packages/web/src/components/agentMigration/log/model.ts (everything below the parser, ported
 *    function by function: the page filters, counts and searches what it has read).
 */

internal enum class AgentMigrationLogOutcome(val wire: String) {
    Completed("completed"),
    Failed("failed"),
    Canceled("canceled"),
    InProgress("in_progress"),
    ;

    companion object {
        fun fromWire(value: String): AgentMigrationLogOutcome? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a failed move failed, as a reason people can act on. */
internal enum class AgentMigrationLogFailureKind(val wire: String) {
    TooLarge("too_large"),
    Network("network"),
    Other("other"),
    ;

    companion object {
        /** A reason this build doesn't know reads as [Other]. */
        fun fromWire(value: String): AgentMigrationLogFailureKind = entries.firstOrNull { it.wire == value } ?: Other
    }
}

/** Who started a move, while they are still a member of the server. */
internal data class AgentMigrationLogInitiator(val id: String, val name: String, val displayName: String?)

internal data class AgentMigrationLogEntry(
    val migrationRef: String,
    val agentId: String,
    val sourceMachineId: String,
    /** The source computer's name when the move started (it may be renamed or deleted since). */
    val sourceMachineName: String?,
    val toCloud: Boolean,
    val targetMachineId: String?,
    val targetMachineName: String?,
    val initiatedBy: AgentMigrationLogInitiator?,
    val outcome: AgentMigrationLogOutcome,
    val startedAt: String,
    /** When it completed, failed or was canceled; null while it is in progress. */
    val endedAt: String?,
    val failureKind: AgentMigrationLogFailureKind?,
    /** #proj-raft-computer:#135 (slock #16567): completed move whose agent could not answer on target. */
    val cannotRespond: AgentMigrationCannotRespondReason? = null,
) {
    /** The place it moved to: a computer's id, or [CLOUD_NODE] (web `targetNode`). */
    val targetNode: String
        get() = if (toCloud) CLOUD_NODE else targetMachineId ?: CLOUD_NODE

    /** Null once unreadable: the row stays, without a time. */
    val startedAtMillis: Long? = epochMillis(startedAt)

    /** Seconds from start to end, once it has ended (web `durationSec`). */
    val durationSeconds: Long?
        get() {
            val end = endedAt?.let(::epochMillis) ?: return null
            val start = startedAtMillis ?: return null
            return ((end - start + 500) / 1000).coerceAtLeast(0)
        }

    internal companion object {
        /** The cloud as a place; real computers use their machine id. */
        const val CLOUD_NODE = "cloud"

        private fun epochMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
    }
}

/** The log, oldest move first; [truncated] when the server holds more (it keeps the newest). */
internal data class AgentMigrationLog(val entries: List<AgentMigrationLogEntry>, val truncated: Boolean)

private val migrationLogJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class AgentMigrationLogInitiatorDto(val id: String, val name: String, val displayName: String? = null)

@Serializable
private data class AgentMigrationLogEntryDto(
    val migrationRef: String,
    val agentId: String,
    val sourceMachineId: String,
    val sourceMachineName: String? = null,
    val targetKind: String,
    val targetMachineId: String? = null,
    val targetMachineName: String? = null,
    val initiatedBy: AgentMigrationLogInitiatorDto? = null,
    val outcome: String,
    val startedAt: String,
    val endedAt: String? = null,
    val failureKind: String? = null,
    val cannotRespond: String? = null,
)

@Serializable
private data class AgentMigrationLogResponseDto(val migrations: List<JsonElement>, val truncated: Boolean = false)

/** The response body, or null when it isn't one. A row it can't read is dropped (and reported), not the log. */
internal fun parseAgentMigrationLog(bodyText: String?): AgentMigrationLog? {
    val wire =
        runCatching { migrationLogJson.decodeFromString(AgentMigrationLogResponseDto.serializer(), bodyText.orEmpty()) }
            .getOrNull() ?: return null
    val entries =
        wire.migrations.mapIndexedNotNull { index, row ->
            val dto = runCatching { migrationLogJson.decodeFromJsonElement(AgentMigrationLogEntryDto.serializer(), row) }.getOrNull()
            val outcome = dto?.let { AgentMigrationLogOutcome.fromWire(it.outcome) }
            if (dto == null || outcome == null) {
                reportProjectionRowDropped(source = "agent_migration_log", reason = "undecodable", index = index)
                null
            } else {
                AgentMigrationLogEntry(
                    migrationRef = dto.migrationRef,
                    agentId = dto.agentId,
                    sourceMachineId = dto.sourceMachineId,
                    sourceMachineName = dto.sourceMachineName,
                    toCloud = dto.targetKind == "hosted",
                    targetMachineId = dto.targetMachineId,
                    targetMachineName = dto.targetMachineName,
                    initiatedBy = dto.initiatedBy?.let { AgentMigrationLogInitiator(it.id, it.name, it.displayName) },
                    outcome = outcome,
                    startedAt = dto.startedAt,
                    endedAt = dto.endedAt,
                    failureKind = dto.failureKind?.let(AgentMigrationLogFailureKind::fromWire),
                    cannotRespond = AgentMigrationCannotRespondReason.fromWire(dto.cannotRespond),
                )
            }
        }
    return AgentMigrationLog(entries, wire.truncated)
}

/** Where an agent was or went: a computer, or the cloud. */
internal data class AgentMigrationLogNode(
    val id: String,
    /** The computer's current name, else the name it had when it last moved an agent; blank for the cloud. */
    val name: String,
    /** Three characters, unique on the server, like an airport code (HE3, AMA). */
    val code: String,
    /** Where it is hosted (a provider, else its kind of device); the ring groups by it. */
    val group: String,
    /** Still a computer of this server (false: deleted since, or the cloud). */
    val known: Boolean,
)

/** A computer of the server now. */
internal data class AgentMigrationLogMachine(val id: String, val name: String, val hostProvider: String? = null, val deviceClass: String? = null)

/**
 * A code from a computer's name: the first letters of up to three words, keeping a digit when the
 * name ends in one (hetzner-ex63-3 → HE3, armbian-1 → AR1, Alices-MacBook-Air → AMA). Codes are
 * unique on the server: a clash takes the next letter of the name instead (web `computerCodes`).
 */
internal fun agentMigrationComputerCodes(names: List<Pair<String, String>>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val used = mutableSetOf<String>()
    for ((id, name) in names.sortedWith(compareBy<Pair<String, String>> { it.second.lowercase() }.thenBy { it.second }.thenBy { it.first })) {
        val words = name.replace(LocalSuffix, "").split(NotLetterOrDigit).filter { it.isNotEmpty() }
        val letters = words.joinToString("").uppercase().filter { it.isLetterOrDigit() }
        val last = words.lastOrNull().orEmpty()
        val base =
            when {
                words.size >= 2 && last.isNotEmpty() && last.all { it in '0'..'9' } ->
                    "${words[0][0]}${if (words.size >= 3) words[1][0] else words[0].getOrElse(1) { 'X' }}$last"
                words.size >= 3 -> "${words[0][0]}${words[1][0]}${words[2][0]}"
                words.size == 2 -> "${words[0][0]}${words[1].take(2)}"
                else -> letters.ifEmpty { "PC" }.take(3)
            }.uppercase().take(3).padEnd(3, 'X')
        var code = base
        for (letter in letters) if (code in used) code = base.take(2) + letter else break
        for (letter in 'A'..'Z') if (code in used) code = base.take(2) + letter else break
        used += code
        out[id] = code
    }
    return out
}

private val LocalSuffix = Regex("\\.local$", RegexOption.IGNORE_CASE)
private val NotLetterOrDigit = Regex("[^\\p{L}\\p{N}]+")

/**
 * Every place in the log, plus every current computer of the server, moves or not (so a computer
 * without moves still has a filter option), in ring order: groups by size, then names; the cloud and
 * the removed computers last (web `buildNodes`).
 */
internal fun agentMigrationLogNodes(entries: List<AgentMigrationLogEntry>, machines: List<AgentMigrationLogMachine>): List<AgentMigrationLogNode> {
    val machineById = machines.associateBy { it.id }
    val current = machineById.mapValues { it.value.name }
    val names = LinkedHashMap(current)
    for (entry in entries) {
        names[entry.sourceMachineId] = current[entry.sourceMachineId] ?: entry.sourceMachineName ?: entry.sourceMachineId.take(8)
        val target = entry.targetNode
        if (target != AgentMigrationLogEntry.CLOUD_NODE) names[target] = current[target] ?: entry.targetMachineName ?: target.take(8)
    }
    val codes = agentMigrationComputerCodes(names.toList())
    val nodes =
        names.mapTo(mutableListOf()) { (id, name) ->
            val machine = machineById[id]
            val group = if (machine == null) REMOVED_GROUP else (machine.hostProvider ?: machine.deviceClass ?: OTHER_GROUP).lowercase()
            AgentMigrationLogNode(id, name, codes.getValue(id), group, known = machine != null)
        }
    if (entries.any { it.targetNode == AgentMigrationLogEntry.CLOUD_NODE }) {
        nodes += AgentMigrationLogNode(AgentMigrationLogEntry.CLOUD_NODE, "", "CLD", CLOUD_GROUP, known = false)
    }
    val groupSize = nodes.groupingBy { it.group }.eachCount()
    return nodes.sortedWith(
        compareBy<AgentMigrationLogNode> { groupRank(it.group) }
            .thenByDescending { groupSize.getValue(it.group) }
            .thenBy { it.group }
            .thenBy { it.name.lowercase() }
            .thenBy { it.name },
    )
}

private const val OTHER_GROUP = "other"
private const val CLOUD_GROUP = "cloud"
private const val REMOVED_GROUP = "removed"

private fun groupRank(group: String): Int =
    when (group) {
        OTHER_GROUP -> 1
        CLOUD_GROUP -> 2
        REMOVED_GROUP -> 3
        else -> 0
    }

internal data class AgentMigrationLogFilters(
    val agents: List<String> = emptyList(),
    /** A computer on either end. */
    val computers: List<String> = emptyList(),
    /** Moves leaving these computers. */
    val from: List<String> = emptyList(),
    /** Moves arriving at these places (the cloud included). */
    val to: List<String> = emptyList(),
    val outcomes: List<AgentMigrationLogOutcome> = emptyList(),
    /** Days back from now; null for all time. */
    val days: Int? = null,
) {
    val isFiltered: Boolean
        get() = this != AgentMigrationLogFilters()

    fun apply(entries: List<AgentMigrationLogEntry>, nowMillis: Long): List<AgentMigrationLogEntry> {
        val since = days?.let { nowMillis - it * 86_400_000L }
        return entries.filter { entry ->
            (agents.isEmpty() || entry.agentId in agents) &&
                (computers.isEmpty() || entry.sourceMachineId in computers || entry.targetNode in computers) &&
                (from.isEmpty() || entry.sourceMachineId in from) &&
                (to.isEmpty() || entry.targetNode in to) &&
                (outcomes.isEmpty() || entry.outcome in outcomes) &&
                (since == null || (entry.startedAtMillis ?: Long.MIN_VALUE) >= since)
        }
    }
}

internal data class AgentMigrationLogStats(
    val total: Int,
    /** Completed out of finished moves, cancels excluded, in percent; null with none finished. */
    val successRate: Int?,
    val agents: Int,
    val computers: Int,
)

internal fun agentMigrationLogStats(entries: List<AgentMigrationLogEntry>): AgentMigrationLogStats {
    val ok = entries.count { it.outcome == AgentMigrationLogOutcome.Completed }
    val finished = ok + entries.count { it.outcome == AgentMigrationLogOutcome.Failed }
    return AgentMigrationLogStats(
        total = entries.size,
        // `Math.round`: halves go up.
        successRate = if (finished == 0) null else (ok * 200 + finished) / (finished * 2),
        agents = entries.distinctBy { it.agentId }.size,
        computers = entries.flatMapTo(mutableSetOf()) { listOf(it.sourceMachineId, it.targetNode) }.size,
    )
}

/** Each agent in the log with its number of moves, the busiest first (the agent filter's order). */
internal fun agentMigrationLogAgentMoves(entries: List<AgentMigrationLogEntry>): List<Pair<String, Int>> =
    entries.groupingBy { it.agentId }.eachCount().toList().sortedByDescending { it.second }

/**
 * The moves whose texts ([textsOf]: the agent's name, both computers' names and codes, the names
 * they had then) contain [query], case-insensitively; all of them for an empty query.
 */
internal fun searchAgentMigrationLog(
    entries: List<AgentMigrationLogEntry>,
    query: String,
    textsOf: (AgentMigrationLogEntry) -> List<String?>,
): List<AgentMigrationLogEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { entry -> textsOf(entry).any { it?.contains(needle, ignoreCase = true) == true } }
}

/** A route with its successful and failed moves; canceled and running moves are not drawn. */
internal data class AgentMigrationRouteCell(val src: String, val dst: String, val ok: Int, val failed: Int) {
    val volume: Int get() = ok + failed
}

internal fun agentMigrationRouteCells(entries: List<AgentMigrationLogEntry>): List<AgentMigrationRouteCell> =
    entries
        .filter { it.outcome == AgentMigrationLogOutcome.Completed || it.outcome == AgentMigrationLogOutcome.Failed }
        .groupBy { it.sourceMachineId to it.targetNode }
        .map { (route, moves) ->
            val ok = moves.count { it.outcome == AgentMigrationLogOutcome.Completed }
            AgentMigrationRouteCell(route.first, route.second, ok, moves.size - ok)
        }

/** One completed move of an agent, with the failed and canceled tries that led to it, oldest first. */
internal data class AgentMigrationLeg(val entry: AgentMigrationLogEntry, val tries: List<AgentMigrationLogEntry>)

/** An agent's path: where it began, each completed move, and the tries after the last one. */
internal data class AgentMigrationJourney(val start: String?, val legs: List<AgentMigrationLeg>, val pending: List<AgentMigrationLogEntry>)

internal fun agentMigrationJourney(entries: List<AgentMigrationLogEntry>, agentId: String): AgentMigrationJourney {
    val mine = entries.filter { it.agentId == agentId }
    val legs = mutableListOf<AgentMigrationLeg>()
    val tries = mutableListOf<AgentMigrationLogEntry>()
    for (entry in mine) {
        when (entry.outcome) {
            AgentMigrationLogOutcome.Completed -> {
                legs += AgentMigrationLeg(entry, tries.toList())
                tries.clear()
            }
            AgentMigrationLogOutcome.InProgress -> Unit
            else -> tries += entry
        }
    }
    return AgentMigrationJourney(mine.firstOrNull()?.sourceMachineId, legs, tries.toList())
}

internal sealed interface AgentMigrationLogLoad {
    data object Loading : AgentMigrationLogLoad

    data class Loaded(val log: AgentMigrationLog) : AgentMigrationLogLoad

    /** Only owners and admins read the log; asking again won't help. */
    data object Forbidden : AgentMigrationLogLoad

    data object Failed : AgentMigrationLogLoad
}

internal fun interface AgentMigrationLogSource {
    suspend fun load(serverId: String): AgentMigrationLogLoad
}

internal class NetworkAgentMigrationLogSource(private val networkClient: RaftNetworkClient) : AgentMigrationLogSource {
    override suspend fun load(serverId: String): AgentMigrationLogLoad {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "agent-migrations/log",
                    serverIdOverride = ServerId(serverId),
                )
            }.getOrElse { return AgentMigrationLogLoad.Failed }
        if (response.code == 403) return AgentMigrationLogLoad.Forbidden
        if (!response.isSuccessful) return AgentMigrationLogLoad.Failed
        return parseAgentMigrationLog(response.bodyText)?.let(AgentMigrationLogLoad::Loaded) ?: AgentMigrationLogLoad.Failed
    }
}
