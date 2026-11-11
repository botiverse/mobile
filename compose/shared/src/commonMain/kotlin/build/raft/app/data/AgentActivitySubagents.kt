package build.raft.app.data

import build.raft.app.model.AgentActivitySubagentLineage
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull

// The Activity tab's Sub-agents view, folded from the activity the page already holds. Ported
// from botiverse/slock 853c6998bcef45df5c25e63b1035e2388608721d
// packages/web/src/components/agent/subagentGroups.ts (#proj-runtime:#41, #13029, #13383). A
// row belongs to a sub-agent only by its explicit lineage marker, never by its text. A group is
// one run: the call that started it (Claude's Agent tool_use id, Codex's spawn call id, Kimi's
// Agent call) and the sub-agent's own id (`taskId`).

/** Web `SubagentState`: running, an outcome the runtime reported, or ended without one. */
internal enum class AgentActivitySubagentState(val wireOutcome: String?) {
    Running(null),
    Done("done"),
    Failed("failed"),
    Ceiling("ceiling"),
    Stopped("stopped"),
    Ended(null);

    companion object {
        fun fromOutcome(outcome: String?): AgentActivitySubagentState =
            entries.firstOrNull { it.wireOutcome != null && it.wireOutcome == outcome } ?: Ended
    }
}

/** Web `SubagentGroup`: one sub-agent run. */
internal data class AgentActivitySubagentGroup(
    /** The run: the call that started it and its own id (`taskId`) when known. */
    val key: String,
    /** The call that started it: its title, and the raw log's link to it. */
    val parentToolUseId: String? = null,
    /** What it was asked: the spawn row's input, without Codex's operation prefix. Absent from older daemons. */
    val title: String? = null,
    val subagentType: String? = null,
    /** Its own id, which a stop names. */
    val taskId: String? = null,
    val state: AgentActivitySubagentState,
    val startedAtMillis: Long,
    /** When it finished; null while it runs. */
    val endedAtMillis: Long? = null,
    val lastAtMillis: Long,
    /** Its tool calls. */
    val steps: Int = 0,
    /** The tool it called last. */
    val lastToolName: String? = null,
    /** Its own rows, oldest first, without the lineage marker (the view is already this sub-agent's). */
    val items: List<AgentActivityEntry> = emptyList(),
)

private const val SubagentKeySeparator = '\u0000'

private fun AgentActivityEntry.epochMillis(): Long = parseRaftTimestampEpochMillisOrNull(timestamp) ?: 0L

private fun AgentActivityEntry.withoutSubagentLineage(): AgentActivityEntry =
    copy(isSubagent = false, subagentType = null, subagent = null)

/** A Codex collab summary leads with its operation ("spawnAgent: …"); the title is the task. */
private val SpawnOperationPrefix = Regex("^spawnAgent(?::\\s*|$)")

internal fun agentActivitySubagentTitleFromSpawn(toolInput: String): String? =
    toolInput.replaceFirst(SpawnOperationPrefix, "").trim().takeIf { it.isNotEmpty() }

private enum class SubagentLifecycle { Open, Close }

/**
 * Whether a row opens or closes its sub-agent. Only lifecycle rows count: the daemon's status
 * rows (`subagent_activity`) and a hosted agent's system rows. Other rows that carry a phase (a
 * hosted agent's tool end says `active`) are its steps, not its lifecycle.
 */
private fun AgentActivityEntry.subagentLifecycle(lineage: AgentActivitySubagentLineage): SubagentLifecycle? {
    val lifecycleRow = (type == "status" && statusDetailKind == "subagent_activity") || type == "system"
    if (!lifecycleRow) return null
    return when (lineage.phase) {
        "started", "progress" -> SubagentLifecycle.Open
        "notification", "ended" -> SubagentLifecycle.Close
        else -> null
    }
}

/**
 * Web `subagentKeyResolver`: the group key a row's lineage belongs to, as
 * [groupAgentActivitySubagents] keys them, so a row can open exactly its own run.
 */
internal fun agentActivitySubagentKeyResolver(log: List<AgentActivityEntry>): (AgentActivitySubagentLineage) -> String? {
    // One call can start several sub-agents (a Kimi AgentSwarm, a Codex spawn naming several
    // threads). Claude's inner rows carry only the call; they belong to that call's one task.
    val tasksByCall = HashMap<String, LinkedHashSet<String>>()
    for (item in log) {
        val lineage = item.subagent ?: continue
        val call = lineage.parentToolUseId ?: continue
        val task = lineage.taskId ?: continue
        tasksByCall.getOrPut(call) { LinkedHashSet() }.add(task)
    }
    // A run is one call and one task: a swarm's sub-agents share the call, a Kimi resume reuses
    // the task under a new call, a Codex follow-up turn keeps both.
    return { lineage ->
        val call = lineage.parentToolUseId
        if (call == null) {
            lineage.taskId
        } else {
            val tasks = tasksByCall[call]
            val task = lineage.taskId ?: tasks?.singleOrNull()
            if (task != null) "$call$SubagentKeySeparator$task" else call
        }
    }
}

/** Web `groupSubagents`: the sub-agent runs in [log] (oldest first), running first, then the most recent. */
internal fun groupAgentActivitySubagents(log: List<AgentActivityEntry>): List<AgentActivitySubagentGroup> {
    val groups = LinkedHashMap<String, AgentActivitySubagentGroup>()
    val items = HashMap<String, MutableList<AgentActivityEntry>>()
    val spawnSummaries = HashMap<String, Pair<String, Long>>()
    val keyOf = agentActivitySubagentKeyResolver(log)
    for (item in log) {
        val at = item.epochMillis()
        if (item.type == "tool_start" && !item.callId.isNullOrEmpty() && !item.toolInput.isNullOrEmpty()) {
            spawnSummaries[item.callId] = item.toolInput to at
        }
        val lineage = item.subagent ?: continue
        val key = keyOf(lineage) ?: continue
        var group =
            groups[key] ?: AgentActivitySubagentGroup(
                key = key,
                state = AgentActivitySubagentState.Ended,
                startedAtMillis = at,
                lastAtMillis = at,
            )
        group = group.copy(
            parentToolUseId = group.parentToolUseId ?: lineage.parentToolUseId,
            subagentType = group.subagentType ?: lineage.subagentType,
            taskId = group.taskId ?: lineage.taskId,
            lastAtMillis = at,
        )
        when (item.subagentLifecycle(lineage)) {
            // A start (again, for a follow-up turn) opens it.
            SubagentLifecycle.Open -> group = group.copy(state = AgentActivitySubagentState.Running, endedAtMillis = null)
            // Without an outcome (an older daemon, or a notification that is only an update) it
            // reads as ended; a later start reopens it.
            SubagentLifecycle.Close ->
                group = group.copy(state = AgentActivitySubagentState.fromOutcome(lineage.outcome), endedAtMillis = at)
            null ->
                if (!(item.type == "status" && item.statusDetailKind == "subagent_activity")) {
                    if (item.type == "tool_start") {
                        group = group.copy(steps = group.steps + 1, lastToolName = item.toolName)
                    }
                    items.getOrPut(key) { mutableListOf() }.add(item.withoutSubagentLineage())
                }
        }
        groups[key] = group
    }
    return groups.values
        .map { group ->
            // Its start may have scrolled out of the loaded activity: what is left reads as ended
            // at its last row, never as still running time.
            var done = group.copy(items = items[group.key].orEmpty())
            if (done.state != AgentActivitySubagentState.Running && done.endedAtMillis == null) {
                done = done.copy(endedAtMillis = done.lastAtMillis)
            }
            val spawn = done.parentToolUseId?.let(spawnSummaries::get)
            if (spawn != null) {
                done = done.copy(
                    title = agentActivitySubagentTitleFromSpawn(spawn.first),
                    startedAtMillis = minOf(done.startedAtMillis, spawn.second),
                )
            }
            done
        }
        .sortedWith(
            compareByDescending<AgentActivitySubagentGroup> { it.state == AgentActivitySubagentState.Running }
                .thenByDescending { it.startedAtMillis }
        )
}

/**
 * Web `subagentRowPresence`: a system row that is a sub-agent's lifecycle (OAR runtimes') reads
 * as that sub-agent's state (`busy`, `online`, `error`), not as an error; null for any other row.
 */
internal fun agentActivitySubagentRowPresence(lineage: AgentActivitySubagentLineage?): String? =
    when (lineage?.phase) {
        "started", "progress" -> "busy"
        "notification", "ended" -> if (lineage.outcome == "failed") "error" else "online"
        else -> null
    }

/**
 * The runtimes that can stop one sub-agent (web `SUBAGENT_STOP_RUNTIMES`): Codex, Claude Code,
 * Kimi (one another sub-agent started answers `unsupported`), Qoder (OAR's `stopTask`).
 */
private val AgentSubagentStopRuntimes = setOf("codex", "claude", "kimi-sdk", "qoder")

/**
 * Web `canStopSubagentsOf`: Stop turn's runtime authority (the controlAgentRuntime capability
 * or creator authority, on an agent of a Computer), a live agent, and a runtime that can stop a
 * single sub-agent. The server checks the authority again.
 */
internal fun canStopAgentSubagents(
    serverRole: String?,
    isCreator: Boolean,
    isExternalAgent: Boolean,
    deleted: Boolean,
    runtime: String?,
): Boolean =
    !deleted && !isExternalAgent && runtime in AgentSubagentStopRuntimes &&
        (serverRoleCanControlAgentRuntime(serverRole) || isCreator)
