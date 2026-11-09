package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How the server applies a saved runtime config change: `configApply` on the `PATCH /agents/:id`
 * answer (botiverse/slock `packages/server/src/routes/agents.ts`, reconciler step 11b; web
 * `AgentConfigApply` in `packages/web/src/store/agentStore.ts`, #13372).
 *
 * - [WhenIdle] `when_idle`: the running agent's daemon took the new config and restarts it at its
 *   next idle point.
 * - [Restarted] `restarted`: the request carried `restartMode`, so it restarted now.
 * - [Saved] `saved`: only saved; the next start uses it (a stopped agent, or a daemon too old
 *   for `when_idle`).
 */
enum class AgentConfigApply(val wireValue: String) {
    WhenIdle("when_idle"),
    Restarted("restarted"),
    Saved("saved");

    companion object {
        fun fromWire(value: String?): AgentConfigApply? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
private data class AgentConfigApplyWire(val configApply: String? = null)

private val agentConfigApplyJson = Json { ignoreUnknownKeys = true }

/**
 * The `configApply` of a `PATCH /agents/:id` answer; null when absent (an older server) or not
 * one of the three values.
 */
fun parseSharedAgentConfigApply(bodyText: String): AgentConfigApply? =
    runCatching { agentConfigApplyJson.decodeFromString(AgentConfigApplyWire.serializer(), bodyText) }
        .getOrNull()
        ?.configApply
        ?.let(AgentConfigApply::fromWire)
