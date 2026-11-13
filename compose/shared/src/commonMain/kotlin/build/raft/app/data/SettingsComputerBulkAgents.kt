package build.raft.app.data

import build.raft.app.model.REASONING_EFFORT_RUNTIMES
import build.raft.app.model.RUNTIME_CONFIG_VERSION
import build.raft.app.model.RUNTIME_FAST_MODE_RUNTIMES
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bulk "Change model" on a Computer's agents (web `bulkChangeModelPlan.ts`, botiverse/slock
 * `packages/web/src/components/machine/`).
 *
 * Pure: given the selected agents and one target it decides per agent what the apply step does;
 * the dialog only shows these rows and sends the requests they describe.
 *
 * Contract (the web's): each agent is saved through its own `PATCH /agents/:id`
 * ([buildRaftBulkAgentModelUpdateBody]). By default it carries no restart: a running agent switches
 * at its next idle point. With "Restart running agents now" a running agent's save carries
 * `restartMode: "restart"`. Agents whose settings this flow cannot rebuild without dropping
 * something (a custom provider, command or model name, an external agent, a runtime the bulk flow
 * does not offer) are skipped with a reason instead of being rewritten.
 */

/** Runtimes whose model list depends on per-agent provider state (web `BULK_UNSUPPORTED_RUNTIMES`). */
private val RAFT_BULK_UNSUPPORTED_RUNTIMES = setOf("builtin", "pi", "kimi-sdk")

fun isRaftBulkModelSelectableRuntime(runtime: String): Boolean = runtime !in RAFT_BULK_UNSUPPORTED_RUNTIMES

/** The runtime, model and reasoning effort the selected agents move to. */
data class RaftBulkModelTarget(
    val runtime: String,
    val model: String,
    /** Null: the runtime's default effort. */
    val reasoningEffort: String?,
)

/** What a change that didn't finish still owes: the save itself, or only the restart after it. */
enum class RaftBulkUnfinishedPhase { SaveFailed, RestartPending }

enum class RaftBulkSkipReason { CustomSettings, NoChange }

sealed class RaftBulkPlanRow {
    abstract val agentId: String

    data class Change(override val agentId: String, val runtimeChanges: Boolean, val restarts: Boolean) : RaftBulkPlanRow()

    /** Re-opened from a partial failure: the config already matches, only the restart is owed. */
    data class RestartOnly(override val agentId: String) : RaftBulkPlanRow()

    data class Skip(override val agentId: String, val reason: RaftBulkSkipReason) : RaftBulkPlanRow()
}

data class RaftBulkPlan(
    val rows: List<RaftBulkPlanRow>,
    /** Agents that receive a request. */
    val applyCount: Int,
    /** Agents restarted now ("N running agents will restart."). */
    val restartCount: Int,
    /** Running agents among the changes, restarted now or switching when next idle. */
    val runningChangeCount: Int,
)

data class RaftBulkPlanAgent(
    val agent: SettingsComputerAgentSnapshot,
    val running: Boolean,
    /** Set only when re-opened from a partial-failure receipt. */
    val phase: RaftBulkUnfinishedPhase? = null,
)

/**
 * True when this flow can rebuild the agent's config without dropping anything the user set
 * (web `agentHasOnlyRebuildableSettings`): conservative, anything else is left to the agent's own
 * editor.
 */
fun raftBulkAgentHasOnlyRebuildableSettings(agent: SettingsComputerAgentSnapshot): Boolean {
    if (agent.external) return false
    val runtime = agent.runtime ?: return false
    if (!isRaftBulkModelSelectableRuntime(runtime)) return false
    val config = agent.runtimeConfig ?: return true
    if (config.runtime != runtime) return false
    if (config.modelKind != "preset") return false
    if (config.providerKind != null && config.providerKind != "default") return false
    if (!config.command.isNullOrBlank()) return false
    return true
}

private fun SettingsComputerAgentSnapshot.matches(target: RaftBulkModelTarget): Boolean =
    runtime == target.runtime && model == target.model && reasoningEffort == target.reasoningEffort

/** [restartNow]: running agents restart at once instead of switching at their next idle point. */
fun planRaftBulkModelChange(agents: List<RaftBulkPlanAgent>, target: RaftBulkModelTarget, restartNow: Boolean): RaftBulkPlan {
    val rows =
        agents.map { input ->
            val agent = input.agent
            when {
                input.phase == RaftBulkUnfinishedPhase.RestartPending && agent.matches(target) -> RaftBulkPlanRow.RestartOnly(agent.id)
                !raftBulkAgentHasOnlyRebuildableSettings(agent) -> RaftBulkPlanRow.Skip(agent.id, RaftBulkSkipReason.CustomSettings)
                agent.matches(target) -> RaftBulkPlanRow.Skip(agent.id, RaftBulkSkipReason.NoChange)
                else -> RaftBulkPlanRow.Change(agent.id, agent.runtime != target.runtime, restarts = input.running && restartNow)
            }
        }
    val running = agents.filter { it.running }.map { it.agent.id }.toSet()
    val applying = rows.filter { it !is RaftBulkPlanRow.Skip }
    return RaftBulkPlan(
        rows = rows,
        applyCount = applying.size,
        restartCount = applying.count { it is RaftBulkPlanRow.RestartOnly || (it is RaftBulkPlanRow.Change && it.restarts) },
        runningChangeCount = applying.count { it is RaftBulkPlanRow.Change && it.agentId in running },
    )
}

/**
 * After a failed request, decide from a fresh read of the agent what is still owed: the server
 * saves before it restarts, so a request can fail with the new config already stored.
 */
fun classifyRaftBulkUnfinished(fresh: RaftBulkModelTarget?, target: RaftBulkModelTarget): RaftBulkUnfinishedPhase =
    if (fresh == target) RaftBulkUnfinishedPhase.RestartPending else RaftBulkUnfinishedPhase.SaveFailed

/** Shared `RUNTIME_FAST_MODE_UNSUPPORTED_MODELS`. */
private val RAFT_FAST_MODE_UNSUPPORTED_MODELS = mapOf("claude" to setOf("claude-opus-4-8"))

/** Shared `runtimeModelSupportsFastMode`: a trailing variant tag (`[1m]`) names the same model. */
internal fun raftRuntimeModelSupportsFastMode(runtime: String, modelId: String): Boolean {
    val baseId = modelId.trim().replace(Regex("\\[[^\\]]*\\]$"), "")
    return runtime in RUNTIME_FAST_MODE_RUNTIMES && RAFT_FAST_MODE_UNSUPPORTED_MODELS[runtime]?.contains(baseId) != true
}

/**
 * The `PATCH /agents/:id` body for one agent (web `buildBulkAgentUpdate` + `updateAgent`):
 * `runtime`, `model`, `reasoningEffort` and a rebuilt v1 `runtimeConfig` that keeps the agent's
 * own env vars, and its fast mode while it stays on a runtime and model that run fast. Provider
 * and command never appear here: such agents are skipped by the planner. [restart] adds
 * `restartMode: "restart"`.
 */
fun buildRaftBulkAgentModelUpdateBody(agent: SettingsComputerAgentSnapshot, target: RaftBulkModelTarget, restart: Boolean): String {
    val keepFast =
        agent.runtime == target.runtime &&
            target.runtime in RUNTIME_FAST_MODE_RUNTIMES &&
            agent.runtimeConfig?.fastMode == true &&
            raftRuntimeModelSupportsFastMode(target.runtime, target.model)
    val envVars = agent.runtimeConfig?.envVars ?: agent.envVars
    val effort: JsonPrimitive = target.reasoningEffort?.let(::JsonPrimitive) ?: JsonNull
    val runtimeConfig: JsonObject =
        buildJsonObject {
            put("version", RUNTIME_CONFIG_VERSION)
            put("runtime", target.runtime)
            // Web `supportsRuntimeApiUrl`: only Claude Code carries a provider (the default one here).
            if (target.runtime == "claude") put("provider", buildJsonObject { put("kind", "default") })
            put(
                "model",
                buildJsonObject {
                    put("kind", "preset")
                    put("id", target.model)
                },
            )
            put("mode", buildJsonObject { put("kind", if (keepFast) "fast" else "default") })
            put("reasoningEffort", effort)
            if (envVars.isNullOrEmpty()) {
                put("envVars", JsonNull)
            } else {
                put("envVars", JsonObject(envVars.mapValues { JsonPrimitive(it.value) }))
            }
        }
    return buildJsonObject {
        put("runtime", target.runtime)
        put("model", target.model)
        put("reasoningEffort", effort)
        put("runtimeConfig", runtimeConfig)
        if (restart) put("restartMode", "restart")
    }.toString()
}

/** Shared `REASONING_EFFORTS` order. */
private val RAFT_REASONING_EFFORT_ORDER = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/** Shared `BASE_REASONING_EFFORTS`: what a model that declares no efforts offers. */
private val RAFT_BASE_REASONING_EFFORTS = setOf("low", "medium", "high", "xhigh")

/**
 * Web `reasoningEffortOptionsForModel` for the bulk runtimes: the model's declared efforts, else
 * the base set; none on a runtime without reasoning effort.
 */
fun raftBulkReasoningEffortOptions(runtime: String, supported: List<String>): List<String> {
    if (runtime !in REASONING_EFFORT_RUNTIMES) return emptyList()
    val allowed = supported.toSet().ifEmpty { RAFT_BASE_REASONING_EFFORTS }
    return RAFT_REASONING_EFFORT_ORDER.filter { it in allowed }
}

/**
 * Web `reconcileReasoningEffort`: the choice while the model offers it, else the model's declared
 * default, else the runtime default (null).
 */
fun raftBulkReconcileReasoningEffort(
    runtime: String,
    supported: List<String>,
    defaultEffort: String?,
    current: String?,
): String? {
    if (runtime !in REASONING_EFFORT_RUNTIMES) return null
    if (supported.isEmpty()) return current?.takeIf { it in RAFT_BASE_REASONING_EFFORTS }
    if (current != null && current in supported) return current
    return defaultEffort?.takeIf { it in supported }
}
